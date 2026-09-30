package hu.motor.telemetria.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** A webes felületen megrajzolt, címkézett terület. */
data class MapArea(
    val id: Long,
    val name: String,
    /** A megállástípus, amit ad: SHOP, FUEL, PLACE, PARKING, SIGNAL, ROAD. */
    val kind: String,
    val polygon: List<DoubleArray>,
    /** A logó helye – a szerver számolja, hogy a webbel egyezzen. */
    val centerLat: Double,
    val centerLon: Double,
    /** A logó feltöltésének ideje; null, ha nincs logó. */
    val logoAt: Long?
) {
    private val minLat = polygon.minOf { it[0] }
    private val maxLat = polygon.maxOf { it[0] }
    private val minLon = polygon.minOf { it[1] }
    private val maxLon = polygon.maxOf { it[1] }

    /**
     * A sokszög valódi területe, összevetéshez – ugyanaz a képlet, mint a
     * szerveren. Nem a befoglaló téglalap: egy átlós belső sávnak ugyanakkora
     * a téglalapja, mint a körülötte lévő plázának.
     */
    val size: Double = run {
        val cos = Math.cos(Math.toRadians(polygon[0][0]))
        var sum = 0.0
        var j = polygon.size - 1
        for (i in polygon.indices) {
            sum += polygon[j][1] * cos * polygon[i][0] - polygon[i][1] * cos * polygon[j][0]
            j = i
        }
        Math.abs(sum) / 2
    }

    /** Pont a sokszögben – ugyanaz a szabály, mint a szerveren. */
    fun contains(lat: Double, lon: Double): Boolean {
        if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a[0] > lat) != (b[0] > lat) && lon < (b[1] - a[1]) * (lat - a[0]) / (b[0] - a[0]) + a[1]) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * A webes felületen megrajzolt területek és logóik.
 *
 * A lista egyetlen fájlban, a logók képfájlként maradnak meg a telefonon, így
 * net nélkül is kirajzolhatók. Egy logót csak akkor töltünk le újra, ha a
 * feltöltési ideje változott – a fájl neve ezt az időt is tartalmazza.
 */
object AreaRepository {

    private const val FILE_NAME = "areas.json"
    private const val LOGO_DIR = "area_logos"

    @Volatile
    private var areas: List<MapArea> = emptyList()
    private val bitmaps = HashMap<String, Bitmap>()

    fun current(): List<MapArea> = areas

    fun byId(id: Long?): MapArea? = id?.let { wanted -> areas.firstOrNull { it.id == wanted } }

    /** A pontot tartalmazó terület; ha több is, a legkisebb nyer (a plázán belüli bolt). */
    fun areaAt(lat: Double, lon: Double): MapArea? =
        areas.filter { it.contains(lat, lon) }.minByOrNull { it.size }

    /** A legutóbb letöltött lista a fájlból – net nélkül is. */
    suspend fun loadCached(context: Context): List<MapArea> = withContext(Dispatchers.IO) {
        if (areas.isNotEmpty()) return@withContext areas
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) runCatching { areas = parse(JSONObject(file.readText())) }
        areas
    }

    /** Lekéri a listát, és letölti a hiányzó vagy megváltozott logókat. */
    suspend fun refresh(context: Context): Result<List<MapArea>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = ApiClient.getJson("/api/areas")
            val fresh = parse(response)
            File(context.filesDir, FILE_NAME).writeText(response.toString())

            val dir = File(context.filesDir, LOGO_DIR).apply { mkdirs() }
            val wanted = fresh.mapNotNull { area -> area.logoAt?.let { logoName(area) } }.toSet()
            for (area in fresh) {
                val name = area.logoAt?.let { logoName(area) } ?: continue
                val file = File(dir, name)
                if (file.exists()) continue
                // Egy hibás logó ne vigye el a többit: az marad kezdőbetűs korong.
                runCatching { file.writeBytes(ApiClient.getBytes("/api/areas/${area.id}/logo?v=${area.logoAt}")) }
            }
            // A lecserélt vagy törölt logók fájljai ne gyűljenek.
            dir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
            synchronized(bitmaps) { bitmaps.keys.retainAll(wanted) }

            areas = fresh
            fresh
        }
    }

    /** A terület logója képként, ha van és már letöltöttük. */
    fun logo(context: Context, area: MapArea): Bitmap? {
        if (area.logoAt == null) return null
        val name = logoName(area)
        synchronized(bitmaps) { bitmaps[name]?.let { return it } }
        val file = File(File(context.filesDir, LOGO_DIR), name)
        if (!file.exists()) return null
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        synchronized(bitmaps) { bitmaps[name] = bitmap }
        return bitmap
    }

    private fun logoName(area: MapArea) = "${area.id}_${area.logoAt}.img"

    private fun parse(json: JSONObject): List<MapArea> {
        val array = json.optJSONArray("areas") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val points = item.optJSONArray("polygon") ?: return@mapNotNull null
            val polygon = (0 until points.length()).mapNotNull { i ->
                points.optJSONArray(i)?.let { doubleArrayOf(it.optDouble(0), it.optDouble(1)) }
            }
            if (polygon.size < 3) return@mapNotNull null
            val center = item.optJSONArray("center")
            MapArea(
                id = item.optLong("id"),
                name = item.optString("name"),
                kind = item.optString("kind", "PLACE"),
                polygon = polygon,
                centerLat = center?.optDouble(0) ?: polygon.map { it[0] }.average(),
                centerLon = center?.optDouble(1) ?: polygon.map { it[1] }.average(),
                logoAt = if (item.isNull("logoAt")) null else item.optLong("logoAt")
            )
        }
    }
}
