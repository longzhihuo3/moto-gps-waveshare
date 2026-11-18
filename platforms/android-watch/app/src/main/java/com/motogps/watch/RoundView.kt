package com.motogps.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** UI 层聚合状态（主线程更新后 invalidate）。page: 0导航 1速度 2音乐（协议页 3，航向页已移除）。 */
data class NavUiState(
    var advertising: Boolean = false,
    var centralConnected: Boolean = false,
    var protocolReady: Boolean = false,
    var linkActive: Boolean = true,
    var wifiLink: Boolean = false,
    var page: Int = 0,
    var snapshot: NavSnapshotData? = null,
    var route: BlePeripheral.CommittedRoute? = null,
    var scene: MapSceneData? = null,
    var media: MediaStateData? = null,
)

/**
 * 极简圆屏 UI：黑底 + 白色路线 + 灰色周边道路/建筑 + 转向图标 + 大号距离 + 底部进度弧。
 * 布局参照 shared/nav_ui 的导航页（heading-up，车头箭头固定）。
 */
class RoundView(context: Context) : View(context) {

    var state = NavUiState()
        set(value) {
            field = value
            if (value.snapshot != null) startAnim()
            invalidate()
        }

    // ---- 插值动画层：BLE 快照 ≤5Hz，在更新之间用 60fps 缓动填充，消除跳变感 ----
    private var dispSpeedKph = 0f
    private var dispHeading = Float.NaN // 首帧直接对齐
    private var maxSpeedKph = 0f
    private var speedTrend = 0f // 正=加速 负=减速（每秒变化量）
    private var animating = false
    private var lastFrameNs = 0L
    private val choreographer = Choreographer.getInstance()

    // 航向源融合：速度足够时用 GPS 行进方向（真实航向），静止/低速用手表
    // 罗盘（GPS 低速漂移会让地图/指针乱飞；罗盘让转动手表时地图跟着转）。
    // gpsHeadingMode 带迟滞（>2.0 进 / <1.5 出）避免模式来回抖动。
    private var gpsHeadingMode = false
    var compassHeadingProvider: (() -> Float)? = null

    // 显示原点平滑：GPS 定位 ±5~15m 抖动若直接驱动地图平移会剧烈晃动，
    // 用缓动追踪快照原点（scene.origin），低速时更慢（几乎冻结），骑行中跟随。
    private var dispLatE6 = Double.NaN
    private var dispLonE6 = Double.NaN

    // ---- 地图层离屏缓存（Glimpse/高德同款性能方案）：底图+路线渲染进一张朝北位图，
    // 每帧只做一次位图旋转+平移，不再每帧重投影全部路径 → 大幅转动不再掉帧卡顿 ----
    private var mapBitmap: Bitmap? = null
    private var mapBmpCanvas: Canvas? = null
    private var bmpOriginLatE6 = 0.0
    private var bmpOriginLonE6 = 0.0
    private var bmpMPerDegLon = 111_320f
    private var lastMapKey = Long.MIN_VALUE
    private val mapBmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tmpPt = FloatArray(2)

    /** 罗盘更新通知（静止转动时快照不变，需主动起帧循环）。 */
    fun onCompassChanged() {
        if (state.snapshot != null) startAnim()
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(ns: Long) {
            val dt = if (lastFrameNs == 0L) 0.016f else ((ns - lastFrameNs) / 1e9f).coerceIn(0.001f, 0.1f)
            lastFrameNs = ns
            // 页面滑动过渡独立于快照驱动（无快照时也能动画）
            val swipeStill = tickSwipe(dt)
            val stillMoving = swipeStill || (state.snapshot?.let { tickAnimation(it, dt) } ?: false)
            // 生命周期页（连接/空闲）持续呼吸动画；无其它动画时限帧 ~30fps 省电
            lifecycleT += dt
            if (stillMoving) {
                invalidate()
                choreographer.postFrameCallback(this)
            } else if (isLifecycleScreen()) {
                invalidate()
                postDelayed({ startAnim() }, 32)
                animating = false
            } else {
                animating = false
            }
        }
    }

    private fun startAnim() {
        if (animating) return
        animating = true
        lastFrameNs = 0L
        choreographer.postFrameCallback(frameCallback)
    }

    // ---- 页面滑动过渡（类系统 ViewPager 手感）：拖拽跟手 + 松手缓动收尾 ----
    /** 滑动结算落地（target 为本地页 0..2），主线程回调；Activity 在此提交页面与命令。 */
    var onPageSettled: ((Int) -> Unit)? = null
    private var swipeFromPage = -1
    private var swipeTargetPage = -1
    private var swipeProgress = 0f // 0=起始页 1=目标页
    private var settling = false
    private var settleFrom = 0f
    private var settleTarget = 0f
    private var settleElapsedMs = 0L
    private var settleDurationMs = 0L

    /** 当前拖拽进度（0..1），Activity 松手时判定是否翻页用。 */
    val currentSwipeProgress: Float get() = swipeProgress

    /** 开始一次页面滑动（from/target 均为本地页）。若上次过渡未结束，先按最近页即时结算。 */
    fun beginPageSwipe(from: Int, target: Int) {
        if (swipeTargetPage >= 0) {
            val nearest = if (swipeProgress > 0.5f) swipeTargetPage else swipeFromPage
            swipeTargetPage = -1
            onPageSettled?.invoke(nearest) // commitPage 内部会判重，重复结算无副作用
        }
        swipeFromPage = from
        swipeTargetPage = target
        swipeProgress = 0f
        settling = false
        startAnim()
        invalidate()
    }

    /** 拖拽跟手：progress 0..1。 */
    fun updatePageSwipe(progress: Float) {
        if (swipeTargetPage < 0 || settling) return
        swipeProgress = progress.coerceIn(0f, 1f)
        startAnim()
        invalidate()
    }

    /** 松手：以缓动动画滑到 target（只能是起始页或目标页）。 */
    fun settlePageSwipe(target: Int) {
        if (swipeTargetPage < 0) return
        settling = true
        settleFrom = swipeProgress
        settleTarget = if (target == swipeTargetPage) 1f else 0f
        settleElapsedMs = 0L
        // 距离越远时长略长，但上限压短，保持「干脆」的系统手感
        settleDurationMs = (110f + 160f * abs(settleTarget - settleFrom)).toLong().coerceIn(90L, 260L)
        startAnim()
    }

    /** 每帧推进滑动过渡；返回是否仍在动画。 */
    private fun tickSwipe(dt: Float): Boolean {
        if (swipeTargetPage < 0) return false
        if (!settling) return true
        settleElapsedMs += (dt * 1000f).toLong()
        val t = (settleElapsedMs.toFloat() / settleDurationMs).coerceIn(0f, 1f)
        val ease = 1f - (1f - t) * (1f - t) // 二次缓出（系统 decelerate 同族）
        swipeProgress = settleFrom + (settleTarget - settleFrom) * ease
        if (t >= 1f) {
            val done = swipeTargetPage
            swipeTargetPage = -1
            onPageSettled?.invoke(done)
            invalidate()
            return false
        }
        return true
    }

