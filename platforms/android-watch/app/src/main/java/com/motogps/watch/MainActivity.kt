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
                    if (uiState.page == 3) {
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
                    val dx = e2.x - (e1?.x ?: e2.x)
                    val dy = e2.y - (e1?.y ?: e2.y)
                    Log.d("MotoUI", "fling dx=$dx dy=$dy vx=$velocityX vy=$velocityY page=${uiState.page}")
                    if (abs(dx) > abs(dy) && abs(dx) > 60) {
                        // 原版四页：导航→速度→航向→音乐；左滑下一页。
                        // 手机端只认 kind=0 PageSelected（滑动 kind 3/4 会被拒收），
                        // 本地乐观切换，快照 display_page 回来后以手机为准。
                        // 音乐页例外：OPPO 手表内置音乐控制（com.heytap.wearable.
                        // musiccontroller）已通过系统蓝牙联动手机播放，直接拉起，
                        // 本地页保持不变（返回时仍停在航向页）。
                        val delta = if (dx < 0) 1 else -1
                        val newPage = (uiState.page + delta + 4) % 4
                        if (newPage == 3) {
                            launchSystemMusicController()
                        } else {
                            uiState.page = newPage
                            roundView.state = uiState
                            sendCommand(kind = 0, page = newPage)
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
        roundView.setOnTouchListener { v, event ->
            gesture.onTouchEvent(event)
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
        peripheral.sendDeviceCommand(
            kind = kind,
            commandId = nextCommandId,
            page = page,
            eventTimeMs = SystemClock.uptimeMillis(),
        )
    }

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
        // 手机是页面权威：快照 display_page 变化时同步本地页（0导航 1速度 2航向 3音乐）
        if (snapshot.displayPage in 0..3 && snapshot.displayPage != uiState.page) {
            uiState.page = snapshot.displayPage
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
