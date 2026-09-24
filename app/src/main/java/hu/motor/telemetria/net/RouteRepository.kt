package hu.motor.telemetria.net

import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.SavedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Útvonaltervezés a szerveren keresztül.
 *
 * Minden hívás blokkol, ezért IO szálon fut. A tervezés helyi Valhallával
 * fél másodperc körül van, de a kanyargós stílus több jelöltet is végigszámol,
 * ezért bőven mérjük az időkorlátot.
 */
object RouteRepository {

    /** Kanyargós stílusnál 5-6 útvonaljelölt is elemzésre kerül. */
    private const val PLAN_TIMEOUT_MS = 90_000

    /** Ennyi automatikusan eltett tervet őrzünk meg. */
    const val AUTO_KEEP = 15

    suspend fun plan(
        waypoints: List<Waypoint>,
        style: String,
        avoidTolls: Boolean = true,
        avoidUnpaved: Boolean = false,
        roundTrip: Boolean = false,
        arriveSameSide: Boolean = true
    ): RoutePlan = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("waypoints", JSONArray().apply { waypoints.forEach { put(it.toJson()) } })
            put("style", style)
            put("options", JSONObject().apply {
                put("avoidTolls", avoidTolls)
                put("avoidUnpaved", avoidUnpaved)
                put("roundTrip", roundTrip)
                put("arriveSameSide", arriveSameSide)
                put("useHistory", true)
            })
        }
        RoutePlan.parse(ApiClient.postJson("/api/route/plan", body, PLAN_TIMEOUT_MS))
    }

    suspend fun familiar(waypoints: List<Waypoint>): List<JSONObject> = withContext(Dispatchers.IO) {
        val response = ApiClient.postJson("/api/route/familiar", JSONObject().put(
            "waypoints", JSONArray().apply { waypoints.forEach { put(it.toJson()) } }
        ))
        val routes = response.optJSONArray("routes") ?: JSONArray()
        (0 until routes.length()).map { routes.getJSONObject(it) }
    }

    /** A modell állapota: mennyit tanult már a túráidból. */
    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        ApiClient.getJson("/api/route/status")
    }

    /**
     * Minden sikeres terv eltevése, hogy később - akár net nélkül - újra
     * elővehető legyen.
     *
     * Ez nem a kézi mentés helyett van: nevet nem kér, a szerverre sem tolja
     * fel, és csak a legutóbbi néhány marad meg. Ugyanarra a pontsorra a
     * meglévő tételt frissítjük, különben egy délután tervezgetés tele szórná
     * a listát ugyanazzal az úttal.
     */
    suspend fun autoSave(
        database: AppDatabase,
        name: String,
        waypoints: List<Waypoint>,
        plan: RoutePlan
    ): Long = withContext(Dispatchers.IO) {
        val dao = database.routeDao()
        val waypointsJson = JSONArray().apply { waypoints.forEach { put(it.toJson()) } }.toString()
        val existing = dao.autoByWaypoints(waypointsJson)
        val now = System.currentTimeMillis()
        val id = dao.upsert(
            SavedRoute(
                id = existing?.id ?: 0,
                name = name,
                waypointsJson = waypointsJson,
                planJson = plan.raw,
                style = plan.style,
                distanceMeters = plan.distanceMeters,
                averageMillis = plan.times.totalAverage,
                autoSaved = true,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
        dao.trimAuto(AUTO_KEEP)
        id
    }

    /** Mentés: a telefonra mindenképp, a szerverre ha megy a hálózat. */
    suspend fun save(
        database: AppDatabase,
        name: String,
        waypoints: List<Waypoint>,
        plan: RoutePlan?
    ): Long = withContext(Dispatchers.IO) {
        val waypointsJson = JSONArray().apply { waypoints.forEach { put(it.toJson()) } }.toString()
        // A névvel mentett út kiváltja az ugyanarra a pontsorra magától eltett
        // tételt, hogy ne szerepeljen kétszer a listában.
        database.routeDao().autoByWaypoints(waypointsJson)?.let { database.routeDao().delete(it.id) }
        val localId = database.routeDao().upsert(
            SavedRoute(
                name = name,
                waypointsJson = waypointsJson,
                planJson = plan?.raw,
                style = plan?.style ?: "FAST",
                distanceMeters = plan?.distanceMeters ?: 0.0,
                averageMillis = plan?.times?.totalAverage ?: 0
            )
        )

        // A szerveroldali mentés csak kényelmi másolat: ha nincs hálózat, a
        // terv a telefonon akkor is megmarad.
        runCatching {
            val body = JSONObject().apply {
                put("name", name)
                put("waypoints", JSONArray(waypointsJson))
                put("style", plan?.style ?: "FAST")
                if (plan != null) put("plan", JSONObject(plan.raw))
            }
            val response = ApiClient.postJson("/api/routes", body)
            val remoteId = response.optLong("id").takeIf { it > 0 }
            if (remoteId != null) {
                database.routeDao().byId(localId)?.let {
                    database.routeDao().upsert(it.copy(remoteId = remoteId))
                }
            }
        }
        localId
    }
}
