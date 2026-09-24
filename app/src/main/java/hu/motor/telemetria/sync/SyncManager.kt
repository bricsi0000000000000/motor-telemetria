package hu.motor.telemetria.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.data.TrackPoint
import hu.motor.telemetria.net.ApiClient
import hu.motor.telemetria.net.ServerSettings
import hu.motor.telemetria.service.TrackingState
import hu.motor.telemetria.service.TrackingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A szinkron pillanatnyi állapota a statisztika fül számára. */
data class SyncStatus(
    val syncing: Boolean = false,
    /** Hány túra vár feltöltésre (a most futó is ide számít). */
    val pendingTracks: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null
)

/**
 * A telefon és a szerver közti feltöltés.
 *
 * Az elv: nincs külön üzenetsor, maga az adatbázis az "outbox". Minden túrán
 * ott van, hogy változott-e ([Track.dirty]) és hány pontját vette át a szerver
 * ([Track.syncedPoints]) – ebből mindig kiszámolható, mi hiányzik. Így egy
 * elveszett válasz vagy egy hálózat nélküli hét sem okoz adatvesztést: a
 * következő sikeres kapcsolatnál minden felmegy, duplikátum nélkül.
 */
object SyncManager {

    private const val TAG = "SyncManager"

    /** Egy kérésben ennyi túra összesítője mehet. */
    private const val TRACKS_PER_REQUEST = 5

    /** Túránként ennyi pont megy egy körben – így egy hosszú túra sem eszi meg a memóriát. */
    private const val POINTS_PER_TRACK = 1500

    /** Ennyi kör után abbahagyjuk; a maradék a következő szinkronra marad. */
    private const val MAX_ROUNDS = 20

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val dao by lazy { AppDatabase.get(appContext).trackDao() }

    fun init(context: Context) {
        appContext = context.applicationContext
        registerNetworkCallback()
        SyncJobService.schedule(appContext)
        requestSync()
    }

    /** Nem blokkol: a hívó (UI, szolgáltatás) csak jelez, hogy volna mit feltölteni. */
    fun requestSync() {
        if (!::appContext.isInitialized) return
        scope.launch { syncNow() }
    }

    /**
     * Egy teljes szinkronkör. Akkor tér vissza igazzal, ha nem maradt feltöltendő.
     * Hálózati hiba esetén csendben hamissal tér vissza – a hívó ilyenkor
     * később újrapróbálja (hálózat visszatérése, JobScheduler, app indítás).
     */
    suspend fun syncNow(): Boolean = mutex.withLock {
        if (!::appContext.isInitialized) return false
        if (!hasNetwork()) {
            refreshPending(lastError = null)
            return false
        }

        _status.value = _status.value.copy(syncing = true)
        try {
            var rounds = 0
            while (rounds++ < MAX_ROUNDS) {
                val deletes = dao.getPendingDeletes()
                val pending = dao.getPendingTracks().take(TRACKS_PER_REQUEST)
                if (pending.isEmpty() && deletes.isEmpty()) break

                val moreToCome = uploadBatch(pending, deletes)
                if (!moreToCome) break
            }

            ServerSettings.lastSyncAt = System.currentTimeMillis()
            refreshPending(lastError = null)
            return _status.value.pendingTracks == 0
        } catch (e: IOException) {
            // Nincs net, alszik a szerver, rossz a cím – mindegyik ugyanaz: később újra.
            Log.i(TAG, "Szinkron elhalasztva: ${e.message}")
            refreshPending(lastError = e.message ?: "Nem érhető el a szerver")
            return false
        } catch (e: Exception) {
            Log.w(TAG, "Váratlan szinkronhiba", e)
            refreshPending(lastError = e.message ?: "Ismeretlen hiba")
            return false
        } finally {
            _status.value = _status.value.copy(syncing = false)
        }
    }

    /** @return igaz, ha maradt még feltöltendő adat (érdemes új kört futtatni). */
    private suspend fun uploadBatch(tracks: List<Track>, deletes: List<Long>): Boolean {
        val payload = JSONObject()
        payload.put("deviceUid", ServerSettings.deviceUid)

        if (deletes.isNotEmpty()) {
            payload.put("deletedClientIds", JSONArray().apply { deletes.forEach { put(it) } })
        }

        var sentPoints = 0
        val trackArray = JSONArray()
        for (track in tracks) {
            val points = dao.getPointsFrom(track.id, track.syncedPoints, POINTS_PER_TRACK)
            sentPoints += points.size
            trackArray.put(trackJson(track, points))
        }
        payload.put("tracks", trackArray)

        val response = ApiClient.postJson("/api/sync", payload)
        val results = response.optJSONArray("results") ?: JSONArray()

        for (i in 0 until results.length()) {
            val result = results.getJSONObject(i)
            val clientId = result.optLong("clientId")
            val track = tracks.firstOrNull { it.id == clientId } ?: continue
            dao.markUploaded(
                id = track.id,
                remoteId = result.optLong("serverId").takeIf { it > 0 },
                syncedPoints = result.optInt("storedPoints", track.syncedPoints)
            )
            dao.clearDirtyIfUnchanged(
                id = track.id,
                distanceMeters = track.distanceMeters,
                durationMillis = track.durationMillis,
                endTime = track.endTime
            )
        }

        if (deletes.isNotEmpty()) dao.clearPendingDeletes(deletes)

        // Ha a pontok elfogytak a keretből, biztosan van még mit küldeni.
        return sentPoints >= POINTS_PER_TRACK || tracks.size >= TRACKS_PER_REQUEST
    }

