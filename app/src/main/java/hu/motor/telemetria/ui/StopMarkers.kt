package hu.motor.telemetria.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import hu.motor.telemetria.R
import hu.motor.telemetria.net.AreaRepository
import hu.motor.telemetria.net.ObservedStopRepository.Stop
import hu.motor.telemetria.util.Fmt
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/** A kézi javítás megmarad a telefonon, és felülírja az automatikus becslést. */
class StopMarkers(private val context: Context, private val map: MapView) {
    private val markers = mutableListOf<Marker>()
    private val prefs = context.getSharedPreferences("observed_stop_types", Context.MODE_PRIVATE)
    private var stops = emptyList<Stop>()
    private val density = context.resources.displayMetrics.density
    private val pins = HashMap<String, Pin>()
    private val labels = linkedMapOf("ROAD" to "Forgalomban állás", "SIGNAL" to "Lámpánál állás", "SHOP" to "Bolt / bevásárlás",
        "FUEL" to "Tankolás", "PARKING" to "Út melletti megállás", "PLACE" to "Más hely felkeresése", "OTHER" to "Ismeretlen megállás")

    /** Az ikon és alatta a kártya; a horgony a kettő találkozásánál van. */
    private class Pin(val drawable: BitmapDrawable, val anchorV: Float)

    fun clear() { markers.forEach { it.closeInfoWindow(); map.overlays.remove(it) }; markers.clear() }

    /**
     * A megállás hossza egy kis lebegő kártyán, a jelölő alatt: koppintás nélkül
     * is látszik, meddig álltunk. A kép jelölőnként és feliratonként egyszer készül.
     */
    private fun pin(key: String, text: String, glyph: () -> Bitmap): Pin {
        // Hosszú túrán minden megállás más hosszú; a gyorsítótár ne nőjön korlátlanul.
        if (pins.size > 300) pins.clear()
        return pins.getOrPut("$key|$text") { compose(glyph(), text) }
    }

    /** A vektoros megállásikon képpé rajzolva. */
    private fun iconGlyph(iconRes: Int): Bitmap {
        val icon = ContextCompat.getDrawable(context, iconRes)!!
        val bitmap = Bitmap.createBitmap(icon.intrinsicWidth, icon.intrinsicHeight, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        icon.draw(Canvas(bitmap))
        return bitmap
    }

    private fun compose(glyph: Bitmap, text: String): Pin {
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = ContextCompat.getColor(context, R.color.on_surface)
        }
        val metrics = label.fontMetrics
        val cardWidth = label.measureText(text) + 12f * density
        val cardHeight = metrics.descent - metrics.ascent + 6f * density
        val gap = 2f * density
        val width = maxOf(glyph.width.toFloat(), cardWidth + 2f * density)
        val height = glyph.height + gap + cardHeight + 2f * density
        val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawBitmap(glyph, (width - glyph.width) / 2, 0f, null)
        val card = RectF((width - cardWidth) / 2, glyph.height + gap, (width + cardWidth) / 2, glyph.height + gap + cardHeight)
        val radius = cardHeight / 2
        canvas.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.surface)
        })
        // Halvány keret, hogy világos térképen se folyjon össze a háttérrel.
        canvas.drawRoundRect(card, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = ContextCompat.getColor(context, R.color.on_surface)
            alpha = 60
        })
        canvas.drawText(text, width / 2, card.centerY() - (metrics.ascent + metrics.descent) / 2, label)
        return Pin(BitmapDrawable(context.resources, bitmap), glyph.height / height)
    }

    fun show(items: List<Stop>) {
        clear()
        stops = items
        for (stop in items) {
            val manual = prefs.getString(stop.id, null)
            val type = manual ?: stop.type
            val title = AreaRepository.byId(stop.areaId)?.name?.takeIf { manual == null }
                ?: labels[type] ?: labels.getValue("OTHER")
            // A megrajzolt terület a saját logóját kapja; a felismert lánc csak az
            // automatikus besorolásnál jelzi magát – a kézi javítás felülírja mindkettőt.
            val area = if (manual == null) AreaRepository.byId(stop.areaId) else null
            val brand = if (manual == null && area == null && type == "SHOP") ShopBadges[stop.brand] else null
            val millis = stop.shownMillis
            val duration = Fmt.stopDuration(millis)
            val text = when {
                area != null -> "${area.name} · $duration"
                brand != null -> "${brand.label} · $duration"
                else -> duration
            }
            val pin = if (area != null) {
                pin("area:${area.id}:${area.logoAt}", text) { ShopBadges.areaDisc(context, area, 36f) }
            } else if (brand != null) pin("brand:${stop.brand}", text) { ShopBadges.disc(context, brand, 36f) }
            else {
                val icon = when (type) {
                    "ROAD" -> R.drawable.ic_stop_road
                    "SIGNAL" -> R.drawable.ic_stop_signal
                    "SHOP" -> R.drawable.ic_stop_shop
                    "FUEL" -> R.drawable.ic_stop_fuel
                    "PARKING" -> R.drawable.ic_stop_parking
                    else -> R.drawable.ic_place_destination
                }
                pin("icon:$icon", text) { iconGlyph(icon) }
            }
            val marker = Marker(map).apply {
                position = GeoPoint(stop.lat, stop.lon)
                setAnchor(Marker.ANCHOR_CENTER, pin.anchorV)
                this.icon = pin.drawable
                this.title = "$title · $duration"
                setOnMarkerClickListener { _, _ ->
                    // Egy területnél az összeadott bent töltött idő az egyetlen adat.
                    val message = if (area != null) "Ennyi időt töltöttél itt ezen az úton, a be- és kilépésekből összeadva."
                    else listOfNotNull("Megálltál: ${Fmt.timeOnly(stop.startedAt)}",
                        stop.name.takeIf { manual == null },
                        if (manual != null) "Kézzel megadott típus." else stop.reason).joinToString("\n")
                    AlertDialog.Builder(context).setTitle("$title · $duration")
                        .setMessage(message)
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
