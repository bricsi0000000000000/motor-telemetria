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
                    val reason: String = "Megállás; a hely típusa még nem azonosítható.")

    /** Offline is jelölünk megállást; a célt ilyenkor nem találjuk ki. */
    fun detect(points: List<PathPoint>): List<Stop> {
        val out = mutableListOf<Stop>()
        var start = 0
        while (start < points.size) {
            if (points[start].speedMps > 2.5f) { start++; continue }
            var end = start + 1
            while (end < points.size && points[end].segment == points[start].segment &&
                points[end].time - points[end - 1].time in 1..120000 && points[end].speedMps <= 2.5f &&
                GeoPoint(points[start].lat, points[start].lon).distanceToAsDouble(GeoPoint(points[end].lat, points[end].lon)) < 150) end++
            val still = points.subList(start, end).filter { it.speedMps <= 0.8f }
            if (still.size >= 2 && points[end - 1].time - points[start].time >= 15000) {
                out.add(Stop(points[start].time.toString(), still.map { it.lat }.sorted()[still.size / 2],
                    still.map { it.lon }.sorted()[still.size / 2], points[start].time, points[end - 1].time))
            }
            start = end
        }
        return out
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
                    s.optString("type", "OTHER"), if (s.isNull("name")) null else s.optString("name"), s.optString("reason"))
            }
        }.getOrDefault(local)
    }
}
