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
 * 手表航向融合 v3：陀螺仪静止判定 + 磁力计校准门控。
 *
 * 针对用户实测两大痛点的根治方案：
 *  1. **平放不动地图自己打转** → 根因是室内磁扰动让方位角来回漂 ±10~20°，
 *     显示层缓动会主动追着漂移转。本版在融合层加入陀螺仪静止判定：
 *     陀螺仪实测手腕没转时，航向**冻结**，磁扰动只能通过「持续存在的偏差」
 *     （用于消除陀螺零偏）以 ≤1.2°/s 极慢速率修正——来回摆动的磁扰被
 *     3s 时间常数的误差均值天然滤除，净影响≈0，地图纹丝不动。
 *  2. **转动手腕跟手** → 陀螺仪积分 1:1 驱动航向（真实转速、无磁噪声），
 *     旋转中磁力计以 err×2（上限 12°/s）回拉对齐真北；≥45° 的磁跳变拒收。
 *  3. 口径自适应（12 点方向 Y 轴优先，迟滞 0.15），防表盘姿态变化时航向退化。
 *  4. onChange 仅在航向变化 >0.3° 时触发——静止时不再以 50Hz 空转 UI 帧循环。
 *
 * 数据源：TYPE_ROTATION_VECTOR（含陀螺仪预积分，系统指南针同款）+ 独立
 * TYPE_GYROSCOPE（静止判定与积分）；不可用时退回加速度计+磁力计倾角补偿。
 */
class HeadingFusion(context: Context) : SensorEventListener {

    /** 融合航向 0..360（NaN=尚未初始化）。 */
    @Volatile
    var headingDeg: Float = Float.NaN
        private set

    /** 航向变化 >0.3° 时回调（主线程）。 */
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

    // ---- 旋转矩阵 / 磁方位角提取 ----
    private val r = FloatArray(9)
    private val rScreen = FloatArray(9)
    private val orient = FloatArray(3)
    private var haveRotation = false

    // 口径自适应迟滞（12 点方向 Y 轴 |R[7]| vs 表盘法线 Z 轴 |R[8]|）
    private var useWrist = false

    // 磁方位角输出低通（sin/cos 分量，防 0/360 跳变）
    private var magSin = Float.NaN
    private var magCos = Float.NaN
    private var lastMagNs = 0L

    // ---- 陀螺仪积分 + 静止判定 ----
    private var fusedDeg = Float.NaN
    private var yawRateDeg = Float.NaN
    private var biasDegPerS = 0f // 静止时估计的陀螺零偏（实测本机 ≈ -0.13°/s）
    private var lastGyroNs = 0L
    private var still = true
    private var quietSinceNs = 0L
    private var errAvgDeg = 0f // 静止时的误差均值（≈陀螺零偏对应的稳态偏差）
    private var lastCorrNs = 0L
    private var lastReportedDeg = Float.NaN

