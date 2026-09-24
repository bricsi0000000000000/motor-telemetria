package hu.motor.telemetria.net

import android.content.Context
import android.location.Address
import android.location.Geocoder
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.GeoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * "Hol van ez a pont?" – a túralistában az ismeretlen végpontok betájolása.
 *
 * Sorrend: helyi gyorsítótár → a telefon beépített geokódolója → a szerver
 * (OSM Nominatim). A telefonos geokódoló gyors és ingyenes, de nincs minden
 * készüléken; a szerver pedig úgyis gyorsítótáraz, tehát a Nominatimot alig
 * terheljük.
 */
object GeocodeRepository {

    /** ~11 méteres rács: ugyanaz a parkoló ugyanaz a kulcs. */
    private const val GRID = 1e4

    private val hungarian = Locale("hu", "HU")

    /** Amit ebben a futásban nem sikerült feloldani – ne próbáljuk körbe-körbe. */
    private val failed = mutableSetOf<String>()

    fun key(latitude: Double, longitude: Double): String {
        val lat = Math.round(latitude * GRID) / GRID
        val lon = Math.round(longitude * GRID) / GRID
        return String.format(Locale.US, "%.4f,%.4f", lat, lon)
    }

    /** A már ismert címek egy lekérdezéssel – ezzel indul a lista. */
    suspend fun cached(context: Context, keys: List<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext emptyMap()
            AppDatabase.get(context).geoLabelDao().get(keys.distinct())
                .associate { it.key to it.label }
        }

    /**
     * Egy pont feloldása. Sikeres feloldás után eltesszük, így a lista legközelebb
     * már azonnal ki tudja írni.
     */
    suspend fun resolve(context: Context, latitude: Double, longitude: Double): String? =
        withContext(Dispatchers.IO) {
            val key = key(latitude, longitude)
            if (key in failed) return@withContext null

            val dao = AppDatabase.get(context).geoLabelDao()
            dao.get(listOf(key)).firstOrNull()?.let { return@withContext it.label }

            val label = fromDeviceGeocoder(context, latitude, longitude)
                ?: fromServer(latitude, longitude)

            if (label == null) {
                failed += key
                return@withContext null
            }
            dao.save(GeoLabel(key = key, label = label, updatedAt = System.currentTimeMillis()))
            label
        }

    /** A telefon saját geokódolója (ha van rajta ilyen szolgáltatás). */
    @Suppress("DEPRECATION")
    private fun fromDeviceGeocoder(context: Context, latitude: Double, longitude: Double): String? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val addresses: List<Address>? =
                Geocoder(context, hungarian).getFromLocation(latitude, longitude, 1)
            addresses?.firstOrNull()?.let { address ->
                format(
                    road = address.thoroughfare,
                    houseNumber = address.subThoroughfare,
                    suburb = address.subLocality,
                    city = address.locality ?: address.subAdminArea,
                    county = address.adminArea
                )
            }
        }.getOrNull()
    }

    /** A szerver Nominatim-lekérdezése, ha a telefon nem tudott válaszolni. */
    private fun fromServer(latitude: Double, longitude: Double): String? = runCatching {
        val response = ApiClient.getJson(
            "/api/geocode?lat=${String.format(Locale.US, "%.5f", latitude)}" +
                "&lon=${String.format(Locale.US, "%.5f", longitude)}",
            readTimeoutMs = 30_000
        )
        format(
            road = response.stringOrNull("road"),
            houseNumber = response.stringOrNull("houseNumber"),
            suburb = response.stringOrNull("suburb"),
            city = response.stringOrNull("city"),
            county = response.stringOrNull("county")
        )
    }.getOrNull()

    /**
     * A legpontosabb ismert szint nyer: pontos cím, majd utca, majd városrész,
     * végül a település (illetve ha semmi más nincs, a megye).
     */
    private fun format(
        road: String?,
        houseNumber: String?,
        suburb: String?,
        city: String?,
        county: String?
    ): String? {
        val settlement = city ?: county
        return when {
            !road.isNullOrBlank() && !houseNumber.isNullOrBlank() ->
                listOfNotNull("$road $houseNumber", settlement).joinToString(", ")

            !road.isNullOrBlank() -> listOfNotNull(road, settlement).joinToString(", ")

            // Városban a városrész mond többet, mint a puszta városnév.
            !suburb.isNullOrBlank() && !city.isNullOrBlank() -> "$city, $suburb"

            !suburb.isNullOrBlank() -> suburb
            !city.isNullOrBlank() -> city
            !county.isNullOrBlank() -> county
            else -> null
        }
    }
}
