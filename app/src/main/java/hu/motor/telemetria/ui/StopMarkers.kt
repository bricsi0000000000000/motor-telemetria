package hu.motor.telemetria.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import hu.motor.telemetria.R
import hu.motor.telemetria.net.ObservedStopRepository.Stop
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** A kézi javítás megmarad a telefonon, és felülírja az automatikus becslést. */
class StopMarkers(private val context: Context, private val map: MapView) {
    private val markers = mutableListOf<Marker>()
    private val prefs = context.getSharedPreferences("observed_stop_types", Context.MODE_PRIVATE)
    private var stops = emptyList<Stop>()
    private val labels = linkedMapOf("ROAD" to "Úton várakozás", "SIGNAL" to "Lámpánál várakozás", "SHOP" to "Bolt / bevásárlás",
        "FUEL" to "Tankolás", "PARKING" to "Út melletti megállás", "PLACE" to "Más hely felkeresése", "OTHER" to "Ismeretlen megállás")

    fun clear() { markers.forEach { it.closeInfoWindow(); map.overlays.remove(it) }; markers.clear() }

    fun show(items: List<Stop>) {
        clear()
        stops = items
        for (stop in items) {
            val manual = prefs.getString(stop.id, null)
            val type = manual ?: stop.type
            val title = labels[type] ?: labels.getValue("OTHER")
            val icon = when (type) {
                "ROAD" -> R.drawable.ic_stop_road
                "SIGNAL" -> R.drawable.ic_road_signals
                "SHOP" -> R.drawable.ic_stop_shop
                "FUEL" -> R.drawable.ic_stop_fuel
                "PARKING" -> R.drawable.ic_stop_parking
                else -> R.drawable.ic_place_destination
            }
            val seconds = ((stop.endedAt - stop.startedAt) / 1000).coerceAtLeast(0)
            val duration = if (seconds < 60) "$seconds mp" else "${seconds / 60} p ${seconds % 60} mp"
            val marker = Marker(map).apply {
                position = GeoPoint(stop.lat, stop.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                this.icon = ContextCompat.getDrawable(context, icon)
                this.title = "$title · $duration"
                setOnMarkerClickListener { _, _ ->
                    AlertDialog.Builder(context).setTitle("$title · $duration")
                        .setMessage(listOfNotNull(stop.name.takeIf { manual == null },
                            if (manual != null) "Kézzel megadott típus." else stop.reason).joinToString("\n"))
                        .setPositiveButton("Típus javítása") { _, _ ->
                            val types = labels.keys.toList()
                            AlertDialog.Builder(context).setTitle("Mi történt itt?")
                                .setItems((labels.values + "Automatikus besorolás").toTypedArray()) { _, index ->
                                    prefs.edit().apply {
                                        if (index < types.size) putString(stop.id, types[index]) else remove(stop.id)
                                    }.apply()
                                    show(stops)
                                }.setNegativeButton(android.R.string.cancel, null).show()
                        }.setNegativeButton("Bezárás", null).show()
                    true
                }
            }
            markers.add(marker)
            map.overlays.add(marker)
        }
        map.invalidate()
    }
}
