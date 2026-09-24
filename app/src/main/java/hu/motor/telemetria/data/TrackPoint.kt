package hu.motor.telemetria.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Egyetlen GPS mintavétel.
 *
 * A [segment] a szüneteltetés miatt kell: minden folytatás után eggyel nő,
 * így a térképen nem húzunk vonalat a szünet két vége közé.
 */
@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = Track::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId")]
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val speedMps: Float,
    val accuracy: Float,
    val bearing: Float,
    val time: Long,
    val segment: Int
)
