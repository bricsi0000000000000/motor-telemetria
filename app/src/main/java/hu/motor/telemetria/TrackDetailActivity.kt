package hu.motor.telemetria

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import androidx.lifecycle.lifecycleScope
import hu.motor.telemetria.analysis.AnalysisRepository
import hu.motor.telemetria.analysis.AnalysisSection
import hu.motor.telemetria.analysis.TrackAnalysis
import hu.motor.telemetria.analysis.TelemetryMath
import hu.motor.telemetria.analysis.SpeedBand
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.PendingDelete
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.ui.StopMarkers
import hu.motor.telemetria.net.AreaRepository
import hu.motor.telemetria.net.ObservedStopRepository
import hu.motor.telemetria.net.DisplayTrackRepository
import hu.motor.telemetria.service.PathPoint
import hu.motor.telemetria.data.TrackPoint
import hu.motor.telemetria.databinding.ActivityTrackDetailBinding
import hu.motor.telemetria.databinding.ItemSectionBinding
import hu.motor.telemetria.sync.SyncManager
import hu.motor.telemetria.util.Fmt
import hu.motor.telemetria.util.GpxExporter
import hu.motor.telemetria.util.ThemeSettings
import hu.motor.telemetria.widget.StatsWidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import kotlin.math.ceil
import kotlin.math.roundToInt

class TrackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK_ID = "track_id"
    }

    private lateinit var binding: ActivityTrackDetailBinding
    private val dao by lazy { AppDatabase.get(this).trackDao() }

    private var trackId = 0L
    private var track: Track? = null
    private var points: List<TrackPoint> = emptyList()

    /** A listából kiválasztott szakasz kiemelése. */
    private var highlightOverlay: Polyline? = null

    /** Hány pontból készült a betöltött elemzés – ennyi kell az indexek használatához. */
    private var analysisPointCount = -1
    private var stopMarkers: StopMarkers? = null
    private var displayTrack: DisplayTrackRepository.Display? = null
    private val trackOverlays = mutableListOf<Polyline>()
    private val speedSectionMarkers = mutableListOf<Marker>()
    private val speedCardCache = mutableMapOf<SpeedCardKey, BitmapDrawable>()

    private data class SpeedSection(
        @ColorInt val color: Int,
        val path: List<GeoPoint>,
        val minKmh: Int,
        val maxKmh: Int
    )

    private data class SpeedCardKey(@ColorInt val color: Int, val minKmh: Int, val maxKmh: Int)

    private fun displayLinks(): List<List<GeoPoint>> = if (binding.smartTrack.isChecked && displayTrack != null) displayTrack!!.links
        else DisplayTrackRepository.rawLinks(points.map { PathPoint(it.lat, it.lon, it.segment, it.speedMps, it.accuracy, it.time) })

    private fun redrawTrack() {
        trackOverlays.forEach { binding.map.overlays.remove(it) }
        trackOverlays.clear()
        speedSectionMarkers.forEach { binding.map.overlays.remove(it) }
        speedSectionMarkers.clear()

        for (section in speedSections()) {
            val line = Polyline(binding.map).apply {
                setPoints(section.path)
                outlinePaint.color = section.color
                outlinePaint.strokeWidth = 12f
            }
            trackOverlays.add(line)
            binding.map.overlays.add(0, line)

            val marker = Marker(binding.map).apply {
                position = section.path[section.path.size / 2]
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = speedSectionCard(section)
                title = getString(
                    R.string.speed_section_min_max,
                    section.minKmh,
                    section.maxKmh
                )
                setInfoWindow(null)
            }
            speedSectionMarkers += marker
            binding.map.overlays.add(marker)
        }
        highlightOverlay?.let { binding.map.overlays.remove(it) }
        highlightOverlay = null
        binding.map.invalidate()
    }

    /** Az útra illesztett kapcsolat az eredeti pontpár sebességét tartja meg. */
    private fun speedSections(): List<SpeedSection> {
        val result = mutableListOf<SpeedSection>()
        var activeColor: Int? = null
        var active = mutableListOf<GeoPoint>()
        var activeMinKmh = Int.MAX_VALUE
        var activeMaxKmh = Int.MIN_VALUE

        fun flush() {
            activeColor?.let { color ->
                if (active.size > 1 && activeMinKmh != Int.MAX_VALUE) {
                    result += SpeedSection(
                        color = color,
                        path = active.toList(),
                        minKmh = activeMinKmh,
                        maxKmh = activeMaxKmh
                    )
                }
            }
            activeColor = null
            active = mutableListOf()
            activeMinKmh = Int.MAX_VALUE
            activeMaxKmh = Int.MIN_VALUE
        }

        displayLinks().forEachIndexed { index, link ->
            if (link.isEmpty() || index + 1 >= points.size) {
                flush()
                return@forEachIndexed
            }
            val speedKmh = (points[index + 1].speedMps * 3.6f).coerceAtLeast(0f).roundToInt()
            val color = speedColor(points[index + 1].speedMps)
            if (activeColor != color) flush()
            if (active.isEmpty()) active.addAll(link)
            else active.addAll(link.drop(if (active.last() == link.first()) 1 else 0))
            activeColor = color
            activeMinKmh = minOf(activeMinKmh, speedKmh)
            activeMaxKmh = maxOf(activeMaxKmh, speedKmh)
        }
        flush()
        return result
    }

    /** Mindig látható, a szakasz fölött lebegő min/max sebességkártya. */
    private fun speedSectionCard(section: SpeedSection): BitmapDrawable {
        val key = SpeedCardKey(section.color, section.minKmh, section.maxKmh)
        return speedCardCache.getOrPut(key) {
            val density = resources.displayMetrics.density
            val scaledDensity = resources.displayMetrics.scaledDensity
            val label = getString(
                R.string.speed_section_min_max,
                section.minKmh,
                section.maxKmh
            )
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(this@TrackDetailActivity, R.color.on_surface)
                textSize = 11f * scaledDensity
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val horizontalPadding = 10f * density
            val verticalPadding = 7f * density
            val dotRadius = 3.5f * density
            val dotGap = 7f * density
            val pointerHeight = 6f * density
            val shadowPadding = 3f * density
            val textMetrics = textPaint.fontMetrics
            val textHeight = textMetrics.descent - textMetrics.ascent
            val cardWidth = horizontalPadding * 2 + dotRadius * 2 + dotGap + textPaint.measureText(label)
            val cardHeight = verticalPadding * 2 + textHeight
            val bitmap = Bitmap.createBitmap(
                ceil(cardWidth + shadowPadding * 2).toInt(),
                ceil(cardHeight + pointerHeight + shadowPadding * 2).toInt(),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            val card = RectF(
                shadowPadding,
                shadowPadding,
                shadowPadding + cardWidth,
                shadowPadding + cardHeight
            )
            val cornerRadius = 9f * density
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(this@TrackDetailActivity, R.color.surface)
                setShadowLayer(2.5f * density, 0f, 1.5f * density, 0x55000000)
            }
            canvas.drawRoundRect(card, cornerRadius, cornerRadius, fillPaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = section.color
                style = Paint.Style.STROKE
                strokeWidth = 2f * density
            }
            canvas.drawRoundRect(card, cornerRadius, cornerRadius, borderPaint)

            val pointerX = bitmap.width / 2f
            val pointer = Path().apply {
                moveTo(pointerX - 5f * density, card.bottom - density)
                lineTo(pointerX + 5f * density, card.bottom - density)
                lineTo(pointerX, card.bottom + pointerHeight)
                close()
            }
            canvas.drawPath(pointer, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = section.color })

            val dotX = card.left + horizontalPadding + dotRadius
            val centreY = card.centerY()
            canvas.drawCircle(
                dotX,
                centreY,
                dotRadius,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = section.color }
            )
            val baseline = centreY - (textMetrics.ascent + textMetrics.descent) / 2f
            canvas.drawText(label, dotX + dotRadius + dotGap, baseline, textPaint)
            BitmapDrawable(resources, bitmap)
        }
    }

    @ColorInt
    private fun speedColor(speedMps: Float): Int {
        val resource = when (TelemetryMath.speedBand(speedMps)) {
            SpeedBand.STOPPED -> R.color.speed_0
            SpeedBand.GREEN -> R.color.speed_10
            SpeedBand.YELLOW -> R.color.speed_30
            SpeedBand.ORANGE -> R.color.speed_50
            SpeedBand.LIGHT_BLUE -> R.color.speed_69
            SpeedBand.DARK_BLUE -> R.color.speed_90
            SpeedBand.PINK -> R.color.speed_120
            SpeedBand.PURPLE -> R.color.speed_over_120
        }
        return ContextCompat.getColor(this, resource)
    }

    private lateinit var sheet: BottomSheetBehavior<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrackDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        trackId = intent.getLongExtra(EXTRA_TRACK_ID, 0L)
        if (trackId == 0L) {
            finish()
            return
        }

        with(binding.map) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            if (ThemeSettings.isNight(this@TrackDetailActivity)) {
                overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            }
        }

        setUpBottomSheet()
        setUpSpeedLegend()

        binding.btnExport.setOnClickListener { exportGpx() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnAnalysisRefresh.setOnClickListener { loadAnalysis(force = true) }

        binding.smartTrack.setOnCheckedChangeListener { _, checked ->
            binding.trackDisplayStatus.text = if (!checked) "Eredeti, változatlan GPS-nyomvonal."
                else "Motorozás útra illesztve; séta, megállás és bizonytalan részek eredeti GPS szerint."
            redrawTrack()
        }
        load()
    }

    private fun setUpSpeedLegend() {
        val bands = listOf(
            R.color.speed_0 to "0",
            R.color.speed_10 to "1–10",
            R.color.speed_30 to "11–30",
            R.color.speed_50 to "31–50",
            R.color.speed_69 to "51–69",
            R.color.speed_90 to "70–90",
            R.color.speed_120 to "91–120",
            R.color.speed_over_120 to "120+"
        )
        binding.tvSpeedLegend.text = SpannableStringBuilder().apply {
            bands.forEachIndexed { index, (colorRes, label) ->
                if (index > 0) append("  ")
                val start = length
                append("●")
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this@TrackDetailActivity, colorRes)),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                append(" $label")
            }
            append(" km/h")
        }
    }

    /** A lap alaphelyzetben félig van felhúzva, és kézzel bármeddig húzható. */
    private fun setUpBottomSheet() {
        sheet = BottomSheetBehavior.from(binding.bottomSheet)
        sheet.isFitToContents = false
        sheet.halfExpandedRatio = 0.45f
        sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    /** A lap lejjebb húzása, hogy a térképen látszódjon, amit épp megnéznénk. */
    private fun revealMap() {
        if (sheet.state == BottomSheetBehavior.STATE_EXPANDED) {
            sheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    private fun load() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val t = dao.getTrack(trackId) ?: return@withContext null
                t to dao.getPoints(trackId)
            }
            if (loaded == null) {
                finish()
                return@launch
            }
            track = loaded.first
            points = loaded.second
            val telemetry = withContext(Dispatchers.IO) { dao.getTelemetry(trackId) }
            binding.telemetryChart.setSamples(telemetry)
            binding.tvTelemetryChartTitle.visibility = if (telemetry.isEmpty()) View.GONE else View.VISIBLE
            renderStats(loaded.first)
            renderMap(loaded.second)
            loadAnalysis()
            stopMarkers = StopMarkers(this@TrackDetailActivity, binding.map)
            val pathPoints = points.map { PathPoint(it.lat, it.lon, it.segment, it.speedMps, it.accuracy, it.time) }
            // A megrajzolt területek kellenek a megállások nevéhez és logójához.
            AreaRepository.loadCached(applicationContext)
            stopMarkers?.show(ObservedStopRepository.detect(pathPoints))
            lifecycleScope.launch {
                AreaRepository.refresh(applicationContext)
                stopMarkers?.show(ObservedStopRepository.load(pathPoints))
            }
            binding.trackDisplayStatus.text = "Útra illesztés ellenőrzése… Az eredeti GPS-vonal már látható."
            displayTrack = DisplayTrackRepository.load(this@TrackDetailActivity,
                points.map { PathPoint(it.lat, it.lon, it.segment, it.speedMps, it.accuracy, it.time) })
            binding.trackDisplayStatus.text = if (!binding.smartTrack.isChecked) "Eredeti, változatlan GPS-nyomvonal."
                else if (displayTrack!!.offline) "Részleges vagy offline nézet: a nem illeszthető részek eredeti GPS szerint maradnak."
                else "${displayTrack!!.matched} motoros szakasz útra illesztve. Séta és megállás: eredeti GPS."
            redrawTrack()
        }
    }

    private fun renderStats(track: Track) {
        supportActionBar?.title = Fmt.dateTime(track.startTime)
        binding.tvDistance.text = Fmt.distance(track.distanceMeters)
        binding.tvDuration.text = Fmt.duration(track.durationMillis)
        binding.tvMovingTime.text = Fmt.duration(track.movingMillis)
        binding.tvAvgSpeed.text = Fmt.speedKmhUnit(track.avgSpeedMps)
        binding.tvMaxSpeed.text = Fmt.speedKmhUnit(track.maxSpeedMps)
        binding.tvElevGain.text = Fmt.meters(track.elevationGainMeters)
    }

    private fun renderMap(points: List<TrackPoint>) {
        if (points.isEmpty()) return

        redrawTrack()

        addMarker(points.first(), R.drawable.ic_start_flag)
        addMarker(points.last(), R.drawable.ic_finish_flag)

        val bounds = BoundingBox.fromGeoPointsSafe(points.map { GeoPoint(it.lat, it.lon) })
        binding.map.post { fitBounds(bounds, animated = false) }
    }

    /**
     * A nyomvonal a lap fölötti, szabadon maradó sávba kerüljön.
     *
     * Illesztés után a térkép közepét arra a pontra tesszük, ami eddig a látható
     * sáv közepén volt – így a lap nem takarja el a nyomvonal alját.
     */
    private fun fitBounds(bounds: BoundingBox, animated: Boolean) {
        val map = binding.map
        map.zoomToBoundingBox(bounds, false, 90)
        map.post {
            val hidden = (map.height * sheet.halfExpandedRatio).toInt()
            if (hidden <= 0) return@post
            val visibleCentre = map.projection.fromPixels(map.width / 2, (map.height + hidden) / 2)
            val target = GeoPoint(visibleCentre.latitude, visibleCentre.longitude)
            if (animated) map.controller.animateTo(target) else map.controller.setCenter(target)
        }
    }

    private fun addMarker(point: TrackPoint, iconRes: Int) {
        val marker = Marker(binding.map)
        marker.position = GeoPoint(point.lat, point.lon)
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        marker.icon = ContextCompat.getDrawable(this, iconRes)
        marker.setInfoWindow(null)
        binding.map.overlays.add(marker)
    }

    // --- elemzés ---------------------------------------------------------------

    private fun loadAnalysis(force: Boolean = false) {
        val current = track ?: return
        binding.btnAnalysisRefresh.isEnabled = false
        binding.tvAnalysisState.setText(
            if (force) R.string.analysis_recalculating else R.string.analysis_loading
        )

        lifecycleScope.launch {
            val result = AnalysisRepository.load(this@TrackDetailActivity, current, force)
            binding.btnAnalysisRefresh.isEnabled = true
            result
                .onSuccess { render(it) }
                .onFailure { error ->
                    binding.analysisContainer.removeAllViews()
                    binding.tvAnalysisState.text = when (error) {
                        is AnalysisRepository.NotSyncedException -> getString(R.string.analysis_not_synced)
                        else -> getString(R.string.analysis_error, error.message.orEmpty())
                    }
                }
        }
    }

    private fun render(analysis: TrackAnalysis) {
        val summary = analysis.summary
        val container = binding.analysisContainer
        container.removeAllViews()

        if (summary == null) {
            binding.tvAnalysisState.setText(R.string.analysis_too_short)
            return
        }

        binding.tvAnalysisState.text = getString(
            R.string.analysis_updated,
            Fmt.dateTime(analysis.computedAt)
        )

        // --- összegzés ---
        addLine(
            getString(
                R.string.analysis_elevation,
                Fmt.meters(summary.elevationGainMeters),
                Fmt.meters(summary.elevationLossMeters),
                Fmt.grade(summary.steepestClimbPercent)
            )
        )

        summary.telemetry?.let { telemetry ->
            addLine(
                getString(
                    R.string.analysis_telemetry_summary,
                    telemetry.validSampleCount,
                    Fmt.percent(telemetry.qualityCoverage),
                    telemetry.maxAccelerationMps2,
                    telemetry.maxBrakingMps2
                )
            )
            if (!telemetry.personalizationEligible) {
                addLine(getString(R.string.analysis_telemetry_learning))
            }
            addLine(
                getString(
                    R.string.analysis_telemetry_detail,
                    telemetry.maxLeanDegrees,
                    Fmt.meters(telemetry.fusedElevationGainMeters),
                    telemetry.roughEventCount,
                    telemetry.incidentCandidateCount
                )
            )
        }

        // --- szakaszok ---
        addGroup(R.string.analysis_group_fast, analysis.fast, R.color.brand) { section ->
            SectionText(
                title = section.road ?: getString(R.string.section_fast),
                detail = getString(
                    R.string.section_detail_speed,
                    Fmt.distance(section.distanceMeters),
                    Fmt.duration(section.durationMillis)
                ),
                value = Fmt.kmh(section.avgKmh)
            )
        }

        addGroup(R.string.analysis_group_climbs, analysis.climbs, R.color.climb) { section ->
            SectionText(
                title = section.road ?: getString(R.string.section_climb),
                detail = getString(
                    R.string.section_detail_grade,
                    Fmt.grade(section.gradePercent),
                    Fmt.distance(section.distanceMeters),
                    Fmt.kmh(section.avgKmh)
                ),
                value = "+${Fmt.meters(section.elevationDeltaMeters)}"
            )
        }

        addGroup(R.string.analysis_group_descents, analysis.descents, R.color.position) { section ->
            SectionText(
                title = section.road ?: getString(R.string.section_descent),
                detail = getString(
                    R.string.section_detail_grade,
                    Fmt.grade(section.gradePercent),
                    Fmt.distance(section.distanceMeters),
                    Fmt.kmh(section.avgKmh)
                ),
                value = Fmt.meters(section.elevationDeltaMeters)
            )
        }

        addGroup(R.string.analysis_group_slow, analysis.slow, R.color.warning) { section ->
            SectionText(
                title = section.road ?: getString(R.string.section_slow),
                detail = getString(
                    R.string.section_detail_slow,
                    Fmt.duration(section.durationMillis),
                    Fmt.distance(section.distanceMeters)
                ),
                value = Fmt.kmh(section.avgKmh)
            )
        }

        fun telemetryText(section: AnalysisSection, title: String, suffix: String) = SectionText(
            title = title,
            detail = getString(
                R.string.analysis_telemetry_event_detail,
                Fmt.dateTime(section.startTime),
                Fmt.kmh(section.avgKmh),
                Fmt.percent(section.mountQuality ?: 0.0)
            ),
            value = String.format("%.1f %s", section.telemetryValue ?: 0.0, suffix)
        )
        addGroup(R.string.analysis_group_braking, analysis.braking, R.color.speeding) {
            telemetryText(it, getString(R.string.analysis_group_braking), "m/s²")
        }
        addGroup(R.string.analysis_group_acceleration, analysis.acceleration, R.color.limit_50) {
            telemetryText(it, getString(R.string.analysis_group_acceleration), "m/s²")
        }
        addGroup(R.string.analysis_group_roughness, analysis.roughness, R.color.limit_90) {
            telemetryText(it, getString(R.string.analysis_group_roughness), "m/s²")
        }
        addGroup(
            R.string.analysis_group_incidents,
            analysis.incidents,
            R.color.climb,
            onLongClick = ::labelIncident
        ) {
            val label = eventLabelText(it.eventLabel) ?: getString(R.string.event_label_hold_hint)
            telemetryText(it, "${getString(R.string.analysis_group_incidents)} · $label", "")
        }

        if (container.childCount == 0) addLine(getString(R.string.analysis_no_sections))

        analysisPointCount = analysis.pointCount
    }

    private class SectionText(val title: String, val detail: String, val value: String)

    private fun addLine(text: String) {
        val view = TextView(this)
        view.text = text
        view.textSize = 13f
        view.setPadding(0, 6, 0, 0)
        binding.analysisContainer.addView(view)
    }

    private fun addGroup(
        @StringRes titleRes: Int,
        sections: List<AnalysisSection>,
        @ColorRes colorRes: Int,
        onLongClick: ((AnalysisSection) -> Unit)? = null,
        describe: (AnalysisSection) -> SectionText
    ) {
        if (sections.isEmpty()) return

        val header = TextView(this)
        header.setText(titleRes)
        header.textSize = 12f
        header.alpha = 0.6f
        header.setPadding(0, 16, 0, 0)
        binding.analysisContainer.addView(header)

        val inflater = LayoutInflater.from(this)
        for (section in sections) {
            val row = ItemSectionBinding.inflate(inflater, binding.analysisContainer, false)
            val text = describe(section)
            row.tvSectionTitle.text = text.title
            row.tvSectionDetail.text = text.detail
            row.tvSectionValue.text = text.value
            val color = ContextCompat.getColor(this, colorRes)
            row.marker.backgroundTintList = ColorStateList.valueOf(color)
            row.tvSectionValue.setTextColor(color)
            // Koppintásra a térkép odaugrik és kiemeli a szakaszt.
            row.root.isClickable = true
            row.root.setBackgroundResource(selectableItemBackground)
            row.root.setOnClickListener { focusSection(section, color) }
            if (onLongClick != null && section.eventId != null) {
                row.root.setOnLongClickListener {
                    onLongClick(section)
                    true
                }
            }
            binding.analysisContainer.addView(row.root)
        }
    }

    private fun labelIncident(section: AnalysisSection) {
        val current = track ?: return
        val eventId = section.eventId ?: return
        val labels = arrayOf(
            getString(R.string.event_label_real),
            getString(R.string.event_label_pothole),
            getString(R.string.event_label_phone),
            getString(R.string.event_label_false)
        )
        val values = arrayOf("REAL_EVENT", "POTHOLE", "PHONE_MOVED", "FALSE_POSITIVE")
        AlertDialog.Builder(this)
            .setTitle(R.string.event_label_title)
            .setItems(labels) { _, which ->
                lifecycleScope.launch {
                    AnalysisRepository.labelIncident(
                        this@TrackDetailActivity, current, eventId, values[which]
                    ).onSuccess(::render).onFailure { error ->
                        Toast.makeText(
                            this@TrackDetailActivity,
                            getString(R.string.event_label_error, error.message.orEmpty()),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun eventLabelText(label: String?): String? = when (label) {
        "REAL_EVENT" -> getString(R.string.event_label_real)
        "POTHOLE" -> getString(R.string.event_label_pothole)
        "PHONE_MOVED" -> getString(R.string.event_label_phone)
        "FALSE_POSITIVE" -> getString(R.string.event_label_false)
        else -> null
    }

    /** A koppintható sorok visszajelzése (a téma szerinti hullámeffekt). */
    private val selectableItemBackground: Int
        get() = TypedValue().let { value ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            value.resourceId
        }

    /**
     * A listában kiválasztott szakasz megmutatása: a térkép ráközelít, a szakasz
     * pedig vastag vonallal kiemelve marad, amíg másikat nem választunk.
     */
    private fun focusSection(section: AnalysisSection, color: Int) {
        if (points.isEmpty()) return

        highlightOverlay?.let { binding.map.overlays.remove(it) }
        highlightOverlay = null

        // Az indexek csak akkor érvényesek, ha az elemzés ugyanennyi pontból készült;
        // egyébként a szakasz kezdőpontjára ugrunk.
        val geoPoints = if (analysisPointCount == points.size) {
            val from = section.from.coerceIn(0, points.lastIndex)
            val to = section.to.coerceIn(from, points.lastIndex)
            DisplayTrackRepository.paths(displayLinks().subList(from, to)).maxByOrNull { it.size }
                ?: listOf(GeoPoint(points[from].lat, points[from].lon))
        } else {
            listOf(GeoPoint(section.lat, section.lon))
        }

        if (geoPoints.size > 1) {
            val highlight = Polyline(binding.map).apply {
                outlinePaint.color = color
                outlinePaint.strokeWidth = 22f
                outlinePaint.alpha = 160
            }
            geoPoints.forEach { highlight.addPoint(it) }
            highlightOverlay = highlight
            binding.map.overlays.add(highlight)

            val bounds = BoundingBox.fromGeoPointsSafe(geoPoints)
            binding.map.post { fitBounds(bounds, animated = true) }
        } else {
            binding.map.controller.setZoom(16.0)
            binding.map.controller.animateTo(geoPoints.first())
        }

        binding.map.invalidate()
        revealMap()
    }

    // --- műveletek -------------------------------------------------------------

    private fun exportGpx() {
        val current = track ?: return
        if (points.isEmpty()) {
            Toast.makeText(this, R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                GpxExporter.writeToCache(this@TrackDetailActivity, current, points)
            }
            val uri = FileProvider.getUriForFile(
                this@TrackDetailActivity,
                "${BuildConfig.APPLICATION_ID}.fileprovider",
                file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.action_export)))
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, Fmt.dateTime(track?.startTime ?: 0L)))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // A szerveren is törlődjön; ha most nincs net, a jelzés kivár.
                        dao.addPendingDelete(PendingDelete(trackId))
                        dao.deleteTrack(trackId)
                        java.io.File(filesDir, "telemetry_clips").listFiles()
                            ?.filter { it.name.startsWith("track-$trackId-") }
                            ?.forEach { it.delete() }
                    }
                    SyncManager.requestSync()
                    StatsWidgetRenderer.refresh(this@TrackDetailActivity)
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        binding.map.onDetach()
        super.onDestroy()
    }
}
