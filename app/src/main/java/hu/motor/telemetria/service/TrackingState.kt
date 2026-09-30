package hu.motor.telemetria.service

enum class TrackingStatus { IDLE, RUNNING, PAUSED }

/** A mérés pillanatnyi állapota, amit a UI figyel. */
data class TrackingState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val trackId: Long = 0L,
    val distanceMeters: Double = 0.0,
    val durationMillis: Long = 0L,
    val movingMillis: Long = 0L,
    val currentSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val altitudeMeters: Double = 0.0,
    val elevationGainMeters: Double = 0.0,
    val bearingDegrees: Float = 0f,
    val accuracyMeters: Float = 0f,
    /** Az utolsó fix helyzete – ebből tudja az automata leállítás, hogy hazaértünk-e. */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val pointCount: Int = 0,
    val lastFixTime: Long = 0L,
    val hasFix: Boolean = false,
    val telemetryEnabled: Boolean = false,
    val telemetryQuality: Float = 0f,
    /** Az utoljára lezárt túra azonosítója – a Stop után ezt tudja megnyitni a UI. */
    val lastFinishedTrackId: Long = 0L
) {
    val avgSpeedMps: Float
        get() = if (movingMillis > 0) (distanceMeters / (movingMillis / 1000.0)).toFloat() else 0f

    val isActive: Boolean
        get() = status != TrackingStatus.IDLE
}
