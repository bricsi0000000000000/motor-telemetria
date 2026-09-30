package hu.motor.telemetria.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import hu.motor.telemetria.R
import hu.motor.telemetria.data.TelemetrySample
import kotlin.math.max

/** Könnyű, függőségmentes szint- és gyorsulásgrafikon a túrarészlethez. */
class TelemetryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var samples: List<TelemetrySample> = emptyList()
    private val altitudePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val accelerationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.limit_90)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val eventPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.climb)
        style = Paint.Style.FILL
    }

    fun setSamples(value: List<TelemetrySample>) {
        samples = value
        visibility = if (value.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (samples.size < 2) return
        val altitudes = samples.mapNotNull { it.fusedAltitudeMeters }
        val minAltitude = altitudes.minOrNull() ?: 0.0
        val altitudeRange = max(5.0, (altitudes.maxOrNull() ?: minAltitude) - minAltitude)
        val half = height / 2f
        val step = width.toFloat() / (samples.size - 1)
        var previousAltitude: Pair<Float, Float>? = null
        var previousAcceleration: Pair<Float, Float>? = null
        samples.forEachIndexed { index, sample ->
            val x = index * step
            sample.fusedAltitudeMeters?.let { altitude ->
                val y = half - (((altitude - minAltitude) / altitudeRange) * (half - 10f)).toFloat()
                previousAltitude?.let { canvas.drawLine(it.first, it.second, x, y, altitudePaint) }
                previousAltitude = x to y
            }
            val normalized = (sample.forwardMeanMps2 / 6f).coerceIn(-1f, 1f)
            val accelerationY = half + half / 2f - normalized * (half / 2f - 8f)
            previousAcceleration?.let {
                canvas.drawLine(it.first, it.second, x, accelerationY, accelerationPaint)
            }
            previousAcceleration = x to accelerationY
            if (sample.flags and (TelemetrySample.FLAG_ROUGH or TelemetrySample.FLAG_IMPACT_CANDIDATE) != 0) {
                canvas.drawCircle(x, height - 8f, 5f, eventPaint)
            }
        }
    }
}
