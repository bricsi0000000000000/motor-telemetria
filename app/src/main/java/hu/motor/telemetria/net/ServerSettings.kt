package hu.motor.telemetria.net

import android.content.Context
import android.os.Build
import hu.motor.telemetria.BuildConfig
import java.util.UUID

/**
 * Szerverbeállítások és az eszköz azonosítója.
 *
 * A cím és a token nincs beégetve a forrásba: fordításkor a repóból kihagyott
 * `secrets.properties` fájlból jön (lásd `secrets.properties.example`), futás
 * közben pedig a Beállítások képernyőn bármikor átírható. Ha a fájl hiányzik,
 * mindkettő üres – ilyenkor az app első indításkor a Beállításokat kéri.
 */
object ServerSettings {

    private const val PREFS = "server_prefs"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_DEVICE_UID = "device_uid"
    private const val KEY_LAST_SYNC = "last_sync_at"

    val DEFAULT_BASE_URL: String = BuildConfig.DEFAULT_BASE_URL
    val DEFAULT_TOKEN: String = BuildConfig.DEFAULT_TOKEN

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private val prefs
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty().ifBlank { DEFAULT_BASE_URL }
        set(value) {
            prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()
        }

    var token: String
        get() = prefs.getString(KEY_TOKEN, DEFAULT_TOKEN).orEmpty().ifBlank { DEFAULT_TOKEN }
        set(value) {
            prefs.edit().putString(KEY_TOKEN, value.trim()).apply()
        }

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC, value).apply()
        }

    /**
     * Eszközazonosító: a szerver ezzel párosítja a telefonon lévő túraazonosítókat.
     * Első indításkor generáljuk, és onnantól nem változik (app-privát tárolóban él).
     */
    val deviceUid: String
        get() = prefs.getString(KEY_DEVICE_UID, null) ?: synchronized(this) {
            prefs.getString(KEY_DEVICE_UID, null) ?: run {
                val model = Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").take(12).ifBlank { "android" }
                val generated = "$model-${UUID.randomUUID().toString().take(8)}"
                prefs.edit().putString(KEY_DEVICE_UID, generated).apply()
                generated
            }
        }
}
