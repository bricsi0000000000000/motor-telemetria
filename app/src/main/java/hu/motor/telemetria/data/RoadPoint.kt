package hu.motor.telemetria.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Egy útmenti elem típusa. A sorrend a fontosságot is jelenti. */
enum class RoadPointKind { SPEED_CAMERA, POLICE, ACCIDENT, TRAFFIC_SIGNALS, OTHER }

/**
 * Fix sebességmérő, rendőri kitelepülés, baleset vagy lámpás kereszteződés.
 *
 * A szerverről érkezik, és itt is eltároljuk, hogy net nélkül is látszódjon a
 * legutóbb lehívott állapot.
 */
@Entity(tableName = "road_points")
data class RoadPoint(
    @PrimaryKey val id: Long,
    val source: String,
    val kind: String,
    val lat: Double,
    val lon: Double,
    val road: String? = null,
    val description: String? = null,
    val speedLimit: Int? = null,
    val reportedAt: Long? = null,
    /** Ideiglenes bejelentésnél a lejárat, állandó elemnél null. */
    val expiresAt: Long? = null
) {
    val pointKind: RoadPointKind
        get() = runCatching { RoadPointKind.valueOf(kind) }.getOrDefault(RoadPointKind.OTHER)
}

@Dao
interface RoadPointDao {

    @Query("SELECT * FROM road_points")
    fun observeAll(): Flow<List<RoadPoint>>

    @Query("SELECT * FROM road_points")
    suspend fun getAll(): List<RoadPoint>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(points: List<RoadPoint>)

    @Query("DELETE FROM road_points")
    suspend fun clear()
}
