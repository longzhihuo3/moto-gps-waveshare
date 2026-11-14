package com.motogps.watch

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Wi-Fi 热点直传通道（方案 1）：
 * iPhone 开个人热点 → 手表以客户端加入 → 双端同处 172.20.10.x/28 局域网。
 * 手表做 TCP 服务端（端口 23333），iPhone 扫描网段发现后连入并推送大数据：
 *   - snapshot：高频导航快照（BLE 5Hz 之外的高速通道）
 *   - route   ：完整路线折线（BLE 分块慢，Wi-Fi 一帧到位）
 * 帧格式：4 字节大端长度 + UTF-8 JSON。
 * BLE 仍是命令与保底通道；本服务只收不发大数据，断开无副作用。
 */
class WifiLinkServer(
    private val listener: Listener,
    private val mainThread: (Runnable) -> Unit,
) {
    interface Listener {
        /** Wi-Fi 链路建立/断开（主线程回调）。 */
        fun onWifiLinkChanged(active: Boolean)

        /** 收到 Wi-Fi 快照（主线程回调，字段与 BLE 快照同义）。 */
        fun onWifiSnapshot(snapshot: NavSnapshotData)

        /** 收到完整路线折线（主线程回调，绝对 GCJ-02 E6：[lat0,lon0,lat1,lon1,...]）。 */
        fun onWifiRoute(pointsE6: IntArray, routeToken: Long)
    }

    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    companion object {
        const val PORT = 23333
        private const val TAG = "MotoWifi"
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(PORT))
                serverSocket = server
                Log.i(TAG, "tcp server listening on :$PORT")
                while (running.get()) {
                    val client = server.accept()
                    Log.i(TAG, "client connected: ${client.inetAddress?.hostAddress}")
                    serve(client)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept loop ended: ${e.message}")
            } finally {
                runCatching { serverSocket?.close() }
            }
        }, "MotoWifi-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        acceptThread = null
    }

    /** 单客户端串行服务：读帧直到断开，然后回到 accept 循环。 */
    private fun serve(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = 0
        clientSocket = client
        notifyLink(true)
        try {
            val input = BufferedInputStream(client.getInputStream(), 64 * 1024)
            val header = ByteArray(4)
            while (running.get() && !client.isClosed) {
                readFully(input, header)
                val len = ((header[0].toInt() and 0xFF) shl 24) or
                    ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                if (len <= 0 || len > 8 * 1024 * 1024) {
                    Log.w(TAG, "bad frame length $len, dropping client")
                    break
                }
                val payload = ByteArray(len)
                readFully(input, payload)
                handleFrame(String(payload, Charsets.UTF_8), client)
            }
        } catch (e: Exception) {
            if (running.get()) Log.i(TAG, "client disconnected: ${e.message}")
        } finally {
            runCatching { client.close() }
            if (clientSocket === client) clientSocket = null
            notifyLink(false)
        }
    }

    private fun readFully(input: BufferedInputStream, buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) throw EOFException("stream ended")
            off += n
        }
    }

    private fun handleFrame(text: String, client: Socket) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "bad json: ${text.take(80)}")
            return
        }
        when (json.optString("type")) {
            "hello" -> {
                Log.i(TAG, "hello from ${json.optString("name")}, ver=${json.optInt("ver")}")
                send(client, JSONObject().put("type", "welcome").put("ver", 1))
            }
            "ping" -> send(client, JSONObject().put("type", "pong").put("t", json.optLong("t")))
            "snapshot" -> {
                val snap = parseSnapshot(json) ?: return
                mainThread { listener.onWifiSnapshot(snap) }
            }
            "route" -> {
                val arr = json.optJSONArray("points") ?: return
                val pts = IntArray(arr.length())
                for (i in 0 until arr.length()) pts[i] = arr.optInt(i)
                if (pts.size >= 4) {
                    mainThread { listener.onWifiRoute(pts, json.optLong("token")) }
                }
            }
        }
    }

    private fun send(client: Socket, json: JSONObject) {
        try {
            val out = client.getOutputStream()
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val header = byteArrayOf(
                (payload.size ushr 24).toByte(),
                (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            )
            val buffered = BufferedOutputStream(out, 16 * 1024)
            buffered.write(header)
            buffered.write(payload)
            buffered.flush()
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    private fun notifyLink(active: Boolean) {
        mainThread { listener.onWifiLinkChanged(active) }
    }

    /** 快照 JSON → NavSnapshotData（缺省 0/空串，与 BLE 快照字段一一对应）。 */
    private fun parseSnapshot(json: JSONObject): NavSnapshotData? {
        if (!json.has("state")) return null
        return NavSnapshotData(
            state = json.optInt("state"),
            network = json.optInt("network"),
            displayPage = json.optInt("displayPage"),
            maneuver = json.optInt("maneuver"),
            traffic = json.optInt("traffic"),
            flags = json.optInt("flags"),
            routeToken = json.optLong("routeToken"),
            routeGeneration = json.optLong("routeGeneration"),
            maneuverId = json.optLong("maneuverId"),
            distanceToManeuverM = json.optLong("distanceToManeuverM"),
            remainingDistanceM = json.optLong("remainingDistanceM"),
            remainingDurationS = json.optLong("remainingDurationS"),
            routeProgressM = json.optLong("routeProgressM"),
            totalDistanceM = json.optLong("totalDistanceM"),
            speedDeciKph = json.optInt("speedDeciKph"),
            speedLimitKph = json.optInt("speedLimitKph"),
            headingCdeg = json.optInt("headingCdeg"),
            accuracyDm = json.optInt("accuracyDm"),
            crossTrackDm = json.optInt("crossTrackDm"),
            roundaboutExit = json.optInt("roundaboutExit"),
            roadName = json.optString("roadName", ""),
            instruction = json.optString("instruction", ""),
        )
    }
}
