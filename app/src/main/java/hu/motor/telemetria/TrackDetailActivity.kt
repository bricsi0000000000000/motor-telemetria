package hu.motor.telemetria

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.util.TypedValue
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
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
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.PendingDelete
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.ui.StopMarkers
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

class TrackDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TRACK_ID = "track_id"
    }

    private lateinit var binding: ActivityTrackDetailBinding
    private val dao by lazy { AppDatabase.get(this).trackDao() }

    private var trackId = 0L
    private var track: Track? = null
    private var points: List<TrackPoint> = emptyList()

    /** Az elemzés kiemelt szakaszai a nyomvonalra rajzolva. */
    private val analysisOverlays = mutableListOf<Polyline>()

    /** A listából kiválasztott szakasz kiemelése. */
    private var highlightOverlay: Polyline? = null

    /** Hány pontból készült a betöltött elemzés – ennyi kell az indexek használatához. */
    private var analysisPointCount = -1
    private var stopMarkers: StopMarkers? = null
    private var displayTrack: DisplayTrackRepository.Display? = null
    private val trackOverlays = mutableListOf<Polyline>()
    private var lastAnalysis: TrackAnalysis? = null

    private fun displayLinks(): List<List<GeoPoint>> = if (binding.smartTrack.isChecked && displayTrack != null) displayTrack!!.links
        else DisplayTrackRepository.rawLinks(points.map { PathPoint(it.lat, it.lon, it.segment, it.speedMps, it.accuracy, it.time) })

    private fun redrawTrack() {
        trackOverlays.forEach { binding.map.overlays.remove(it) }
        trackOverlays.clear()
        for (path in DisplayTrackRepository.paths(displayLinks())) {
            val line = Polyline(binding.map).apply {
                setPoints(path)
                outlinePaint.color = ContextCompat.getColor(this@TrackDetailActivity, R.color.track)
                outlinePaint.strokeWidth = 12f
            }
            trackOverlays.add(line)
            binding.map.overlays.add(0, line)
        }
        highlightOverlay?.let { binding.map.overlays.remove(it) }
        highlightOverlay = null
        lastAnalysis?.let { drawAnalysisOverlays(it) }
        binding.map.invalidate()
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
            renderStats(loaded.first)
            renderMap(loaded.second)
            loadAnalysis()
            stopMarkers = StopMarkers(this@TrackDetailActivity, binding.map)
            val pathPoints = points.map { PathPoint(it.lat, it.lon, it.segment, it.speedMps, it.accuracy, it.time) }
            stopMarkers?.show(ObservedStopRepository.detect(pathPoints))
            lifecycleScope.launch { stopMarkers?.show(ObservedStopRepository.load(pathPoints)) }
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

        val limit = summary.limit
        when {
            limit.error != null ->
                addLine(getString(R.string.analysis_limit_failed, limit.error))

            limit.coverage <= 0.0 ->
                addLine(getString(R.string.analysis_limit_unknown))

            limit.aboveShare <= 0.0 ->
                addLine(getString(R.string.analysis_limit_clean))

            else -> {
                addLine(
                    getString(
                        R.string.analysis_limit_above,
                        Fmt.percent(limit.aboveShare),
                        Fmt.distance(limit.aboveDistanceMeters),
                        Fmt.duration(limit.aboveDurationMillis)
                    )
                )
                if (limit.maxOverAtLimitKmh != null) {
                    addLine(
                        getString(
                            R.string.analysis_limit_max,
                            limit.maxOverKmh.toInt(),
                            limit.maxOverAtLimitKmh,
                            limit.maxOverSpeedKmh.toInt()
                        )
                    )
                }
            }
        }
        limit.avgRatio?.let {
            addLine(getString(R.string.analysis_limit_ratio, Fmt.ratio(it)))
        }
        if (limit.coverage > 0.0 && limit.coverage < 0.9) {
            addLine(getString(R.string.analysis_limit_coverage, Fmt.percent(limit.coverage)))
        }

        // --- szakaszok ---
        addGroup(R.string.analysis_group_speeding, analysis.speeding, R.color.speeding) { section ->
            val over = section.maxOverKmh?.toInt() ?: 0
            SectionText(
                title = section.road ?: getString(R.string.section_speeding),
                detail = getString(
                    R.string.section_detail_limit,
                    Fmt.kmh(section.maxKmh),
                    section.limitKmh ?: 0,
                    Fmt.distance(section.distanceMeters)
                ),
                value = "+$over"
            )
        }

        addGroup(R.string.analysis_group_fast, analysis.fast, R.color.brand) { section ->
            SectionText(
                title = section.road ?: getString(R.string.section_fast),
                detail = getString(
                    R.string.section_detail_speed,
                    Fmt.distance(section.distanceMeters),
                    Fmt.duration(section.durationMillis),
                    section.limitKmh?.let { "$it km/h" } ?: getString(R.string.no_data)
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

        if (container.childCount == 0) addLine(getString(R.string.analysis_no_sections))

        analysisPointCount = analysis.pointCount
        drawAnalysisOverlays(analysis)
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
            binding.analysisContainer.addView(row.root)
        }
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

    /** A limit feletti és a meredek szakaszok külön színnel a nyomvonalon. */
    private fun drawAnalysisOverlays(analysis: TrackAnalysis) {
        lastAnalysis = analysis
        analysisOverlays.forEach { binding.map.overlays.remove(it) }
        analysisOverlays.clear()

        // Az indexek a szerveren tárolt pontsorrendre mutatnak: csak akkor
        // rajzolunk, ha ugyanannyi pontunk van, mint amiből az elemzés készült.
        if (analysis.pointCount != points.size) {
            binding.map.invalidate()
            return
        }

        fun overlay(section: AnalysisSection, @ColorRes colorRes: Int, width: Float) {
            val from = section.from.coerceIn(0, points.lastIndex)
            val to = section.to.coerceIn(from, points.lastIndex)
            if (to - from < 1) return
            for (path in DisplayTrackRepository.paths(displayLinks().subList(from, to))) {
                val polyline = Polyline(binding.map).apply {
                    outlinePaint.color = ContextCompat.getColor(this@TrackDetailActivity, colorRes)
                    outlinePaint.strokeWidth = width
                    setPoints(path)
                }
                analysisOverlays.add(polyline)
                binding.map.overlays.add(polyline)
            }
        }

        analysis.climbs.filter { it.steep }.forEach { overlay(it, R.color.climb, 14f) }
        analysis.speeding.forEach { overlay(it, R.color.speeding, 8f) }
        binding.map.invalidate()
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
