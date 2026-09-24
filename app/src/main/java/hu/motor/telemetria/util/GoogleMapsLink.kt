package hu.motor.telemetria.util

import hu.motor.telemetria.net.Waypoint
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A megtervezett út átadása a Google Maps-nek.
 *
 * A Maps URL API csak pontokat fogad, nyomvonalat nem, ráadásul legfeljebb
 * kilenc köztes pontot. A saját tervünk viszont pont attól más, mint a Google
 * ajánlata, hogy hol vezet - ezért a felvett pontok mellé a szabad helyeket
 * feltöltjük a nyomvonalról vett mintákkal. Így a Google nagyjából ugyanazt az
 * utat rakja ki, nem a saját leggyorsabb változatát.
 */
object GoogleMapsLink {

    /** A Maps URL API korlátja: ennyi köztes pont mehet át. */
    const val MAX_WAYPOINTS = 9

    /** Ennél közelebb nem teszünk két átadott pontot egymáshoz. */
    private const val MIN_SPACING_METERS = 150.0

    data class Point(val lat: Double, val lon: Double)

    data class Link(
        val url: String,
        /** Igaz, ha a felhasználó pontjai közül a korlát miatt kimaradt valamelyik. */
        val droppedWaypoints: Boolean
    )

    /**
     * @param waypoints a felhasználó pontjai, sorrendben (legalább kettő)
     * @param shape a megtervezett nyomvonal pontjai, ha van kész terv
     */
    fun build(waypoints: List<Waypoint>, shape: List<Point> = emptyList()): Link? {
        if (waypoints.size < 2) return null
        val origin = waypoints.first()
        val destination = waypoints.last()
        val mids = waypoints.subList(1, waypoints.size - 1).map { Point(it.lat, it.lon) }

        val (via, dropped) = intermediates(mids, shape)

        val url = buildString {
            append("https://www.google.com/maps/dir/?api=1&travelmode=driving")
            append("&origin=").append(encode(format(Point(origin.lat, origin.lon))))
            append("&destination=").append(encode(format(Point(destination.lat, destination.lon))))
            if (via.isNotEmpty()) {
                append("&waypoints=").append(encode(via.joinToString("|") { format(it) }))
            }
        }
        return Link(url, dropped)
    }

    /**
     * A ténylegesen átadott köztes pontok, útvonal szerinti sorrendben: előbb a
     * felhasználó pontjai, a maradék helyeken a nyomvonal mintái.
     */
    private fun intermediates(mids: List<Point>, shape: List<Point>): Pair<List<Point>, Boolean> {
        if (shape.size < 2) {
            // Terv nélkül csak a pontok vannak; ha túl sok, ritkítani kell.
            return if (mids.size <= MAX_WAYPOINTS) mids to false
            else thin(mids, MAX_WAYPOINTS) to true
        }

        val kept = if (mids.size <= MAX_WAYPOINTS) mids else thin(mids, MAX_WAYPOINTS)
        // A pontokat a nyomvonalon elfoglalt helyük szerint kell sorba tenni,
        // különben a Google más sorrendben járná be őket, mint a terv.
        val anchors = anchorIndices(kept, shape)
        val chosen = kept.indices.map { anchors[it] to kept[it] }.toMutableList()

        val budget = MAX_WAYPOINTS - kept.size
        if (budget > 0) {
            for (index in sampleIndices(shape, budget)) {
                val point = shape[index]
                if (chosen.any { distanceMeters(it.second, point) < MIN_SPACING_METERS }) continue
                chosen.add(index to point)
            }
        }
        chosen.sortBy { it.first }
        return chosen.map { it.second } to (mids.size > kept.size)
    }

    /** A nyomvonalon egyenletesen - hossz szerint - elosztott mintapontok. */
    private fun sampleIndices(shape: List<Point>, count: Int): List<Int> {
        val cumulative = DoubleArray(shape.size)
        for (i in 1 until shape.size) {
            cumulative[i] = cumulative[i - 1] + distanceMeters(shape[i - 1], shape[i])
        }
        val total = cumulative.last()
        if (total <= 0.0) return emptyList()

        val result = mutableListOf<Int>()
        for (k in 1..count) {
            val target = total * k / (count + 1)
            var index = cumulative.indexOfFirst { it >= target }
            if (index < 0) index = shape.size - 1
            if (result.lastOrNull() != index) result.add(index)
        }
        return result
    }

    /**
     * Melyik nyomvonalpont esik legközelebb az egyes felvett pontokhoz.
     * Előrefelé keresünk, hogy két egymáshoz közeli pont se cserélődjön fel.
     */
    private fun anchorIndices(points: List<Point>, shape: List<Point>): List<Int> {
        var from = 0
        return points.map { point ->
            var best = from
            var bestDistance = Double.MAX_VALUE
            for (i in from until shape.size) {
                val distance = distanceMeters(point, shape[i])
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            from = best
            best
        }
    }

    /** Egyenletes ritkítás: a széleket is megtartva `count` pontra. */
    private fun thin(points: List<Point>, count: Int): List<Point> {
        if (points.size <= count) return points
        return (0 until count).map { k ->
            points[(k.toDouble() * (points.size - 1) / (count - 1).coerceAtLeast(1)).toInt()]
        }.distinct()
    }

    private fun format(point: Point) =
        String.format(Locale.US, "%.6f,%.6f", point.lat, point.lon)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun distanceMeters(a: Point, b: Point): Double {
        val radius = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * radius * atan2(sqrt(h), sqrt(abs(1 - h)))
    }
}