    private fun resetFilter() {
        haveRotation = false
        useWrist = false
        magSin = Float.NaN
        magCos = Float.NaN
        lastMagNs = 0L
        fusedDeg = Float.NaN
        yawRateDeg = Float.NaN
        biasDegPerS = 0f
        lastGyroNs = 0L
        still = true
        quietSinceNs = 0L
        errAvgDeg = 0f
        lastCorrNs = 0L
        lastReportedDeg = Float.NaN
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
                if (!haveRotation) return
                val now = event.timestamp
                val dt = if (lastGyroNs == 0L) 0f
                else ((now - lastGyroNs) / 1e9f).coerceIn(0.001f, 0.5f)
                // 绕世界垂直轴的角速度 = gyro · up(设备系)，up = R 第 3 行
                val rateDeg = Math.toDegrees(
                    (event.values[0] * r[6] + event.values[1] * r[7] + event.values[2] * r[8]).toDouble(),
                ).toFloat()
                yawRateDeg = if (yawRateDeg.isNaN()) rateDeg
                else yawRateDeg + (rateDeg - yawRateDeg) * (1f - exp(-dt * 15f))
                // 静止判定（迟滞）：转动中 |yaw|>5°/s 才算动；静止需 <2.5°/s 持续 0.6s
                if (still) {
                    if (abs(yawRateDeg) > 5f) still = false
                } else {
                    if (abs(yawRateDeg) < 2.5f) {
                        if (quietSinceNs == 0L) quietSinceNs = now
                        else if (now - quietSinceNs > 600_000_000L) {
                            still = true
                            quietSinceNs = 0L
                            errAvgDeg = 0f
                        }
                    } else {
                        quietSinceNs = 0L
                    }
                }
                // 静止时估计陀螺零偏（~1.3s 时间常数）：本机实测 -0.13°/s，
                // 不扣除的话静止航向以 7.8°/min 漂移——这是「平放自转」来源之一。
                if (still && abs(yawRateDeg) < 2.5f) {
                    biasDegPerS += (yawRateDeg - biasDegPerS) * (1f - exp(-dt * 0.8f))
                }
                // 陀螺仪积分（扣除零偏）：手腕真实转速 1:1 驱动航向（磁噪声无法伪造）
                if (!fusedDeg.isNaN() && dt > 0f) {
                    fusedDeg = (fusedDeg + (yawRateDeg - biasDegPerS) * dt + 360f) % 360f
                    headingDeg = fusedDeg
                    report()
                }
                lastGyroNs = now
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                haveRotation = true
                processMag(event.timestamp)
            }
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(accelVals)
                haveAccel = true
                if (haveAccel && haveMag && SensorManager.getRotationMatrix(r, null, accelVals, magVals)) {
                    haveRotation = true
                    processMag(event.timestamp)
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (haveMag) lowPass(magVals, event.values, event.timestamp - lastMagNs, 10f)
                else event.values.copyInto(magVals)
                lastMagNs = event.timestamp
                haveMag = true
                if (haveAccel && haveMag && SensorManager.getRotationMatrix(r, null, accelVals, magVals)) {
                    haveRotation = true
                    processMag(event.timestamp)
                }
            }
            else -> return
        }
    }

    private val accelVals = FloatArray(3)
    private val magVals = FloatArray(3)
    private var haveAccel = false
    private var haveMag = false

    /** 磁力计侧：提取方位角并按静止/旋转两种门控校准融合航向。 */
    private fun processMag(nowNs: Long) {
        SensorManager.getOrientation(r, orient)
        val yVert = abs(r[7])
        val zVert = abs(r[8])
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

        val s = sin(azim)
        val c = cos(azim)
        if (magSin.isNaN()) {
            magSin = s
            magCos = c
        } else {
            val dt = ((nowNs - lastMagNs) / 1e9f).coerceIn(0.001f, 0.5f)
            val a = 1f - exp(-dt * 12f)
            magSin += (s - magSin) * a
            magCos += (c - magCos) * a
        }
        lastMagNs = nowNs

        var magDeg = Math.toDegrees(atan2(magSin.toDouble(), magCos.toDouble())).toFloat()
        if (magDeg < 0f) magDeg += 360f

        if (fusedDeg.isNaN()) {
            fusedDeg = magDeg
            headingDeg = fusedDeg
            report()
            return
        }

        val dt = if (lastCorrNs == 0L) 0.016f
        else ((nowNs - lastCorrNs) / 1e9f).coerceIn(0.001f, 0.5f)
        lastCorrNs = nowNs

        val err = ((magDeg - fusedDeg + 540f) % 360f) - 180f
        errAvgDeg += (err - errAvgDeg) * (1f - exp(-dt * 0.35f)) // ~3s 均值

        // 校准门控：
        //  静止 → 零偏已在积分端扣除；这里只修正 <15° 的稳态残差（≤0.5°/s 不可感知），
        //          偏差 ≥15° 说明拿起/旋转期间磁力计被扰动过——**完全冻结不自转**，
        //          等下次真实转动时（旋转门控 12°/s）快速回同步。
        //  旋转 → err×2 回拉（上限 12°/s）快速对齐真北；≥45° 视为磁跳变拒收，仅 3°/s。
        val corrDegPerS = if (still) {
            if (abs(errAvgDeg) < 15f) (errAvgDeg * 0.3f).coerceIn(-0.5f, 0.5f) else 0f
        } else {
            if (abs(err) < 45f) (err * 2f).coerceIn(-12f, 12f)
            else kotlin.math.sign(err) * 3f
        }
        fusedDeg = (fusedDeg + corrDegPerS * dt + 360f) % 360f
        headingDeg = fusedDeg
        report()
    }

    /** 航向变化 >0.3° 才通知 UI（静止时不空转帧循环）。 */
    private fun report() {
        if (headingDeg.isNaN()) return
        if (lastReportedDeg.isNaN() ||
            abs(((headingDeg - lastReportedDeg + 540f) % 360f) - 180f) > 0.3f
        ) {
            lastReportedDeg = headingDeg
            onChange?.invoke()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度事件由融合门控消化。
    }
}
