package com.motogps.watch

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * 手表端入口：全屏、亮屏常驻、启动 BLE 外设端。
 * 打开 App 即广播，iPhone MOTO GPS App 会自动扫描并连接（类 CarPlay 体验）。
 */
class MainActivity : AppCompatActivity(), BlePeripheral.Listener {

    private lateinit var roundView: RoundView
    private var ble: BlePeripheral? = null
    private val uiState = NavUiState()
    private var nextCommandId = 0

    // 横向拖拽翻页状态（跟手滑动）
    private var dragging = false
    private var swipeTarget = -1
    private var flingVx = 0f

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) {
                startBle()
            } else {
                Toast.makeText(this, "需要蓝牙权限才能与手机连接", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        // 前台保活：防 OPPO 激进后台管理杀进程（息屏后回主页/断链）
        KeepAliveService.start(this)
        requestIgnoreBatteryOptimizationOnce()
        roundView = RoundView(this)
        roundView.state = uiState
        // 手表罗盘 → 航向融合源（静止/低速时地图跟随手表旋转，防 GPS 漂移乱飞）
        roundView.compassHeadingProvider = { headingFusion.headingDeg }
        headingFusion.onChange = {
            if (!compassLogged) {
                compassLogged = true
                Log.i("MotoUI", "compass live: heading=${headingFusion.headingDeg}")
            }
            roundView.onCompassChanged()
        }
        setContentView(roundView)

        val gesture = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (uiState.page == 2) { // 音乐页（本地 2 = 协议页 3）
                        val btn = roundView.musicButtonAt(e.x, e.y)
                        if (btn >= 0) {
                            sendCommand(kind = 16 + btn, page = 3) // 16上一首 17播放暂停 18下一首
                            return true
                        }
                    }
                    sendCommand(kind = 1, page = uiState.page) // Tap
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
                ): Boolean {
                    flingVx = velocityX
                    if (dragging) return true // 横向拖拽翻页中，fling 只用于松手判定
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    Log.d("MotoUI", "fling dx=$dx dy=$dy vx=$velocityX vy=$velocityY page=${uiState.page}")
                    if (abs(dx) > abs(dy) && abs(dx) > 60) {
                        if (dx < 0 && uiState.page == 1) {
                            // 音乐页不入本地滑动循环：直接拉起系统音乐控制（不切本地页）
                            launchSystemMusicController()
                        } else {
                            // 拖拽未启动的斜向快滑兑底：直接带动画翻页（与拖拽同一提交链路）
                            val target = uiState.page + if (dx < 0) 1 else -1
                            if (target in 0..1) {
                                roundView.beginPageSwipe(uiState.page, target)
                                roundView.settlePageSwipe(target)
                            }
                        }
                        return true
                    }
                    val kind = when {
                        dy < 0 -> 5  // SwipeUp
                        else -> 6    // SwipeDown
                    }
                    sendCommand(kind = kind, page = uiState.page)
                    return true
                }
            },
        )
        // 页面滑动结算：RoundView 动画结束后回调（本地页 0导航 1速度 2音乐）
        roundView.onPageSettled = { target -> commitPage(target) }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        roundView.setOnTouchListener { v, event ->
            gesture.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    flingVx = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        // 本地滑动循环仅 0导航↔1速度；2 音乐只由手机快照驱动显示，
                        // 不参与拖拽过渡（滑向音乐直接拉起系统音乐控制，见 onFling）。
                        // 方向门限放宽为主轴判定，斜向滑动也能启动翻页。
                        val from = uiState.page
                        val target = from + if (dx < 0) 1 else -1
                        if (target in 0..1) {
                            dragging = true
                            swipeTarget = target
                            roundView.beginPageSwipe(from, target)
                        }
                    }
                    if (dragging) {
                        roundView.updatePageSwipe(abs(event.x - downX) / v.width.toFloat())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        val from = uiState.page
                        val forward = roundView.currentSwipeProgress > 0.34f ||
                            (swipeTarget > from && flingVx < -650f) ||
                            (swipeTarget < from && flingVx > 650f)
                        roundView.settlePageSwipe(if (forward) swipeTarget else from)
                    }
                }
            }
            v.performClick()
            true
        }
        requestBlePermissionsOrStart()
    }

    private val headingFusion by lazy { HeadingFusion(this) }
    private var compassLogged = false

    /** 屏幕亮屏锁：OPPO 会在无触摸后压暗/息屏第三方应用，keepScreenOn 之上再加一道保险。 */
    private var screenWakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireScreenLock() {
        if (screenWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            screenWakeLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "MotoGPS:screen",
            ).apply { setReferenceCounted(false) }
        }
        screenWakeLock?.acquire(4 * 60 * 60 * 1000L) // 单次骑行上限 4 小时，防泄漏
    }

    private fun releaseScreenLock() {
        screenWakeLock?.takeIf { it.isHeld }?.release()
    }

    /**
     * 请求电池优化白名单；OPPO 杀后台的主要防线。
     * 注意：不要用 resolveActivity 探测 —— Android 11+ 包可见性限制下系统设置页查不到，
     * 会静默失败。已入白名单则不弹；直接弹确认框，失败再退到白名单列表页。
     */
    private fun requestIgnoreBatteryOptimizationOnce() {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val direct = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(android.net.Uri.parse("package:$packageName"))
        runCatching { startActivity(direct) }.onFailure {
            // 兜底：打开"电池优化"应用列表页，让用户手动把 MOTO GPS 设为不优化
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        acquireScreenLock()
        if (headingFusion.available) headingFusion.start()
    }

    override fun onPause() {
        releaseScreenLock()
        headingFusion.stop()
        super.onPause()
    }

    override fun onDestroy() {
        releaseScreenLock()
        headingFusion.stop()
        ble?.stop()
        ble = null
        super.onDestroy()
    }

    private fun abs(v: Float): Float = kotlin.math.abs(v)

    private fun hideSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun requestBlePermissionsOrStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = arrayOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            ).filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                permissionLauncher.launch(needed.toTypedArray())
                return
            }
        }
        startBle()
    }

    private fun startBle() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (manager.adapter?.isEnabled != true) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_LONG).show()
            return
        }
        val peripheral = BlePeripheral(applicationContext, this)
        if (!peripheral.start()) {
            Toast.makeText(this, "BLE 外设初始化失败（检查蓝牙/开发者选项）", Toast.LENGTH_LONG).show()
            return
        }
        ble = peripheral
        uiState.advertising = true
        roundView.state = uiState
    }

    private fun sendCommand(kind: Int, page: Int) {
        val peripheral = ble ?: return
        nextCommandId = if (nextCommandId >= 0xFFFF) 1 else nextCommandId + 1
        // 本地页→协议页映射（协议 2 航向已移除，本地 2 音乐 = 协议 3）
        val protoPage = if (page == 2) 3 else page
        peripheral.sendDeviceCommand(
            kind = kind,
            commandId = nextCommandId,
            page = protoPage,
            eventTimeMs = SystemClock.uptimeMillis(),
        )
    }

    /** 滑动过渡结束后的页面提交（本地页：0导航 1速度；音乐页不进入拖拽循环）。 */
    private fun commitPage(target: Int) {
        val changed = target != uiState.page
        if (changed) {
            // 记录本次乐观切换的来源页：短暂时间窗内的旧快照回显将被忽略（防弹回）
            pageSwitchFrom = uiState.page
            pageSwitchAt = SystemClock.elapsedRealtime()
        }
        uiState.page = target
        roundView.state = uiState
        if (changed) {
            sendCommand(kind = 0, page = target)
        }
    }

    private var pageSwitchAt = 0L
    private var pageSwitchFrom = -1

    /** 拉起 OPPO 内置音乐控制（系统级联动手机播放），优先官方蓝牙音乐动作。 */
    private fun launchSystemMusicController() {
        val action = Intent("heytap.wearable.intent.action.music.BLUETOOTH_MUSIC").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(action) }.onFailure {
            runCatching {
                startActivity(
                    Intent().setClassName(
                        "com.heytap.wearable.musiccontroller",
                        "com.heytap.wearable.musiccontroller.activity.MainActivity",
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { e -> Log.w("MotoUI", "launch musiccontroller failed", e) }
        }
    }

    // region BlePeripheral.Listener（主线程回调）

    override fun onCentralConnected(connected: Boolean) {
        uiState.centralConnected = connected
        roundView.state = uiState
    }

    override fun onProtocolReady() {
        uiState.protocolReady = true
        roundView.state = uiState
    }

    override fun onLinkActive(active: Boolean) {
        uiState.linkActive = active
        roundView.state = uiState
    }

    override fun onDisconnected() {
        uiState.protocolReady = false
        uiState.linkActive = true
        uiState.snapshot = null
        uiState.route = null
        uiState.scene = null
        roundView.state = uiState
    }

    override fun onSnapshot(snapshot: NavSnapshotData) {
        uiState.snapshot = snapshot
        // 手机是页面权威：快照 display_page 同步本地页。
        // 协议页 0导航 1速度 2航向(已移除，忽略) 3音乐(本地 2)。
        val localPage = when (snapshot.displayPage) {
            0 -> 0
            1 -> 1
            3 -> 2
            else -> -1
        }
        if (localPage >= 0 && localPage != uiState.page) {
            // 快照是 5Hz 的：本地乐观切换后 1.5s 内来自「切换前页面」的回显是旧状态，
            // 忽略之，否则会概率性弹回旧页（用户反馈「切换没成功」的主因）。
            val staleEcho = localPage == pageSwitchFrom &&
                SystemClock.elapsedRealtime() - pageSwitchAt < 1500
            if (!staleEcho) {
                uiState.page = localPage
            }
        }
        if (++snapLogCounter % 30 == 1) {
            Log.i(
                "MotoUI",
                "nav route=${uiState.route?.let { "tok=${it.routeToken} gen=${it.generation} pts=${it.pointsE6.size / 2}" } ?: "null"} " +
                    "scene=${uiState.scene?.let { "roads=${it.roads.size} bld=${it.buildings.size}" } ?: "null"} " +
                    "flags=0x${snapshot.flags.toString(16)} maneuver=${snapshot.maneuver} dist=${snapshot.distanceToManeuverM}",
            )
        }
        roundView.state = uiState
    }

    private var snapLogCounter = 0

    override fun onRouteCommitted(route: BlePeripheral.CommittedRoute) {
        uiState.route = route
        roundView.state = uiState
    }

    override fun onRouteInvalidated() {
        uiState.route = null
        roundView.state = uiState
    }

    override fun onMapScene(scene: MapSceneData) {
        uiState.scene = scene
        roundView.state = uiState
    }

    override fun onTraffic(deviation: TrafficDeviationData) {
        // MVP：路况分段不渲染，偏航状态由快照 flags 驱动
    }

    override fun onMediaState(state: MediaStateData) {
        uiState.media = state
        roundView.state = uiState
    }

    // endregion
}
