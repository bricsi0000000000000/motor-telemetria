package hu.motor.telemetria.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Build
import android.view.WindowManager
import hu.motor.telemetria.data.TelemetrySample
import hu.motor.telemetria.analysis.TelemetryMath
import hu.motor.telemetria.analysis.TelemetryAccumulator
import hu.motor.telemetria.util.TelemetrySettings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Egy lezárt, egy másodperces ablak – a szolgáltatás rendeli a túrához. */
data class AggregatedTelemetry(
    val time: Long,
    val lat: Double?,
    val lon: Double?,
    val speedMps: Float,
    val bearingDegrees: Float,
    val pressureHpa: Float?,
    val fusedAltitudeMeters: Double?,
    val forwardMeanMps2: Float,
    val forwardMinMps2: Float,
    val forwardMaxMps2: Float,
    val lateralMeanMps2: Float,
    val lateralRmsMps2: Float,
    val lateralPeakMps2: Float,
    val verticalRmsMps2: Float,
    val verticalPeakMps2: Float,
    val yawPeakRadS: Float,
    val rollPeakRadS: Float,
    val leanDegrees: Float?,
    val mountQuality: Float,
    val sampleCount: Int,
    val flags: Int
)

data class RawTelemetryFrame(val timeNanos: Long, val x: Float, val y: Float, val z: Float)
data class SensorEventClip(val triggerTime: Long, val frames: List<RawTelemetryFrame>)

/**
 * 50 Hz-es mozgás- és 5 Hz-es nyomásadatot egy másodperces sorokká tömörít.
 * A számolás külön HandlerThreaden fut, ezért a GPS és a UI szálát nem terheli.
 */
