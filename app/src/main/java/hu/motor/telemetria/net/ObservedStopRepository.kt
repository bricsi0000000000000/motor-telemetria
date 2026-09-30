package hu.motor.telemetria.net

import hu.motor.telemetria.service.PathPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint

object ObservedStopRepository {
    data class Stop(val id: String, val lat: Double, val lon: Double, val startedAt: Long, val endedAt: Long,
                    val type: String = "OTHER", val name: String? = null,
                    /** A felismert üzletlánc kulcsa (lidl, spar, obi, arkad…) a jelölő ikonjához. */
                    val brand: String? = null,
                    val reason: String = "Megállás; a hely típusa még nem azonosítható.",
                    /** A webes felületen megrajzolt terület, amibe a megállás esik. */
                    val areaId: Long? = null,
                    /**
                     * Egy terület-látogatásnál a be- és kilépésekből összeadott idő. Ez nem a
                     * vége mínusz eleje: közben kiléphettél, akár egy belső területre is.
                     */
                    val durationMs: Long? = null) {
        /** A kiírandó időtartam. */
        val shownMillis: Long get() = (durationMs ?: (endedAt - startedAt)).coerceAtLeast(0)
    }

    /**
     * Ezekben a területekben gyalog is jársz: a bent töltött idő számít,
     * sebességtől függetlenül. Ugyanaz, mint a szerveren.
     */
    val VISIT_KINDS = setOf("SHOP", "PLACE", "FUEL", "PARKING")

    /** A pontot tartalmazó (legbelső) gyalog bejárt terület: ott séta van, nem motorozás. */
    fun visitAreaAt(lat: Double, lon: Double, areas: List<MapArea> = AreaRepository.current()): MapArea? =
        areas.filter { it.kind in VISIT_KINDS && it.contains(lat, lon) }.minByOrNull { it.size }

    /** Ennél rövidebb összesített bent-tartózkodás elhaladás vagy GPS-tévedés. */
    private const val MIN_VISIT_MS = 30_000L

    /** Ennél pontatlanabb pont nem dönt arról, hol vagy: épületben a GPS sokat téved. */
    private const val MAX_ACCURACY_M = 40f

    private class Total(val area: MapArea) {
        var ms = 0L
        var still = 0
        var first: Long? = null
        var last = 0L
    }

    /**
     * Mennyi időt töltöttél az egyes területeken – ugyanaz a szabály, mint a
     * szerveren, hogy net nélkül se mondjon mást. Minden pontnál eldől, melyik
     * (legbelső) területen vagy; ha ez változik, az kilépés a régiből és belépés
     * az újba. A bent töltött szakaszokat területenként összeadjuk. GPS-kiesés
     * vagy szüneteltetés alatt a legutóbb látott területen számolunk tovább.
     */
    fun visits(points: List<PathPoint>, areas: List<MapArea>): List<Stop> {
        val candidates = areas.filter { it.kind in VISIT_KINDS }
        if (candidates.isEmpty()) return emptyList()
        val totals = LinkedHashMap<Long, Total>()
        var current: MapArea? = null
        var since = 0L
        var lastTime = 0L
        fun leave(time: Long) {
            val area = current ?: return
            val total = totals.getOrPut(area.id) { Total(area) }
            total.ms += time - since
            total.last = time
        }
        for (p in points) {
            if (!(p.accuracy <= MAX_ACCURACY_M)) continue
            val here = candidates.filter { it.contains(p.lat, p.lon) }.minByOrNull { it.size }
            if (here?.id != current?.id) {
                leave(p.time)
                current = here
                since = p.time
            }
            if (here != null) {
                val total = totals.getOrPut(here.id) { Total(here) }
                if (p.speedMps <= 0.8f) total.still++
                if (total.first == null) total.first = p.time
            }
            lastTime = p.time
        }
        leave(lastTime)
        // Áthajtás nem látogatás: legalább fél perc, és legalább egyszer álltál is.
        return totals.values.filter { it.ms >= MIN_VISIT_MS && it.still >= 2 }.map { total ->
            val first = total.first ?: total.last
            // Egy látogatás egyetlen pont: a terület pontja (ahol a logója is áll).
            Stop(first.toString(), total.area.centerLat, total.area.centerLon,
                first, total.last, type = total.area.kind, name = total.area.name,
                reason = "A területen töltött idő összesen, a be- és kilépések alapján.",
                areaId = total.area.id, durationMs = total.ms)
        }.sortedBy { it.startedAt }
    }

