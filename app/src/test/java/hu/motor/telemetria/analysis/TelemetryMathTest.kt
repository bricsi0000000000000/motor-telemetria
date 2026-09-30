package hu.motor.telemetria.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryMathTest {
    @Test
    fun speedBandsUseTheRequestedInclusiveLimits() {
        fun kmh(value: Int) = TelemetryMath.speedBand(value / 3.6f)
        assertEquals(SpeedBand.STOPPED, kmh(0))
        assertEquals(SpeedBand.GREEN, kmh(1))
        assertEquals(SpeedBand.GREEN, kmh(10))
        assertEquals(SpeedBand.YELLOW, kmh(11))
        assertEquals(SpeedBand.YELLOW, kmh(30))
        assertEquals(SpeedBand.ORANGE, kmh(31))
        assertEquals(SpeedBand.ORANGE, kmh(50))
        assertEquals(SpeedBand.LIGHT_BLUE, kmh(51))
        assertEquals(SpeedBand.LIGHT_BLUE, kmh(69))
        assertEquals(SpeedBand.DARK_BLUE, kmh(70))
        assertEquals(SpeedBand.DARK_BLUE, kmh(90))
        assertEquals(SpeedBand.PINK, kmh(91))
        assertEquals(SpeedBand.PINK, kmh(120))
        assertEquals(SpeedBand.PURPLE, kmh(121))
    }

    @Test
    fun lowerPressureRaisesRelativeAltitude() {
        val altitude = TelemetryMath.pressureAltitude(120.0, 1013.25f, 1001.25f)
        assertTrue(altitude > 210.0)
        assertTrue(altitude < 230.0)
        assertEquals(120.0, TelemetryMath.pressureAltitude(120.0, 1013.25f, 1013.25f), 0.001)
    }

    @Test
    fun monotonicSensorTimeIsAlignedToWallClock() {
        assertEquals(9_750L, TelemetryMath.monotonicToWallMillis(10_000, 5_000_000_000, 4_750_000_000))
    }

    @Test
    fun worldAxesFollowGpsBearing() {
        val north = TelemetryMath.worldToVehicle(0f, 2f, 3f, 0f)
        assertEquals(2f, north[0], 0.001f)
        assertEquals(0f, north[1], 0.001f)
        val east = TelemetryMath.worldToVehicle(2f, 0f, 3f, 90f)
        assertEquals(2f, east[0], 0.001f)
        assertEquals(0f, east[1], 0.001f)
    }

    @Test
    fun secondAggregationAndQualityAreDeterministic() {
        assertEquals(2.5f, TelemetryMath.rms(floatArrayOf(3f, 4f, 0f, 0f)), 0.001f)
        assertEquals(1f, TelemetryMath.mountQuality(50, 1f, true, true, false), 0.001f)
        assertEquals(0.2f, TelemetryMath.mountQuality(50, 1f, true, false, false), 0.001f)
        assertEquals(0f, TelemetryMath.mountQuality(50, 1f, false, true, false), 0.001f)
        assertEquals(0.7f, TelemetryMath.mountQuality(50, 0f, false, true, true), 0.001f)
    }

    @Test
    fun manualMountCoversAllFourDirections() {
        assertEquals(2f, TelemetryMath.manualToVehicle(1f, 2f, 3f, 0)!![0], 0.001f)
        assertEquals(1f, TelemetryMath.manualToVehicle(1f, 2f, 3f, 1)!![0], 0.001f)
        assertEquals(-2f, TelemetryMath.manualToVehicle(1f, 2f, 3f, 2)!![0], 0.001f)
        assertEquals(-1f, TelemetryMath.manualToVehicle(1f, 2f, 3f, 3)!![0], 0.001f)
    }
}
