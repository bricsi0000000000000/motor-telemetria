package hu.motor.telemetria.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryReplayTest {
    @Test
    fun recordedStreamProducesStableWindow() {
        val accumulator = TelemetryAccumulator()
        val lines = checkNotNull(javaClass.classLoader?.getResourceAsStream("sensor_replay.csv"))
            .bufferedReader().readLines().drop(1)
        lines.forEach { line ->
            val fields = line.split(',')
            accumulator.add(fields[1].toFloat(), fields[2].toFloat(), fields[3].toFloat())
        }
        val window = checkNotNull(accumulator.snapshot())
        assertEquals(4, window.count)
        assertEquals(0f, window.forwardMean, 0.001f)
        assertEquals(-3f, window.forwardMin, 0.001f)
        assertEquals(2f, window.forwardMax, 0.001f)
        assertTrue(window.verticalRms > 1.4f)
        assertEquals(2f, window.verticalPeak, 0.001f)
    }
}
