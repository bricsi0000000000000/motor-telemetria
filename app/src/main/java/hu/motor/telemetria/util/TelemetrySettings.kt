package hu.motor.telemetria.util

import android.content.Context

/** Felhasználói és diagnosztikai állapot a bővített telemetriához. */
object TelemetrySettings {
    private const val PREFS = "telemetry_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CALIBRATION = "calibration_quality"
    private const val KEY_MANUAL_MOUNT = "manual_mount_quarter_turns"
    private const val CALIBRATION_PREFIX = "mount_profile_"
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    var enabled: Boolean
        get() = prefs().getBoolean(KEY_ENABLED, true)
        set(value) = prefs().edit().putBoolean(KEY_ENABLED, value).apply()

    var calibrationQuality: Float
        get() = prefs().getFloat(KEY_CALIBRATION, 0f)
        set(value) = prefs().edit().putFloat(KEY_CALIBRATION, value.coerceIn(0f, 1f)).apply()

    /** Null: automatikus kalibráció. 0..3: a telefon felső/jobb/alsó/bal éle néz előre. */
    var manualMountQuarterTurns: Int?
        get() = prefs().getInt(KEY_MANUAL_MOUNT, -1).takeIf { it in 0..3 }
        set(value) = prefs().edit().putInt(KEY_MANUAL_MOUNT, value ?: -1).apply()

    fun savedBaseline(profileKey: String): Float? {
        val key = CALIBRATION_PREFIX + profileKey
        return if (prefs().contains(key)) prefs().getFloat(key, 0f) else null
    }

    fun saveBaseline(profileKey: String, rollRadians: Float) {
        prefs().edit().putFloat(CALIBRATION_PREFIX + profileKey, rollRadians).apply()
    }

    fun resetCalibration() {
        val editor = prefs().edit().putFloat(KEY_CALIBRATION, 0f)
        prefs().all.keys.filter { it.startsWith(CALIBRATION_PREFIX) }.forEach(editor::remove)
        editor.apply()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
