package com.motogps.watch

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手表侧 GATT 外设端，对标 platforms/esp32 的 BleNavTransport（NimBLE）：
 * - 广播 Navigation Service UUID，等待 iPhone（central）连接
 * - 加密特征：RX 收手机数据，TX notify 上报心跳/ACK/命令
 * - 规范握手：Phone Starting -> Device Ready -> Phone Ready -> Device Ready -> 就绪
 * - 每 heartbeat_interval 发心跳；3000 ms 无有效帧标记链路失活
 */
class BlePeripheral(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onCentralConnected(connected: Boolean)
        fun onProtocolReady()
        fun onLinkActive(active: Boolean)
        fun onDisconnected()
        fun onSnapshot(snapshot: NavSnapshotData)
        fun onRouteCommitted(route: CommittedRoute)
        fun onRouteInvalidated()
        fun onMapScene(scene: MapSceneData)
        fun onTraffic(deviation: TrafficDeviationData)
        fun onMediaState(state: MediaStateData)
    }

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null

    private val started = AtomicBoolean(false)
    private val devices = LinkedHashSet<BluetoothDevice>()
    private var subscribed = false

    // 会话状态
    private var sessionId = 0L
    private var protocolReady = false
    private var peerMaxFrameSize = BleSpec.MAX_FRAME_VALUE
    private var localFrameValue = 20 // MTU 协商前保守取 20
    private var lastHeartbeatIntervalMs = BleSpec.HEARTBEAT_INTERVAL_MS

    // 链路活性
    private var lastValidFrameMs = 0L
    private var linkActive = false

    // 接收
    private val rx = Reassembler()
    private var rxLastSequence = 0
    private var hasRxSequence = false

    // ACK 缓存：重复已完成序号时重发原 ACK（规范 4.2）
    private val recentAcks = LinkedHashMap<Int, ByteArray>(8)

    // 发送
    private var txSequence = 0
    private val pendingAck = PendingAck()

    // 路线窗口 staging（规范 6.5）
    private var stagedChunks = HashMap<Int, RouteGeometryData>()
    private var committedRoute: CommittedRoute? = null

    private class PendingAck {
        var active = false
        var sequence = 0
        var commandId = 0
    }

    data class CommittedRoute(
        val routeToken: Long,
        val generation: Long,
        val originLatE6: Int,
        val originLonE6: Int,
        val pointsE6: IntArray,
    )

    // region 生命周期

    fun start(): Boolean {
        if (!started.compareAndSet(false, true)) return true
        val a = adapter ?: return false
        if (!a.isEnabled) return false

        val service = BluetoothGattService(
            UUID.fromString(BleSpec.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).apply {
            val rxUuid = UUID.fromString(BleSpec.CHAR_RX_UUID)
            val txUuid = UUID.fromString(BleSpec.CHAR_TX_UUID)
            addCharacteristic(
                BluetoothGattCharacteristic(
                    rxUuid,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    // 规范要求 encrypted write；触发 iPhone 端系统配对
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
                ),
            )
            val tx = BluetoothGattCharacteristic(
                txUuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ or
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
            )
            tx.addDescriptor(
                android.bluetooth.BluetoothGattDescriptor(
                    UUID.fromString(BleSpec.CCCD_UUID),
                    android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ or
                        android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
                ),
            )
            addCharacteristic(tx)
            rxCharacteristic = getCharacteristic(rxUuid)
            txCharacteristic = tx
        }

        // openGattServer 在 BluetoothManager 上（不是 BluetoothAdapter）
        val server = bluetoothManager.openGattServer(appContext, gattServerCallback)
        if (server == null) {
            started.set(false)
            return false
        }
        server.addService(service)
        gattServer = server

        trySetName(a, BleSpec.ADVERTISED_NAME)
        startAdvertising(a)
        handler.post(tick)
        return true
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        handler.removeCallbacksAndMessages(null)
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        gattServer?.close()
        gattServer = null
        resetSession()
        devices.clear()
    }

    private fun trySetName(a: BluetoothAdapter, name: String) {
        runCatching { a.name = name }
    }

    private fun startAdvertising(a: BluetoothAdapter) {
        val leAdvertiser = a.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "device does not support LE advertising")
            return
        }
        advertiser = leAdvertiser
        // 先停再开：连接型广播在 central 连上后会被系统自动停掉，
        // 若不重启，其他 central（iPhone）将永远扫不到本机
        runCatching { leAdvertiser.stopAdvertising(advertiseCallback) }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        // 主广播包只放 UUID（部分控制器实际可用 <31 字节，名字放 scan response）
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleSpec.SERVICE_UUID)))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        leAdvertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                // 已在广播，等价于成功，不重试
                Log.i(TAG, "advertising already active")
                return
            }
            Log.w(TAG, "advertising failed: $errorCode; retrying in 2s")
            handler.postDelayed({
                val a = adapter
                if (started.get() && a != null && a.isEnabled) startAdvertising(a)
            }, 2000)
        }
    }

    // endregion

    // region GATT server 回调

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(devices) { devices.add(device) }
                    Log.i(TAG, "central connected: ${device.address}")
                    listener.onCentralConnected(true)
                    // 连接会吃掉连接型广播；延迟重启，让其他 central（iPhone）也能扫到
                    handler.postDelayed({
                        val a = adapter
                        if (started.get() && a != null && a.isEnabled) startAdvertising(a)
                    }, 500)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(devices) { devices.remove(device) }
                    Log.i(TAG, "central disconnected: ${device.address}")
                    listener.onCentralConnected(synchronized(devices) { devices.isNotEmpty() })
                    resetSession()
                    listener.onDisconnected()
                    val a = adapter
                    if (started.get() && a != null && a.isEnabled) startAdvertising(a)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            localFrameValue = minOf(mtu - 3, BleSpec.MAX_FRAME_VALUE)
            Log.i(TAG, "mtu=$mtu localFrameValue=$localFrameValue")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (characteristic.uuid != UUID.fromString(BleSpec.CHAR_RX_UUID) || value == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null,
                    )
                }
                return
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            handleRxValue(value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid != UUID.fromString(BleSpec.CCCD_UUID)) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            // 只暴露当前订阅位，不含业务载荷
            val bits = byteArrayOf(if (subscribed) 1 else 0, 0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, bits)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid != UUID.fromString(BleSpec.CCCD_UUID) || value == null) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                }
                return
            }
            subscribed = value.size >= 2 && (value[0].toInt() and 0x01) != 0
            Log.i(TAG, "tx notify subscribed=$subscribed from=${device.address}")
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            if (!subscribed) {
                subscribed = false
                resetSession()
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notifyOutstanding = (notifyOutstanding - 1).coerceAtLeast(0)
            if (txLogBudget > 0) {
                txLogBudget--
                Log.i(TAG, "notify sent ok=${status == 0} outstanding=$notifyOutstanding to=${device.address}")
            }
        }
    }

    // endregion

    // region 接收路径

    private fun handleRxValue(value: ByteArray) {
        val frame = FrameCodec.parse(value)
        if (frame == null) {
            Log.w(TAG, "rx frame parse failed (${value.size} bytes): ${value.joinToString("") { "%02x".format(it) }}")
            return
        }
        val now = System.currentTimeMillis()
        lastValidFrameMs = now
        notifyLink(true)
        rx.expire(now)

        when (val result = rx.offer(frame, now)) {
            is Reassembler.OfferResult.Complete ->
                handleAssembled(result.message)
            is Reassembler.OfferResult.Duplicate ->
                resendRecentAck(frame.sequence)
            else -> Unit
        }
    }

    /** 重复已完成序号：不重复执行副作用，但从缓存重发原 ACK（规范 4.2）。 */
    private fun resendRecentAck(sequence: Int) {
        recentAcks[sequence]?.let { ackPayload -> sendAckPayload(ackPayload) }
    }

    private fun handleAssembled(message: AssembledMessage) {
        // 新会话检测必须在序号过滤之前（规范 3.6）：手机重连后序号从 1 重新
        // 开始，若沿用旧会话基线，新握手会被 isNewer 判为旧序号静默丢弃，
        // 永远无法重新握手。
        if (message.type == BleSpec.MSG_CONNECTION_STATUS) {
            val incoming = MessageCodec.decodeConnectionStatus(message.payload)
            if (incoming != null && incoming.sessionId != 0L && incoming.sessionId != sessionId) {
                Log.i(TAG, "new session detected: ${incoming.sessionId} (old=$sessionId); reset rx baseline")
                rxLastSequence = 0
                hasRxSequence = false
                rx.reset()
            }
        }
        // 序号去重：重复已完成序号不重复执行副作用，但需重发 ACK（规范 4.2）
        if (hasRxSequence && message.sequence == rxLastSequence) {
            if (message.ackRequested) resendRecentAck(message.sequence)
            return
        }
        if (hasRxSequence && !Sequencing.isNewer(message.sequence, rxLastSequence)) {
            Log.i(TAG, "drop stale/old sequence msg=${message.sequence} last=$rxLastSequence type=0x%02x".format(message.type))
            return
        }
        rxLastSequence = message.sequence
        hasRxSequence = true
        if (message.type != BleSpec.MSG_HEARTBEAT) {
            Log.i(
                TAG,
                "rx msg type=0x%02x seq=%d ackReq=%b payload=%d".format(
                    message.type, message.sequence, message.ackRequested, message.payload.size,
                ),
            )
        }

        when (message.type) {
            BleSpec.MSG_CONNECTION_STATUS -> {
                val status = MessageCodec.decodeConnectionStatus(message.payload)
                if (status == null) {
                    Log.w(TAG, "decode ConnectionStatus FAILED payload=${message.payload.joinToString("") { "%02x".format(it) }}")
                    return
                }
                onPhoneConnectionStatus(status)
                if (message.ackRequested) queueAck(message, ok = true, commandId = 0)
            }
            BleSpec.MSG_HEARTBEAT -> Unit // 心跳只用于活性检测
            BleSpec.MSG_ACK -> {
                val payload = message.payload
                if (payload.size >= 6 && (payload[0].toInt() and 0xFF) == 1) {
                    val seq = FrameCodec.u16(payload, 1)
                    val commandId = FrameCodec.u16(payload, 4)
                    val pending = pendingAck
                    if (pending.active && pending.sequence == seq && pending.commandId == commandId) {
                        pending.active = false
                    }
                }
            }
            BleSpec.MSG_NAVIGATION_SNAPSHOT -> {
                val snapshot = MessageCodec.decodeNavigationSnapshot(message.payload) ?: return
                if (snapshot.routeToken == 0L || snapshot.routeGeneration == 0L) {
                    committedRoute = null
                    listener.onRouteInvalidated()
                }
                listener.onSnapshot(snapshot)
                if (message.ackRequested) queueAck(message, ok = true, commandId = 0)
            }
            BleSpec.MSG_ROUTE_GEOMETRY -> {
                val geometry = MessageCodec.decodeRouteGeometry(message.payload)
                if (geometry == null) {
                    Log.w(TAG, "decode RouteGeometry FAILED payload=${message.payload.size}B")
                    return queueAck(message, ok = false, commandId = 0)
                }
                stageGeometry(geometry)
                if (message.ackRequested) queueAck(message, ok = true, commandId = 0)
            }
            BleSpec.MSG_TRAFFIC_DEVIATION -> {
                val deviation = MessageCodec.decodeTrafficDeviation(message.payload) ?: return
                val current = committedRoute
                if (deviation.routeToken != 0L && current != null &&
                    (deviation.routeToken != current.routeToken || deviation.generation != current.generation)
                ) {
                    return // token/generation 不匹配整条丢弃
                }
                if (deviation.routeInvalidated) {
                    committedRoute = null
                    listener.onRouteInvalidated()
                }
                listener.onTraffic(deviation)
            }
            BleSpec.MSG_MAP_SCENE -> {
                val scene = MessageCodec.decodeMapScene(message.payload)
                if (scene == null) {
                    Log.w(TAG, "decode MapScene FAILED payload=${message.payload.size}B")
                    return queueAck(message, ok = false, commandId = 0)
                }
                listener.onMapScene(scene)
                queueAck(message, ok = true, commandId = 0) // MapScene 建议带 ACK，提交成功后回 Ok
            }
            BleSpec.MSG_MEDIA_STATE -> {
                MessageCodec.decodeMediaState(message.payload)?.let {
                    listener.onMediaState(it)
                }
            }
            else -> {
                // v1 解码器拒绝未知消息类型
            }
        }
    }

    private fun onPhoneConnectionStatus(status: ConnectionStatusData) {
        Log.i(TAG, "rx ConnectionStatus state=${status.state} session=${status.sessionId} maxFrame=${status.maxFrameSize} hb=${status.heartbeatIntervalMs}")
        when (status.state) {
            STATE_STARTING, STATE_READY -> {
                if (status.sessionId == 0L) return
                if (status.sessionId != sessionId) {
                    // 新会话：清空序号基准、重组状态与路线窗口（规范 3.6）
                    resetSession()
                    sessionId = status.sessionId
                    rxLastSequence = 0
                    hasRxSequence = false
                }
                peerMaxFrameSize = status.maxFrameSize.coerceIn(13, BleSpec.MAX_FRAME_VALUE)
                if (status.heartbeatIntervalMs >= 250) {
                    lastHeartbeatIntervalMs = status.heartbeatIntervalMs
                }
                // Phone Starting -> 回 Device Ready；Phone Ready -> 再回 Device Ready 并开门
                sendConnectionStatus(STATE_READY, urgent = true)
                if (status.state == STATE_READY) {
                    if (!protocolReady) {
                        protocolReady = true
                        listener.onProtocolReady()
                    }
                }
            }
            STATE_CLOSING -> resetSession()
        }
    }

    private fun stageGeometry(geometry: RouteGeometryData) {
        val current = committedRoute
        if (geometry.generation > (current?.generation ?: 0L) &&
            geometry.chunkIndex == 0
        ) {
            stagedChunks.clear()
        }
        val existing = stagedChunks
        // staging 内 token/generation/origin/count 必须一致
        val first = existing.values.firstOrNull()
        if (first != null && (first.routeToken != geometry.routeToken ||
                first.generation != geometry.generation ||
                first.totalPointCount != geometry.totalPointCount)
        ) {
            existing.clear()
        }
        existing[geometry.chunkIndex] = geometry
        if (existing.size != geometry.chunkCount) return
        // 完整到达：拼接并原子提交
        val all = ArrayList<Int>(geometry.totalPointCount * 2)
        for (index in 0 until geometry.chunkCount) {
            val chunk = existing[index] ?: run { existing.clear(); return }
            if (chunk.routeToken != geometry.routeToken || chunk.generation != geometry.generation) {
                existing.clear(); return
            }
            if (index == 0 && chunk.firstPointIndex != 0) { existing.clear(); return }
            // 相邻 chunk 接缝：前一块末点与后一块首点为同一路线点序列
            all.addAll(chunk.pointsE6.toList())
        }
        if (all.size / 2 != geometry.totalPointCount) { existing.clear(); return }
        committedRoute = CommittedRoute(
            routeToken = geometry.routeToken,
            generation = geometry.generation,
            originLatE6 = geometry.originLatE6,
            originLonE6 = geometry.originLonE6,
            pointsE6 = all.toIntArray(),
        )
        stagedChunks = HashMap()
        committedRoute?.let { listener.onRouteCommitted(it) }
    }

    // endregion

    // region 发送路径

    private fun nextTxSequence(): Int {
        txSequence = if (txSequence >= 0xFFFF) 1 else txSequence + 1
        return txSequence
    }

    fun sendConnectionStatus(state: Int, urgent: Boolean) {
        if (sessionId == 0L) return
        val payload = MessageCodec.encodeConnectionStatus(
            role = ROLE_DEVICE,
            state = state,
            sessionId = sessionId,
            // 广播稳定的本地帧大小（与 ESP32 一致）；对端声明的值只影响发送分片，
            // 否则两次 Device Ready 不一致，手机会无限重启握手（规范 3 步骤 5）
            maxFrameSize = localFrameValue.coerceIn(20, BleSpec.MAX_FRAME_VALUE),
            heartbeatIntervalMs = lastHeartbeatIntervalMs,
            capabilities = DEVICE_CAPABILITIES,
        )
        sendRaw(payload, urgent, BleSpec.MSG_CONNECTION_STATUS)
    }

    fun sendDeviceCommand(kind: Int, commandId: Int, page: Int, eventTimeMs: Long) {
        val payload = MessageCodec.encodeDeviceCommand(
            kind = kind, commandId = commandId, page = page,
            eventTimeMs = eventTimeMs,
        )
        // DeviceCommand：ACK_REQUESTED + URGENT，最多重试 2 次
        val seq = nextTxSequence()
        val fragments = FrameCodec.segment(
            BleSpec.MSG_DEVICE_COMMAND, ackRequested = true, urgent = true,
            sequence = seq, payload = payload, frameValueSize = negotiatedFrameSize(),
        )
        pendingAck.apply {
            active = true
            sequence = seq
            this.commandId = 0
        }
        fragments.forEach { sendRaw(it, urgent = true) }
    }

    private fun queueAck(message: AssembledMessage, ok: Boolean, commandId: Int) {
        val status = if (ok) ACK_OK else ACK_FAILED
        val payload = MessageCodec.encodeAck(message.sequence, status, commandId)
        recentAcks[message.sequence] = payload
        if (recentAcks.size > 8) {
            val it = recentAcks.entries.iterator()
            it.next(); it.remove()
        }
        sendAckPayload(payload)
    }

    /** ACK 使用新的本方向序号发送，且不再请求 ACK（规范 6.3）。 */
    private fun sendAckPayload(payload: ByteArray) {
        val values = FrameCodec.segment(
            BleSpec.MSG_ACK, ackRequested = false, urgent = false,
            sequence = nextTxSequence(), payload = payload,
            frameValueSize = negotiatedFrameSize(),
        )
        val snapshot = synchronized(devices) { devices.toList() }
        val tx = txCharacteristic ?: return
        for (value in values) {
            for (device in snapshot) {
                notifyValue(device, tx, value)
            }
        }
    }

    private fun negotiatedFrameSize(): Int =
        minOf(localFrameValue, peerMaxFrameSize, BleSpec.MAX_FRAME_VALUE).coerceAtLeast(20)

    private fun sendRaw(payload: ByteArray, urgent: Boolean, type: Int = 0) {
        // payload 是完整 GATT value（帧），或业务 payload（type != 0 时走分段）
        val values = if (type != 0) {
            FrameCodec.segment(
                type, ackRequested = false, urgent = urgent,
                sequence = nextTxSequence(), payload = payload,
                frameValueSize = negotiatedFrameSize(),
            )
        } else {
            listOf(payload)
        }
        val snapshot = synchronized(devices) { devices.toList() }
        val tx = txCharacteristic ?: return
        for (value in values) {
            for (device in snapshot) {
                notifyValue(device, tx, value)
            }
        }
    }

    /** 发送 notify 并记录真实结果——TX 失败在此现形。 */
    private var txLogBudget = 24

    // TX 流控看门狗：实测 OPPO 栈会在连接中途静默停发 notify——
    // notifyCharacteristicChanged 返回 true 但栈不再发送（系统日志无
    // sendNotification），直到客户端重写 CCCD 才恢复。用 onNotificationSent
    // 完成事件计数检测：未完成通知持续超阈值且 3 秒无任何完成事件，判定
    // 停摆，主动断开让 iPhone 走自身重连流程重建链路。
    private var notifyOutstanding = 0
    private var oldestOutstandingMs = 0L

    private fun notifyValue(device: BluetoothDevice, tx: BluetoothGattCharacteristic, value: ByteArray) {
        val ok = runCatching {
            // 三参版本 + setValue，兼容 API 28+（带 value 的四参版本要 API 33）
            tx.setValue(value)
            gattServer?.notifyCharacteristicChanged(device, tx, false)
        }.getOrNull()
        if (ok == true) {
            if (notifyOutstanding == 0) oldestOutstandingMs = System.currentTimeMillis()
            notifyOutstanding++
        } else if (txLogBudget > 0) {
            txLogBudget--
            Log.w(TAG, "notify FAILED device=${device.address} size=${value.size} hex=${value.joinToString("") { "%02x".format(it) }}")
        }
    }

    private fun sendHeartbeat() {
        if (!protocolReady || sessionId == 0L || !subscribed || synchronized(devices) { devices.isEmpty() }) return
        val payload = MessageCodec.encodeHeartbeat(sessionId, System.currentTimeMillis())
        sendRaw(payload, urgent = false, type = BleSpec.MSG_HEARTBEAT)
        if (System.currentTimeMillis() / 1000 % 5 == 0L) {
            val inboundAge = if (lastValidFrameMs == 0L) "never" else "${System.currentTimeMillis() - lastValidFrameMs}ms ago"
            Log.i(TAG, "heartbeat tx session=$sessionId inbound=$inboundAge devices=${synchronized(devices) { devices.joinToString(",") { it.address } }}")
        }
    }

    // endregion

    // region 会话与看门狗

    private fun resetSession() {
        sessionId = 0
        protocolReady = false
        // 注意：不动 subscribed——同一物理链路上的重新握手（手机侧重连）
        // 不重写 CCCD，清掉它会导致心跳永远停发。订阅位只由 CCCD 写入路径管理。
        rx.reset()
        pendingAck.active = false
        recentAcks.clear()
        stagedChunks.clear()
        committedRoute = null
        hasRxSequence = false
        peerMaxFrameSize = BleSpec.MAX_FRAME_VALUE
    }

    private fun notifyLink(active: Boolean) {
        if (linkActive != active) {
            linkActive = active
            listener.onLinkActive(active)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            rx.expire(now)
            if (protocolReady) {
                if (lastValidFrameMs != 0L && now - lastValidFrameMs >= BleSpec.LINK_DEAD_TIMEOUT_MS) {
                    // 保留物理连接，标记链路失活并清除未完成分片（规范 3）
                    rx.reset()
                    notifyLink(false)
                }
                // TX 停摆自愈：栈长时间不回调 onNotificationSent 且积压未清，
                // 主动断开；iPhone 会自动重连并重新握手。
                if (notifyOutstanding > 4 && now - oldestOutstandingMs > 3000) {
                    Log.w(TAG, "TX stall detected (outstanding=$notifyOutstanding for ${now - oldestOutstandingMs}ms); forcing reconnect")
                    notifyOutstanding = 0
                    val victims = synchronized(devices) { devices.toList() }
                    victims.forEach { runCatching { gattServer?.cancelConnection(it) } }
                }
                if (now - lastHeartbeatSentMs >= lastHeartbeatIntervalMs) {
                    lastHeartbeatSentMs = now
                    sendHeartbeat()
                }
            }
            handler.postDelayed(this, 250)
        }
    }
    private var lastHeartbeatSentMs = 0L

    // endregion

    companion object {
        private const val TAG = "MotoBlePeripheral"
        const val ROLE_DEVICE = 2
        const val STATE_STARTING = 0
        const val STATE_READY = 1
        const val STATE_CLOSING = 3
        const val ACK_OK = 0
        const val ACK_FAILED = 3
        const val DEVICE_CAPABILITIES =
            BleSpec.CAP_NAVIGATION or BleSpec.CAP_ROUTE_GEOMETRY or BleSpec.CAP_TRAFFIC or
                BleSpec.CAP_MEDIA_STATE or BleSpec.CAP_TOUCH_COMMANDS or BleSpec.CAP_MUSIC_COMMANDS or
                BleSpec.CAP_COMMAND_ACK or BleSpec.CAP_MAP_SCENE
    }
}
