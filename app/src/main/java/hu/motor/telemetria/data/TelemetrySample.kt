package hu.motor.telemetria.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo

/**
 * Egy másodperc nagyfrekvenciás szenzoradatai összesítve.
 *
 * A nyers 50 Hz-es folyam nem kerül az adatbázisba: ez a sor elég a vezetési
 * dinamika, az útminőség és az árnyék módban futó eseménykeresés számára.
 */
@Entity(
    tableName = "telemetry_samples",
    primaryKeys = ["trackId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index(value = ["trackId", "time"])]
)
data class TelemetrySample(
    val trackId: Long,
    val seq: Int,
    val time: Long,
    val lat: Double?,
    val lon: Double?,
    val speedMps: Float,
    /** GPS szerinti haladási irány, 0..360 fok; az irányonkénti útminőséghez kell. */
    @ColumnInfo(defaultValue = "0") val bearingDegrees: Float,
    val pressureHpa: Float?,
    val fusedAltitudeMeters: Double?,
    val forwardMeanMps2: Float,
    val forwardMinMps2: Float,
    val forwardMaxMps2: Float,
    val lateralMeanMps2: Float,
    val lateralRmsMps2: Float,
    val lateralPeakMps2: Float,
    val verticalRmsMps2: Float,
    val verticalPeakMps2: Float,
    val yawPeakRadS: Float,
    val rollPeakRadS: Float,
    /** A motor becsült dőlése; null, amíg a tartó kalibrációja nem érvényes. */
    val leanDegrees: Float?,
    /** 0..1, ennyi bizalmat kap ez a másodperc a statisztikában. */
    val mountQuality: Float,
    val sampleCount: Int,
    val flags: Int
) {
    companion object {
        const val FLAG_CALIBRATING = 1
        const val FLAG_POOR_MOUNT = 1 shl 1
        const val FLAG_HARD_BRAKE = 1 shl 2
        const val FLAG_HARD_ACCEL = 1 shl 3
        const val FLAG_ROUGH = 1 shl 4
        const val FLAG_IMPACT_CANDIDATE = 1 shl 5
    }
}
