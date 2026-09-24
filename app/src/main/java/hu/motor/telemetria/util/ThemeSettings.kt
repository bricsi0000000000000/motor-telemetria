package hu.motor.telemetria.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Nappali / éjszakai mód.
 *
 * Alapból a telefon beállítását követjük; a Statisztika fülön ez felülírható.
 * A választást az [AppCompatDelegate] alkalmazza, ami magától újraépíti a
 * futó képernyőket – nem kell újraindítani az appot.
 */
object ThemeSettings {

    enum class Mode(val storedValue: String, val nightMode: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

        companion object {
            fun from(value: String?): Mode =
                entries.firstOrNull { it.storedValue == value } ?: SYSTEM
        }
    }

    private const val PREFS = "ui_prefs"
    private const val KEY_MODE = "theme_mode"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
        apply(mode)
    }

    var mode: Mode
        get() = Mode.from(
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        )
        set(value) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_MODE, value.storedValue).apply()
            apply(value)
        }

    private fun apply(mode: Mode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }

    /** A térkép csempéihez kell: sötétben invertálva olvasható éjszaka. */
    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}