class SensorTelemetryCollector(
    context: Context,
    private val onEventClip: (SensorEventClip) -> Unit = {},
    private val onSample: (AggregatedTelemetry) -> Unit
) : SensorEventListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val thread = HandlerThread("motor-telemetry-sensors")
    private lateinit var handler: Handler

    private val rotation = FloatArray(9)
    private var hasRotation = false
    private var currentRoll = 0f
    @Volatile private var pressure: Float? = null
    @Volatile private var pressureAnchor: Float? = null
    @Volatile private var altitudeAnchor: Double? = null
    private val gravity = FloatArray(3)

    @Volatile private var lat: Double? = null
    @Volatile private var lon: Double? = null
    @Volatile private var speedMps = 0f
    @Volatile private var bearingDegrees = 0f
    @Volatile private var gpsAltitude: Double? = null

    private var bucketSecond = -1L
    private val motion = TelemetryAccumulator()
    private var yawPeak = 0f
    private var rollPeak = 0f
    private var calibrationSeconds = 0
    private var baselineRoll = 0f
    private var hingeState = "none"
    private var activeProfileKey = ""

    val providesBarometricAltitude: Boolean
        get() = pressureAnchor != null && altitudeAnchor != null

    /** Utolsó tíz másodperc nyers gyorsulása későbbi eseménykliphez. */
    private val rawRing = ArrayDeque<RawTelemetryFrame>(500)
    private var pendingClip: PendingClip? = null
    private var lastClipTriggerNanos = 0L
    private data class PendingClip(
        val triggerWallTime: Long,
        val endNanos: Long,
        val frames: MutableList<RawTelemetryFrame>
    )

    fun start() {
        if (thread.isAlive) return
        thread.start()
        handler = Handler(thread.looper)
        selectCalibrationProfile()
        val motionDelayUs = 20_000 // 50 Hz
        val pressureDelayUs = 200_000 // 5 Hz
        val maxLatencyUs = 1_000_000
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        listOfNotNull(
            linear,
            manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ).forEach { manager.registerListener(this, it, motionDelayUs, maxLatencyUs, handler) }
        manager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
            manager.registerListener(this, it, pressureDelayUs, maxLatencyUs, handler)
        }
        manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            manager.registerListener(this, it, pressureDelayUs, maxLatencyUs, handler)
        }
    }

    fun stop() {
        if (!thread.isAlive) return
        val stopped = CountDownLatch(1)
        handler.post {
            manager.unregisterListener(this)
            if (bucketSecond >= 0) emitBucket()
            pendingClip?.let { onEventClip(SensorEventClip(it.triggerWallTime, it.frames.toList())) }
            pendingClip = null
            thread.quitSafely()
            stopped.countDown()
        }
        stopped.await(1, TimeUnit.SECONDS)
    }

    fun updateLocation(
        latitude: Double,
        longitude: Double,
        speed: Float,
        bearing: Float,
        altitude: Double?
    ) {
        lat = latitude
        lon = longitude
        speedMps = speed
        bearingDegrees = bearing
        gpsAltitude = altitude
        val p = pressure
        if (p != null && altitude != null) {
            if (pressureAnchor == null || altitudeAnchor == null) {
                pressureAnchor = p
                altitudeAnchor = altitude
            } else {
                // Az időjárási driftet nagyon lassan a GPS abszolút magasságához húzzuk.
                val predicted = fusedAltitude(p)
                if (predicted != null) altitudeAnchor = altitudeAnchor!! + (altitude - predicted) * 0.01
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val wallTime = TelemetryMath.monotonicToWallMillis(
            System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), event.timestamp
        )
        rollBucket(wallTime / 1000L)
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                hasRotation = true
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotation, orientation)
                currentRoll = orientation[2]
            }
            Sensor.TYPE_PRESSURE -> pressure = event.values[0]
            Sensor.TYPE_HINGE_ANGLE -> {
                val next = when {
                    event.values[0] < 15f -> "closed"
                    event.values[0] < 165f -> "half"
                    else -> "open"
                }
                if (next != hingeState) {
                    hingeState = next
                    selectCalibrationProfile()
                }
            }
            Sensor.TYPE_GYROSCOPE -> addGyroscope(event.values)
            Sensor.TYPE_LINEAR_ACCELERATION -> addAcceleration(event.timestamp, event.values)
            Sensor.TYPE_ACCELEROMETER -> {
                val linear = FloatArray(3)
                for (i in 0..2) {
                    gravity[i] = 0.9f * gravity[i] + 0.1f * event.values[i]
                    linear[i] = event.values[i] - gravity[i]
                }
                addAcceleration(event.timestamp, linear)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun rollBucket(second: Long) {
        if (bucketSecond < 0) bucketSecond = second
        if (second != bucketSecond) {
            emitBucket()
            bucketSecond = second
        }
    }

    private fun addAcceleration(timeNanos: Long, values: FloatArray) {
        val raw = RawTelemetryFrame(timeNanos, values[0], values[1], values[2])
        rawRing.addLast(raw)
        while (rawRing.size > 500) rawRing.removeFirst()
        pendingClip?.let { clip ->
            clip.frames += raw
            if (timeNanos >= clip.endNanos) {
                onEventClip(SensorEventClip(clip.triggerWallTime, clip.frames.toList()))
                pendingClip = null
            }
        }
        val manualAxes = manualAxes(values)
        val axes = if (manualAxes != null) {
            manualAxes
        } else if (hasRotation) {
            val east = rotation[0] * values[0] + rotation[1] * values[1] + rotation[2] * values[2]
            val north = rotation[3] * values[0] + rotation[4] * values[1] + rotation[5] * values[2]
            val up = rotation[6] * values[0] + rotation[7] * values[1] + rotation[8] * values[2]
            TelemetryMath.worldToVehicle(east, north, up, bearingDegrees).let {
                Triple(it[0], it[1], it[2])
            }
        } else return
        val (forward, lateral, up) = axes
        motion.add(forward, lateral, up)
    }

    private fun addGyroscope(values: FloatArray) {
        val manualAxes = manualAxes(values)
        val (roll, yaw) = if (manualAxes != null) {
            manualAxes.first to manualAxes.third
        } else if (hasRotation) {
            val wx = rotation[0] * values[0] + rotation[1] * values[1] + rotation[2] * values[2]
            val wy = rotation[3] * values[0] + rotation[4] * values[1] + rotation[5] * values[2]
            val wz = rotation[6] * values[0] + rotation[7] * values[1] + rotation[8] * values[2]
            val heading = Math.toRadians(bearingDegrees.toDouble())
            (wx * sin(heading) + wy * cos(heading)).toFloat() to wz
        } else return
        yawPeak = maxOf(yawPeak, abs(yaw))
        rollPeak = maxOf(rollPeak, abs(roll))
    }

    private fun emitBucket() {
        val window = motion.snapshot()
        if (window == null) {
            resetBucket()
            return
        }
        val samples = window.count
        selectCalibrationProfile()
        val manual = TelemetrySettings.manualMountQuarterTurns != null
        if (!manual && hasRotation && calibrationSeconds < 20 &&
            speedMps >= 20f / 3.6f && yawPeak < 0.25f
        ) {
            baselineRoll = if (calibrationSeconds == 0) currentRoll
            else (baselineRoll * calibrationSeconds + currentRoll) / (calibrationSeconds + 1)
            calibrationSeconds = min(20, calibrationSeconds + 1)
            if (calibrationSeconds == 20) {
                TelemetrySettings.saveBaseline(activeProfileKey, baselineRoll)
            }
        }
        val calibration = if (manual) 0.75f else (calibrationSeconds / 20f).coerceIn(0f, 1f)
        val rollDelta = angleDistance(currentRoll, baselineRoll)
        val mountStable = !(hasRotation && calibrationSeconds >= 20 && speedMps > 3f &&
            yawPeak < 0.4f && rollDelta > Math.toRadians(25.0).toFloat())
        val quality = TelemetryMath.mountQuality(
            samples, calibration, hasRotation, mountStable, manual
        )
        TelemetrySettings.calibrationQuality = calibration
        var flags = 0
        if (calibration < 1f) flags = flags or TelemetrySample.FLAG_CALIBRATING
        if (quality < 0.65f) flags = flags or TelemetrySample.FLAG_POOR_MOUNT
        if (window.forwardMin < -3f) flags = flags or TelemetrySample.FLAG_HARD_BRAKE
        if (window.forwardMax > 3f) flags = flags or TelemetrySample.FLAG_HARD_ACCEL
        if (window.verticalPeak > 6f && speedMps > 3f) flags = flags or TelemetrySample.FLAG_ROUGH
        if (window.verticalPeak > 20f || yawPeak > 3.5f || rollPeak > 3.5f) {
            flags = flags or TelemetrySample.FLAG_IMPACT_CANDIDATE
            startEventClip()
        }
        val lean = if (!manual && calibration >= 1f) {
            Math.toDegrees((currentRoll - baselineRoll).toDouble()).toFloat()
        } else null
        onSample(
            AggregatedTelemetry(
                time = bucketSecond * 1000L,
                lat = lat,
                lon = lon,
                speedMps = speedMps,
                bearingDegrees = bearingDegrees,
                pressureHpa = pressure,
                fusedAltitudeMeters = pressure?.let(::fusedAltitude),
                forwardMeanMps2 = window.forwardMean,
                forwardMinMps2 = window.forwardMin,
                forwardMaxMps2 = window.forwardMax,
                lateralMeanMps2 = window.lateralMean,
                lateralRmsMps2 = window.lateralRms,
                lateralPeakMps2 = window.lateralPeak,
                verticalRmsMps2 = window.verticalRms,
                verticalPeakMps2 = window.verticalPeak,
                yawPeakRadS = yawPeak,
                rollPeakRadS = rollPeak,
                leanDegrees = lean,
                mountQuality = quality,
                sampleCount = samples,
                flags = flags
            )
        )
        resetBucket()
    }

    private fun fusedAltitude(value: Float): Double? {
        val p0 = pressureAnchor ?: return gpsAltitude
        val h0 = altitudeAnchor ?: return gpsAltitude
        return TelemetryMath.pressureAltitude(h0, p0, value)
    }

    private fun manualAxes(values: FloatArray): Triple<Float, Float, Float>? =
        TelemetrySettings.manualMountQuarterTurns?.let { turns ->
            TelemetryMath.manualToVehicle(values[0], values[1], values[2], turns)?.let {
                Triple(it[0], it[1], it[2])
            }
        }

    private fun startEventClip() {
        val newest = rawRing.lastOrNull()?.timeNanos ?: return
        if (pendingClip != null ||
            (lastClipTriggerNanos > 0L && newest - lastClipTriggerNanos < 30_000_000_000L)
        ) return
        lastClipTriggerNanos = newest
        pendingClip = PendingClip(
            triggerWallTime = bucketSecond * 1000L,
            endNanos = newest + 20_000_000_000L,
            frames = rawRing.toMutableList()
        )
    }

    private fun selectCalibrationProfile() {
        val displayRotation = runCatching {
            @Suppress("DEPRECATION")
            (appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.rotation
        }.getOrDefault(0)
        val key = "${Build.MODEL}|$displayRotation|$hingeState"
        if (key == activeProfileKey) return
        activeProfileKey = key
        val saved = TelemetrySettings.savedBaseline(key)
        baselineRoll = saved ?: 0f
        calibrationSeconds = if (saved == null) 0 else 20
    }

    private fun angleDistance(a: Float, b: Float): Float {
        var value = a - b
        val pi = Math.PI.toFloat()
        while (value > pi) value -= pi * 2
        while (value < -pi) value += pi * 2
        return abs(value)
    }

    private fun resetBucket() {
        motion.clear()
        yawPeak = 0f
        rollPeak = 0f
    }

    companion object {
        fun canCollect(context: Context): Boolean {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val motion = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val orientation = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            return motion != null && (orientation != null || TelemetrySettings.manualMountQuarterTurns != null)
        }

        /** Felhasználónak is olvasható futásidejű szenzorleltár. */
        fun inventory(context: Context): String {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val interesting = setOf(
                Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION,
                Sensor.TYPE_GYROSCOPE, Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_PRESSURE,
                Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY,
                Sensor.TYPE_HINGE_ANGLE
            )
            return manager.getSensorList(Sensor.TYPE_ALL)
                .filter { it.type in interesting }
                .joinToString("\n") { sensor ->
                    val rate = if (sensor.minDelay > 0) "%.0f Hz".format(1_000_000f / sensor.minDelay)
                    else "eseményvezérelt"
                    "${sensor.name} · ${sensor.vendor} · felbontás ${sensor.resolution} · max. $rate · ${sensor.fifoMaxEventCount} FIFO"
                }.ifBlank { "A telefon nem tett elérhetővé támogatott szenzort." }
        }
    }
}
