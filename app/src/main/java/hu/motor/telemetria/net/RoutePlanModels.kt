package hu.motor.telemetria.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * A szervertől kapott útvonalterv.
 *
 * A számolás szándékosan ott fut: a személyes sebességmodell a megtett túráid
 * összesítéséből épül, ami a szerveren van, és a térképre illesztés is ott
 * történik. A telefon csak megjeleníti.
 */
data class RoutePlan(
    val style: String,
    val label: String?,
    val distanceMeters: Double,
    val shape: String,
    val stepMeters: Int,
    val times: RouteTimes,
    val confidence: RouteConfidence,
    val profile: RouteProfile,
    val sections: List<RouteSection>,
    val cornerSummary: CornerSummary,
    val stops: List<RouteStop>,
    val warnings: List<String>,
    val computedAt: Long,
    /** A nyers válasz: ezt tesszük el, hogy net nélkül is megnyitható legyen. */
    val raw: String
) {
    companion object {
        fun parse(json: JSONObject): RoutePlan = RoutePlan(
            style = json.optString("style", "FAST"),
            label = json.stringOrNull("label"),
            distanceMeters = json.optDouble("distanceMeters", 0.0),
            shape = json.optString("shape"),
            stepMeters = json.optInt("stepMeters", 20),
            times = RouteTimes.parse(json.optJSONObject("times") ?: JSONObject()),
            confidence = RouteConfidence.parse(json.optJSONObject("confidence") ?: JSONObject()),
            profile = RouteProfile.parse(json.optJSONObject("profile") ?: JSONObject()),
            sections = json.optJSONArray("sections").mapObjects { RouteSection.parse(it) },
            cornerSummary = CornerSummary.parse(json.optJSONObject("cornerSummary") ?: JSONObject()),
            stops = json.optJSONArray("stops").mapObjects { RouteStop.parse(it) },
            warnings = json.optJSONArray("warnings").mapStrings(),
            computedAt = json.optLong("computedAt"),
            raw = json.toString()
        )
    }
}

/**
 * A három idő, mozgásra és állásra bontva.
 *
 * A bontás nem kozmetika: ugyanazon az úton a mozgásidő ±15%-ot szór, a teljes
 * idő viszont akár háromszorosát - a különbség szinte teljesen abból jön, hogy
 * mennyit álltál. Egy számba gyúrva a becslés félrevezető lenne.
 */
data class RouteTimes(
    val movingFastest: Long, val movingAverage: Long, val movingSlowest: Long,
    val stopsFastest: Long, val stopsAverage: Long, val stopsSlowest: Long,
    val totalFastest: Long, val totalAverage: Long, val totalSlowest: Long
) {
    companion object {
        fun parse(json: JSONObject): RouteTimes {
            val moving = json.optJSONObject("moving") ?: JSONObject()
            val stops = json.optJSONObject("stops") ?: JSONObject()
            val total = json.optJSONObject("total") ?: JSONObject()
            return RouteTimes(
                movingFastest = moving.optLong("fastest"),
                movingAverage = moving.optLong("average"),
                movingSlowest = moving.optLong("slowest"),
                stopsFastest = stops.optLong("fastest"),
                stopsAverage = stops.optLong("average"),
                stopsSlowest = stops.optLong("slowest"),
                totalFastest = total.optLong("fastest"),
                totalAverage = total.optLong("average"),
                totalSlowest = total.optLong("slowest")
            )
        }
    }
}

/** Mennyire a te tempód ez, és mennyire általános becslés. */
data class RouteConfidence(
    val level: String,
    val reason: String,
    val historyShare: Double,
    val bucketShare: Double,
    val calibrationTracks: Int
) {
    companion object {
        fun parse(json: JSONObject) = RouteConfidence(
            level = json.optString("level", "COLD"),
            reason = json.optString("reason"),
            historyShare = json.optDouble("historyShare", 0.0),
            bucketShare = json.optDouble("bucketShare", 0.0),
            calibrationTracks = json.optInt("calibrationTracks")
        )
    }
}

