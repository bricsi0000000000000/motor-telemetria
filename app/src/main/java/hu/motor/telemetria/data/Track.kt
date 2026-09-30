package hu.motor.telemetria.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/** Egy rögzített túra összesítő adatai. */
@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    /** null, amíg a mérés fut */
    val endTime: Long? = null,
    val distanceMeters: Double = 0.0,
    /** teljes idő szünetek nélkül */
    val durationMillis: Long = 0,
    /** csak az az idő, amíg ténylegesen mozgott a motor */
    val movingMillis: Long = 0,
    val maxSpeedMps: Float = 0f,
    val elevationGainMeters: Double = 0.0,
    val pointCount: Int = 0,
    /** 0 = régi/GPS-only túra, 1 = másodperces szenzortelemetria. */
    @ColumnInfo(defaultValue = "0") val telemetryVersion: Int = 0,
    @ColumnInfo(defaultValue = "0") val telemetrySampleCount: Int = 0,

    // --- szinkron állapot ------------------------------------------------------
    /** A szerver oldali azonosító, ha már feltöltöttük. */
    val remoteId: Long? = null,
    /** Hány pontot vett át eddig a szerver – innen folytatjuk a küldést. */
    val syncedPoints: Int = 0,
    /** Hány szenzormintát igazolt vissza a szerver. */
    @ColumnInfo(defaultValue = "0") val syncedTelemetrySamples: Int = 0,
    /** Változott az összesítő a legutóbbi feltöltés óta? */
    val dirty: Boolean = true
) {
    val avgSpeedMps: Float
        get() = if (movingMillis > 0) (distanceMeters / (movingMillis / 1000.0)).toFloat() else 0f

    val overallSpeedMps: Float
        get() = if (durationMillis > 0) (distanceMeters / (durationMillis / 1000.0)).toFloat() else 0f
}
