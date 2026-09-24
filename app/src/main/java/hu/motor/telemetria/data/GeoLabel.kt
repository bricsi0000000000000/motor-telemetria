package hu.motor.telemetria.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Egy koordinátához tartozó emberi olvasható hely ("Hunyadi János utca 56/A,
 * Leányfalu"). A kulcs kerekített koordináta, így ugyanaz a parkoló egy sor.
 */
@Entity(tableName = "geo_labels")
data class GeoLabel(
    @PrimaryKey val key: String,
    val label: String,
    val updatedAt: Long
)

@Dao
interface GeoLabelDao {

    @Query("SELECT * FROM geo_labels WHERE key IN (:keys)")
    suspend fun get(keys: List<String>): List<GeoLabel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(label: GeoLabel)
}
