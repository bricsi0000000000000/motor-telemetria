package hu.motor.telemetria.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaceDao {

    /** A lista sorrendje: otthon, munkahely, majd a célpontok. */
    @Query("SELECT * FROM places ORDER BY type ASC, name ASC")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places")
    suspend fun getAll(): List<Place>

    @Query("SELECT * FROM places WHERE enabled = 1")
    suspend fun getActive(): List<Place>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun get(id: Long): Place?

    @Query("SELECT COUNT(*) FROM places")
    suspend fun count(): Int

    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Delete
    suspend fun delete(place: Place)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: Long)
}
