package hu.motor.telemetria.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A szerveren számolt túraelemzés eltárolva, hogy net nélkül is látszódjon.
 *
 * A nyers JSON-t őrizzük: az elemzés bővülhet a szerveren anélkül, hogy a
 * telefon adatbázisát migrálni kellene.
 */
@Entity(tableName = "track_analysis")
data class TrackAnalysisEntity(
    @PrimaryKey val trackId: Long,
    val json: String,
    /** Hány pontból készült – ha a túra azóta nőtt, újra kell kérni. */
    val pointCount: Int,
    val updatedAt: Long
)
