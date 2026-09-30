package hu.motor.telemetria.analysis

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class SpeedBand { STOPPED, GREEN, YELLOW, ORANGE, LIGHT_BLUE, DARK_BLUE, PINK, PURPLE }

object TelemetryMath {
    fun speedBand(speedMps: Float): SpeedBand = when ((speedMps * 3.6f).coerceAtLeast(0f).roundToInt()) {
        0 -> SpeedBand.STOPPED
        in 1..10 -> SpeedBand.GREEN
        in 11..30 -> SpeedBand.YELLOW
        in 31..50 -> SpeedBand.ORANGE
        in 51..69 -> SpeedBand.LIGHT_BLUE
        in 70..90 -> SpeedBand.DARK_BLUE
        in 91..120 -> SpeedBand.PINK
        else -> SpeedBand.PURPLE
    }

    fun pressureAltitude(anchorAltitude: Double, anchorPressureHpa: Float, pressureHpa: Float): Double =
        anchorAltitude + 44330.0 *
            (1.0 - (pressureHpa / anchorPressureHpa).toDouble().pow(0.1903))

    fun monotonicToWallMillis(nowWallMillis: Long, nowElapsedNanos: Long, eventNanos: Long): Long =
        nowWallMillis - (nowElapsedNanos - eventNanos) / 1_000_000L

    /** Kelet–észak–fel koordinátából menetirány–oldal–fel koordináta. */
    fun worldToVehicle(east: Float, north: Float, up: Float, bearingDegrees: Float): FloatArray {
        val heading = Math.toRadians(bearingDegrees.toDouble())
        return floatArrayOf(
            (east * sin(heading) + north * cos(heading)).toFloat(),
            (east * cos(heading) - north * sin(heading)).toFloat(),
            up
        )
    }

    /** Kézi tartóbeállítás tartalék arra az esetre, ha nincs forgásvektor. */
    fun manualToVehicle(x: Float, y: Float, z: Float, quarterTurns: Int): FloatArray? =
        when (quarterTurns) {
            0 -> floatArrayOf(y, x, z)
            1 -> floatArrayOf(x, -y, z)
            2 -> floatArrayOf(-y, -x, z)
            3 -> floatArrayOf(-x, y, z)
            else -> null
        }

    fun rms(values: FloatArray): Float =
        if (values.isEmpty()) 0f else sqrt(values.sumOf { (it * it).toDouble() } / values.size).toFloat()

    fun mountQuality(
        sampleCount: Int,
        calibration: Float,
        hasRotation: Boolean,
        mountStable: Boolean,
        manual: Boolean
    ): Float {
        val completeness = (sampleCount / 45f).coerceIn(0f, 1f)
        return completeness * when {
            manual -> 0.7f
            !hasRotation -> 0f
            !mountStable -> 0.2f
            else -> maxOf(0.25f, calibration.coerceIn(0f, 1f))
        }
    }
}
