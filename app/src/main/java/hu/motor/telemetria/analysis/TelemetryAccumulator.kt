package hu.motor.telemetria.analysis

import kotlin.math.abs
import kotlin.math.sqrt

data class MotionWindow(
    val count: Int,
    val forwardMean: Float,
    val forwardMin: Float,
    val forwardMax: Float,
    val lateralMean: Float,
    val lateralRms: Float,
    val lateralPeak: Float,
    val verticalRms: Float,
    val verticalPeak: Float
)

/** Androidtól független egy-másodperces aggregátor, visszajátszó tesztekhez is. */
class TelemetryAccumulator {
    private var count = 0
    private var forwardSum = 0.0
    private var forwardMin = Float.POSITIVE_INFINITY
    private var forwardMax = Float.NEGATIVE_INFINITY
    private var lateralSum = 0.0
    private var lateralSquare = 0.0
    private var lateralPeak = 0f
    private var verticalSquare = 0.0
    private var verticalPeak = 0f

    fun add(forward: Float, lateral: Float, vertical: Float) {
        count++
        forwardSum += forward
        forwardMin = minOf(forwardMin, forward)
        forwardMax = maxOf(forwardMax, forward)
        lateralSum += lateral
        lateralSquare += lateral * lateral
        lateralPeak = maxOf(lateralPeak, abs(lateral))
        verticalSquare += vertical * vertical
        verticalPeak = maxOf(verticalPeak, abs(vertical))
    }

    fun snapshot(): MotionWindow? = if (count == 0) null else MotionWindow(
        count = count,
        forwardMean = (forwardSum / count).toFloat(),
        forwardMin = forwardMin,
        forwardMax = forwardMax,
        lateralMean = (lateralSum / count).toFloat(),
        lateralRms = sqrt(lateralSquare / count).toFloat(),
        lateralPeak = lateralPeak,
        verticalRms = sqrt(verticalSquare / count).toFloat(),
        verticalPeak = verticalPeak
    )

    fun clear() {
        count = 0
        forwardSum = 0.0
        forwardMin = Float.POSITIVE_INFINITY
        forwardMax = Float.NEGATIVE_INFINITY
        lateralSum = 0.0
        lateralSquare = 0.0
        lateralPeak = 0f
        verticalSquare = 0.0
        verticalPeak = 0f
    }
}