    /** 向目标值缓动；返回是否仍需要继续画帧。 */
    private fun tickAnimation(s: NavSnapshotData, dt: Float): Boolean {
        var moving = false
        // 规划页：路线描画循环（约 1.9s 一轮），驱动帧循环
        if (s.state == 1 || s.state == 2 || s.routeRequestInFlight) {
            routeAnimT = (routeAnimT + dt / 1.9f) % 1f
            moving = true
        }
        // 速度：指数缓动（时间常数 ~100ms），并维护峰值/趋势
        val targetSpeed = s.speedDeciKph / 10f
        val alpha = 1f - exp(-dt * 10f)
        val prevSpeed = dispSpeedKph
        dispSpeedKph += (targetSpeed - dispSpeedKph) * alpha
        if (abs(targetSpeed - dispSpeedKph) > 0.05f) moving = true
        if (dispSpeedKph > maxSpeedKph) maxSpeedKph = dispSpeedKph
        speedTrend = speedTrend * 0.85f + ((dispSpeedKph - prevSpeed) / dt) * 0.15f
        // 航向源选择（迟滞切换，骑行 ≥9km/h 才信 GPS 航向）+ 最短路径缓动
        val speedMps = s.speedDeciKph / 36f
        gpsHeadingMode = if (gpsHeadingMode) speedMps > 2.0f else speedMps > 2.5f
        val gpsH = ((s.headingCdeg / 100f) % 360f + 360f) % 360f
        val compassH = compassHeadingProvider?.invoke() ?: Float.NaN
        val targetHeading = when {
            gpsHeadingMode -> gpsH
            !compassH.isNaN() -> compassH
            else -> gpsH
        }
        if (dispHeading.isNaN()) {
            dispHeading = targetHeading
        } else {
            // 回退到用户实测「改进很大」版本的缓动参数（2026-11-14）：
            // 死区 0.2°；GPS 源 240°/s（正常骑行转弯 ≈90°/s），罗盘源 300°/s 防跳变；
            // 自适应缓动（高德/百度同款手感）：小误差慢跟压噪声，大误差快跟保灵敏。
            var diff = ((targetHeading - dispHeading + 540f) % 360f) - 180f
            if (abs(diff) > 0.2f) moving = true
            val maxStep = (if (gpsHeadingMode) 240f else 300f) * dt
            if (abs(diff) > maxStep) diff = kotlin.math.sign(diff) * maxStep
            val easeRate = if (gpsHeadingMode) 20f
            else 4f + 14f * (abs(diff) / 25f).coerceIn(0f, 1f)
            dispHeading = (dispHeading + diff * (1f - exp(-dt * easeRate)) + 360f) % 360f
        }
        // 位置缓动：追踪 scene 原点（用户位置），抑制 GPS 抖动平移。
        // 低速（含静止）rate 3/s 几乎冻结（静止漂移不晃图），骑行 8/s 跟随。
        // 位置缓动同样驱动帧循环（>0.5m 残差时），否则落格跳变。
        state.scene?.let { sc ->
            if (dispLatE6.isNaN()) {
                dispLatE6 = sc.originLatE6.toDouble()
                dispLonE6 = sc.originLonE6.toDouble()
            } else {
                val dLatM = (sc.originLatE6 - dispLatE6) / 1e6 * 111_320.0
                val dLonM = (sc.originLonE6 - dispLonE6) / 1e6 * bmpMPerDegLon
                if (dLatM * dLatM + dLonM * dLonM > 0.25) moving = true
                val pa = 1f - exp(-dt * (if (speedMps < 1f) 3f else 8f))
                dispLatE6 += (sc.originLatE6 - dispLatE6) * pa
                dispLonE6 += (sc.originLonE6 - dispLonE6) * pa
            }
        }
        return moving
    }

