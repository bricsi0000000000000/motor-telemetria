package hu.motor.telemetria.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import hu.motor.telemetria.MainActivity
import hu.motor.telemetria.R
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.data.TrackPoint
import hu.motor.telemetria.data.TelemetrySample
import hu.motor.telemetria.sync.SyncManager
import hu.motor.telemetria.util.AutoSettings
import hu.motor.telemetria.util.Fmt
import hu.motor.telemetria.util.TelemetrySettings
import hu.motor.telemetria.widget.StatsWidgetRenderer
import hu.motor.telemetria.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Előtérben futó szolgáltatás, ami a GPS-t hallgatja és a nyomvonalat menti.
 *
 * A hívók a companion objectben lévő [start] / [pause] / [resume] / [stop]
 * függvényeket használják, az állapotot pedig a [state] flow-n keresztül figyelik.
 */
class TrackingService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "hu.motor.telemetria.action.START"
        const val ACTION_PAUSE = "hu.motor.telemetria.action.PAUSE"
        const val ACTION_RESUME = "hu.motor.telemetria.action.RESUME"
        const val ACTION_STOP = "hu.motor.telemetria.action.STOP"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001

        private const val PREFS = "tracking_prefs"
        private const val KEY_ACTIVE_TRACK = "active_track_id"

        /** GPS mintavétel gyakorisága. */
        private const val GPS_INTERVAL_MS = 1000L

        /** Ennél pontatlanabb fixet eldobunk. */
        private const val MIN_ACCURACY_M = 35f

        /** Új pontot csak ennyi elmozdulás után rögzítünk… */
        private const val MIN_RECORD_DISTANCE_M = 3.0

        /** …vagy ha ennyi idő eltelt (álló helyzetben is legyen minta). */
        private const val MAX_RECORD_INTERVAL_MS = 5000L

        /** Ez alatt "állónak" tekintjük a motort (kb. 3 km/h). */
        private const val MOVING_SPEED_MPS = 0.8f

        /** Két fix között ennél nagyobb ugrás biztosan GPS hiba. */
        private const val MAX_JUMP_M = 200.0

        /** Szintemelkedés-számláló hiszterézise (a GPS magasság zajos). */
        private const val ELEVATION_THRESHOLD_M = 4.0
        private const val BARO_ELEVATION_THRESHOLD_M = 1.5

        /** Ilyen gyakran mentjük a túra összesítőjét, hogy crash esetén se vesszen el. */
        private const val CHECKPOINT_MS = 20_000L

        /** Ilyen gyakran rajzoljuk át a kezdőképernyős widgeteket. */
        private const val WIDGET_REFRESH_MS = 2000L

        /** Élő állapot a szervernek: kis csomag, ezért mehet sűrűn. */
        private const val LIVE_PUSH_MS = 3000L

        /** A nyomvonal feltöltése mérés közben – a korlátlan mobilnet miatt sűrűn. */
        private const val SYNC_INTERVAL_MS = 15_000L

        /** Megérkezés után ennyi ideig kell egy helyen belül állni az automatikus lezáráshoz. */
        private const val ARRIVAL_DWELL_MS = 90_000L

        /** Ez alatt "megálltunk" (kb. 7 km/h). */
        private const val ARRIVED_SPEED_MPS = 2f

        /** Ennyi egy helyben állás után magától szünetel a mérés. */
        private const val AUTO_PAUSE_STILL_MS = 5 * 60 * 1000L

        /** Ekkora körön belül maradva számítunk "egy helyben állónak". */
        private const val STILL_RADIUS_M = 25.0

        /** Szünet alatt ennyit távolodva a megállás helyétől magától folytatódik a mérés. */
        private const val AUTO_RESUME_DISTANCE_M = 100.0

        /** Szünet alatt is figyeljük a GPS-t, de ritkábban – az akku miatt. */
        private const val PAUSED_GPS_INTERVAL_MS = 5000L

        /** A wakelock lejárata; a ticker checkpointonként megújítja. */
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L

        private val _state = MutableStateFlow(TrackingState())
        val state: StateFlow<TrackingState> = _state.asStateFlow()

        fun start(context: Context) = send(context, ACTION_START)
        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            val intent = Intent(context, TrackingService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    // Egyszálú végrehajtó: a DB írások sorrendje garantált, így a túrát lezáró
    // mentés biztosan az utolsó pontok beszúrása után fut le.
    private val dbExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + dbExecutor.asCoroutineDispatcher())
    private var ticker: Job? = null

    private lateinit var locationManager: LocationManager
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val dao by lazy { AppDatabase.get(this).trackDao() }

    // --- mérés közbeni állapot -------------------------------------------------
    private var trackId = 0L
    private var startedAt = 0L
    private var pausedTotalMs = 0L
    private var pausedAt = 0L
    private var segment = 0

    private var lastLocation: Location? = null
    private var lastRecordedAt = 0L
    private var distanceMeters = 0.0
    private var movingMillis = 0L
    private var maxSpeedMps = 0f
    private var elevationGain = 0.0
    private var elevationRef = Double.NaN
    private var pointCount = 0
    private var telemetrySampleCount = 0
    private var telemetrySeq = 0
    private var telemetryElevationRef = Double.NaN
    private var telemetryCollector: SensorTelemetryCollector? = null

    // --- automatikus lezárás ---------------------------------------------------
    /** A figyelt helyek; a mérés indulásakor töltjük be. */
    private var autoPlaces: List<Place> = emptyList()

    /** Mióta állunk egy helyen belül – ennyi idő után zárjuk le a túrát. */
    private var arrivedSince = 0L

    // --- automatikus szünet és folytatás ---------------------------------------
    /** Az a pont, ahol megálltunk – ehhez mérjük, hogy tényleg egy helyben vagyunk-e. */
    private var stillAnchor: Location? = null

    /** Mióta állunk egy helyben – ennyi idő után szünetel a mérés. */
    private var stillSince = 0L

    /** A szünet kezdetének helye – ettől mérjük a folytatáshoz kellő 100 métert. */
    private var pauseAnchor: Location? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            // A rendszer indított újra minket (START_STICKY): folytassuk a félbemaradt túrát.
            null -> handleProcessRestart()
        }
        return START_STICKY
    }

    // --- parancsok -------------------------------------------------------------

    private fun handleStart() {
        if (_state.value.isActive) return
        if (!hasLocationPermission()) {
            stopSelf()
            return
        }

        trackId = 0L
        startedAt = System.currentTimeMillis()
        pausedTotalMs = 0L
        pausedAt = 0L
        segment = 0
        lastLocation = null
        lastRecordedAt = 0L
        distanceMeters = 0.0
        movingMillis = 0L
        maxSpeedMps = 0f
        elevationGain = 0.0
        elevationRef = Double.NaN
        pointCount = 0
        telemetrySampleCount = 0
        telemetrySeq = 0
        telemetryElevationRef = Double.NaN
        PathBuffer.clear()

        _state.value = TrackingState(
            status = TrackingStatus.RUNNING,
            telemetryEnabled = TelemetrySettings.enabled
        )

        goForeground()
        updateWidgets()
        acquireWakeLock()
        startLocationUpdates()
        startTicker()

        arrivedSince = 0L
        resetAutoPauseState()
        loadAutoPlaces()

        scope.launch {
            dao.deleteEmptyUnfinished()
            trackId = dao.insertTrack(
                Track(
                    startTime = startedAt,
                    telemetryVersion = if (
                        TelemetrySettings.enabled && SensorTelemetryCollector.canCollect(this@TrackingService)
                    ) 1 else 0
                )
            )
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putLong(KEY_ACTIVE_TRACK, trackId).apply()
            _state.value = _state.value.copy(trackId = trackId)
            withContext(Dispatchers.Main) { startTelemetry() }
        }
    }

    private fun handlePause() {
        if (_state.value.status != TrackingStatus.RUNNING) return
        pausedAt = System.currentTimeMillis()
        lastLocation = null
        stopTelemetry()
        resetAutoPauseState()
        // A GPS szünet alatt is figyel, csak ritkábban: ebből látjuk, ha újra elindulunk.
        stopLocationUpdates()
        startLocationUpdates(PAUSED_GPS_INTERVAL_MS)
        _state.value = _state.value.copy(status = TrackingStatus.PAUSED, currentSpeedMps = 0f)
        updateNotification()
        updateWidgets()
        checkpoint()
    }

    private fun handleResume() {
        if (_state.value.status != TrackingStatus.PAUSED) return
        if (pausedAt > 0) pausedTotalMs += System.currentTimeMillis() - pausedAt
        pausedAt = 0L
        // Új szakasz, hogy a szünet két vége közé ne rajzoljunk egyenest.
        segment++
        lastRecordedAt = 0L
        resetAutoPauseState()
        _state.value = _state.value.copy(status = TrackingStatus.RUNNING)
        stopLocationUpdates()
        startLocationUpdates()
        startTelemetry()
        updateNotification()
        updateWidgets()
    }

    private fun handleStop() {
        if (!_state.value.isActive) {
            stopSelf()
            return
        }
        if (pausedAt > 0) {
            pausedTotalMs += System.currentTimeMillis() - pausedAt
            pausedAt = 0L
        }

        stopLocationUpdates()
        stopTelemetry()
        ticker?.cancel()
        releaseWakeLock()

        val finishedId = trackId
        val endTime = System.currentTimeMillis()
        val duration = elapsedMillis()
        val snapshot = TrackingState(
            status = TrackingStatus.IDLE,
            distanceMeters = distanceMeters,
            durationMillis = duration,
            movingMillis = movingMillis,
            maxSpeedMps = maxSpeedMps,
            elevationGainMeters = elevationGain,
            pointCount = pointCount,
            lastFinishedTrackId = finishedId
        )

        val id = trackId
        val dist = distanceMeters
        val moving = movingMillis
        val maxSpeed = maxSpeedMps
        val elev = elevationGain
        val points = pointCount
        val start = startedAt

        // Előbb a lezáró mentés fusson le, csak utána álljunk le – a stopSelf()
        // kiváltotta onDestroy különben elvágná a coroutine scope-ot.
        trackId = 0L
        _state.value = snapshot
        updateWidgets()

        scope.launch {
            if (id != 0L) {
                dao.updateSummary(
                    id = id,
                    endTime = endTime,
                    distanceMeters = dist,
                    durationMillis = duration,
                    movingMillis = moving,
                    maxSpeedMps = maxSpeed,
                    elevationGainMeters = elev,
                    pointCount = points,
                    telemetrySampleCount = dao.countTelemetry(id)
                )
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_ACTIVE_TRACK).apply()
            // A lezárt túra mehet fel egyben; a szerveren se maradjon "mérés fut" állapot.
            StatsWidgetRenderer.refresh(this@TrackingService)
            SyncManager.requestSync()
            SyncManager.pushIdle()
            withContext(Dispatchers.Main) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    /**
     * A folyamatot elölte a rendszer, de a szolgáltatást újraindította.
     * A checkpointolt összesítőből és a mentett pontokból folytatjuk a mérést.
     */
    private fun handleProcessRestart() {
        if (_state.value.isActive) return
        val savedId = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_ACTIVE_TRACK, 0L)
        if (savedId == 0L || !hasLocationPermission()) {
            stopSelf()
            return
        }

        _state.value = TrackingState(
            status = TrackingStatus.RUNNING,
            trackId = savedId,
            telemetryEnabled = TelemetrySettings.enabled
        )
        arrivedSince = 0L
        resetAutoPauseState()
        loadAutoPlaces()
        goForeground()
        updateWidgets()

        scope.launch {
            val track = dao.getTrack(savedId)
            if (track == null) {
                withContext(Dispatchers.Main) { handleStop() }
                return@launch
            }
            val saved = dao.getPoints(savedId)

            trackId = savedId
            startedAt = track.startTime
            distanceMeters = track.distanceMeters
            movingMillis = track.movingMillis
            maxSpeedMps = track.maxSpeedMps
            elevationGain = track.elevationGainMeters
            pointCount = saved.size
            telemetrySampleCount = dao.countTelemetry(savedId)
            telemetrySeq = telemetrySampleCount
            telemetryElevationRef = Double.NaN
            // A kiesett időt szünetnek könyveljük el, hogy ne ugorjon meg az időmérő.
            val lastPointTime = saved.lastOrNull()?.time ?: track.startTime
            pausedTotalMs = (System.currentTimeMillis() - lastPointTime).coerceAtLeast(0L)
            segment = (saved.lastOrNull()?.segment ?: 0) + 1
            lastLocation = null
            lastRecordedAt = 0L
            elevationRef = Double.NaN

            PathBuffer.clear()
            PathBuffer.addAll(saved.map { PathPoint(it.lat, it.lon, it.segment, it.speedMps, it.accuracy, it.time) })

            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    trackId = savedId,
                    distanceMeters = distanceMeters,
                    movingMillis = movingMillis,
                    maxSpeedMps = maxSpeedMps,
                    elevationGainMeters = elevationGain,
                    pointCount = pointCount
                )
                acquireWakeLock()
                startLocationUpdates()
                startTelemetry()
                startTicker()
            }
        }
    }

    // --- GPS -------------------------------------------------------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun startLocationUpdates(intervalMs: Long = GPS_INTERVAL_MS) {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                intervalMs,
                0f,
                this
            )
        } catch (e: SecurityException) {
            // Elvették a jogot menet közben – nincs mit tenni.
        } catch (e: IllegalArgumentException) {
            // Nincs GPS provider ezen az eszközön.
        }
    }

    private fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(this)
        } catch (e: SecurityException) {
            // ignoráljuk
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasAccuracy() && location.accuracy > MIN_ACCURACY_M) return
        // Szünetben nem rögzítünk semmit, csak azt figyeljük, elindultunk-e.
        if (_state.value.status == TrackingStatus.PAUSED) {
            checkAutoResume(location)
            return
        }
        if (_state.value.status != TrackingStatus.RUNNING) return

        val now = System.currentTimeMillis()
        val speed = if (location.hasSpeed()) location.speed else 0f
        telemetryCollector?.updateLocation(
            location.latitude,
            location.longitude,
            speed,
            if (location.hasBearing()) location.bearing else 0f,
            if (location.hasAltitude()) location.altitude else null
        )
        val previous = lastLocation

        var movedMeters = 0.0
        if (previous != null) {
            movedMeters = previous.distanceTo(location).toDouble()
            val dtMillis = (location.time - previous.time).coerceAtLeast(0L)

            // Zaj- és ugrásszűrés: állóban ne kúszzon a táv, és a kiugró fixeket dobjuk.
            val plausible = movedMeters in 2.0..MAX_JUMP_M
            val moving = speed >= MOVING_SPEED_MPS || movedMeters >= 5.0
            if (plausible && moving) {
                distanceMeters += movedMeters
                if (dtMillis in 1..30_000) movingMillis += dtMillis
            }
        }

        if (speed > maxSpeedMps) maxSpeedMps = speed

        if (location.hasAltitude() && telemetryCollector?.providesBarometricAltitude != true) {
            val alt = location.altitude
            if (elevationRef.isNaN()) {
                elevationRef = alt
            } else if (alt - elevationRef >= ELEVATION_THRESHOLD_M) {
                elevationGain += alt - elevationRef
                elevationRef = alt
            } else if (alt < elevationRef) {
                elevationRef = alt
            }
        }

        val shouldRecord = previous == null ||
            movedMeters >= MIN_RECORD_DISTANCE_M ||
            (now - lastRecordedAt) >= MAX_RECORD_INTERVAL_MS

        if (shouldRecord) {
            lastRecordedAt = now
            pointCount++
            PathBuffer.add(PathPoint(location.latitude, location.longitude, segment, speed, location.accuracy, now))

            val point = TrackPoint(
                trackId = trackId,
                lat = location.latitude,
                lon = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else 0.0,
                speedMps = speed,
                accuracy = if (location.hasAccuracy()) location.accuracy else 0f,
                bearing = if (location.hasBearing()) location.bearing else 0f,
                time = if (location.time > 0) location.time else now,
                segment = segment
            )
            val id = trackId
            if (id != 0L) {
                scope.launch { dao.insertPoint(point) }
            }
        }

        lastLocation = location

        _state.value = _state.value.copy(
            distanceMeters = distanceMeters,
            durationMillis = elapsedMillis(),
            movingMillis = movingMillis,
            currentSpeedMps = speed,
            maxSpeedMps = maxSpeedMps,
            altitudeMeters = if (location.hasAltitude()) location.altitude else 0.0,
            elevationGainMeters = elevationGain,
            bearingDegrees = if (location.hasBearing()) location.bearing else 0f,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 0f,
            lat = location.latitude,
            lon = location.longitude,
            pointCount = pointCount,
            lastFixTime = now,
            hasFix = true
        )
    }

    // A LocationListener régi metódusai: API 30 alatt nincs default implementációjuk,
    // ezért mindet felül kell írni, különben AbstractMethodError.
    @Deprecated("Deprecated in API 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) = Unit

    // --- nagyfrekvenciás szenzortelemetria ------------------------------------

    private fun startTelemetry() {
        if (!TelemetrySettings.enabled || telemetryCollector != null || trackId == 0L) return
        if (!SensorTelemetryCollector.canCollect(this)) return
        telemetryCollector = SensorTelemetryCollector(
            this,
            onEventClip = { clip ->
                val id = trackId
                if (id != 0L) scope.launch { saveEventClip(id, clip) }
            }
        ) { aggregate ->
            val id = trackId
            if (id == 0L) return@SensorTelemetryCollector
            scope.launch {
                aggregate.fusedAltitudeMeters?.let { altitude ->
                    if (telemetryElevationRef.isNaN()) {
                        telemetryElevationRef = altitude
                    } else if (altitude - telemetryElevationRef >= BARO_ELEVATION_THRESHOLD_M) {
                        elevationGain += altitude - telemetryElevationRef
                        telemetryElevationRef = altitude
                    } else if (altitude < telemetryElevationRef) {
                        telemetryElevationRef = altitude
                    }
                }
                val seq = telemetrySeq++
                dao.insertTelemetry(
                    TelemetrySample(
                        trackId = id,
                        seq = seq,
                        time = aggregate.time,
                        lat = aggregate.lat,
                        lon = aggregate.lon,
                        speedMps = aggregate.speedMps,
                        bearingDegrees = aggregate.bearingDegrees,
                        pressureHpa = aggregate.pressureHpa,
                        fusedAltitudeMeters = aggregate.fusedAltitudeMeters,
                        forwardMeanMps2 = aggregate.forwardMeanMps2,
                        forwardMinMps2 = aggregate.forwardMinMps2,
                        forwardMaxMps2 = aggregate.forwardMaxMps2,
                        lateralMeanMps2 = aggregate.lateralMeanMps2,
                        lateralRmsMps2 = aggregate.lateralRmsMps2,
                        lateralPeakMps2 = aggregate.lateralPeakMps2,
                        verticalRmsMps2 = aggregate.verticalRmsMps2,
                        verticalPeakMps2 = aggregate.verticalPeakMps2,
                        yawPeakRadS = aggregate.yawPeakRadS,
                        rollPeakRadS = aggregate.rollPeakRadS,
                        leanDegrees = aggregate.leanDegrees,
                        mountQuality = aggregate.mountQuality,
                        sampleCount = aggregate.sampleCount,
                        flags = aggregate.flags
                    )
                )
                telemetrySampleCount = telemetrySeq
                _state.value = _state.value.copy(telemetryQuality = aggregate.mountQuality)
            }
        }.also { it.start() }
        scope.launch { dao.markTelemetryEnabled(trackId) }
    }

    /** 10 mp előzmény + legfeljebb 20 mp utózmény, tömörített bináris klip. */
    private fun saveEventClip(id: Long, clip: SensorEventClip) {
        val directory = File(filesDir, "telemetry_clips").apply { mkdirs() }
        val file = File(directory, "track-${id}-${clip.triggerTime}.bin.gz")
        DataOutputStream(GZIPOutputStream(file.outputStream())).use { output ->
            output.writeInt(1) // formátumverzió
            output.writeLong(clip.triggerTime)
            output.writeInt(clip.frames.size)
            clip.frames.forEach { frame ->
                output.writeLong(frame.timeNanos)
                output.writeFloat(frame.x)
                output.writeFloat(frame.y)
                output.writeFloat(frame.z)
            }
        }
    }

    private fun stopTelemetry() {
        telemetryCollector?.stop()
        telemetryCollector = null
    }

    // --- időzítő és mentés -----------------------------------------------------

    private fun elapsedMillis(): Long {
        if (startedAt == 0L) return 0L
        val pausedNow = if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0L
        return (System.currentTimeMillis() - startedAt - pausedTotalMs - pausedNow).coerceAtLeast(0L)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var sinceCheckpoint = 0L
            var sinceWidget = 0L
            var sinceLive = 0L
            var sinceSync = 0L
            while (true) {
                delay(1000L)
                var current = _state.value
                if (!current.isActive) continue

                // A Beállítások kapcsolója aktív túra alatt is legfeljebb egy
                // másodpercen belül életbe lép.
                val telemetryEnabled = TelemetrySettings.enabled
                if (current.status == TrackingStatus.RUNNING) {
                    if (telemetryEnabled && telemetryCollector == null) startTelemetry()
                    if (!telemetryEnabled && telemetryCollector != null) stopTelemetry()
                }
                if (current.telemetryEnabled != telemetryEnabled) {
                    current = current.copy(
                        telemetryEnabled = telemetryEnabled,
                        telemetryQuality = if (telemetryEnabled) current.telemetryQuality else 0f
                    )
                    _state.value = current
                }

                val staleFix = current.hasFix &&
                    System.currentTimeMillis() - current.lastFixTime > 5000L

                _state.value = current.copy(
                    durationMillis = elapsedMillis(),
                    currentSpeedMps = if (staleFix) 0f else current.currentSpeedMps
                )
                updateNotification()

                // A widget ritkábban frissül, mint az értesítés: a RemoteViews
                // átküldése a launcher folyamatába drágább művelet.
                sinceWidget += 1000L
                if (sinceWidget >= WIDGET_REFRESH_MS) {
                    sinceWidget = 0L
                    updateWidgets()
                }

                // Megérkeztünk valamelyik helyre és meg is álltunk? Akkor zárjuk a túrát.
                checkAutoStop(current)

                // Ha nem hely körén belül, csak sokáig állunk, elég szüneteltetni.
                checkAutoPause(current)

                sinceCheckpoint += 1000L
                if (sinceCheckpoint >= CHECKPOINT_MS) {
                    sinceCheckpoint = 0L
                    checkpoint()
                    renewWakeLock()
                }

                // Élő adat a szervernek: a webes felület ebből rajzol menet közben.
                sinceLive += 1000L
                if (sinceLive >= LIVE_PUSH_MS) {
                    sinceLive = 0L
                    val position = lastLocation
                    SyncManager.pushLive(_state.value, position?.latitude, position?.longitude)
                }

                // A nyomvonal feltöltése. Ha nincs net, ez csendben elmarad, és
                // a következő sikeres kapcsolatnál pótlódik – nem vész el semmi.
                sinceSync += 1000L
                if (sinceSync >= SYNC_INTERVAL_MS) {
                    sinceSync = 0L
                    checkpoint()
                    SyncManager.requestSync()
                }
            }
        }
    }

    /** Az automatikus lezáráshoz kellő helyek – a mérés alatt nem változnak. */
    private fun loadAutoPlaces() {
        scope.launch {
            autoPlaces = if (AutoSettings.autoEnabled) {
                runCatching { AppDatabase.get(this@TrackingService).placeDao().getActive() }
                    .getOrDefault(emptyList())
            } else {
                emptyList()
            }
        }
    }

    /**
     * Automatikus lezárás: ha bármelyik megadott hely körén belül vagyunk és
     * másfél percig állunk, a túra véget ért. A puszta belépés nem elég – át is
     * haladhatunk a körön.
     */
    private fun checkAutoStop(current: TrackingState) {
        if (autoPlaces.isEmpty() || current.status != TrackingStatus.RUNNING) {
            arrivedSince = 0L
            return
        }
        if (!current.hasFix) return

        val inside = autoPlaces.any { it.contains(current.lat, current.lon) }
        if (!inside || current.currentSpeedMps >= ARRIVED_SPEED_MPS) {
            arrivedSince = 0L
            return
        }

        val now = System.currentTimeMillis()
        if (arrivedSince == 0L) {
            arrivedSince = now
        } else if (now - arrivedSince >= ARRIVAL_DWELL_MS) {
            arrivedSince = 0L
            // Intenten keresztül állítjuk le magunkat, hogy a lezárás a szokásos
            // úton (fő szálon) fusson le, ne a ticker közepén.
            stop(this)
        }
    }

    /** Álló- és szünethorgony nullázása – minden állapotváltásnál tiszta lappal indulunk. */
    private fun resetAutoPauseState() {
        stillAnchor = null
        stillSince = 0L
        pauseAnchor = null
    }

    /**
     * Automatikus szünet: ha öt percig egy helyben állunk, magától szünetel a
     * mérés. Nem elég a nulla sebesség (a GPS álló helyzetben is "sodródik"):
     * a megállás pontjától mért 25 méteres körön belül kell maradni. Ha nincs
     * fix (garázs, alagút), az is állásnak számít.
     */
    private fun checkAutoPause(current: TrackingState) {
        if (current.status != TrackingStatus.RUNNING) {
            stillAnchor = null
            stillSince = 0L
            return
        }

        val now = System.currentTimeMillis()
        val here = lastLocation
        val anchor = stillAnchor
        val moved = current.currentSpeedMps >= MOVING_SPEED_MPS ||
            (here != null && anchor != null && anchor.distanceTo(here) > STILL_RADIUS_M)

        if (stillSince == 0L || moved) {
            stillSince = now
            stillAnchor = here
            return
        }

        if (now - stillSince >= AUTO_PAUSE_STILL_MS) {
            stillSince = 0L
            stillAnchor = null
            // A checkAutoStop mintájára intenten keresztül, hogy a szüneteltetés
            // a fő szálon fusson le, ne a ticker közepén.
            pause(this)
        }
    }

    /**
     * Automatikus folytatás: szünet közben a megállás helyétől légvonalban mért
     * 100 méter után újraindul a mérés. A légvonalbeli távolság a GPS sodródására
     * nem ugrik be, csak a tényleges elindulásra.
     */
    private fun checkAutoResume(location: Location) {
        val anchor = pauseAnchor
        if (anchor == null) {
            pauseAnchor = location
            return
        }
        if (anchor.distanceTo(location) >= AUTO_RESUME_DISTANCE_M) {
            pauseAnchor = null
            resume(this)
        }
    }

    /** Az összesítő kiírása a DB-be, hogy váratlan leállásnál se vesszen el. */
    private fun checkpoint() {
        val id = trackId
        if (id == 0L) return
        val duration = elapsedMillis()
        val dist = distanceMeters
        val moving = movingMillis
        val maxSpeed = maxSpeedMps
        val elev = elevationGain
        val points = pointCount
        scope.launch {
            dao.updateSummary(
                id = id,
                endTime = null,
                distanceMeters = dist,
                durationMillis = duration,
                movingMillis = moving,
                maxSpeedMps = maxSpeed,
                elevationGainMeters = elev,
                pointCount = points,
                telemetrySampleCount = telemetrySampleCount
            )
        }
    }

    // --- értesítés -------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val current = _state.value
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = getString(
            R.string.notif_content,
            Fmt.distance(current.distanceMeters),
            Fmt.duration(current.durationMillis),
            Fmt.speedKmh(current.currentSpeedMps)
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_route)
            .setContentTitle(
                getString(
                    if (current.status == TrackingStatus.PAUSED) R.string.notif_paused
                    else R.string.notif_recording
                )
            )
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (current.status == TrackingStatus.RUNNING) {
            builder.addAction(
                R.drawable.ic_pause,
                getString(R.string.action_pause),
                servicePendingIntent(ACTION_PAUSE, 1)
            )
        } else {
            builder.addAction(
                R.drawable.ic_play,
                getString(R.string.action_resume),
                servicePendingIntent(ACTION_RESUME, 2)
            )
        }
        builder.addAction(
            R.drawable.ic_stop,
            getString(R.string.action_stop),
            servicePendingIntent(ACTION_STOP, 3)
        )

        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, TrackingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    /** A kezdőképernyős widgetek átrajzolása az aktuális állapottal. */
    private fun updateWidgets() {
        WidgetRenderer.updateAll(this)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // --- wakelock --------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MotorTelemetria::tracking"
        ).apply {
            // Időkorláttal, hogy egy beragadt szolgáltatás ne szívja le az akkut.
            // A ticker minden körben megújítja, amíg tényleg fut a mérés.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    /** A ticker hívja: amíg megy a mérés, tologatjuk a wakelock lejáratát. */
    private fun renewWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) lock.release()
        lock.acquire(WAKELOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopTelemetry()
        ticker?.cancel()
        releaseWakeLock()
        // Ha kilövik alólunk a szolgáltatást, legalább az utolsó összesítő menjen ki.
        if (trackId != 0L) {
            runBlocking {
                dao.updateSummary(
                    id = trackId,
                    endTime = null,
                    distanceMeters = distanceMeters,
                    durationMillis = elapsedMillis(),
                    movingMillis = movingMillis,
                    maxSpeedMps = maxSpeedMps,
                    elevationGainMeters = elevationGain,
                    pointCount = pointCount,
                    telemetrySampleCount = telemetrySampleCount
                )
            }
        }
        scope.cancel()
        dbExecutor.shutdown()
        super.onDestroy()
    }
}
