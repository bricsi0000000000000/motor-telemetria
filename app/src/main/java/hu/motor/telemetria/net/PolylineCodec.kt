package hu.motor.telemetria.net

import org.osmdroid.util.GeoPoint

/**
 * Google-féle tömörített vonal dekódolása.
 *
 * A szerver ebben küldi az útvonalat: egy 100 kilométeres terv 5000 pontja
 * nyers JSON-ban több száz kilobájt, így viszont néhány tíz - és a telefon
 * mobilneten kapja.
 */
object PolylineCodec {

    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<GeoPoint>(encoded.length / 3)
        var index = 0
        var lat = 0
        var lon = 0

        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var byte: Int
            do {
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20 && index < encoded.length)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0
            shift = 0
            do {
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20 && index < encoded.length)
            lon += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(GeoPoint(lat / factor, lon / factor))
        }
        return points
    }
}
