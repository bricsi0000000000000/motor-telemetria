package hu.motor.telemetria.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * A motor Bluetooth-eszköze (pl. a fedélzeti kihangosító).
 *
 * Ha be van kapcsolva a feltétel, csak akkor indul automatikusan mérés, amikor
 * a telefon ehhez az eszközhöz csatlakozik – így a gyalogos vagy autós utak nem
 * lesznek "motortúrák".
 */
object BikeBluetooth {

    private const val PREFS = "bike_bt_prefs"
    private const val KEY_REQUIRED = "required"
    private const val KEY_ADDRESS = "device_address"
    private const val KEY_NAME = "device_name"
    private const val KEY_CONNECTED = "connected"

    /** A telefon ezeken a profilokon látja a motor kihangosítóját. */
    private val PROFILES = listOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP)

    /** A profil proxy felépítése ritkán, de eltarthat pár másodpercig. */
    private const val PROFILE_TIMEOUT_MS = 6000L

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Kell-e a motor Bluetooth-a az automatikus indításhoz? */
    var required: Boolean
        get() = prefs(appContext).getBoolean(KEY_REQUIRED, false)
        set(value) = prefs(appContext).edit().putBoolean(KEY_REQUIRED, value).apply()

    var deviceAddress: String?
        get() = prefs(appContext).getString(KEY_ADDRESS, null)
        private set(value) {
            prefs(appContext).edit().putString(KEY_ADDRESS, value).apply()
        }

    var deviceName: String?
        get() = prefs(appContext).getString(KEY_NAME, null)
        private set(value) {
            prefs(appContext).edit().putString(KEY_NAME, value).apply()
        }

    fun setDevice(address: String, name: String?) {
        deviceAddress = address
        deviceName = name
    }

    /** A broadcast figyelő írja: épp csatlakozik-e a motor eszköze. */
    fun markConnected(context: Context, connected: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONNECTED, connected).apply()
    }

    fun wasConnected(context: Context): Boolean = prefs(context).getBoolean(KEY_CONNECTED, false)

    fun hasPermission(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** A párosított eszközök a beállítások listájához. */
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        return try {
            adapter(context)?.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    /**
     * Tényleg csatlakozik-e most a motor eszköze.
     *
     * A gyorsítótárazott állapot újraindítás után elavulhat, ezért megkérdezzük
     * a rendszertől is (headset és A2DP profil). Ha ez nem megy, marad a
     * legutóbb látott állapot.
     */
    suspend fun isConnected(context: Context): Boolean {
        val target = deviceAddress ?: return false
        val adapter = adapter(context) ?: return false
        if (!adapter.isEnabled) {
            markConnected(context, false)
            return false
        }
        if (!hasPermission(context)) return wasConnected(context)

        // A profilokat párhuzamosan kérdezzük: a proxy felépítése lassabb, mint
        // maga a lekérdezés, egymás után indítva könnyen kifutnánk az időből.
        val viaProfile = withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
            coroutineScope {
                PROFILES
                    .map { profile -> async { connectedViaProfile(context, adapter, profile, target) } }
                    .awaitAll()
                    .any { it }
            }
        }

        if (viaProfile == true) {
            markConnected(context, true)
            return true
        }

        // Ha egyik profil sem ismerte fel, még mindig ott van, amit az ACL
        // (csatlakozás/bontás) üzenetekből tudunk – egy eszköz csatlakozhat úgy
        // is, hogy egyik lekérdezett profilban sem jelenik meg.
        return wasConnected(context)
    }

    /**
     * Mit lát a telefon – hibakereséshez, a főképernyőn hosszan nyomva.
     * Szándékosan minden részletet kiír, mert a párosítás sokféleképpen elromolhat.
     */
    suspend fun diagnostics(context: Context): String {
        val adapter = adapter(context)
        val target = deviceAddress
        val lines = mutableListOf<String>()

        lines += "Eszköz: ${deviceName ?: "–"} (${target ?: "nincs kiválasztva"})"
        lines += "Bluetooth: " + when {
            adapter == null -> "nem elérhető"
            adapter.isEnabled -> "bekapcsolva"
            else -> "kikapcsolva"
        }
        lines += "Engedély: " + if (hasPermission(context)) "megvan" else "hiányzik"
        lines += "Párosítva: " + if (pairedDevices(context).any { it.address == target }) "igen" else "nem"
        lines += "Utolsó esemény szerint: " +
            if (wasConnected(context)) "csatlakozva" else "nincs csatlakozva"

        if (adapter != null && adapter.isEnabled && hasPermission(context)) {
            for (profile in PROFILES) {
                val devices = withTimeoutOrNull(PROFILE_TIMEOUT_MS) {
                    devicesOnProfile(context, adapter, profile)
                }
                lines += "${profileName(profile)}: " + when {
                    devices == null -> "időtúllépés"
                    devices.isEmpty() -> "nincs csatlakozott eszköz"
                    else -> devices.joinToString()
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun profileName(profile: Int) = when (profile) {
        BluetoothProfile.HEADSET -> "Kihangosító (HFP)"
        BluetoothProfile.A2DP -> "Hang (A2DP)"
        else -> "Profil $profile"
    }

    private suspend fun connectedViaProfile(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int,
        address: String
    ): Boolean = devicesOnProfile(context, adapter, profile) { it.address == address }

    /**
     * A profilhoz épp csatlakozó eszközök nevei (diagnosztikához).
     * Az engedélyt a hívó (isConnected / diagnostics) előre ellenőrzi.
     */
    @SuppressLint("MissingPermission")
    private suspend fun devicesOnProfile(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int
    ): List<String> {
        val names = mutableListOf<String>()
        devicesOnProfile(context, adapter, profile) { device ->
            names += runCatching { device.name }.getOrNull() ?: device.address
            false
        }
        return names
    }

    /**
     * A profil proxyja csak aszinkron épül fel, ezért a hívó addig felfüggesztve
     * vár. A [match] minden csatlakozott eszközre lefut; ha igazat ad, a
     * lekérdezés eredménye igaz lesz.
     */
    private suspend fun devicesOnProfile(
        context: Context,
        adapter: BluetoothAdapter,
        profile: Int,
        match: (BluetoothDevice) -> Boolean
    ): Boolean = suspendCoroutine { continuation ->
        var resumed = false
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileType: Int, proxy: BluetoothProfile) {
                val found = try {
                    // A match minden eszközre lefut, mert a diagnosztika gyűjti is őket.
                    proxy.connectedDevices.map { match(it) }.any { it }
                } catch (e: SecurityException) {
                    false
                }
                adapter.closeProfileProxy(profileType, proxy)
                if (!resumed) {
                    resumed = true
                    continuation.resume(found)
                }
            }

            override fun onServiceDisconnected(profileType: Int) = Unit
        }
        val started = try {
            adapter.getProfileProxy(context, listener, profile)
        } catch (e: SecurityException) {
            false
        }
        if (!started && !resumed) {
            resumed = true
            continuation.resume(false)
        }
    }
}
