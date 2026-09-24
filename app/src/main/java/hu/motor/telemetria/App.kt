package hu.motor.telemetria

import android.app.Application
import android.content.Context
import hu.motor.telemetria.net.ServerSettings
import hu.motor.telemetria.service.PlaceGeofences
import hu.motor.telemetria.sync.SyncManager
import hu.motor.telemetria.util.AutoSettings
import hu.motor.telemetria.util.BikeBluetooth
import hu.motor.telemetria.util.ThemeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class App : Application() {

    /** Rövid, indításkori háttérmunkákhoz (pl. régi beállítás átemelése). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /**
         * Olyan rövid háttérmunkákhoz, amiknek a képernyő eltűnése után is be
         * kell fejeződniük - például a megtervezett út eltevéséhez.
         */
        val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onCreate() {
        super.onCreate()

        // A választott (vagy a rendszertől örökölt) nappali/éjszakai mód még az
        // első képernyő létrejötte előtt álljon be.
        ThemeSettings.init(this)

        // Szerverbeállítások és szinkron: az indításkor felfedezett elmaradást
        // (offline töltött túrákat) a SyncManager magától elkezdi feltölteni.
        ServerSettings.init(this)
        SyncManager.init(this)

        // A helyek figyelése: ha be van kapcsolva, induljon az appal együtt.
        AutoSettings.init(this)
        BikeBluetooth.init(this)
        appScope.launch {
            AutoSettings.migrateLegacyHome(this@App)
            PlaceGeofences.refresh(this@App)
        }

        // Az OSM csempeszerver User-Agent nélkül tiltja a kéréseket, ezért ezt
        // minden térkép létrehozása ELŐTT be kell állítani.
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        // Alkalmazás-privát cache: nem kell hozzá tárhely engedély.
        val base = getExternalFilesDir(null) ?: filesDir
        Configuration.getInstance().osmdroidBasePath = base
        Configuration.getInstance().osmdroidTileCache = File(base, "tiles").apply { mkdirs() }
    }
}
