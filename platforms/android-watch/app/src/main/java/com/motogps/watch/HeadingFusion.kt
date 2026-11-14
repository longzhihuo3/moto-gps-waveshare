package com.motogps.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * 手表航向融合（回退版：对齐 2026-11-14 用户实测「改进很大」版本的手感）。
 *
 * 对齐高德/百度/系统指南针手感的三个要点：
 *  1. **优先用 TYPE_ROTATION_VECTOR（陀螺仪融合）**——系统指南针同款数据源，
 *     陀螺仪预积分输出，比裸加速度计+磁力计顺滑得多且无延迟；不可用才退回
 *     getRotationMatrix(gravity, magnetic) 倾角补偿；
 *  2. **时间常数低通滤波**（加速度计 12/s、磁力计 8/s、输出矢量 10~15/s，
 *     sin/cos 分量滤波防 0/360 跳变）——残余高频噪声不滤则地图抖动；
 *  3. **口径按参考轴质量自适应**（非 pitch 硬分！）：azimuth 由"指向近似水平的
 *     参考轴"的水平投影决定——裸矩阵口径参考 12 点方向（Y 轴），竖屏重映射口径
 *     参考表盘法线（Z 轴）。表盘朝上时 Y 轴水平、Z 轴指向天顶，此时若错误地用
 *     重映射口径，azimuth 退化、噪声被极度放大 → 微动就大幅疯转、停手还转个不停。
 */
class HeadingFusion(context: Context) : SensorEventListener {

    /** 磁北方位角 0..360（NaN=传感器不可用）。 */
    @Volatile
    var headingDeg: Float = Float.NaN
        private set

    /** 每次传感器更新回调（主线程）。 */
    var onChange: (() -> Unit)? = null

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // 陀螺仪融合旋转矢量（系统指南针/高德同款）：优先，预积分平滑无延迟
    private val rotVec: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val available: Boolean get() = rotVec != null || (accel != null && mag != null)

    fun start() {
        resetFilter()
        if (rotVec != null) {
            sm.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
        } else {
            accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
            mag?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
        }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    private val accelVals = FloatArray(3)
    private val magVals = FloatArray(3)
    private var haveAccel = false
    private var haveMag = false
    private var lastAccelNs = 0L
    private var lastMagNs = 0L
    private val r = FloatArray(9)
    private val rScreen = FloatArray(9)
    private val orient = FloatArray(3)

    // 口径自适应迟滞：azimuth 质量取决于参考轴的水平投影分量。
    //   裸矩阵口径参考 Y 轴（12 点方向），其竖直分量 = |R[7]|；
    //   重映射口径参考 Z 轴（表盘法线），其竖直分量 = |R[8]|。
    // 选竖直分量更小（水平投影更好）的口径；质量差超过 0.15 才切换，防反复跳变。
    // 戴腕骑行时 12 点沿手臂近似水平 → 稳定用裸矩阵，任意手腕翻转都平滑衔接。
    private var useWrist = false

    // 输出矢量低通（sin/cos 分量滤波，防 0°/360° 边界跳变）
    private var filtSin = Float.NaN
    private var filtCos = Float.NaN
    private var lastOutNs = 0L

    private fun resetFilter() {
        haveAccel = false
        haveMag = false
        filtSin = Float.NaN
        filtCos = Float.NaN
        lastAccelNs = 0L
        lastMagNs = 0L
        lastOutNs = 0L
        headingDeg = Float.NaN
    }

    private fun lowPass(prev: FloatArray, cur: FloatArray, dtNs: Long, rate: Float) {
        val dt = (dtNs / 1e9f).coerceIn(0.001f, 0.5f)
        val a = 1f - exp(-dt * rate)
        for (i in prev.indices) prev[i] += (cur[i] - prev[i]) * a
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                processOrientation(event.timestamp, outRate = 15f) // 已陀螺仪预积分，轻滤即可
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (haveAccel) lowPass(accelVals, event.values, event.timestamp - lastAccelNs, 12f)
                else for (i in 0..2) accelVals[i] = event.values[i]
                lastAccelNs = event.timestamp
                haveAccel = true
                if (haveAccel && haveMag) {
                    if (SensorManager.getRotationMatrix(r, null, accelVals, magVals)) {
                        processOrientation(event.timestamp, outRate = 10f)
                    }
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (haveMag) lowPass(magVals, event.values, event.timestamp - lastMagNs, 8f)
                else for (i in 0..2) magVals[i] = event.values[i]
                lastMagNs = event.timestamp
                haveMag = true
                if (haveAccel && haveMag) {
                    if (SensorManager.getRotationMatrix(r, null, accelVals, magVals)) {
                        processOrientation(event.timestamp, outRate = 10f)
                    }
                }
            }
            else -> return
        }
    }

    /** 从当前旋转矩阵 r 提取航向：口径自适应 + 输出矢量低通 + 回调。 */
    private fun processOrientation(nowNs: Long, outRate: Float) {
        SensorManager.getOrientation(r, orient)
        val yVert = abs(r[7])
        val zVert = abs(r[8])
        if (useWrist) {
            if (yVert + 0.15f < zVert) useWrist = false
        } else {
            if (zVert + 0.15f < yVert) useWrist = true
        }

        val azim = if (useWrist) {
            // 仅当 12 点方向接近竖直（如手臂下垂）而表盘法线朝水平时才换参考轴
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, rScreen)
            SensorManager.getOrientation(rScreen, orient)
            orient[0]
        } else {
            orient[0]
        }

        // 输出矢量低通（rate 10~15/s，与系统指南针手感相当）
        val s = sin(azim)
        val c = cos(azim)
        if (filtSin.isNaN()) {
            filtSin = s
            filtCos = c
        } else {
            val dt = ((nowNs - lastOutNs) / 1e9f).coerceIn(0.001f, 0.5f)
            val a = 1f - exp(-dt * outRate)
            filtSin += (s - filtSin) * a
            filtCos += (c - filtCos) * a
        }
        lastOutNs = nowNs

        var deg = Math.toDegrees(atan2(filtSin.toDouble(), filtCos.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        headingDeg = deg
        onChange?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度事件由滤波层消化，无需处理。
    }
}
