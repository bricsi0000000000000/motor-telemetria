package hu.motor.telemetria.util

import android.content.Context
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.data.TrackPoint
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A túrát szabványos GPX 1.1 fájlba írja, hogy bármelyik térképes alkalmazás
 * (Strava, Komoot, GPX Viewer, OsmAnd…) beolvassa.
 */
object GpxExporter {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun writeToCache(context: Context, track: Track, points: List<TrackPoint>): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "motortura_${Fmt.fileStamp(track.startTime)}.gpx")

        file.bufferedWriter().use { out ->
            out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            out.newLine()
            out.write(
                """<gpx version="1.1" creator="Motor Telemetria" """ +
                    """xmlns="http://www.topografix.com/GPX/1/1">"""
            )
            out.newLine()
            out.write("  <metadata>")
            out.newLine()
            out.write("    <name>Motortúra ${escape(Fmt.dateTime(track.startTime))}</name>")
            out.newLine()
            out.write("    <time>${isoFormat.format(Date(track.startTime))}</time>")
            out.newLine()
            out.write("  </metadata>")
            out.newLine()
            out.write("  <trk>")
            out.newLine()
            out.write("    <name>Motortúra ${escape(Fmt.dateTime(track.startTime))}</name>")
            out.newLine()

            // Minden szünet új <trkseg>, így nem lesz egyenes vonal a kihagyás fölött.
            var openSegment: Int? = null
            for (point in points) {
                if (openSegment != point.segment) {
                    if (openSegment != null) {
                        out.write("    </trkseg>")
                        out.newLine()
                    }
                    out.write("    <trkseg>")
                    out.newLine()
                    openSegment = point.segment
                }
                out.write(
                    "      <trkpt lat=\"${format(point.lat)}\" lon=\"${format(point.lon)}\">"
                )
                out.newLine()
                if (point.altitude != 0.0) {
                    out.write("        <ele>${String.format(Locale.US, "%.1f", point.altitude)}</ele>")
                    out.newLine()
                }
                out.write("        <time>${isoFormat.format(Date(point.time))}</time>")
                out.newLine()
                out.write("      </trkpt>")
                out.newLine()
            }
            if (openSegment != null) {
                out.write("    </trkseg>")
                out.newLine()
            }

            out.write("  </trk>")
            out.newLine()
            out.write("</gpx>")
            out.newLine()
        }
        return file
    }

    private fun format(value: Double) = String.format(Locale.US, "%.7f", value)

    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
