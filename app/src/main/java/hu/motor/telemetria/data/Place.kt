package hu.motor.telemetria.data

import android.location.Location
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Egy hely szerepe – ez dönti el az ikont és az alapértelmezett nevet. */
enum class PlaceType { HOME, WORK, DESTINATION }

/**
 * Nevezetes hely: otthon, munkahely vagy egy célpont.
 *
 * Ha bekapcsolt automatikával elhagyod bármelyiket, elindul a mérés; ha
 * megérkezel valamelyikbe és megállsz, lezárul. A koordináta csak a telefonon
 * van, a szerverre soha nem megy fel.
 */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** A [PlaceType] neve – szövegként, hogy ne kelljen típuskonverter. */
    val type: String = PlaceType.DESTINATION.name,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Int = 300,
    /** Kikapcsolt hely a térképen látszik, de nem indít és nem állít le mérést. */
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    val placeType: PlaceType
        get() = runCatching { PlaceType.valueOf(type) }.getOrDefault(PlaceType.DESTINATION)

    /** Távolság ettől a helytől méterben. */
    fun distanceTo(latitude: Double, longitude: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat, lon, latitude, longitude, results)
        return results[0].toDouble()
    }

    fun contains(latitude: Double, longitude: Double): Boolean =
        distanceTo(latitude, longitude) < radiusMeters
}
