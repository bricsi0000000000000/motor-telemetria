package hu.motor.telemetria.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Egységes megjelenítés a képernyőkön és az értesítésben. */
object Fmt {

    private val hu = Locale("hu", "HU")

    /** "842 m" vagy "12,43 km" */
    fun distance(meters: Double): String =
        if (meters < 1000.0) "${meters.roundToInt()} m"
        else String.format(hu, "%.2f km", meters / 1000.0)

    /** Csak a szám, mértékegység nélkül – a layout írja ki a "km/h"-t. */
    fun speedKmh(mps: Float): String = String.format(hu, "%.1f", mps * 3.6f)

    fun speedKmhUnit(mps: Float): String = "${speedKmh(mps)} km/h"

    /** "1:23:45" vagy "23:45" */
    fun duration(millis: Long): String {
        val totalSeconds = millis / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(hu, "%d:%02d:%02d", h, m, s)
        else String.format(hu, "%02d:%02d", m, s)
    }

    /** Megállás hossza a térképi kártyán: "8 mp", "05:03", "1:05:03". */
    fun stopDuration(millis: Long): String {
        val seconds = (millis / 1000).coerceAtLeast(0)
        return if (seconds < 60) "$seconds mp" else duration(millis)
    }

    fun meters(value: Double): String = "${value.roundToInt()} m"

    /** Az elemzés eleve km/h-ban számol, itt már csak kiírni kell. */
    fun kmh(value: Double): String = String.format(hu, "%.0f km/h", value)

    /** 0,12 → "12%" */
    fun percent(share: Double): String = String.format(hu, "%.0f%%", share * 100)

    /** Lejtő/emelkedő meredeksége: 3,14 → "3,1%" */
    fun grade(percent: Double): String = String.format(hu, "%.1f%%", percent)

    /** A limithez mért tempó: 0,92 → "0,92×" */
    fun ratio(value: Double): String = String.format(hu, "%.2f×", value)

    /** Összesített idő emberi léptékben: "3 nap 4 ó", "12 ó 30 p", "45 p". */
    fun longDuration(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes % (60 * 24)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "$days nap $hours ó"
            hours > 0 -> "$hours ó $minutes p"
            else -> "$minutes p"
        }
    }

    private val monthNames = arrayOf(
        "január", "február", "március", "április", "május", "június",
        "július", "augusztus", "szeptember", "október", "november", "december"
    )

    /** A "2026-08" alakú kulcsból "2026. augusztus". */
    fun monthLabel(key: String): String {
        val parts = key.split("-")
        val year = parts.getOrNull(0) ?: return key
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return key
        return "$year. ${monthNames.getOrElse(month - 1) { "" }}"
    }

    /** Naptári nap kulcsa a csoportosításhoz (helyi idő szerint). */
    fun dayKey(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", hu).format(Date(timestamp))

    /** "2026. augusztus 15., szombat" */
    fun dayLabel(timestamp: Long): String =
        SimpleDateFormat("yyyy. MMMM d., EEEE", hu).format(Date(timestamp))

    /** Csak az óra:perc – a napi fejléc alatt ennyi is elég. */
    fun timeOnly(timestamp: Long): String =
        SimpleDateFormat("HH:mm", hu).format(Date(timestamp))

    fun dateTime(timestamp: Long): String =
        SimpleDateFormat("yyyy. MM. dd. HH:mm", hu).format(Date(timestamp))

    /** Rövid dátum a widgethez: "08. 14." */
    fun shortDate(timestamp: Long): String =
        SimpleDateFormat("MM. dd.", hu).format(Date(timestamp))

    /** "3 perce", "2 órája", "5 napja" – a rétegek frissességéhez. */
    fun relativeTime(timestamp: Long): String {
        if (timestamp <= 0L) return "még sosem"
        val elapsed = System.currentTimeMillis() - timestamp
        val minutes = elapsed / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "az imént"
            minutes < 60 -> "$minutes perce"
            hours < 24 -> "$hours órája"
            days < 30 -> "$days napja"
            else -> dateTime(timestamp)
        }
    }

    fun fileStamp(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmm", hu).format(Date(timestamp))
}
