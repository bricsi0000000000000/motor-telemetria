package hu.motor.telemetria.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Helyben törölt túra, ami a szerveren még megvan. A törlés is "offline-tűrő":
 * a sor addig marad, amíg a szerver vissza nem igazolja a törlést.
 */
@Entity(tableName = "pending_deletes")
data class PendingDelete(
    @PrimaryKey val trackId: Long
)
