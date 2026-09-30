package hu.motor.telemetria.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import hu.motor.telemetria.net.AreaRepository
import hu.motor.telemetria.net.MapArea

/**
 * Kör alakú jelölők: a jelölt üzletláncok márkaszínű jelzője és a webes
 * felületen megrajzolt területek feltöltött logója.
 *
 * Saját rajz, nem a lánc logója: azok védjegyoltalom alatt állnak. A korong
 * színe és a betűjel azonosít, a bolt neve a mellette lévő feliratra kerül.
 * Ugyanez a jelölő kerül a megállásokra és az állandó boltrétegre is, hogy a
 * két nézet ugyanarra a boltra ne mondjon mást.
 */
object ShopBadges {

    class Brand(val mark: String, val disc: Int, val ink: Int, val label: String)

    private val brands = mapOf(
        "lidl" to Brand("L", 0xFFFFF000.toInt(), 0xFFE3000F.toInt(), "Lidl"),
        "spar" to Brand("S", 0xFFE30613.toInt(), 0xFFFFFFFF.toInt(), "Spar"),
        "aldi" to Brand("A", 0xFF00005F.toInt(), 0xFFFFFFFF.toInt(), "Aldi"),
        "penny" to Brand("P", 0xFFC8102E.toInt(), 0xFFFFFFFF.toInt(), "Penny"),
        "obi" to Brand("OBI", 0xFFFF7E00.toInt(), 0xFFFFFFFF.toInt(), "OBI"),
        "arkad" to Brand("Á", 0xFF1F3C88.toInt(), 0xFFFFFFFF.toInt(), "Árkád"),
        "etopark" to Brand("ETO", 0xFF009640.toInt(), 0xFFFFFFFF.toInt(), "ETO Park"),
        "mall" to Brand("PL", 0xFF7C3AED.toInt(), 0xFFFFFFFF.toInt(), "Pláza")
    )

    operator fun get(key: String?): Brand? = key?.let { brands[it] }

    /** A megállástípusok színei – ugyanazok, mint a webes felületen. */
    private val kindColours = mapOf(
        "SHOP" to 0xFF16A34A.toInt(),
        "FUEL" to 0xFFDC2626.toInt(),
        "PLACE" to 0xFFDB2777.toInt(),
        "PARKING" to 0xFF2563EB.toInt(),
        "SIGNAL" to 0xFF475569.toInt(),
        "SIGNAL_QUEUE" to 0xFFF59E0B.toInt(),
        "ROAD" to 0xFFF59E0B.toInt()
    )

    fun kindColour(kind: String): Int = kindColours[kind] ?: 0xFF64748B.toInt()

    /**
     * Egy megrajzolt terület jelölője: a feltöltött logó körben, vagy ha nincs
     * (még), a név kezdőbetűje a típus színén.
     */
    fun areaDisc(context: Context, area: MapArea, sizeDp: Float): Bitmap {
        AreaRepository.logo(context, area)?.let { return logoDisc(context, it, sizeDp) }
        val initial = area.name.trim().take(1).uppercase().ifEmpty { "?" }
        return disc(context, Brand(initial, kindColour(area.kind), 0xFFFFFFFF.toInt(), area.name), sizeDp)
    }

    /**
     * Feltöltött logó körben: fehér gyűrű, a kép középre vágva és körre
     * metszve – pontosan úgy, ahogy a webes felület előnézete mutatja.
     */
    fun logoDisc(context: Context, logo: Bitmap, sizeDp: Float): Bitmap {
        val size = sizeDp * context.resources.displayMetrics.density
        val ring = size / 24f
        val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2
        canvas.drawCircle(centre, centre, centre, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        // Árnyalóval rajzolva a kör széle élsimított marad (a clipPath nem az).
        val side = minOf(logo.width, logo.height).toFloat()
        val scale = (size - 2 * ring) / side
        val shader = BitmapShader(logo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(ring - (logo.width - side) / 2f * scale, ring - (logo.height - side) / 2f * scale)
            })
        }
        canvas.drawCircle(centre, centre, centre - ring, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        })
        return bitmap
    }

    /** Fehér gyűrű, márkaszínű korong, benne a betűjel. */
    fun disc(context: Context, brand: Brand, sizeDp: Float): Bitmap {
        val size = sizeDp * context.resources.displayMetrics.density
        val ring = size / 24f
        val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val centre = size / 2
        canvas.drawCircle(centre, centre, centre, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
        canvas.drawCircle(centre, centre, centre - ring, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand.disc })
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            color = brand.ink
            textSize = size * 0.56f
        }
        // A hosszabb betűjel (OBI, ETO) is férjen el a korongon belül.
        mark.textSize *= minOf(1f, (size - 6f * ring) / mark.measureText(brand.mark))
        val metrics = mark.fontMetrics
        canvas.drawText(brand.mark, centre, centre - (metrics.ascent + metrics.descent) / 2, mark)
        return bitmap
    }
}