    /** 绘制用平滑值；动画未启动时直接用目标。 */
    private fun smoothSpeed(): Float = dispSpeedKph
    private fun smoothHeading(s: NavSnapshotData): Float =
        if (dispHeading.isNaN()) ((s.headingCdeg / 100f) % 360f + 360f) % 360f else dispHeading

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 路线外缘低饱和冷色描边（规范 3.2：夜间轮廓增强）
    private val routeUnderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF15202E.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3C3C3C.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
    }
    private val buildingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1F1F1F.toInt()
        style = Paint.Style.FILL
    }
    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val wifiDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(76, 217, 100) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    // 生命周期页（连接/空闲）画笔
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8D8D8.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF34C368.toInt() }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A9A9A.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val arcTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF262626.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // 进度弧：亮橙色（参照原版骑行 UI，夜间醒目）
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF8C1A.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcRect = RectF()
    private val scratchPath = Path()
    private val clipPath = Path() // 缓存：避免每帧分配

    // 多页 UI 画笔
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A5A5A.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9FDDF2.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB8B8B8.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val discFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt() }
    private val discRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A4A4A.toInt()
        style = Paint.Style.STROKE
    }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A3A.toInt()
        style = Paint.Style.STROKE
    }
    // 限速牌：红环白底黑字
    private val limitBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val limitRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE03A3A.toInt()
        style = Paint.Style.STROKE
    }
    private val limitTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1A1A.toInt()
        textAlign = Paint.Align.CENTER
    }
    // ---- 速度页大表盘 + 导航页顶部速度（借鉴 gps-speedometer / Garmin：模拟指针 + 大号数字）----
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val speedNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val gaugeLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9E9E9E.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val navSpeedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val navUnitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        textAlign = Paint.Align.CENTER
    }
    // 刻度数字缓存（仅最大刻度变化时重建字符串，避免 60fps 逐帧分配）
    private var gaugeMaxScale = -1f
    private val gaugeLabels = ArrayList<Pair<String, Float>>()

    /** 速度色阶：低速绿 → 中速橙 → 高速红（表盘弧与数字环共用）。 */
    private fun speedColor(fraction: Float): Int = when {
        fraction < 0.5f -> 0xFF3FD57C.toInt()
        fraction < 0.8f -> 0xFFFF8C1A.toInt()
        else -> 0xFFE24B4A.toInt()
    }

    // 距离文本缓存（快照 5Hz 更新，文本不随 60fps 动画帧变化）
    private var cachedDistM = -1L
    private var cachedDistText = ""

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = min(w, h) / 2f
        canvas.drawPaint(bgPaint)
        // 圆形裁剪（Path 缓存复用，仅尺寸变化时重建）
        canvas.save()
        val clipR = radius * 0.98f
        clipPath.rewind()
        clipPath.addCircle(cx, cy, clipR, Path.Direction.CW)
        canvas.clipPath(clipPath)

        updateLifecycleClock()
        val s = state
        val lifecycle = when {
            !s.centralConnected && s.advertising -> {
                drawConnectScreen(canvas, cx, cy, radius, "CONNECT PHONE", "请打开手机应用", fast = false, alert = false); true
            }
            !s.protocolReady -> {
                drawConnectScreen(canvas, cx, cy, radius, "CONNECTING", "正在建立连接", fast = true, alert = false); true
            }
            uptimeMillis() - protocolReadyAt < 920L -> {
                // 离线→在线边沿：绿勾轻呼吸 920ms 后自动进入下一状态（原版同款）
                drawConnectedScreen(canvas, cx, cy, radius); true
            }
            !s.linkActive -> {
                drawConnectScreen(canvas, cx, cy, radius, "SIGNAL LOST", "请确认手机 MOTO GPS App 在前台", fast = false, alert = true); true
            }
            else -> false
        }
        if (lifecycle) {
            startAnim()
        } else if (swipeTargetPage >= 0) {
            // 双页滑动过渡：两页并排横移（dir=+1 内容左移翻下一页）
            val dir = swipeTargetPage - swipeFromPage
            val w = width.toFloat()
            canvas.save()
            canvas.translate(-dir * swipeProgress * w, 0f)
            drawPage(canvas, cx, cy, radius, swipeFromPage)
            canvas.restore()
            canvas.save()
            canvas.translate(dir * (1f - swipeProgress) * w, 0f)
            drawPage(canvas, cx, cy, radius, swipeTargetPage)
            canvas.restore()
        } else {
            drawPage(canvas, cx, cy, radius, state.page)
        }
        // 页点：滑动中以视觉页为准；过渡期间始终显示给出位置反馈
        val visualPage = if (swipeTargetPage >= 0 && swipeProgress > 0.5f) swipeTargetPage else state.page
        if (state.page != 0 || swipeTargetPage >= 0) drawPageDots(canvas, cx, cy, radius, visualPage)
        // Wi-Fi 直传指示：绿点 = iPhone 热点通道已建立（右缘中部，避让顶部状态区）
        if (state.wifiLink) {
            wifiDotPaint.alpha = if (lifecycleT % 1.6f < 1.2f) 255 else 120
            canvas.drawCircle(cx + radius * 0.80f, cy, radius * 0.022f, wifiDotPaint)
        }
        canvas.restore()
    }

    private fun uptimeMillis() = android.os.SystemClock.uptimeMillis()

    /** 绘制单页内容（本地页：0导航 1速度 2音乐）。 */
    private fun drawPage(canvas: Canvas, cx: Float, cy: Float, radius: Float, page: Int) {
        val s = state
        when (page) {
            1 -> s.snapshot?.let { drawSpeed(canvas, cx, cy, radius, it) }
            2 -> drawMusic(canvas, cx, cy, radius, s.media)
            else -> {
                val snapshot = s.snapshot
                if (snapshot == null || snapshot.state == 0) {
                    drawReadyToRide(canvas, cx, cy, radius)
                } else if (snapshot.state == 1 || snapshot.state == 2 || snapshot.routeRequestInFlight) {
                    // 已在出路线流程（等待定位/规划中/请求在途）：原版 BUILDING ROUTE 页。
                    // 「等待定位」也算——会话开始后骑手视角就是在规划路线，
                    // 且纯规划请求往往几百毫秒就结束，只认 state==2 会一闪而过。
                    drawBuildingRoute(canvas, cx, cy, radius)
                } else {
                    drawNavigation(canvas, cx, cy, radius, snapshot)
                }
            }
        }
    }

    // region 规划页（对齐原版 WAVESHARE 原型：白线沿路线图形循环描画）

    private var routeAnimT = 0f

    /** 装饰性路线图形（归一化坐标，x 右正 y 上正；首点=起点，末点=终点圆环）。 */
    private val planningRoutePts = floatArrayOf(
        -0.52f, 0.28f,
        -0.20f, 0.52f,
        -0.02f, 0.20f,
        0.24f, 0.44f,
        0.58f, 0.64f,
    )

    /** Wi-Fi 直传收到的真实路线（归一化坐标，同上约定）；空则用装饰图形。 */
    private var detailedRoutePts: FloatArray? = null

    /** 手机端经 Wi-Fi 推来的完整路线；传 null 恢复装饰图形。 */
    fun setDetailedRoute(pts: FloatArray?) {
        detailedRoutePts = if (pts != null && pts.size >= 4) pts else null
        invalidate()
    }

    private fun drawBuildingRoute(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val gy = cy - radius * 0.22f
        val sx = radius * 0.70f
        val sy = radius * 0.48f
        val pts = detailedRoutePts ?: planningRoutePts
        val n = pts.size / 2
        fun mx(i: Int) = cx + pts[i * 2] * sx
        fun my(i: Int) = gy - pts[i * 2 + 1] * sy

        // 段长与累计长度（部分描画用）
        val segLen = FloatArray(n - 1)
        val cum = FloatArray(n)
        var total = 0f
        for (i in 0 until n - 1) {
            segLen[i] = kotlin.math.hypot(mx(i + 1) - mx(i), my(i + 1) - my(i))
            cum[i + 1] = cum[i] + segLen[i]
            total += segLen[i]
        }

        // 底层深灰描边（全长，视频同款阴影）
        routeUnderPaint.strokeWidth = radius * 0.052f
        scratchPath.reset()
        for (i in 0 until n) {
            if (i == 0) scratchPath.moveTo(mx(i), my(i)) else scratchPath.lineTo(mx(i), my(i))
        }
        canvas.drawPath(scratchPath, routeUnderPaint)

        // 白线描画到进度 routeAnimT
        val drawLen = total * routeAnimT
        routePaint.strokeWidth = radius * 0.034f
        scratchPath.reset()
        scratchPath.moveTo(mx(0), my(0))
        for (i in 0 until n - 1) {
            if (drawLen >= cum[i + 1]) {
                scratchPath.lineTo(mx(i + 1), my(i + 1))
            } else {
                val f = ((drawLen - cum[i]) / segLen[i]).coerceIn(0f, 1f)
                scratchPath.lineTo(mx(i) + (mx(i + 1) - mx(i)) * f, my(i) + (my(i + 1) - my(i)) * f)
                break
            }
        }
        canvas.drawPath(scratchPath, routePaint)

        // 途经点：白线到达后出现的小圆点
        for (i in 1 until n - 1) {
            if (drawLen >= cum[i]) canvas.drawCircle(mx(i), my(i), radius * 0.020f, carPaint)
        }
        // 起点：实心白点；终点：白圆环（始终显示）
        canvas.drawCircle(mx(0), my(0), radius * 0.026f, carPaint)
        iconPaint.strokeWidth = radius * 0.014f
        canvas.drawCircle(mx(n - 1), my(n - 1), radius * 0.042f, iconPaint)

        // 文案：顶部小标签 / 大标题 / 中文副标题
        dialTextPaint.textSize = radius * 0.048f
        canvas.drawText("M O T O   G P S   /   R O U T E", cx, cy - radius * 0.58f, dialTextPaint)
        textPaint.textSize = radius * 0.125f
        // 速度页会把 textPaint 改成 LEFT，这里恢复居中（否则标题偏右被圆边裁剪）
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("BUILDING ROUTE", cx, cy + radius * 0.34f, textPaint)
        hintPaint.textSize = radius * 0.070f
        canvas.drawText("正在规划路线", cx, cy + radius * 0.47f, hintPaint)
    }

    // endregion

    // region 连接生命周期页（对齐原版 LVGL 遮罩规格：手机轮廓扫描 / 绿勾呼吸 / 路线起终点呼吸）

    /** 生命周期动画时钟（秒）。 */
    private var lifecycleT = 0f

    /** 当前是否停在需要持续动画的生命周期页（连接流程或空闲页）。 */
    private fun isLifecycleScreen(): Boolean {
        val s = state
        if (!s.centralConnected && s.advertising) return true
        if (!s.protocolReady) return true
        if (!s.linkActive) return true
        if (uptimeMillis() - protocolReadyAt < 920L) return true
        val snap = s.snapshot
        return s.page == 0 && (snap == null || snap.state == 0)
    }

    /** protocolReady 由 false→true 的时刻（uptime ms），用于 920ms 绿勾过渡。 */
    private var protocolReadyAt = -1L
    private var prevProtocolReady = false

    private fun updateLifecycleClock() {
        val ready = state.protocolReady
        if (ready && !prevProtocolReady) protocolReadyAt = uptimeMillis()
        prevProtocolReady = ready
    }

    /** 顶部品牌小标（各生命周期页共用）。 */
    private fun drawBrandLabel(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        dialTextPaint.textSize = radius * 0.048f
        canvas.drawText("M O T O   G P S", cx, cy - radius * 0.62f, dialTextPaint)
    }

    /**
     * 手机轮廓 + 环形扫描弧（CONNECT PHONE / CONNECTING / SIGNAL LOST 共用）。
     * fast: 握手阶段扫描加速（原版同款语义）；alert: 断链警示（轮廓转警示红、无扫描）。
     */
    private fun drawConnectScreen(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        title: String, subtitle: String, fast: Boolean, alert: Boolean,
    ) {
        drawBrandLabel(canvas, cx, cy, radius)

        val gy = cy - radius * 0.06f
        // 手机圆角轮廓
        val pw = radius * 0.30f
        val ph = radius * 0.52f
        val accent = if (alert) 0xFFE24B4A.toInt() else 0xFFD8D8D8.toInt()
        outlinePaint.color = accent
        outlinePaint.strokeWidth = radius * 0.016f
        canvas.drawRoundRect(cx - pw / 2f, gy - ph / 2f, cx + pw / 2f, gy + ph / 2f, radius * 0.055f, radius * 0.055f, outlinePaint)
        // 听筒线 + 主屏键条
        canvas.drawLine(cx - pw * 0.16f, gy - ph / 2f + radius * 0.055f, cx + pw * 0.16f, gy - ph / 2f + radius * 0.055f, outlinePaint)
        canvas.drawLine(cx - pw * 0.12f, gy + ph / 2f - radius * 0.04f, cx + pw * 0.12f, gy + ph / 2f - radius * 0.04f, outlinePaint)

        if (!alert) {
            // 环形扫描弧：绕手机中心旋转（半径收在标题上方，避免扫过文字）
            val sweepR = radius * 0.38f
            scanPaint.strokeWidth = radius * 0.018f
            scanPaint.color = if (fast) 0xFFFFC94D.toInt() else 0xFF8A8A8A.toInt()
            val startDeg = (lifecycleT * (if (fast) 300f else 110f)) % 360f
            canvas.drawArc(cx - sweepR, gy - sweepR, cx + sweepR, gy + sweepR, startDeg, 72f, false, scanPaint)
        } else {
            // 断链：轮廓中央一道警示斜杠
            canvas.drawLine(cx - pw * 0.34f, gy + ph * 0.34f, cx + pw * 0.34f, gy - ph * 0.34f, outlinePaint)
        }

        textPaint.textSize = radius * 0.098f
        canvas.drawText(title, cx, cy + radius * 0.40f, textPaint)
        hintPaint.textSize = radius * 0.062f
        canvas.drawText(subtitle.take(16), cx, cy + radius * 0.53f, hintPaint)
    }

    /** CONNECTED：绿色圆形 + 白勾轻呼吸（约 0.92s 周期）。 */
    private fun drawConnectedScreen(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        drawBrandLabel(canvas, cx, cy, radius)
        val gy = cy - radius * 0.06f
        val breath = 0.5f + 0.5f * kotlin.math.sin(lifecycleT * (2f * Math.PI.toFloat()) / 0.92f)
        circlePaint.color = 0xFF34C368.toInt()
        circlePaint.alpha = (170 + 85 * breath).toInt()
        val r = radius * (0.22f + 0.015f * breath)
        canvas.drawCircle(cx, gy, r, circlePaint)
        // 白勾
        checkPaint.strokeWidth = radius * 0.028f
        scratchPath.reset()
        scratchPath.moveTo(cx - r * 0.42f, gy + r * 0.02f)
        scratchPath.lineTo(cx - r * 0.10f, gy + r * 0.34f)
        scratchPath.lineTo(cx + r * 0.46f, gy - r * 0.30f)
        canvas.drawPath(scratchPath, checkPaint)

        textPaint.textSize = radius * 0.098f
        canvas.drawText("CONNECTED", cx, cy + radius * 0.40f, textPaint)
        hintPaint.textSize = radius * 0.062f
        canvas.drawText("连接成功", cx, cy + radius * 0.53f, hintPaint)
    }

    /** READY TO RIDE：黑白路线起终点图形轻呼吸（原版同款，替代裸文字「准备出发」）。 */
    private fun drawReadyToRide(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        drawBrandLabel(canvas, cx, cy, radius)
        val breath = 0.5f + 0.5f * kotlin.math.sin(lifecycleT * (2f * Math.PI.toFloat()) / 1.6f)

        val gy = cy - radius * 0.14f
        val sx = radius * 0.52f
        val sy = radius * 0.36f
        val pts = planningRoutePts
        val n = pts.size / 2
        fun mx(i: Int) = cx + pts[i * 2] * sx
        fun my(i: Int) = gy - pts[i * 2 + 1] * sy

        routeUnderPaint.strokeWidth = radius * 0.046f
        scratchPath.reset()
        for (i in 0 until n) {
            if (i == 0) scratchPath.moveTo(mx(i), my(i)) else scratchPath.lineTo(mx(i), my(i))
        }
        canvas.drawPath(scratchPath, routeUnderPaint)
        routePaint.alpha = (150 + 105 * breath).toInt()
        routePaint.strokeWidth = radius * 0.030f
        canvas.drawPath(scratchPath, routePaint)
        routePaint.alpha = 255
        carPaint.alpha = (150 + 105 * breath).toInt()
        canvas.drawCircle(mx(0), my(0), radius * 0.024f, carPaint)
        iconPaint.strokeWidth = radius * 0.013f
        iconPaint.alpha = (150 + 105 * breath).toInt()
        canvas.drawCircle(mx(n - 1), my(n - 1), radius * 0.038f, iconPaint)
        carPaint.alpha = 255
        iconPaint.alpha = 255

        textPaint.textSize = radius * 0.098f
        canvas.drawText("READY TO RIDE", cx, cy + radius * 0.40f, textPaint)
        hintPaint.textSize = radius * 0.062f
        canvas.drawText("请在手机选择目的地", cx, cy + radius * 0.53f, hintPaint)
    }

    // endregion

    // region 导航页

    /**
     * 确保离屏地图位图最新：数据（场景/路线）变化或显示原点漂移超阈值时重绘，
     * 否则直接复用 —— 动画帧（60fps）零重投影开销。位图为朝北正射投影，
     * 用户位置位于位图中心，边长留 √2 余量保证任意旋转角都覆盖圆屏。
     */
    private fun ensureMapBitmap(radius: Float, ppm: Float, snapshot: NavSnapshotData, routeVisible: Boolean) {
        val scene = state.scene
        val route = state.route
        val dispLat = if (!dispLatE6.isNaN()) dispLatE6 else scene?.originLatE6?.toDouble() ?: return
        val dispLon = if (!dispLonE6.isNaN()) dispLonE6 else scene?.originLonE6?.toDouble() ?: return

        // 重建键只含数据版本（场景/路线内容），**不含 GPS 原点**——位置变化由
        // drawNavigation 的 uOff/vOff 平移处理，否则每次 GPS 定位更新（5Hz）都会
        // 触发整张位图擦除重画（几十条路径×5次/秒），这是大幅运动残余卡顿的真凶。
        // 位图仅在：数据版本变化，或显示原点漂离位图中心超过 45m（接近覆盖边界）时重建。
        var key = 1125899906842597L
        if (scene != null) {
            key = key * 31 + scene.sceneRevision
        }
        if (routeVisible && route != null) {
            key = key * 31 + route.routeToken
            key = key * 31 + route.generation
        }
        val needSize = (radius * 2f * 1.45f).toInt()
        val driftM = if (mapBitmap == null) Double.MAX_VALUE else kotlin.math.hypot(
            (dispLat - bmpOriginLatE6) / 1e6 * 111_320.0,
            (dispLon - bmpOriginLonE6) / 1e6 * bmpMPerDegLon,
        )
        if (mapBitmap != null && key == lastMapKey && mapBitmap!!.width == needSize && driftM < 55.0) return

        if (mapBitmap == null || mapBitmap!!.width != needSize) {
            mapBitmap = Bitmap.createBitmap(needSize, needSize, Bitmap.Config.ARGB_8888)
            mapBmpCanvas = Canvas(mapBitmap!!)
        }
        lastMapKey = key
        bmpOriginLatE6 = dispLat
        bmpOriginLonE6 = dispLon
        bmpMPerDegLon = 111_320f * cos(Math.toRadians(dispLat)).toFloat()

        val bmp = mapBitmap!!
        val c = mapBmpCanvas!!
        bmp.eraseColor(Color.TRANSPARENT)
        val half = needSize / 2f

        fun px(latAbsE6: Double, lonAbsE6: Double) {
            tmpPt[0] = half + ((lonAbsE6 - bmpOriginLonE6) / 1e6).toFloat() * bmpMPerDegLon * ppm
            tmpPt[1] = half - ((latAbsE6 - bmpOriginLatE6) / 1e6).toFloat() * 111_320f * ppm
        }

        // 底图：建筑填充 + 道路描边（pointsE6 已是解码器还原的绝对 E6 坐标，勿再加原点）
        if (scene != null) {
            for (building in scene.buildings) {
                if (building.pointsE6.size < 4) continue
                scratchPath.reset()
                for (i in building.pointsE6.indices step 2) {
                    px(building.pointsE6[i].toDouble(), building.pointsE6[i + 1].toDouble())
                    if (i == 0) scratchPath.moveTo(tmpPt[0], tmpPt[1]) else scratchPath.lineTo(tmpPt[0], tmpPt[1])
                }
                scratchPath.close()
                c.drawPath(scratchPath, buildingPaint)
            }
            for (road in scene.roads) {
                if (road.pointsE6.size < 4) continue
                roadPaint.strokeWidth = when (road.shapeClass) {
                    0 -> radius * 0.035f
                    1 -> radius * 0.030f
                    2 -> radius * 0.024f
                    else -> radius * 0.016f
                }
                scratchPath.reset()
                for (i in road.pointsE6.indices step 2) {
                    px(road.pointsE6[i].toDouble(), road.pointsE6[i + 1].toDouble())
                    if (i == 0) scratchPath.moveTo(tmpPt[0], tmpPt[1]) else scratchPath.lineTo(tmpPt[0], tmpPt[1])
                }
                c.drawPath(scratchPath, roadPaint)
            }
        }

        // 路线：外缘冷色描边 + 内层白线（pointsE6 已是绝对 E6 坐标）
        if (routeVisible && route != null) {
            routeUnderPaint.strokeWidth = radius * 0.095f
            routePaint.strokeWidth = radius * 0.065f
            val pts = route.pointsE6
            scratchPath.reset()
            for (i in pts.indices step 2) {
                px(pts[i].toDouble(), pts[i + 1].toDouble())
                if (i == 0) scratchPath.moveTo(tmpPt[0], tmpPt[1]) else scratchPath.lineTo(tmpPt[0], tmpPt[1])
            }
            c.drawPath(scratchPath, routeUnderPaint)
            c.drawPath(scratchPath, routePaint)
        }
    }

    private fun drawNavigation(canvas: Canvas, cx: Float, cy: Float, radius: Float, snapshot: NavSnapshotData) {
        val ppm = radius / 260f // 约 260 m 视野窗口
        val anchorY = cy // 车头箭头居中（对齐 Glimpse/高德：箭头在圆屏正中）
        val heading = smoothHeading(snapshot) // 平滑航向：地图旋转不跳变

        val route = state.route
        val routeVisible = snapshot.hasRouteView && route != null &&
            route.routeToken == snapshot.routeToken &&
            route.generation == snapshot.routeGeneration

        // 1+2. 底图与路线：离屏位图一次渲染，每帧仅一次旋转绘制（消除重投影掉帧）
        ensureMapBitmap(radius, ppm, snapshot, routeVisible)
        mapBitmap?.let { bmp ->
            val half = bmp.width / 2f
            val dispLat = if (!dispLatE6.isNaN()) dispLatE6 else bmpOriginLatE6
            val dispLon = if (!dispLonE6.isNaN()) dispLonE6 else bmpOriginLonE6
            // 显示原点相对位图原点的世界偏移（米→像素）；位图朝北，平移量在旋转系内叠加
            val uOff = ((dispLon - bmpOriginLonE6) / 1e6).toFloat() * bmpMPerDegLon * ppm
            val vOff = -((dispLat - bmpOriginLatE6) / 1e6).toFloat() * 111_320f * ppm
            canvas.save()
            canvas.rotate(-heading, cx, anchorY) // 朝北位图 → 航向朝上
            canvas.drawBitmap(bmp, cx - half - uOff, anchorY - half - vOff, mapBmpPaint)
            canvas.restore()
        }

        // 3. 车头箭头（固定锚点，指向前方）
        drawCarArrow(canvas, cx, anchorY, radius * 0.07f)

        // 4. 圆周进度弧（规范 3.2：无有效路线时隐藏空进度）
        if (snapshot.hasRouteView) drawProgressArc(canvas, cx, cy, radius, snapshot)

        // 5. 底部安全区：转向图标 + 大号距离（文本按快照缓存，避免每帧分配字符串触发 GC 卡顿）
        val distance = snapshot.distanceToManeuverM
        if (distance != cachedDistM) {
            cachedDistM = distance
            cachedDistText = formatDistance(distance)
        }
        val distText = cachedDistText
        textPaint.textSize = radius * 0.28f
        textPaint.textAlign = Paint.Align.LEFT
        val textW = textPaint.measureText(distText)
        val iconSize = radius * 0.24f
        val gap = radius * 0.08f
        val groupW = iconSize * 1.2f + gap + textW
        val baseY = cy + radius * 0.84f
        val startX = cx - groupW / 2f
        drawManeuver(canvas, startX + iconSize * 0.6f, baseY - radius * 0.07f, iconSize, snapshot.maneuver)
        canvas.drawText(distText, startX + iconSize * 1.2f + gap, baseY, textPaint)

        // 6. 顶部信息区：中上大号速度（嵌入进度弧正下方，整页统一视觉） / 其下道路名 / 右上限速牌
        val overLimit = snapshot.speedLimitKph > 0 && smoothSpeed() > snapshot.speedLimitKph + 4f
        drawNavSpeed(canvas, cx, cy, radius, overLimit)
        if (snapshot.offRoute) {
            textPaint.textSize = radius * 0.075f
            textPaint.color = 0xFFE24B4A.toInt()
            canvas.drawText("偏航重算中", cx, cy - radius * 0.28f, textPaint)
            textPaint.color = Color.WHITE
        } else if (snapshot.roadName.isNotEmpty()) {
            hintPaint.textSize = radius * 0.068f
            canvas.drawText(snapshot.roadName.take(12), cx, cy - radius * 0.28f, hintPaint)
        }
        if (snapshot.speedLimitKph > 0) {
            drawSpeedLimit(canvas, cx + radius * 0.62f, cy - radius * 0.52f, radius * 0.13f, snapshot.speedLimitKph)
        }
    }

    /** 导航页常显速度：嵌入顶部进度弧正下方的大号加粗数字（居中，整页统一视觉），超速变红。
     *  数值按整数缓存，仅变化时重建字符串（避免 60fps 分配触发 GC 卡顿）。 */
    private var cachedNavSpeed = Int.MIN_VALUE
    private var cachedNavSpeedText = "0"

    private fun drawNavSpeed(canvas: Canvas, cx: Float, cy: Float, radius: Float, overLimit: Boolean) {
        val spd = smoothSpeed().roundToInt()
        if (spd != cachedNavSpeed) {
            cachedNavSpeed = spd
            cachedNavSpeedText = spd.toString()
        }
        navSpeedPaint.textSize = radius * 0.235f
        navSpeedPaint.color = if (overLimit) 0xFFFF5252.toInt() else Color.WHITE
        canvas.drawText(cachedNavSpeedText, cx, cy - radius * 0.54f, navSpeedPaint)
        navUnitPaint.textSize = radius * 0.055f
        canvas.drawText("km/h", cx, cy - radius * 0.41f, navUnitPaint)
    }

    private fun drawSpeedLimit(canvas: Canvas, cx: Float, cy: Float, r: Float, limitKph: Int) {        canvas.drawCircle(cx, cy, r, limitBgPaint)
        limitRingPaint.strokeWidth = r * 0.26f
        canvas.drawCircle(cx, cy, r - r * 0.13f, limitRingPaint)
        limitTextPaint.textSize = r * 0.82f
        canvas.drawText("$limitKph", cx, cy + r * 0.30f, limitTextPaint)
    }

    private fun drawCarArrow(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        scratchPath.reset()
        scratchPath.moveTo(cx, cy - size * 1.4f)
        scratchPath.lineTo(cx + size, cy + size)
        scratchPath.lineTo(cx, cy + size * 0.45f)
        scratchPath.lineTo(cx - size, cy + size)
        scratchPath.close()
        canvas.drawPath(scratchPath, carPaint)
    }

    private fun drawProgressArc(canvas: Canvas, cx: Float, cy: Float, radius: Float, snapshot: NavSnapshotData) {
        val stroke = radius * 0.035f
        val arcRadius = radius * 0.90f
        arcRect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
        // 底部弧：从 155° 经 270° 到 25°，总跨度 230°
        val start = 155f
        val sweep = 230f
        arcTrackPaint.strokeWidth = stroke
        canvas.drawArc(arcRect, start, sweep, false, arcTrackPaint)
        // 进度弧颜色随路况变化（原版设计：绿=畅通 橙=缓行 红=拥堵）
        arcPaint.color = when (snapshot.traffic) {
            1 -> 0xFF3FD57C.toInt()   // FreeFlow 绿
            2 -> 0xFFFF8C1A.toInt()   // Slow 橙
            3, 4 -> 0xFFE24B4A.toInt() // Congested/Severe 红
            else -> 0xFFFF8C1A.toInt()
        }
        if (snapshot.totalDistanceM > 0) {
            val fraction = (snapshot.routeProgressM.toDouble() / snapshot.totalDistanceM).coerceIn(0.0, 1.0)
            if (fraction > 0.001) {
                arcPaint.strokeWidth = stroke
                canvas.drawArc(arcRect, start, (sweep * fraction).toFloat(), false, arcPaint)
            }
        }
    }

    // endregion

    /** 转向图标：ManeuverType 0..12。 */
    private fun drawManeuver(canvas: Canvas, cx: Float, cy: Float, size: Float, maneuver: Int) {
        iconPaint.strokeWidth = size * 0.22f
        val s = size
        fun arrowHead(x: Float, y: Float, angleDeg: Float, headSize: Float = s * 0.42f) {
            val rad = Math.toRadians(angleDeg.toDouble())
            val dx = kotlin.math.sin(rad).toFloat()
            val dy = -kotlin.math.cos(rad).toFloat()
            val lx = -dy * headSize * 0.7f
            val ly = dx * headSize * 0.7f
            canvas.drawLine(x - dx * headSize + lx, y - dy * headSize + ly, x, y, iconPaint)
            canvas.drawLine(x, y, x - dx * headSize - lx, y - dy * headSize - ly, iconPaint)
        }
        fun stem(bendDeg: Float) {
            // 从底部向上，在 60% 处以 bendDeg 角度折转
            val bendX = cx + kotlin.math.sin(Math.toRadians(bendDeg.toDouble())).toFloat() * s * 0.6f
            val bendY = cy - kotlin.math.cos(Math.toRadians(bendDeg.toDouble())).toFloat() * s * 0.6f
            canvas.drawLine(cx, cy + s * 0.55f, cx, cy - s * 0.05f, iconPaint)
            canvas.drawLine(cx, cy - s * 0.05f, bendX, bendY, iconPaint)
            arrowHead(bendX, bendY, bendDeg)
        }
        when (maneuver) {
            2 -> stem(-25f)                       // SlightLeft
            3 -> stem(-70f)                       // Left
            4 -> stem(-115f)                      // SharpLeft
            6 -> stem(25f)                        // SlightRight
            7 -> stem(70f)                        // Right
            8 -> stem(115f)                       // SharpRight
            5, 9 -> {                             // U-Turn
                val dir = if (maneuver == 5) -1f else 1f
                val r = s * 0.38f
                arcRect.set(cx - r * abs(dir), cy - s * 0.55f, cx + r * abs(dir), cy - s * 0.55f + 2 * r)
                canvas.drawArc(arcRect, 90f * dir, -180f * dir, false, iconPaint)
                arrowHead(cx + dir * r * abs(dir) * 1f, cy - s * 0.55f + r * 2f, 180f * dir)
            }
            10 -> {                               // Roundabout
                val r = s * 0.45f
                canvas.drawCircle(cx - s * 0.15f, cy, r, iconPaint)
                canvas.drawLine(cx - s * 0.15f, cy - r, cx + s * 0.55f, cy - r, iconPaint)
                arrowHead(cx + s * 0.55f, cy - r, 65f)
            }
            11 -> {                               // Exit
                canvas.drawLine(cx, cy + s * 0.55f, cx, cy - s * 0.1f, iconPaint)
                canvas.drawLine(cx, cy - s * 0.1f, cx + s * 0.45f, cy - s * 0.45f, iconPaint)
                arrowHead(cx + s * 0.45f, cy - s * 0.45f, 45f)
            }
            12 -> {                               // Arrive：定位点
                val r = s * 0.4f
                canvas.drawCircle(cx, cy - s * 0.05f, r, iconPaint)
                canvas.drawCircle(cx, cy - s * 0.05f, r * 0.35f, carPaint)
                canvas.drawLine(cx, cy + s * 0.55f, cx, cy + s * 0.28f, iconPaint)
            }
            else -> {                             // Unknown / Continue
                canvas.drawLine(cx, cy + s * 0.55f, cx, cy - s * 0.5f, iconPaint)
                arrowHead(cx, cy - s * 0.5f, 0f)
            }
        }
    }

    // region 速度页

    /**
     * 速度页大表盘 v2（参照 Garmin/Apple Watch 运动表盘）：
     * 表盘弧顶满圆屏边框（0.92R）；轨道为绿→橙→红三段暗色带，进度弧带辉光；
     * 主刻度带数字、白色模拟指针；中央表帽超大数字；底部缺口区大号剩余信息。
     * 注意：本页所有角度统一使用 Canvas 约定（0°=右，90°=下，顺时针）。
     */
    private fun drawSpeed(canvas: Canvas, cx: Float, cy: Float, radius: Float, snapshot: NavSnapshotData) {
        val speedKph = smoothSpeed()
        val maxScale = maxOf(60f, (speedKph / 20f).roundToInt() * 20f)
        val start = 135f
        val sweep = 270f
        val gaugeR = radius * 0.92f
        val tickR = radius * 0.80f

        // 1. 轨道：暗底弧 + 绿/橙/红三段暗色带（预告速度区间，赛道感）
        arcRect.set(cx - gaugeR, cy - gaugeR, cx + gaugeR, cy + gaugeR)
        arcTrackPaint.strokeWidth = radius * 0.062f
        arcTrackPaint.color = 0xFF232323.toInt()
        canvas.drawArc(arcRect, start, sweep, false, arcTrackPaint)
        arcTrackPaint.color = 0x503FD57C.toInt()
        canvas.drawArc(arcRect, start, sweep * 0.5f, false, arcTrackPaint)
        arcTrackPaint.color = 0x50FF8C1A.toInt()
        canvas.drawArc(arcRect, start + sweep * 0.5f, sweep * 0.3f, false, arcTrackPaint)
        arcTrackPaint.color = 0x50E24B4A.toInt()
        canvas.drawArc(arcRect, start + sweep * 0.8f, sweep * 0.2f, false, arcTrackPaint)

        // 2. 进度弧：宽半透明辉光层 + 实体层
        val fraction = (speedKph / maxScale).coerceIn(0f, 1f)
        if (fraction > 0.002f) {
            arcPaint.color = (speedColor(fraction) and 0x00FFFFFF) or 0x59000000
            arcPaint.strokeWidth = radius * 0.15f
            canvas.drawArc(arcRect, start, sweep * fraction, false, arcPaint)
            arcPaint.color = speedColor(fraction)
            arcPaint.strokeWidth = radius * 0.062f
            canvas.drawArc(arcRect, start, sweep * fraction, false, arcPaint)
        }

        // 3. 刻度环 + 数字标注（与外弧同一角度约定：0 在左下，顺时针经顶部到右下）
        val ticks = 25
        if (maxScale != gaugeMaxScale) {
            gaugeMaxScale = maxScale
            gaugeLabels.clear()
            for (i in 0 until ticks step 4) {
                val angle = start + sweep * i / (ticks - 1)
                gaugeLabels.add("${(maxScale * i / (ticks - 1)).roundToInt()}" to angle)
            }
        }
        for (i in 0 until ticks) {
            val angle = start + sweep * i / (ticks - 1)
            val major = i % 4 == 0
            tickPaint.strokeWidth = radius * if (major) 0.018f else 0.009f
            tickPaint.color = if (major) 0xFF9A9A9A.toInt() else 0xFF4A4A4A.toInt()
            val len = radius * if (major) 0.085f else 0.05f
            drawRadialTick(canvas, cx, cy, tickR, angle, len)
        }
        gaugeLabelPaint.textSize = radius * 0.085f
        val labelR = tickR - radius * 0.085f - radius * 0.075f
        for ((label, angle) in gaugeLabels) {
            val rad = Math.toRadians(angle.toDouble())
            canvas.drawText(
                label,
                cx + labelR * kotlin.math.cos(rad).toFloat(),
                cy + labelR * kotlin.math.sin(rad).toFloat() + gaugeLabelPaint.textSize * 0.35f,
                gaugeLabelPaint,
            )
        }

        // 4. 模拟指针：主针 + 尾部配重（画在表帽之下）
        val needleAngle = Math.toRadians((start + sweep * fraction).toDouble())
        val ndx = kotlin.math.cos(needleAngle).toFloat()
        val ndy = kotlin.math.sin(needleAngle).toFloat()
        needlePaint.strokeWidth = radius * 0.026f
        canvas.drawLine(cx, cy, cx + tickR * ndx, cy + tickR * ndy, needlePaint)
        needlePaint.strokeWidth = radius * 0.05f
        canvas.drawLine(cx, cy, cx - radius * 0.12f * ndx, cy - radius * 0.12f * ndy, needlePaint)

        // 5. 中央表帽 + 超大速度数字（表帽环随速度色阶变色）
        canvas.drawCircle(cx, cy, radius * 0.335f, discFillPaint)
        discRingPaint.strokeWidth = radius * 0.016f
        discRingPaint.color = speedColor(fraction)
        canvas.drawCircle(cx, cy, radius * 0.335f, discRingPaint)
        speedNumPaint.textSize = radius * 0.30f
        canvas.drawText(
            if (speedKph >= 10 || speedKph == 0f) "${speedKph.roundToInt()}" else String.format("%.1f", speedKph),
            cx, cy + radius * 0.105f, speedNumPaint,
        )
        hintPaint.textSize = radius * 0.085f
        canvas.drawText("km/h", cx, cy + radius * 0.27f, hintPaint)

        // 6. 加减速趋势箭头（表帽上方）
        if (abs(speedTrend) > 1.5f) {
            dialTextPaint.textSize = radius * 0.10f
            dialTextPaint.color = if (speedTrend > 0) 0xFF7ED957.toInt() else 0xFFFF9F45.toInt()
            canvas.drawText(if (speedTrend > 0) "▲" else "▼", cx, cy - radius * 0.44f, dialTextPaint)
            dialTextPaint.color = 0xFFB8B8B8.toInt()
        }

        // 7. 底部缺口区大号信息条：剩余路程/时间（加粗白色）+ 峰值
        if (snapshot.remainingDistanceM > 0) {
            val remainingKm = snapshot.remainingDistanceM / 1000f
            val remMin = (snapshot.remainingDurationS / 60).coerceAtLeast(1)
            infoPaint.textSize = radius * 0.095f
            canvas.drawText(
                String.format("剩 %.1f km · 约 %d 分", remainingKm, remMin),
                cx, cy + radius * 0.76f, infoPaint,
            )
        }
        dialTextPaint.color = 0xFFADADAD.toInt()
        dialTextPaint.textSize = radius * 0.062f
        canvas.drawText("MAX ${maxSpeedKph.roundToInt()} km/h", cx, cy + radius * 0.88f, dialTextPaint)
    }

    // endregion

    /** 径向刻度：angleDeg 使用 Canvas 约定（0°=右，90°=下，顺时针），与 drawArc 一致。 */
    private fun drawRadialTick(canvas: Canvas, cx: Float, cy: Float, ringR: Float, angleDeg: Float, len: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        canvas.drawLine(
            cx + cos * (ringR - len), cy + sin * (ringR - len),
            cx + cos * ringR, cy + sin * ringR, tickPaint,
        )
    }

    // endregion

    // region 音乐页

    private fun drawMusic(canvas: Canvas, cx: Float, cy: Float, radius: Float, media: MediaStateData?) {
        val connected = media != null && (media.flags and 0x01) != 0
        val playing = media != null && (media.flags and 0x02) != 0

        dialTextPaint.textSize = radius * 0.06f
        canvas.drawText(
            if (connected) (media?.sourceName?.uppercase()?.take(20) ?: "APPLE MUSIC") else "MUSIC",
            cx, cy - radius * 0.52f, dialTextPaint,
        )

        // 唱片：外环亮色=播放中
        val discR = radius * 0.26f
        val discY = cy - radius * 0.10f
        discRingPaint.strokeWidth = radius * 0.022f
        discRingPaint.color = if (playing) 0xFF9FDDF2.toInt() else 0xFF4A4A4A.toInt()
        canvas.drawCircle(cx, discY, discR, discFillPaint)
        canvas.drawCircle(cx, discY, discR, discRingPaint)
        canvas.drawCircle(cx, discY, discR * 0.38f, Paint().apply { color = Color.BLACK })

        if (connected) {
            textPaint.textSize = radius * 0.095f
            canvas.drawText((media?.trackTitle ?: "").take(16).ifEmpty { "未知曲目" }, cx, cy + radius * 0.30f, textPaint)
            hintPaint.textSize = radius * 0.07f
            canvas.drawText((media?.artistName ?: "").take(20), cx, cy + radius * 0.41f, hintPaint)
        } else {
            hintPaint.textSize = radius * 0.08f
            canvas.drawText("手机媒体未连接", cx, cy + radius * 0.32f, hintPaint)
        }

        // 三个控制按钮：上一首 / 播放暂停 / 下一首
        val btnY = cy + radius * 0.62f
        val btnXs = floatArrayOf(cx - radius * 0.30f, cx, cx + radius * 0.30f)
        val btnR = radius * 0.095f
        btnPaint.strokeWidth = radius * 0.012f
        iconPaint.strokeWidth = radius * 0.012f
        for (x in btnXs) canvas.drawCircle(x, btnY, btnR, btnPaint)
        // 上一首：双左三角
        drawTriPair(canvas, btnXs[0], btnY, btnR * 0.5f, -1f)
        // 播放/暂停
        if (playing) {
            val barW = btnR * 0.16f
            canvas.drawRect(btnXs[1] - btnR * 0.34f, btnY - btnR * 0.4f, btnXs[1] - btnR * 0.34f + barW, btnY + btnR * 0.4f, carPaint)
            canvas.drawRect(btnXs[1] + btnR * 0.18f, btnY - btnR * 0.4f, btnXs[1] + btnR * 0.18f + barW, btnY + btnR * 0.4f, carPaint)
        } else {
            scratchPath.reset()
            scratchPath.moveTo(btnXs[1] - btnR * 0.3f, btnY - btnR * 0.42f)
            scratchPath.lineTo(btnXs[1] + btnR * 0.42f, btnY)
            scratchPath.lineTo(btnXs[1] - btnR * 0.3f, btnY + btnR * 0.42f)
            scratchPath.close()
            canvas.drawPath(scratchPath, carPaint)
        }
        // 下一首：双右三角
        drawTriPair(canvas, btnXs[2], btnY, btnR * 0.5f, 1f)
    }

    private fun drawTriPair(canvas: Canvas, cx: Float, cy: Float, s: Float, dir: Float) {
        for (off in floatArrayOf(-s * 0.9f, s * 0.9f)) {
            val tip = cx + off + dir * s
            scratchPath.reset()
            scratchPath.moveTo(tip, cy)
            scratchPath.lineTo(tip - dir * s * 0.8f, cy - s * 0.7f)
            scratchPath.lineTo(tip - dir * s * 0.8f, cy + s * 0.7f)
            scratchPath.close()
            canvas.drawPath(scratchPath, carPaint)
        }
    }

    /** 音乐按钮命中测试：0 上一首 / 1 播放暂停 / 2 下一首 / -1 未命中。 */
    fun musicButtonAt(x: Float, y: Float): Int {
        val radius = min(width, height) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val btnY = cy + radius * 0.62f
        if (abs(y - btnY) > radius * 0.16f) return -1
        val rel = (x - cx) / radius
        return when {
            rel < -0.15f -> 0
            rel > 0.15f -> 2
            else -> 1
        }
    }

    private fun drawPageDots(canvas: Canvas, cx: Float, cy: Float, radius: Float, page: Int) {
        val y = cy + radius * 0.90f
        val spacing = radius * 0.09f
        for (i in 0 until 3) {
            val p = Paint().apply { color = if (i == page) Color.WHITE else 0xFF3A3A3A.toInt() }
            canvas.drawCircle(cx + (i - 1f) * spacing, y, radius * 0.014f, p)
        }
    }

    // endregion

    private fun formatDistance(meters: Long): String = when {
        meters < 1000 -> "${meters} m"
        meters < 10_000 -> String.format("%.1f km", meters / 1000.0)
        else -> "${meters / 1000} km"
    }
}
