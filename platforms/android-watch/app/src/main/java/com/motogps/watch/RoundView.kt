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

/** UI 层聚合状态（主线程更新后 invalidate）。page: 0导航 1速度 2航向 3音乐。 */
data class NavUiState(
    var advertising: Boolean = false,
    var centralConnected: Boolean = false,
    var protocolReady: Boolean = false,
    var linkActive: Boolean = true,
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
            val stillMoving = state.snapshot?.let { tickAnimation(it, dt) } ?: false
            if (stillMoving) {
                invalidate()
                choreographer.postFrameCallback(this)
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

    /** 向目标值缓动；返回是否仍需要继续画帧。 */
    private fun tickAnimation(s: NavSnapshotData, dt: Float): Boolean {
        var moving = false
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
        state.scene?.let { sc ->
            if (dispLatE6.isNaN()) {
                dispLatE6 = sc.originLatE6.toDouble()
                dispLonE6 = sc.originLonE6.toDouble()
            } else {
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
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val warmTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFC94D.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE24B4A.toInt() }
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

        val s = state
        when {
            !s.centralConnected && s.advertising -> drawCentered(
                canvas, cx, cy, "MOTO GPS", "等待手机连接…",
            )
            !s.protocolReady -> drawCentered(canvas, cx, cy, "已连接", "等待手机确认…")
            !s.linkActive -> drawCentered(canvas, cx, cy, "信号丢失", "请确认手机 MOTO GPS App 在前台")
            else -> {
                when (s.page) {
                    1 -> s.snapshot?.let { drawSpeed(canvas, cx, cy, radius, it) }
                    2 -> s.snapshot?.let { drawCompass(canvas, cx, cy, radius, it) }
                    3 -> drawMusic(canvas, cx, cy, radius, s.media)
                    0 -> {
                        val snapshot = s.snapshot
                        if (snapshot == null || snapshot.state == 0 || snapshot.state == 1 || snapshot.state == 2) {
                            drawCentered(canvas, cx, cy, "准备出发", snapshot?.instruction?.takeIf { it.isNotEmpty() } ?: "")
                        } else {
                            drawNavigation(canvas, cx, cy, radius, snapshot)
                        }
                    }
                }
                if (s.page != 0) drawPageDots(canvas, cx, cy, radius, s.page)
            }
        }
        canvas.restore()
    }

    private fun drawCentered(canvas: Canvas, cx: Float, cy: Float, title: String, subtitle: String) {
        textPaint.textSize = this.width / 9f
        canvas.drawText(title, cx, cy - if (subtitle.isEmpty()) 0f else this.width / 40f, textPaint)
        if (subtitle.isNotEmpty()) {
            hintPaint.textSize = this.width / 22f
            canvas.drawText(subtitle.take(18), cx, cy + this.width / 14f, hintPaint)
        }
    }

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
        if (mapBitmap != null && key == lastMapKey && mapBitmap!!.width == needSize && driftM < 45.0) return

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

        // 5. 底部安全区：转向图标 + 大号距离，水平组合居中（参照原版：元素大而少，一眼可读）
        val distance = snapshot.distanceToManeuverM
        val distText = formatDistance(distance)
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

        // 6. 顶部道路名 / 限速牌 / 小速度（原版设计：有限速数据时优先限速牌）
        if (snapshot.roadName.isNotEmpty()) {
            hintPaint.textSize = radius * 0.085f
            canvas.drawText(snapshot.roadName.take(12), cx, cy - radius * 0.74f, hintPaint)
        }
        if (snapshot.speedLimitKph > 0) {
            drawSpeedLimit(canvas, cx + radius * 0.62f, cy - radius * 0.52f, radius * 0.13f, snapshot.speedLimitKph)
        } else if (snapshot.speedDeciKph > 0) {
            textPaint.textSize = radius * 0.10f
            canvas.drawText("${snapshot.speedKph.roundToInt()}", cx + radius * 0.62f, cy - radius * 0.55f, textPaint)
            hintPaint.textSize = radius * 0.055f
            canvas.drawText("km/h", cx + radius * 0.62f, cy - radius * 0.44f, hintPaint)
        }
        if (snapshot.offRoute) {
            textPaint.textSize = radius * 0.10f
            textPaint.color = 0xFFE24B4A.toInt()
            canvas.drawText("偏航重算中", cx, cy - radius * 0.52f, textPaint)
            textPaint.color = Color.WHITE
        }
    }

    private fun drawSpeedLimit(canvas: Canvas, cx: Float, cy: Float, r: Float, limitKph: Int) {
        canvas.drawCircle(cx, cy, r, limitBgPaint)
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

    private fun drawSpeed(canvas: Canvas, cx: Float, cy: Float, radius: Float, snapshot: NavSnapshotData) {
        val speedKph = smoothSpeed()
        val maxScale = maxOf(60f, (speedKph / 20f).roundToInt() * 20f)
        val ringR = radius * 0.80f
        val start = 135f
        val sweep = 270f

        // 刻度环
        val ticks = 25
        for (i in 0 until ticks) {
            val angle = start + sweep * i / (ticks - 1)
            val major = i % 4 == 0
            tickPaint.strokeWidth = radius * if (major) 0.014f else 0.008f
            tickPaint.color = if (major) 0xFF6E6E6E.toInt() else 0xFF444444.toInt()
            val len = radius * if (major) 0.075f else 0.045f
            drawRadialTick(canvas, cx, cy, ringR, angle, len)
        }
        // 进度弧
        val fraction = (speedKph / maxScale).coerceIn(0f, 1f)
        if (fraction > 0.001f) {
            accentPaint.strokeWidth = radius * 0.035f
            canvas.drawArc(
                arcRect.apply { set(cx - ringR, cy - ringR, cx + ringR, cy + ringR) },
                start, sweep * fraction, false, accentPaint,
            )
        }

        dialTextPaint.textSize = radius * 0.075f
        canvas.drawText("SPEED", cx, cy - radius * 0.38f, dialTextPaint)
        textPaint.textSize = radius * 0.34f
        canvas.drawText(if (speedKph >= 10 || speedKph == 0f) "${speedKph.roundToInt()}" else String.format("%.1f", speedKph), cx, cy + radius * 0.10f, textPaint)
        hintPaint.textSize = radius * 0.085f
        canvas.drawText("km/h", cx, cy + radius * 0.24f, hintPaint)
        // 加减速趋势 + 峰值（借鉴同类骑行仪表）
        if (abs(speedTrend) > 1.5f) {
            dialTextPaint.textSize = radius * 0.10f
            dialTextPaint.color = if (speedTrend > 0) 0xFF7ED957.toInt() else 0xFFFF9F45.toInt()
            canvas.drawText(if (speedTrend > 0) "▲" else "▼", cx - radius * 0.24f, cy - radius * 0.10f, dialTextPaint)
            dialTextPaint.color = 0xFFB8B8B8.toInt()
        }
        dialTextPaint.textSize = radius * 0.05f
        canvas.drawText("MAX ${maxSpeedKph.roundToInt()}", cx, cy + radius * 0.55f, dialTextPaint)
    }

    // endregion

    // region 航向页

    private fun drawCompass(canvas: Canvas, cx: Float, cy: Float, radius: Float, snapshot: NavSnapshotData) {
        val heading = smoothHeading(snapshot)
        val ringR = radius * 0.80f

        // 旋转刻度盘：方位角 b 的刻度出现在屏幕角 (b - heading)
        for (b in 0 until 360 step 15) {
            val cardinal = b % 90 == 0
            tickPaint.strokeWidth = radius * if (cardinal) 0.014f else 0.008f
            tickPaint.color = if (cardinal) 0xFF8A8A8A.toInt() else 0xFF444444.toInt()
            drawRadialTick(canvas, cx, cy, ringR, b - heading, radius * if (cardinal) 0.08f else 0.05f)
        }
        // 方位字母（N 黄色高亮，参照原版）
        dialTextPaint.textSize = radius * 0.09f
        val labels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        for ((label, b) in labels) {
            val angle = Math.toRadians(((b - heading + 360f) % 360f - 90f).toDouble())
            val lx = cx + (ringR - radius * 0.14f) * kotlin.math.cos(angle).toFloat()
            val ly = cy + (ringR - radius * 0.14f) * kotlin.math.sin(angle).toFloat()
            dialTextPaint.color = if (label == "N") 0xFFFFC94D.toInt() else 0xFFB8B8B8.toInt()
            canvas.drawText(label, lx, ly + radius * 0.03f, dialTextPaint)
        }
        dialTextPaint.color = 0xFFB8B8B8.toInt()
        // 顶部红色基准标记（固定，指向行进方向）
        canvas.drawPath(scratchPath.apply {
            reset()
            val ty = cy - ringR - radius * 0.02f
            moveTo(cx, ty - radius * 0.045f)
            lineTo(cx + radius * 0.028f, ty + radius * 0.02f)
            lineTo(cx, ty + radius * 0.01f)
            lineTo(cx - radius * 0.028f, ty + radius * 0.02f)
            close()
        }, northPaint)

        // 中心读数
        warmTextPaint.textSize = radius * 0.26f
        canvas.drawText("${heading.roundToInt()}°", cx, cy + radius * 0.02f, warmTextPaint)
        val winds = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        dialTextPaint.textSize = radius * 0.10f
        canvas.drawText(winds[((heading + 22.5f).roundToInt() / 45) % 8], cx, cy + radius * 0.17f, dialTextPaint)
        hintPaint.textSize = radius * 0.075f
        canvas.drawText("${smoothSpeed().roundToInt()} km/h", cx, cy + radius * 0.32f, hintPaint)
    }

    private fun drawRadialTick(canvas: Canvas, cx: Float, cy: Float, ringR: Float, angleDeg: Float, len: Float) {
        val rad = Math.toRadians((angleDeg - 90f).toDouble())
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
        for (i in 0 until 4) {
            val p = Paint().apply { color = if (i == page) Color.WHITE else 0xFF3A3A3A.toInt() }
            canvas.drawCircle(cx + (i - 1.5f) * spacing, y, radius * 0.014f, p)
        }
    }

    // endregion

    private fun formatDistance(meters: Long): String = when {
        meters < 1000 -> "${meters} m"
        meters < 10_000 -> String.format("%.1f km", meters / 1000.0)
        else -> "${meters / 1000} km"
    }
}
