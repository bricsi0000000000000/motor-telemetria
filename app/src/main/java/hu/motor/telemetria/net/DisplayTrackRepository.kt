package hu.motor.telemetria.net

import android.content.Context
import hu.motor.telemetria.service.PathPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.security.MessageDigest

/** Csak rajzolási geometria. A mért pontok, az export és a statisztika változatlan. */
object DisplayTrackRepository {
    data class Display(val links: List<List<GeoPoint>>, val matched: Int, val offline: Boolean)

    fun rawLinks(points: List<PathPoint>): List<List<GeoPoint>> = points.zipWithNext { a, b ->
        if (a.segment != b.segment || (a.time > 0 && b.time - a.time !in 1..30_000)) emptyList()
        else listOf(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon))
    }

    fun paths(links: List<List<GeoPoint>>): List<List<GeoPoint>> {
        val out = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        for (link in links) {
            if (link.isEmpty()) {
                if (current.size > 1) out.add(current)
                current = mutableListOf()
            } else {
                if (current.isEmpty()) current.addAll(link)
                else current.addAll(link.drop(if (current.last() == link.first()) 1 else 0))
            }
        }
        if (current.size > 1) out.add(current)
        return out
    }

    suspend fun load(context: Context, points: List<PathPoint>, cache: Boolean = true): Display = withContext(Dispatchers.IO) {
        val links = rawLinks(points).toMutableList()
        var matched = 0
        var offline = false
        for (start in 0 until (points.size - 1).coerceAtLeast(0) step 450) {
            // Átfedés: a darabolás határán is marad mozgási előzmény.
            val from = (start - 15).coerceAtLeast(0)
            val end = (start + 466).coerceAtMost(points.size)
            val chunk = points.subList(from, end)
            val body = JSONObject().put("points", JSONArray().apply {
                chunk.forEach { p -> put(JSONObject().put("lat", p.lat).put("lon", p.lon)
                    .put("speedMps", p.speedMps).put("accuracy", p.accuracy).put("time", p.time).put("segment", p.segment)) }
            })
            val hash = MessageDigest.getInstance("SHA-256").digest(("v1|" + ServerSettings.baseUrl + body.toString()).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val directory = File(context.cacheDir, "display_tracks").apply { mkdirs() }
            val file = File(directory, "$hash.json")
            val response = runCatching {
                if (cache && file.exists()) JSONObject(file.readText())
                else ApiClient.postJson("/api/track/display", body, 60_000).also {
                    if (cache && it.optInt("failures") == 0) {
                        file.writeText(it.toString())
                        directory.listFiles()?.sortedByDescending { f -> f.lastModified() }?.drop(100)?.forEach { f -> f.delete() }
                    }
                }
            }.getOrNull()
            if (response == null) { offline = true; continue }
            if (response.optInt("failures") > 0) offline = true
            val data = response.optJSONArray("links") ?: continue
            for (i in 0 until data.length()) {
                val link = data.getJSONObject(i)
                val index = from + link.getInt("from")
                if (index < start || index >= minOf(start + 450, points.lastIndex)) continue
                if (link.optBoolean("matched") && !link.isNull("shape")) {
                    val decoded = runCatching { PolylineCodec.decode(link.getString("shape"), 6) }.getOrNull()
                    if (decoded != null && decoded.size >= 2) { links[index] = decoded; matched++ }
                }
            }
        }
        Display(links, matched, offline)
    }
}
