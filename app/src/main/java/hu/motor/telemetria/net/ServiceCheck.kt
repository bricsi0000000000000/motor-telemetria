package hu.motor.telemetria.net

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import hu.motor.telemetria.util.BikeBluetooth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Egy szolgáltatás ellenőrzésének eredménye. */
data class CheckResult(
    val name: String,
    val ok: Boolean,
    val millis: Long,
    val detail: String
)

/**
 * Elérhető-e minden, amire az app támaszkodik: a saját szerver, rajta keresztül
 * az OSM szolgáltatások, illetve a telefonhoz kötött dolgok (csempeszerver,
 * geokódoló, GPS, motor Bluetooth).
 *
 * A Waze külön kérésre fut élesben, mert minden hívása fogyasztja a havi keretet.
 */
object ServiceCheck {

    private const val TILE_URL = "https://tile.openstreetmap.org/14/8938/5686.png"

    /** A lépések neve – ebből tudja a felület, hogy hányadiknál tartunk. */
    val steps = listOf(
        "Saját szerver",
        "OSM szolgáltatások",
        "Térképcsempék",
        "Telefon geokódolója",
        "GPS",
        "Motor Bluetooth"
    )

    /**
     * Végigmegy a lépéseken. Minden lépés előtt szól ([onStep]), és ahogy
     * megvan az eredmény, azonnal jelenti ([onResult]) – így a felületen látszik,
     * hogy halad valami, nem csak áll a képernyő.
     */
    suspend fun run(
        context: Context,
        includeWaze: Boolean,
        onStep: (index: Int, name: String) -> Unit,
        onResult: (CheckResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        onStep(0, steps[0])
        onResult(server())

        onStep(1, steps[1])
        serverSide(includeWaze).forEach(onResult)

        onStep(2, steps[2])
        onResult(tiles())

        onStep(3, steps[3])
        onResult(geocoder(context))

        onStep(4, steps[4])
        onResult(gps(context))

        onStep(5, steps[5])
        onResult(bluetooth(context))
    }

    private fun timed(name: String, worker: () -> String): CheckResult {
        val started = System.currentTimeMillis()
        return try {
            CheckResult(name, true, System.currentTimeMillis() - started, worker())
        } catch (error: Exception) {
            CheckResult(
                name,
                false,
                System.currentTimeMillis() - started,
                error.message ?: "ismeretlen hiba"
            )
        }
    }

    private fun server() = timed("Saját szerver") {
        val response = ApiClient.getJson("/api/health")
        if (!response.optBoolean("ok")) throw IllegalStateException("váratlan válasz")
        ServerSettings.baseUrl
    }

    /** A szerver a maga oldaláról ellenőrzi az OSM szolgáltatásokat. */
    private fun serverSide(includeWaze: Boolean): List<CheckResult> {
        val names = mapOf(
            "overpass" to "OSM útadatok (Overpass)",
            "nominatim" to "Címkeresés (Nominatim)",
            "waze" to "Rendőr/baleset (Waze)"
        )
        return try {
            val path = "/api/selftest" + if (includeWaze) "?waze=1" else ""
            val response = ApiClient.getJson(path, readTimeoutMs = 180_000)
            val checks = response.optJSONArray("checks") ?: return emptyList()

            (0 until checks.length()).mapNotNull { index ->
                val item = checks.optJSONObject(index) ?: return@mapNotNull null
                val key = item.optString("name")
                CheckResult(
                    name = names[key] ?: key,
                    ok = item.optBoolean("ok"),
                    millis = item.optLong("ms"),
                    detail = if (item.isNull("detail")) "" else item.optString("detail")
                )
            }
        } catch (error: Exception) {
            names.values.map {
                CheckResult(it, false, 0, "a szerver nem válaszolt: ${error.message}")
            }
        }
    }

    private fun tiles() = timed("Térképcsempék (OSM)") {
        val connection = URL(TILE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000
            // A csempeszerver User-Agent nélkül tiltja a kéréseket.
            connection.setRequestProperty("User-Agent", "hu.motor.telemetria")
            val code = connection.responseCode
            val bytes = connection.inputStream.use { it.readBytes().size }
            if (code != 200 || bytes == 0) throw IllegalStateException("HTTP $code")
            "letöltve ${bytes / 1024} kB"
        } finally {
            connection.disconnect()
        }
    }

    private fun geocoder(context: Context) = timed("Telefon geokódolója") {
        if (!Geocoder.isPresent()) throw IllegalStateException("nincs ilyen szolgáltatás a telefonon")
        "elérhető (a címkeresés így nem terheli a szervert)"
    }

    private fun gps(context: Context) = timed("GPS") {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw IllegalStateException("nem érhető el")
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            throw IllegalStateException("ki van kapcsolva")
        }
        "bekapcsolva"
    }

    private suspend fun bluetooth(context: Context): CheckResult {
        val name = BikeBluetooth.deviceName
        if (name.isNullOrBlank()) {
            return CheckResult("Motor Bluetooth", true, 0, "nincs kiválasztva eszköz")
        }
        val started = System.currentTimeMillis()
        val connected = BikeBluetooth.isConnected(context)
        return CheckResult(
            "Motor Bluetooth",
            true,
            System.currentTimeMillis() - started,
            if (connected) "$name · csatlakozva" else "$name · nem látható"
        )
    }
}
