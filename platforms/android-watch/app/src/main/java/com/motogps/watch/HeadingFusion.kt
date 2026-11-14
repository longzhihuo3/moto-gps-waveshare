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
import kotlin.math.min
import kotlin.math.sin

/**
 * 手表航向融合（互补滤波：陀螺仪主导 + 磁力计校准）。
 *
 * 实测教训（2026-11-14）：室内磁力计方位角会 ±100° 级跳变（磁干扰），直接驱动
 * 地图则「微动大转」，限速跟随则「锁住慢爬」。根治方案是高德/系统指南针同款架构：
 *
 *  1. **陀螺仪积分主导**：每帧把绕世界垂直轴的实测角速度（gyro · up）积分进航向——
 *     这是手腕的「真实」旋转，完全免疫磁噪声，地图跟手 1:1、停手即停；
 *  2. **磁力计只做慢速校准**：偏差 <45° 时按 0.6/s 缓拉对齐真北（同时消除陀螺零偏
 *     漂移）；偏差 ≥45° 视为磁干扰跳变，拒收大步修正、仅以 2°/s 缓拉防发散；
 *  3. 口径自适应：azimuth 参考「更接近水平的轴」（12 点方向 Y 优先），带迟滞防跳变。
 *
 * 输出 headingDeg 供地图旋转使用；主线程回调。
 */
class HeadingFusion(context: Context) : SensorEventListener {

    /** 融合航向 0..360（NaN=尚未初始化）。 */
    @Volatile
    var headingDeg: Float = Float.NaN
        private set

    /** 每次传感器更新回调（主线程）。 */
    var onChange: (() -> Unit)? = null

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotVec: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val mag: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val available: Boolean get() = rotVec != null || (accel != null && mag != null)

    fun start() {
        resetFilter()
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
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
    private var lastGyroNs = 0L
    private var lastFusedNs = 0L
    private val r = FloatArray(9)
    private val rScreen = FloatArray(9)
    private val orient = FloatArray(3)
    private var haveRotation = false

    /** 陀螺仪绕垂直轴的低通角速度（°/s，NaN=不可用），也用于航向积分。 */
    private var yawRateDeg: Float = Float.NaN

    /** 口径迟滞：false=裸矩阵口径（参考 12 点方向 Y 轴），true=重映射口径（参考表盘法线 Z 轴）。 */
    private var useWrist = false

    private fun resetFilter() {
        haveAccel = false
        haveMag = false
        lastAccelNs = 0L
        lastMagNs = 0L
        lastGyroNs = 0L
        lastFusedNs = 0L
        haveRotation = false
        yawRateDeg = Float.NaN
        headingDeg = Float.NaN
    }

    private fun lowPass(prev: FloatArray, cur: FloatArray, dtNs: Long, rate: Float) {
        val dt = (dtNs / 1e9f).coerceIn(0.001f, 0.5f)
        val a = 1f - exp(-dt * rate)
        for (i in prev.indices) prev[i] += (cur[i] - prev[i]) * a
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                // 绕世界垂直轴的角速度 = gyro · up(设备系)。R 为 device→world，
                // up 在设备系 = R 第 3 行 = (R[6],R[7],R[8])。表盘朝上时 ≈ (0,0,1)。
                if (!haveRotation) return
                val dt = if (lastGyroNs == 0L) 0f
                else ((event.timestamp - lastGyroNs) / 1e9f).coerceIn(0.001f, 0.5f)
                val rateDeg = Math.toDegrees(
                    (event.values[0] * r[6] + event.values[1] * r[7] + event.values[2] * r[8]).toDouble(),
                ).toFloat()
                yawRateDeg = if (yawRateDeg.isNaN()) rateDeg
                else yawRateDeg + (rateDeg - yawRateDeg) * (1f - exp(-dt * 20f))
                // 航向积分（陀螺仪主导）：地图旋转的唯一瞬时来源
                if (!headingDeg.isNaN() && dt > 0f) {
                    headingDeg = (headingDeg + yawRateDeg * dt + 360f) % 360f
                }
                lastGyroNs = event.timestamp
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                haveRotation = true
                processOrientation(event.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (haveAccel) lowPass(accelVals, event.values, event.timestamp - lastAccelNs, 12f)
                else for (i in 0..2) accelVals[i] = event.values[i]
                lastAccelNs = event.timestamp
                haveAccel = true
                if (haveAccel && haveMag && SensorManager.getRotationMatrix(r, null, accelVals, magVals)) {
                    haveRotation = true
                    processOrientation(event.timestamp)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (haveMag) lowPass(magVals, event.values, event.timestamp - lastMagNs, 8f)
                else for (i in 0..2) magVals[i] = event.values[i]
                lastMagNs = event.timestamp
                haveMag = true
                if (haveAccel && haveMag && SensorManager.getRotationMatrix(r, null, accelVals, magVals)) {
                    haveRotation = true
                    processOrientation(event.timestamp)
                }
            }
            else -> return
        }
    }

    /** 磁力计侧校准：由旋转矩阵 r 提取磁方位角，慢速校准融合航向。 */
    private fun processOrientation(nowNs: Long) {
        SensorManager.getOrientation(r, orient)
        val yVert = abs(r[7])   // 12 点方向（Y 轴）的竖直分量
        val zVert = abs(r[8])   // 表盘法线（Z 轴）的竖直分量
        if (useWrist) {
            if (yVert + 0.15f < zVert) useWrist = false
        } else {
            if (zVert + 0.15f < yVert) useWrist = true
        }
        val azim = if (useWrist) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, rScreen)
            SensorManager.getOrientation(rScreen, orient)
            orient[0]
        } else {
            orient[0]
        }

        var magDeg = Math.toDegrees(azim.toDouble()).toFloat()
        if (magDeg < 0f) magDeg += 360f

        if (headingDeg.isNaN()) {
            headingDeg = magDeg
            lastFusedNs = nowNs
            onChange?.invoke()
            return
        }

        val dt = ((nowNs - lastFusedNs) / 1e9f).coerceIn(0.001f, 0.5f)
        lastFusedNs = nowNs
        val err = ((magDeg - headingDeg + 540f) % 360f) - 180f
        // 小偏差（<45°，可信）：0.6/s 缓拉对齐真北并消除陀螺零偏；
        // 大偏差（≥45°，磁干扰跳变）：拒收大步修正，仅 2°/s 缓拉防漂移发散。
        val pull = if (abs(err) < 45f) err * (1f - exp(-dt * 0.6f))
        else kotlin.math.sign(err) * min(abs(err), 2f * dt)
        headingDeg = (headingDeg + pull + 360f) % 360f
        onChange?.invoke()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 磁力计精度事件静默处理：融合层已通过偏差门限天然抗干扰。
    }
}