    private fun trackJson(track: Track, points: List<TrackPoint>): JSONObject {
        val json = JSONObject()
        json.put("clientId", track.id)
        json.put("startTime", track.startTime)
        json.put("endTime", track.endTime ?: JSONObject.NULL)
        json.put("distanceMeters", track.distanceMeters)
        json.put("durationMillis", track.durationMillis)
        json.put("movingMillis", track.movingMillis)
        json.put("maxSpeedMps", track.maxSpeedMps)
        json.put("elevationGainMeters", track.elevationGainMeters)
        json.put("pointCount", track.pointCount)

        val array = JSONArray()
        points.forEachIndexed { index, point ->
            val item = JSONObject()
            // A sorszám a túrán belüli pozíció: ebből lesz a szerveren a duplikátumszűrés.
            item.put("seq", track.syncedPoints + index)
            item.put("lat", point.lat)
            item.put("lon", point.lon)
            item.put("altitude", point.altitude)
            item.put("speedMps", point.speedMps)
            item.put("accuracy", point.accuracy)
            item.put("bearing", point.bearing)
            item.put("time", point.time)
            item.put("segment", point.segment)
            array.put(item)
        }
        json.put("points", array)
        return json
    }

    /**
     * Élő állapot mérés közben. Ezt nem tesszük el offline: egy percnél régebbi
     * "élő" adat úgysem ér semmit, a nyomvonal pedig a rendes szinkronnal felmegy.
     */
    fun pushLive(state: TrackingState, lat: Double? = null, lon: Double? = null) {
        if (!::appContext.isInitialized) return
        if (!hasNetwork()) return
        val payload = JSONObject().apply {
            put("deviceUid", ServerSettings.deviceUid)
            put("status", state.status.name)
            put("trackClientId", state.trackId)
            put("at", System.currentTimeMillis())
            // A helyzet külön mezőben, hogy a webes térkép ki tudja rajzolni.
            if (lat != null && lon != null) {
                put("lat", lat)
                put("lon", lon)
            }
            put("distanceMeters", state.distanceMeters)
            put("durationMillis", state.durationMillis)
            put("movingMillis", state.movingMillis)
            put("speedMps", state.currentSpeedMps)
            put("avgSpeedMps", state.avgSpeedMps)
            put("maxSpeedMps", state.maxSpeedMps)
            put("altitudeMeters", state.altitudeMeters)
            put("elevationGainMeters", state.elevationGainMeters)
            put("bearingDegrees", state.bearingDegrees)
            put("accuracyMeters", state.accuracyMeters)
            put("pointCount", state.pointCount)
            put("hasFix", state.hasFix)
        }
        scope.launch {
            runCatching { ApiClient.postJson("/api/live", payload) }
                .onFailure { Log.d(TAG, "Élő állapot nem ment ki: ${it.message}") }
        }
    }

    /** A mérés végén jelezzük, hogy a szerveren se maradjon "fut még" állapot. */
    fun pushIdle() {
        pushLive(TrackingState(status = TrackingStatus.IDLE))
    }

    /** A statisztika fül kéri, amikor előtérbe kerül. */
    fun refreshPending() {
        scope.launch { refreshPending(lastError = _status.value.lastError) }
    }

    private suspend fun refreshPending(lastError: String?) {
        val pending = runCatching { dao.countPendingTracks() }.getOrDefault(0)
        _status.value = _status.value.copy(
            pendingTracks = pending,
            lastSyncAt = ServerSettings.lastSyncAt,
            lastError = lastError
        )
    }

    /** Kapcsolat teszteléséhez a beállításoknál. */
    suspend fun ping(): Result<String> = runCatching {
        val response = ApiClient.getJson("/api/health")
        if (response.optBoolean("ok")) "Szerver elérhető" else "Váratlan válasz"
    }

    fun hasNetwork(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        // A VALIDATED-et szándékosan nem nézzük: Tailscale (VPN) alatt gyakran hiányzik,
        // pedig a saját szerver ilyenkor is tökéletesen elérhető.
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Amint van megint hálózat, azonnal megpróbáljuk kiüríteni a sort. */
    private fun registerNetworkCallback() {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    requestSync()
                }
            })
        }
    }
}
