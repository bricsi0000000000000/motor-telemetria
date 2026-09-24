package hu.motor.telemetria.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Egy elmentett útvonalterv.
 *
 * A köztes pontok és a szervertől kapott terv is itt van, JSON-ként: így a
 * korábban megtervezett út net nélkül is megnyitható ugyanúgy, ahogy utoljára
 * láttad. Újratervezni persze csak hálózattal lehet.
 */
@Entity(tableName = "saved_routes")
data class SavedRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A szerveren kapott azonosító, ha már felkerült. */
    val remoteId: Long? = null,
    val name: String,
    /** JSON tömb: [{lat, lon, name}] */
    val waypointsJson: String,
    /** A legutóbbi terv teljes válasza, hogy offline is megjeleníthető legyen. */
    val planJson: String? = null,
    val style: String = "FAST",
    val distanceMeters: Double = 0.0,
    val averageMillis: Long = 0,
    /**
     * Igaz, ha a terv magától került ide (minden sikeres tervezés eltevődik),
     * hamis, ha a felhasználó adott neki nevet. Az automatikus tételekből csak
     * a legutóbbiakat tartjuk meg, a kézzel mentetteket soha nem dobjuk el.
     */
    val autoSaved: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface RouteDao {

    @Query("SELECT * FROM saved_routes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedRoute>>

    @Query("SELECT * FROM saved_routes ORDER BY autoSaved ASC, updatedAt DESC")
    suspend fun getAll(): List<SavedRoute>

    /** Ugyanarra a pontsorra ne gyűljön több automatikus tétel. */
    @Query("SELECT * FROM saved_routes WHERE autoSaved = 1 AND waypointsJson = :waypointsJson LIMIT 1")
    suspend fun autoByWaypoints(waypointsJson: String): SavedRoute?

    /** A legutóbbi néhány automatikus terven kívül minden mást eldobunk. */
    @Query(
        "DELETE FROM saved_routes WHERE autoSaved = 1 AND id NOT IN (" +
            "SELECT id FROM saved_routes WHERE autoSaved = 1 ORDER BY updatedAt DESC LIMIT :keep)"
    )
    suspend fun trimAuto(keep: Int)

    @Query("SELECT * FROM saved_routes WHERE id = :id")
    suspend fun byId(id: Long): SavedRoute?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: SavedRoute): Long

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun delete(id: Long)
}