    /** Ennyi állás már megállás – ugyanez a szabály fut a szerveren is. */
    const val MIN_STOP_MS = 1000L

    /** Offline is jelölünk megállást; a célt ilyenkor nem találjuk ki. */
    fun detect(points: List<PathPoint>): List<Stop> {
        val areas = AreaRepository.current()
        val visitAreas = areas.filter { it.kind in VISIT_KINDS }
        val out = mutableListOf<Stop>()
        var start = 0
        while (start < points.size) {
            if (points[start].speedMps > 2.5f) { start++; continue }
            var end = start + 1
            while (end < points.size && points[end].segment == points[start].segment &&
                points[end].time - points[end - 1].time in 1..120000 && points[end].speedMps <= 2.5f &&
                GeoPoint(points[start].lat, points[start].lon).distanceToAsDouble(GeoPoint(points[end].lat, points[end].lon)) < 150) end++
            // Csak az álló pontok számítanak: az araszolás nem megállás.
            val still = points.subList(start, end).filter { it.speedMps <= 0.8f }
            if (still.size >= 2 && still.last().time - still.first().time >= MIN_STOP_MS) {
                val lat = still.map { it.lat }.sorted()[still.size / 2]
                val lon = still.map { it.lon }.sorted()[still.size / 2]
                // A megrajzolt terület net nélkül is dönt: azt te határoztad meg.
                val area = AreaRepository.areaAt(lat, lon)
                out.add(if (area == null) Stop(still.first().time.toString(), lat, lon, still.first().time, still.last().time)
                else Stop(still.first().time.toString(), lat, lon, still.first().time, still.last().time,
                    type = area.kind, name = area.name, reason = "A webes felületen megrajzolt területen belül.",
                    areaId = area.id))
            }
            start = end
        }
        // Ami egy bolt (vagy más gyalog bejárt hely) területén történt, azt a
        // terület összesített ideje már tartalmazza – ott ez az egyetlen adat.
        val regular = out.filter { stop -> visitAreas.none { it.contains(stop.lat, stop.lon) } }
        return (visits(points, areas) + regular).sortedBy { it.startedAt }
    }

    suspend fun load(points: List<PathPoint>): List<Stop> = withContext(Dispatchers.IO) {
        val local = detect(points)
        if (local.isEmpty()) return@withContext local
        runCatching {
            val body = JSONObject().put("points", JSONArray().apply {
                points.forEach { p -> put(JSONObject().put("lat", p.lat).put("lon", p.lon).put("time", p.time)
                    .put("speedMps", p.speedMps).put("accuracy", p.accuracy).put("segment", p.segment)) }
            })
            val data = ApiClient.postJson("/api/track/stops", body, 40_000).getJSONArray("stops")
            (0 until data.length()).map { i ->
                val s = data.getJSONObject(i)
                Stop(s.getString("id"), s.getDouble("lat"), s.getDouble("lon"), s.getLong("startedAt"), s.getLong("endedAt"),
                    s.optString("type", "OTHER"), if (s.isNull("name")) null else s.optString("name"),
                    if (s.isNull("brand")) null else s.optString("brand"), s.optString("reason"),
                    if (s.isNull("areaId") || !s.has("areaId")) null else s.optLong("areaId"),
                    if (s.isNull("durationMs") || !s.has("durationMs")) null else s.optLong("durationMs"))
            }
        }.getOrDefault(local)
    }
}
