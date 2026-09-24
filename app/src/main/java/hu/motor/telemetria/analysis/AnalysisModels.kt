package hu.motor.telemetria.analysis

import hu.motor.telemetria.net.stringOrNull
import org.json.JSONObject

/**
 * A szervertől kapott túraelemzés. A számolás ott fut (ott van net a
 * sebességhatárokhoz), a telefon csak megjeleníti és eltárolja.
 */
data class TrackAnalysis(
    val trackId: Long,
    val computedAt: Long,
    val pointCount: Int,
    val summary: AnalysisSummary?,
    val fast: List<AnalysisSection>,
    val slow: List<AnalysisSection>,
    val climbs: List<AnalysisSection>,
    val descents: List<AnalysisSection>,
    val speeding: List<AnalysisSection>
) {
    companion object {
        fun parse(json: JSONObject): TrackAnalysis {
            val sections = json.optJSONObject("sections") ?: JSONObject()
            return TrackAnalysis(
                trackId = json.optLong("trackId"),
                computedAt = json.optLong("computedAt"),
                pointCount = json.optInt("pointCount"),
                summary = json.optJSONObject("summary")?.let { AnalysisSummary.parse(it) },
                fast = AnalysisSection.parseList(sections, "fast"),
                slow = AnalysisSection.parseList(sections, "slow"),
                climbs = AnalysisSection.parseList(sections, "climbs"),
                descents = AnalysisSection.parseList(sections, "descents"),
                speeding = AnalysisSection.parseList(sections, "speeding")
            )
        }
    }
}

data class AnalysisSummary(
    val distanceMeters: Double,
    val avgMovingKmh: Double,
    val maxKmh: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val steepestClimbPercent: Double,
    val steepestDescentPercent: Double,
    val biggestClimb: AnalysisSection?,
    val fastestSection: AnalysisSection?,
    val fastSectionCount: Int,
    val slowSectionCount: Int,
    val limit: LimitSummary
) {
    companion object {
        fun parse(json: JSONObject) = AnalysisSummary(
            distanceMeters = json.optDouble("distanceMeters", 0.0),
            avgMovingKmh = json.optDouble("avgMovingKmh", 0.0),
            maxKmh = json.optDouble("maxKmh", 0.0),
            elevationGainMeters = json.optDouble("elevationGainMeters", 0.0),
            elevationLossMeters = json.optDouble("elevationLossMeters", 0.0),
            steepestClimbPercent = json.optDouble("steepestClimbPercent", 0.0),
            steepestDescentPercent = json.optDouble("steepestDescentPercent", 0.0),
            biggestClimb = json.optJSONObject("biggestClimb")?.let { AnalysisSection.parse(it) },
            fastestSection = json.optJSONObject("fastestSection")?.let { AnalysisSection.parse(it) },
            fastSectionCount = json.optInt("fastSectionCount"),
            slowSectionCount = json.optInt("slowSectionCount"),
            limit = LimitSummary.parse(json.optJSONObject("limit") ?: JSONObject())
        )
    }
}

/** A helyi sebességhatárhoz mért összevetés. */
data class LimitSummary(
    /** A pontok hány részéhez találtunk utat az OSM-ben (0–1). */
    val coverage: Double,
    val aboveShare: Double,
    val aboveDistanceMeters: Double,
    val aboveDurationMillis: Long,
    /** 1,0 = pont annyival mentél, amennyi ki van írva. */
    val avgRatio: Double?,
    val maxOverKmh: Double,
    val maxOverAtLimitKmh: Int?,
    val maxOverSpeedKmh: Double,
    val speedingSectionCount: Int,
    val error: String?
) {
    companion object {
        fun parse(json: JSONObject) = LimitSummary(
            coverage = json.optDouble("coverage", 0.0),
            aboveShare = json.optDouble("aboveShare", 0.0),
            aboveDistanceMeters = json.optDouble("aboveDistanceMeters", 0.0),
            aboveDurationMillis = json.optLong("aboveDurationMillis"),
            avgRatio = if (json.isNull("avgRatio")) null else json.optDouble("avgRatio"),
            maxOverKmh = json.optDouble("maxOverKmh", 0.0),
            maxOverAtLimitKmh = if (json.isNull("maxOverAtLimitKmh")) null
            else json.optInt("maxOverAtLimitKmh"),
            maxOverSpeedKmh = json.optDouble("maxOverSpeedKmh", 0.0),
            speedingSectionCount = json.optInt("speedingSectionCount"),
            error = json.stringOrNull("error")
        )
    }
}

/** Egy kiemelt szakasz: gyors, lassú, emelkedő, lejtő vagy limit feletti. */
data class AnalysisSection(
    val type: String,
    /** A szakasz kezdőpontja – erre ugrik a térkép, ha a listában rákoppintasz. */
    val lat: Double,
    val lon: Double,
    /** A pont indexe a túrán belül – ebből rajzoljuk ki a térképre. */
    val from: Int,
    val to: Int,
    val startTime: Long,
    val durationMillis: Long,
    val distanceMeters: Double,
    val avgKmh: Double,
    val maxKmh: Double,
    val elevationDeltaMeters: Double,
    val gradePercent: Double,
    val limitKmh: Int?,
    val road: String?,
    val maxOverKmh: Double?,
    val steep: Boolean
) {
    companion object {
        fun parse(json: JSONObject) = AnalysisSection(
            type = json.optString("type"),
            lat = json.optDouble("lat", 0.0),
            lon = json.optDouble("lon", 0.0),
            from = json.optInt("from"),
            to = json.optInt("to"),
            startTime = json.optLong("startTime"),
            durationMillis = json.optLong("durationMillis"),
            distanceMeters = json.optDouble("distanceMeters", 0.0),
            avgKmh = json.optDouble("avgKmh", 0.0),
            maxKmh = json.optDouble("maxKmh", 0.0),
            elevationDeltaMeters = json.optDouble("elevationDeltaMeters", 0.0),
            gradePercent = json.optDouble("gradePercent", 0.0),
            limitKmh = if (json.isNull("limitKmh")) null else json.optInt("limitKmh"),
            road = json.stringOrNull("road"),
            maxOverKmh = if (json.isNull("maxOverKmh")) null else json.optDouble("maxOverKmh"),
            steep = json.optBoolean("steep")
        )

        fun parseList(parent: JSONObject, key: String): List<AnalysisSection> {
            val array = parent.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { parse(it) }
            }
        }
    }
}
