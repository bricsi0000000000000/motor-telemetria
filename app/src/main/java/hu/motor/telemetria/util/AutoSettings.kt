package hu.motor.telemetria.util

import android.content.Context
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.PlaceType

/**
 * Az automatikus mérésindítás kapcsolója.
 *
 * A helyek (otthon, munkahely, célpontok) az adatbázisban vannak – itt csak az
 * az egy beállítás él, hogy figyeljük-e őket egyáltalán.
 */
object AutoSettings {

    private const val PREFS = "home_prefs"
    private const val KEY_ENABLED = "auto_enabled"
    private const val KEY_RADIUS = "home_radius"

    // A korábbi, egyetlen otthont tároló változat kulcsai – egyszer átemeljük.
    private const val KEY_LEGACY_LAT = "home_lat"
    private const val KEY_LEGACY_LON = "home_lon"
    private const val KEY_LEGACY_DONE = "legacy_home_migrated"

    const val DEFAULT_RADIUS_M = 300
    const val MIN_RADIUS_M = 100
    const val MAX_RADIUS_M = 5000

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var autoEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Új hely felvételekor ezt kínáljuk fel sugárnak. */
    var defaultRadiusMeters: Int
        get() = prefs.getInt(KEY_RADIUS, DEFAULT_RADIUS_M)
        set(value) = prefs.edit()
            .putInt(KEY_RADIUS, value.coerceIn(MIN_RADIUS_M, MAX_RADIUS_M))
            .apply()

    /** A régi (egy otthonos) beállítás átemelése a helyek közé. */
    suspend fun migrateLegacyHome(context: Context) {
        if (prefs.getBoolean(KEY_LEGACY_DONE, false)) return

        val lat = Double.fromBits(prefs.getLong(KEY_LEGACY_LAT, 0L))
        val lon = Double.fromBits(prefs.getLong(KEY_LEGACY_LON, 0L))
        prefs.edit().putBoolean(KEY_LEGACY_DONE, true).apply()
        if (lat == 0.0 && lon == 0.0) return

        val dao = AppDatabase.get(context).placeDao()
        if (dao.count() > 0) return
        dao.insert(
            Place(
                name = context.getString(hu.motor.telemetria.R.string.place_type_home),
                type = PlaceType.HOME.name,
                lat = lat,
                lon = lon,
                radiusMeters = defaultRadiusMeters
            )
        )
    }
}
