package hu.motor.telemetria.net

import android.content.Context
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.RoadPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Egy réteg állapota: hány elem van benne és mikor frissült utoljára. */
data class LayerState(
    val key: String,
    val count: Int,
    val updatedAt: Long,
    val status: String,
    val error: String?
) {
    val refreshing: Boolean get() = status == "running"
}

/** A szervertől kapott kísérő adatok (rétegállapotok, Waze-keret). */
data class RoadDataMeta(
    val layers: List<LayerState>,
    val wazeConfigured: Boolean,
    val wazeCalls: Int,
    val wazeLimit: Int,
    val wazeRemaining: Int
) {
    fun layer(key: String): LayerState? = layers.firstOrNull { it.key == key }

    val anyRefreshing: Boolean get() = layers.any { it.refreshing }
}

/**
 * Útmenti adatok (mérők, lámpák, rendőrök, balesetek) a szerverről.
 *
 * A szerver mindent a háttérben frissít, ezért a lekérés azonnali; az app csak
 * eltárolja az eredményt, hogy net nélkül is legyen mit rajzolni.
 */
object RoadDataRepository {

    private const val PREFS = "road_data_prefs"
    private const val KEY_META = "meta_json"

    /** A legutóbb ismert rétegállapotok – net nélkül is ezt mutatjuk. */
    fun cachedMeta(context: Context): RoadDataMeta? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_META, null) ?: return null
        return runCatching { parseMeta(JSONObject(json)) }.getOrNull()
    }

    suspend fun cachedPoints(context: Context): List<RoadPoint> = withContext(Dispatchers.IO) {
        AppDatabase.get(context).roadPointDao().getAll()
    }

    /** Letölti a tárolt állapotot a szerverről (külső API-t nem hív). */
    suspend fun load(context: Context): Result<RoadDataMeta> = withContext(Dispatchers.IO) {
        runCatching { handle(context, ApiClient.getJson("/api/roadpoints")) }
    }

    /**
     * Frissítést kér. A szerver a rétegeket háttérben tölti újra, ezért ez
     * azonnal visszatér – utána a [load] hívásokkal látszik a haladás.
     */
    suspend fun refresh(context: Context, freeOnly: Boolean): Result<RoadDataMeta> =
        withContext(Dispatchers.IO) {
            runCatching {
                val path = "/api/roadpoints/refresh" + if (freeOnly) "?free=1" else ""
                handle(context, ApiClient.postJson(path, JSONObject()))
            }
        }

    private suspend fun handle(context: Context, response: JSONObject): RoadDataMeta {
        val points = response.optJSONArray("points")
        if (points != null) {
            val parsed = (0 until points.length()).mapNotNull { index ->
                points.optJSONObject(index)?.let { parsePoint(it) }
            }
            val dao = AppDatabase.get(context).roadPointDao()
            dao.clear()
            dao.insertAll(parsed)
        }

        val meta = parseMeta(response)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_META, metaJson(response).toString())
            .apply()
        return meta
    }

    /** Csak a metát tesszük el, a pontok az adatbázisban vannak. */
    private fun metaJson(response: JSONObject) = JSONObject().apply {
        put("layers", response.optJSONObject("layers") ?: JSONObject())
        put("waze", response.optJSONObject("waze") ?: JSONObject())
    }

    private fun parsePoint(json: JSONObject) = RoadPoint(
        id = json.optLong("id"),
        source = json.optString("source"),
        kind = json.optString("kind"),
        lat = json.optDouble("lat"),
        lon = json.optDouble("lon"),
        road = json.stringOrNull("road"),
        description = json.stringOrNull("description"),
        speedLimit = if (json.isNull("speedLimit")) null else json.optInt("speedLimit"),
        reportedAt = if (json.isNull("reportedAt")) null else json.optLong("reportedAt"),
        expiresAt = if (json.isNull("expiresAt")) null else json.optLong("expiresAt")
    )

    private fun parseMeta(json: JSONObject): RoadDataMeta {
        val layers = json.optJSONObject("layers") ?: JSONObject()
        val waze = json.optJSONObject("waze") ?: JSONObject()

        val states = layers.keys().asSequence().map { key ->
            val layer = layers.optJSONObject(key) ?: JSONObject()
            LayerState(
                key = key,
                count = layer.optInt("count"),
                updatedAt = layer.optLong("updatedAt"),
                status = layer.stringOrNull("status") ?: "idle",
                error = layer.stringOrNull("error")
            )
        }.toList()

        return RoadDataMeta(
            layers = states,
            wazeConfigured = waze.optBoolean("configured"),
            wazeCalls = waze.optInt("calls"),
            wazeLimit = waze.optInt("limit"),
            wazeRemaining = waze.optInt("remaining")
        )
    }

    class NotAvailable(message: String) : IOException(message)
}
