package hu.motor.telemetria.ui

import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.data.TrackEndpoints
import hu.motor.telemetria.net.GeocodeRepository
import hu.motor.telemetria.util.Fmt

/** A túralista egy sora: vagy napi fejléc, vagy egy túra. */
sealed interface TrackListItem {

    data class DayHeader(
        val dayKey: String,
        val startTime: Long,
        val trackCount: Int,
        val distanceMeters: Double,
        val durationMillis: Long,
        val movingMillis: Long,
        val maxSpeedMps: Float,
        val elevationGainMeters: Double
    ) : TrackListItem {
        /** A nap átlagsebessége a ténylegesen mozgásban töltött időre vetítve. */
        val avgSpeedMps: Float
            get() = if (movingMillis > 0) {
                (distanceMeters / (movingMillis / 1000.0)).toFloat()
            } else {
                0f
            }
    }

    data class Entry(
        val track: Track,
        /** Honnan indult (ha egy megadott hely körében). */
        val from: Place?,
        /** Hova érkezett. */
        val to: Place?,
        /** Ha nem megadott hely: a betájolt cím (utca, városrész vagy település). */
        val fromLabel: String?,
        val toLabel: String?,
        val pair: PairKind
    ) : TrackListItem
}

/** Oda-vissza felismerés: ugyanazon a napon A→B után B→A. */
enum class PairKind { NONE, OUT, BACK, LOOP }

/**
 * A túrákat napokra bontja, és megkeresi az oda-vissza párokat.
 *
 * A "honnan hova" a megadott helyekből jön: ha a túra első pontja az egyik hely
 * körében van, akkor onnan indult. Így a munkába menet és a hazaút összetartozó
 * párként jelenik meg, nem két külön, magyarázat nélküli sorként.
 */
object TrackGrouping {

    /**
     * A naplóbeli jelöléshez nagyobb kört nézünk, mint az automatikus
     * indításhoz. Ennek egyszerű oka van: ha a mérés magától indul, akkor az
     * első rögzített pont már a kör szélén (vagy azon kívül, az első GPS fixnél)
     * van – szigorú sugárral a saját munkahelyünk sem passzolna rá, és cím
     * jelenne meg a neve helyett.
     */
    private const val LABEL_TOLERANCE_M = 300.0

    /**
     * Melyik mentett helyhez tartozik ez a pont (a legközelebbihez, a fenti
     * tűréssel). Ugyanezt használja a lista és a geokódolás kihagyása is.
     */
    fun placeFor(places: List<Place>, lat: Double?, lon: Double?): Place? {
        if (lat == null || lon == null) return null
        return places
            .map { it to it.distanceTo(lat, lon) }
            .filter { (place, distance) -> distance < place.radiusMeters + LABEL_TOLERANCE_M }
            .minByOrNull { it.second }
            ?.first
    }

    fun build(
        tracks: List<Track>,
        endpoints: Map<Long, TrackEndpoints>,
        places: List<Place>,
        /** Kerekített koordináta → betájolt cím. */
        labels: Map<String, String> = emptyMap()
    ): List<TrackListItem> {
        if (tracks.isEmpty()) return emptyList()

        val byDay = tracks.groupBy { Fmt.dayKey(it.startTime) }
        val items = mutableListOf<TrackListItem>()

        // A napok újtól a régi felé, a napon belül szintén (a párosítás viszont
        // időrendben a legegyszerűbb, ezért ott megfordítjuk).
        for ((dayKey, dayTracks) in byDay.entries.sortedByDescending { it.key }) {
            val sorted = dayTracks.sortedBy { it.startTime }
            val entries = sorted.map { track ->
                val ends = endpoints[track.id]
                val from = placeFor(places, ends?.startLat, ends?.startLon)
                val to = placeFor(places, ends?.endLat, ends?.endLon)
                TrackListItem.Entry(
                    track = track,
                    from = from,
                    to = to,
                    // Címet csak oda kérünk, ahol nincs megadott hely.
                    fromLabel = if (from == null) labelAt(labels, ends?.startLat, ends?.startLon) else null,
                    toLabel = if (to == null) labelAt(labels, ends?.endLat, ends?.endLon) else null,
                    pair = PairKind.NONE
                )
            }.toMutableList()

            markPairs(entries)

            items += TrackListItem.DayHeader(
                dayKey = dayKey,
                startTime = sorted.first().startTime,
                trackCount = sorted.size,
                distanceMeters = sorted.sumOf { it.distanceMeters },
                durationMillis = sorted.sumOf { it.durationMillis },
                movingMillis = sorted.sumOf { it.movingMillis },
                maxSpeedMps = sorted.maxOf { it.maxSpeedMps },
                elevationGainMeters = sorted.sumOf { it.elevationGainMeters }
            )
            items += entries.reversed()
        }
        return items
    }

    private fun labelAt(labels: Map<String, String>, lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        return labels[GeocodeRepository.key(lat, lon)]
    }

    private fun markPairs(entries: MutableList<TrackListItem.Entry>) {
        val paired = BooleanArray(entries.size)

        for (i in entries.indices) {
            val out = entries[i]
            val from = out.from ?: continue
            val to = out.to ?: continue

            if (from.id == to.id) {
                // Ugyanonnan indult és oda is ért vissza: körút.
                entries[i] = out.copy(pair = PairKind.LOOP)
                continue
            }
            if (paired[i]) continue

            // A visszaút az első olyan későbbi túra, ami pont fordítva megy.
            val backIndex = (i + 1 until entries.size).firstOrNull { j ->
                !paired[j] &&
                    entries[j].from?.id == to.id &&
                    entries[j].to?.id == from.id
            } ?: continue

            paired[i] = true
            paired[backIndex] = true
            entries[i] = out.copy(pair = PairKind.OUT)
            entries[backIndex] = entries[backIndex].copy(pair = PairKind.BACK)
        }
    }
}