/** Lépésenkénti (20 méteres) adatsorok, párhuzamos tömbökben. */
data class RouteProfile(
    val cornerClass: IntArray,
    val speedKmh: IntArray,
    val radius: IntArray,
    val limitKmh: IntArray,
    val fromHistory: IntArray
) {
    companion object {
        fun parse(json: JSONObject) = RouteProfile(
            cornerClass = json.optJSONArray("cornerClass").toIntArray(),
            speedKmh = json.optJSONArray("speedKmh").toIntArray(),
            radius = json.optJSONArray("radius").toIntArray(),
            limitKmh = json.optJSONArray("limitKmh").toIntArray(),
            fromHistory = json.optJSONArray("fromHistory").toIntArray()
        )
    }
}

/** Egy összevont szakasz: azonos kanyartípusú, egymás utáni lépések. */
data class RouteSection(
    val index: Int,
    val type: String,
    val fromStep: Int,
    val toStep: Int,
    val lat: Double,
    val lon: Double,
    val distanceMeters: Int,
    val durationMillis: Long,
    val minRadiusMeters: Int,
    val predictedKmh: Int,
    val limitKmh: Int?,
    val road: String?,
    val roadClass: String?,
    /** CELL = ezen az úton mért tempód, BUCKET = hasonló úton, CURVE/DEFAULT = becslés. */
    val source: String,
    val samples: Int
) {
    companion object {
        fun parse(json: JSONObject) = RouteSection(
            index = json.optInt("index"),
            type = json.optString("type", "STRAIGHT"),
            fromStep = json.optInt("fromStep"),
            toStep = json.optInt("toStep"),
            lat = json.optDouble("lat", 0.0),
            lon = json.optDouble("lon", 0.0),
            distanceMeters = json.optInt("distanceMeters"),
            durationMillis = json.optLong("durationMillis"),
            minRadiusMeters = json.optInt("minRadiusMeters"),
            predictedKmh = json.optInt("predictedKmh"),
            limitKmh = json.optInt("limitKmh").takeIf { it > 0 },
            road = json.stringOrNull("road"),
            roadClass = json.stringOrNull("roadClass"),
            source = json.optString("source", "CURVE"),
            samples = json.optInt("samples")
        )
    }
}

data class CornerBucket(val count: Int, val meters: Int)

data class CornerSummary(
    val hairpin: CornerBucket,
    val tight: CornerBucket,
    val medium: CornerBucket,
    val gentle: CornerBucket,
    val straight: CornerBucket,
    val twistiness: Double
) {
    companion object {
        private fun bucket(json: JSONObject, key: String): CornerBucket {
            val entry = json.optJSONObject(key) ?: JSONObject()
            return CornerBucket(entry.optInt("count"), entry.optInt("meters"))
        }

        fun parse(json: JSONObject) = CornerSummary(
            hairpin = bucket(json, "hairpin"),
            tight = bucket(json, "tight"),
            medium = bucket(json, "medium"),
            gentle = bucket(json, "gentle"),
            straight = bucket(json, "straight"),
            twistiness = json.optDouble("twistiness", 0.0)
        )
    }
}

data class RouteStop(
    val lat: Double,
    val lon: Double,
    val kind: String,
    val penaltyMillis: Long
) {
    companion object {
        fun parse(json: JSONObject) = RouteStop(
            lat = json.optDouble("lat", 0.0),
            lon = json.optDouble("lon", 0.0),
            kind = json.optString("kind"),
            penaltyMillis = json.optLong("penaltyMillis")
        )
    }
}

/** Egy köztes pont a terven. */
data class Waypoint(val lat: Double, val lon: Double, val name: String? = null, val via: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("via", via)
        put("lat", lat)
        put("lon", lon)
        if (name != null) put("name", name)
    }

    companion object {
        fun parse(json: JSONObject) = Waypoint(
            lat = json.optDouble("lat"),
            lon = json.optDouble("lon"),
            name = json.stringOrNull("name"),
            via = json.optBoolean("via")
        )

        fun parseList(array: JSONArray?): List<Waypoint> = array.mapObjects { parse(it) }
    }
}

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }.map(transform)
}

private fun JSONArray?.mapStrings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
}

private fun JSONArray?.toIntArray(): IntArray {
    if (this == null) return IntArray(0)
    return IntArray(length()) { optInt(it) }
}
