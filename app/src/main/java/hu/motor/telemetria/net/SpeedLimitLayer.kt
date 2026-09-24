package hu.motor.telemetria.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Egy útszakasz a megengedett sebességgel. */
data class SpeedSegment(
    val id: Long,
    val limitKmh: Int,
    /** Igaz, ha nem kiírt érték, hanem az úttípus szerinti alapérték. */
    val guessed: Boolean,
    val road: String?,
    val points: List<DoubleArray>
) {
    val minLat = points.minOf { it[0] }
    val maxLat = points.maxOf { it[0] }
    val minLon = points.minOf { it[1] }
    val maxLon = points.maxOf { it[1] }

    fun intersects(south: Double, west: Double, north: Double, east: Double): Boolean =
        maxLat >= south && minLat <= north && maxLon >= west && minLon <= east
}

/**
 * A sebességhatár-réteg: sok ezer útszakasz, ezért nem az adatbázisban, hanem
 * egyetlen fájlban tároljuk, és memóriában tartjuk, amíg kell.
 */
/**
 * A sebességhatár-réteg: megyényi területen sok tízezer útszakasz, ezért mindig
 * csak a **látható területet** kérjük le, és amit egyszer letöltöttünk, azt
 * megtartjuk (memóriában és egy fájlban, hogy net nélkül is meglegyen).
 */
object SpeedLimitLayer {

    private const val FILE_NAME = "speedlimits.json"

    /** Ennél több szakaszt nem tartunk memóriában (a legrégebbi esik ki). */
    private const val MAX_SEGMENTS = 25_000

    /** A lekért területet ennyivel tágítjuk, hogy a kis mozgás ne kérjen újat. */
    private const val MARGIN = 0.02

    private val segments = LinkedHashMap<Long, SpeedSegment>()

    /** Amit már letöltöttünk – ezeken belül nem kérünk újra. */
    private val loadedAreas = mutableListOf<DoubleArray>()

    private var updatedAt: Long = 0L

    fun loadedSegments(): List<SpeedSegment> = synchronized(segments) { segments.values.toList() }

    fun lastUpdatedAt(): Long = updatedAt

    /** A korábban letöltött réteg a fájlból (net nélkül is működik). */
    suspend fun loadCached(context: Context): List<SpeedSegment> = withContext(Dispatchers.IO) {
        if (segments.isNotEmpty()) return@withContext loadedSegments()
        val file = File(context.cacheDir, FILE_NAME)
        if (!file.exists()) return@withContext emptyList()

        val parsed = runCatching { parse(JSONObject(file.readText())) }.getOrNull().orEmpty()
        merge(parsed)
        updatedAt = file.lastModified()
        loadedSegments()
    }

    /**
     * Letölti a látható területet, ha még nem ismerjük. Visszaadja, hogy
     * érkezett-e új adat (ilyenkor újra kell rajzolni a térképet).
     */
    suspend fun ensureArea(
        context: Context,
        south: Double,
        west: Double,
        north: Double,
        east: Double
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        if (covered(south, west, north, east)) return@withContext Result.success(false)

        runCatching {
            val bbox = "${south - MARGIN},${west - MARGIN},${north + MARGIN},${east + MARGIN}"
            val response = ApiClient.getJson("/api/speedlimits?bbox=$bbox", readTimeoutMs = 60_000)
            val parsed = parse(response)
            merge(parsed)
            loadedAreas += doubleArrayOf(south - MARGIN, west - MARGIN, north + MARGIN, east + MARGIN)
            updatedAt = System.currentTimeMillis()
            save(context)
            parsed.isNotEmpty()
        }
    }

    private fun covered(south: Double, west: Double, north: Double, east: Double): Boolean =
        loadedAreas.any { area ->
            south >= area[0] && west >= area[1] && north <= area[2] && east <= area[3]
        }

    private fun merge(newSegments: List<SpeedSegment>) {
        synchronized(segments) {
            for (segment in newSegments) segments[segment.id] = segment
            // A legrégebben látott szakaszok esnek ki, ha túl sok gyűlt össze.
            while (segments.size > MAX_SEGMENTS) {
                val oldest = segments.keys.first()
                segments.remove(oldest)
            }
        }
    }

    /** A letöltött szakaszok egyetlen fájlban, hogy net nélkül is meglegyenek. */
    private fun save(context: Context) {
        runCatching {
            val array = JSONArray()
            for (segment in loadedSegments()) {
                array.put(
                    JSONObject().apply {
                        put("id", segment.id)
                        put("limitKmh", segment.limitKmh)
                        put("guessed", segment.guessed)
                        put("road", segment.road ?: JSONObject.NULL)
                        put(
                            "geometry",
                            JSONArray().apply {
                                segment.points.forEach { point ->
                                    put(JSONArray().apply { put(point[0]); put(point[1]) })
                                }
                            }
                        )
                    }
                )
            }
            File(context.cacheDir, FILE_NAME)
                .writeText(JSONObject().put("segments", array).toString())
        }
    }

    private fun parse(json: JSONObject): List<SpeedSegment> {
        val array: JSONArray = json.optJSONArray("segments") ?: return emptyList()
        val result = ArrayList<SpeedSegment>(array.length())

        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val geometry = item.optJSONArray("geometry") ?: continue
            if (geometry.length() < 2) continue

            val points = ArrayList<DoubleArray>(geometry.length())
            for (j in 0 until geometry.length()) {
                val pair = geometry.optJSONArray(j) ?: continue
                points += doubleArrayOf(pair.optDouble(0), pair.optDouble(1))
            }
            if (points.size < 2) continue

            result += SpeedSegment(
                id = item.optLong("id"),
                limitKmh = item.optInt("limitKmh"),
                guessed = item.optBoolean("guessed"),
                road = item.stringOrNull("road"),
                points = points
            )
        }
        return result
    }
}
