package hu.motor.telemetria.ui

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.motor.telemetria.MainActivity
import hu.motor.telemetria.R
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.PlaceType
import hu.motor.telemetria.data.RoadPoint
import hu.motor.telemetria.data.RoadPointKind
import hu.motor.telemetria.databinding.FragmentMapBinding
import hu.motor.telemetria.net.AreaRepository
import hu.motor.telemetria.net.ObservedStopRepository
import hu.motor.telemetria.net.DisplayTrackRepository
import hu.motor.telemetria.service.PathBuffer
import hu.motor.telemetria.service.TrackingService
import hu.motor.telemetria.service.TrackingState
import hu.motor.telemetria.service.TrackingStatus
import hu.motor.telemetria.databinding.DialogRoadLayersBinding
import hu.motor.telemetria.net.RoadDataMeta
import hu.motor.telemetria.net.RoadDataRepository
import hu.motor.telemetria.net.SpeedLimitLayer
import hu.motor.telemetria.net.SpeedSegment
import hu.motor.telemetria.util.Fmt
import hu.motor.telemetria.util.ThemeSettings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * A főképernyő: élő térkép, műszerfal és a mérés vezérlőgombjai.
 */
class MapFragment : Fragment() {

    companion object {
        /** Ennyi ideig bízunk még a GPS fixben, mielőtt hálózati helyzetre váltanánk. */
        private const val GPS_TRUST_MS = 15_000L

        /** Ez alatt a nagyítás alatt a sebességhatárok összefolynának. */
        private const val LIMITS_MIN_ZOOM = 14.0

        /** A lámpás kereszteződésekből sok van: csak közelebbről rajzoljuk. */
        private const val SIGNALS_MIN_ZOOM = 15.0

        /** A boltokból is sok van; távolról csak a vonal számít. */
        private const val SHOPS_MIN_ZOOM = 13.0

        /** A megrajzolt területek körvonala csak közelről, távolról elég a logó. */
        private const val AREA_OUTLINE_MIN_ZOOM = 15.0

        /** Egyszerre ennyi útszakasznál többet nem rajzolunk ki. */
        private const val MAX_DRAWN_SEGMENTS = 1200

        /** A térkép mozgatása után ennyivel rajzolunk újra (ne minden képkockán). */
        private const val REDRAW_DELAY_MS = 250L

        private const val UI_PREFS = "ui_prefs"
        private const val KEY_SHOW_LIMITS = "show_speed_limits"
    }

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    /** Hány pontot rajzoltunk már ki a bufferből. */
    private var drawnPointCount = 0
    private var currentSegment = -1
    private var stopMarkers: StopMarkers? = null
    private val observedStops = mutableListOf<ObservedStopRepository.Stop>()
    private var stopLoading = false
    private var lastStopRequestAt = 0L
    private var displayMatchLoading = false
    private var lastDisplayMatchAt = 0L
    private var lastDisplayMatchCount = 0
    private var displayGeneration = 0
    private val matchedDisplayLinks = mutableMapOf<Int, List<GeoPoint>>()
    private var currentPolyline: Polyline? = null
    private val polylines = mutableListOf<Polyline>()

    private var positionMarker: Marker? = null
    /** A jelölő épp a séta ikonját mutatja-e; null, ha még nincs ikonja. */
    private var positionWalking: Boolean? = null
    private var accuracyCircle: Polygon? = null

    /** Az otthon / munkahely / célpont jelölői és köreik. */
    private val placeOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()

    /** Mérők, lámpák, rendőrök, balesetek jelölői. */
    private val roadPointOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()

    /** A sebességhatár szerint színezett útszakaszok. */
    private val speedOverlays = mutableListOf<Polyline>()

    /** A webes felületen megrajzolt területek: logó körben, közelről a körvonal is. */
    private val areaOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()

    private var roadPoints: List<RoadPoint> = emptyList()
    private var showLimits = false

    /** Egyszerre csak egy területletöltés fusson. */
    private var speedAreaLoading = false

    private val redrawHandler = Handler(Looper.getMainLooper())
    private val redrawRunnable = Runnable {
        drawRoadPoints()
        drawSpeedLimits()
        drawAreas()
    }

    private var followMode = true
    private var initialCentreDone = false
    private var lastGpsFixAt = 0L

    /** Melyik túrához tartozik a most kirajzolt vonal – váltáskor törölni kell. */
    private var renderedTrackId = -1L

    private val locationManager by lazy {
        requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    /**
     * A térképen akkor is látszik a helyzetünk, ha épp nem mérünk. Ez a figyelő
     * csak addig él, amíg a fül látható, tehát háttérben nem fogyaszt.
     */
    private val positionListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onPositionUpdate(location)

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startPositionUpdates()
            beginTracking()
        } else {
            Toast.makeText(requireContext(), R.string.error_no_location_permission, Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpMap()
        setUpButtons()
        observeState()
        observePlaces()
        observeRoadPoints()
        loadAreas()

        showLimits = requireContext()
            .getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_LIMITS, false)
        updateLimitsButton()
        if (showLimits) loadSpeedLimits(download = false)
    }

    // --- térkép ----------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpMap() = with(binding.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // Éjszakai módban invertáljuk a csempéket: a fehér OSM térkép sötétben vakít.
        if (ThemeSettings.isNight(requireContext())) {
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        controller.setZoom(16.0)
        // Magyarország közepe, amíg nincs GPS fix.
        controller.setCenter(GeoPoint(47.1625, 19.5033))

        setOnTouchListener { _, _ ->
            if (followMode) {
                followMode = false
                updateFollowButton()
            }
            false
        }

        // A rétegek csak a látható területre rajzolódnak, ezért mozgatás után
        // újraszűrünk – kis késleltetéssel, hogy görgetés közben ne dolgozzunk.
        addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                scheduleRedraw()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                scheduleRedraw()
                return false
            }
        })
    }

    private fun scheduleRedraw() {
        redrawHandler.removeCallbacks(redrawRunnable)
        redrawHandler.postDelayed(redrawRunnable, REDRAW_DELAY_MS)
    }

    /** A megadott helyek a térképen: ikon és a hozzá tartozó kör. */
    private fun observePlaces() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(requireContext()).placeDao().observeAll().collectLatest { places ->
                    drawPlaces(places)
                }
            }
        }
    }

    private fun drawPlaces(places: List<Place>) {
        if (_binding == null) return

        placeOverlays.forEach { binding.map.overlays.remove(it) }
        placeOverlays.clear()

        for (place in places) {
            val centre = GeoPoint(place.lat, place.lon)

            val circle = Polygon(binding.map).apply {
                fillPaint.color = ContextCompat.getColor(requireContext(), R.color.place_circle_fill)
                outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.place_circle_stroke)
                outlinePaint.strokeWidth = 3f
                setInfoWindow(null)
                points = Polygon.pointsAsCircle(centre, place.radiusMeters.toDouble())
            }
            val marker = Marker(binding.map).apply {
                position = centre
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(requireContext(), iconFor(place))
                title = place.name
                setInfoWindow(null)
            }

            // A kör a jelölő alá kerül, hogy az ikon jól látszódjon.
            binding.map.overlays.add(circle)
            binding.map.overlays.add(marker)
            placeOverlays.add(circle)
            placeOverlays.add(marker)
        }

        // A saját helyzetünk mindig a helyjelölők fölött legyen.
        bringPositionMarkerToFront()
        binding.map.invalidate()
    }

    private fun iconFor(place: Place) = when (place.placeType) {
        PlaceType.HOME -> R.drawable.ic_place_home
        PlaceType.WORK -> R.drawable.ic_place_work
        PlaceType.DESTINATION -> R.drawable.ic_place_destination
    }

    // --- útmenti adatok --------------------------------------------------------

    private fun observeRoadPoints() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDatabase.get(requireContext()).roadPointDao().observeAll().collectLatest { points ->
                    roadPoints = points
                    drawRoadPoints()
                }
            }
        }
    }

    /**
     * A megrajzolt területek: előbb a telefonon tárolt lista (net nélkül is),
     * utána a friss a szerverről – az új logókkal együtt.
     */
    private fun loadAreas() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            AreaRepository.loadCached(context)
            drawAreas()
            AreaRepository.refresh(context).onSuccess {
                if (_binding == null) return@onSuccess
                drawAreas()
                // A megállások a friss területekkel kapják a nevüket és logójukat.
                stopMarkers?.show(observedStops.toList())
            }
        }
    }

    private fun drawAreas() {
        if (_binding == null) return
        areaOverlays.forEach { binding.map.overlays.remove(it) }
        areaOverlays.clear()

        val zoom = binding.map.zoomLevelDouble
        if (zoom < SHOPS_MIN_ZOOM) { binding.map.invalidate(); return }
        val context = requireContext()

        // A nagyobb kerül alulra, a kisebb fölé – a plázán belüli bolt logója így
        // nem bújik a pláza alá. Ugyanaz a sorrend, mint a webes felületen.
        for (area in AreaRepository.current().sortedByDescending { it.size }) {
            // Közelről a körvonal is látszik, hogy tudd, meddig tart a terület.
            if (zoom >= AREA_OUTLINE_MIN_ZOOM) {
                val colour = ShopBadges.kindColour(area.kind)
                val outline = org.osmdroid.views.overlay.Polygon(binding.map).apply {
                    points = area.polygon.map { GeoPoint(it[0], it[1]) }
                    outlinePaint.color = colour
                    outlinePaint.strokeWidth = 2f * resources.displayMetrics.density
                    fillPaint.color = (colour and 0x00FFFFFF) or 0x1F000000
                    setOnClickListener { _, _, _ -> false }
                    infoWindow = null
                }
                binding.map.overlays.add(outline)
                areaOverlays.add(outline)
            }
            val icon = BitmapDrawable(resources, ShopBadges.areaDisc(context, area, 30f))
            val bubble = bubbleIcon(icon, area.name, ShopBadges.kindColour(area.kind))
            val marker = Marker(binding.map).apply {
                position = GeoPoint(area.centerLat, area.centerLon)
                setAnchor(icon.intrinsicWidth / 2f / bubble.intrinsicWidth, Marker.ANCHOR_CENTER)
                this.icon = bubble
                setInfoWindow(null)
                setOnMarkerClickListener { _, _ ->
                    Toast.makeText(context, area.name, Toast.LENGTH_SHORT).show()
                    true
                }
            }
            binding.map.overlays.add(marker)
            areaOverlays.add(marker)
        }

        bringPositionMarkerToFront()
        binding.map.invalidate()
    }

    /** Mérők, rendőrök, balesetek, lámpák a térképen. */
    private fun drawRoadPoints() {
        if (_binding == null) return

        roadPointOverlays.forEach { binding.map.overlays.remove(it) }
        roadPointOverlays.clear()

        val zoom = binding.map.zoomLevelDouble
        val now = System.currentTimeMillis()

        for (point in roadPoints) {
            // A lámpákból és a boltokból sok van: csak közelebbről érdemes kirajzolni.
            if (point.pointKind == RoadPointKind.TRAFFIC_SIGNALS && zoom < SIGNALS_MIN_ZOOM) continue
            if (point.pointKind == RoadPointKind.SHOP && zoom < SHOPS_MIN_ZOOM) continue
            if (point.expiresAt != null && point.expiresAt < now) continue

            // A lámpánál nem kell felirat (sok van, és magától értetődő),
            // minden másnál viszont látszódjon a lényeg koppintás nélkül.
            val brand = if (point.pointKind == RoadPointKind.SHOP) ShopBadges[point.description] else null
            val label = if (point.pointKind == RoadPointKind.TRAFFIC_SIGNALS) null else shortLabel(point)
            val icon = if (brand != null) {
                BitmapDrawable(resources, ShopBadges.disc(requireContext(), brand, 26f))
            } else {
                ContextCompat.getDrawable(requireContext(), iconFor(point)) ?: continue
            }
            val outline = brand?.disc ?: ContextCompat.getColor(requireContext(), colourFor(point))
            val bubble = if (label == null) icon else bubbleIcon(icon, label, outline)

            val marker = Marker(binding.map).apply {
                position = GeoPoint(point.lat, point.lon)
                // A jelölő közepe az ikon közepére essen, ne a buborékkal együtt.
                if (label == null) {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                } else {
                    setAnchor(icon.intrinsicWidth / 2f / bubble.intrinsicWidth, Marker.ANCHOR_CENTER)
                }
                this.icon = bubble
                setInfoWindow(null)
                // Az idősebb bejelentés halványabb.
                alpha = ageAlpha(point, now)
                setOnMarkerClickListener { _, _ ->
                    Toast.makeText(requireContext(), describe(point), Toast.LENGTH_LONG).show()
                    true
                }
            }
            binding.map.overlays.add(marker)
            roadPointOverlays.add(marker)
        }

        bringPositionMarkerToFront()
        binding.map.invalidate()
    }

    /** A buborékba kerülő rövid szöveg – a hosszabb leírás koppintásra jön. */
    private fun shortLabel(point: RoadPoint): String = when (point.pointKind) {
        // A nevezetes mérőknél (VÉDA kapu, trafibox) a név is látszik a buborékban.
        RoadPointKind.SPEED_CAMERA -> listOfNotNull(
            point.road?.take(14),
            point.speedLimit?.let { "$it" }
        ).joinToString(" · ").ifBlank { getString(R.string.road_point_camera) }

        RoadPointKind.POLICE -> point.reportedAt?.let { Fmt.relativeTime(it) }
            ?: getString(R.string.road_point_police)

        RoadPointKind.ACCIDENT -> getString(R.string.road_point_accident)
        RoadPointKind.OTHER -> point.description ?: getString(R.string.road_point_other)
        RoadPointKind.TRAFFIC_SIGNALS -> getString(R.string.road_point_signals)
        RoadPointKind.SHOP -> point.road ?: ShopBadges[point.description]?.label
            ?: getString(R.string.road_point_shop)
    }

    private fun colourFor(point: RoadPoint) = when (point.pointKind) {
        RoadPointKind.SPEED_CAMERA -> R.color.road_camera
        RoadPointKind.POLICE -> R.color.road_police
        RoadPointKind.ACCIDENT, RoadPointKind.OTHER -> R.color.road_accident
        RoadPointKind.TRAFFIC_SIGNALS -> R.color.road_signals
        RoadPointKind.SHOP -> R.color.place_destination
    }

    /**
     * Ikon + mellette egy kis buborék a felirattal, egyetlen képként. Így a
     * lényeg koppintás nélkül is olvasható, és nem kell az osmdroid saját
     * buborékablakait nyitogatni.
     */
    private fun bubbleIcon(icon: Drawable, text: String, colour: Int): Drawable {
        val density = resources.displayMetrics.density
        val padding = 5f * density
        val gap = 3f * density
        val radius = 6f * density

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f * density
            color = ContextCompat.getColor(requireContext(), R.color.on_surface)
            isFakeBoldText = true
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(requireContext(), R.color.surface)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }

        val textWidth = textPaint.measureText(text)
        val metrics = textPaint.fontMetrics
        val bubbleHeight = (metrics.descent - metrics.ascent) + padding * 2
        val bubbleWidth = textWidth + padding * 2

        val iconWidth = icon.intrinsicWidth.toFloat()
        val iconHeight = icon.intrinsicHeight.toFloat()
        val width = (iconWidth + gap + bubbleWidth).toInt()
        val height = maxOf(iconHeight, bubbleHeight).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bubbleTop = (height - bubbleHeight) / 2f
        val rect = RectF(iconWidth + gap, bubbleTop, width.toFloat() - 1f, bubbleTop + bubbleHeight)
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, border)
        canvas.drawText(text, rect.left + padding, rect.centerY() - (metrics.ascent + metrics.descent) / 2f, textPaint)

        val iconTop = ((height - iconHeight) / 2f).toInt()
        icon.setBounds(0, iconTop, iconWidth.toInt(), iconTop + iconHeight.toInt())
        icon.draw(canvas)

        return BitmapDrawable(resources, bitmap)
    }

    private fun iconFor(point: RoadPoint) = when (point.pointKind) {
        RoadPointKind.SPEED_CAMERA -> R.drawable.ic_road_camera
        RoadPointKind.POLICE -> R.drawable.ic_road_police
        RoadPointKind.ACCIDENT, RoadPointKind.OTHER -> R.drawable.ic_road_accident
        RoadPointKind.TRAFFIC_SIGNALS -> R.drawable.ic_road_signals
        RoadPointKind.SHOP -> R.drawable.ic_stop_shop
    }

    /** Az ideiglenes bejelentések a lejáratuk felé haladva halványodnak. */
    private fun ageAlpha(point: RoadPoint, now: Long): Float {
        val expires = point.expiresAt ?: return 1f
        val reported = point.reportedAt ?: return 1f
        if (expires <= reported) return 1f
        val remaining = (expires - now).toFloat() / (expires - reported).toFloat()
        return remaining.coerceIn(0.35f, 1f)
    }

    private fun describe(point: RoadPoint): String {
        val name = getString(
            when (point.pointKind) {
                RoadPointKind.SPEED_CAMERA -> R.string.road_point_camera
                RoadPointKind.POLICE -> R.string.road_point_police
                RoadPointKind.ACCIDENT -> R.string.road_point_accident
                RoadPointKind.TRAFFIC_SIGNALS -> R.string.road_point_signals
                RoadPointKind.SHOP -> R.string.road_point_shop
                RoadPointKind.OTHER -> R.string.road_point_other
            }
        )
        val title = point.speedLimit?.let { getString(R.string.road_point_limit, name, it) } ?: name
        val details = listOfNotNull(
            point.road,
            point.description,
            point.reportedAt?.let { Fmt.relativeTime(it) }
        )
        return if (details.isEmpty()) title else "$title · ${details.joinToString(" · ")}"
    }

    /**
     * Az útmenti adatok állapota: rétegenként hány elem van és mikor frissült.
     * Innen indítható a frissítés is – a szerver háttérben tölt, ezért a
     * párbeszédablak a haladást is követi.
     */
    private fun showRoadLayersDialog() {
        val view = DialogRoadLayersBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.road_data_title)
            .setView(view.root)
            .setPositiveButton(R.string.road_refresh, null)
            .setNeutralButton(R.string.road_refresh_free, null)
            .setNegativeButton(android.R.string.ok, null)
            .create()

        // A gombokat kézzel kötjük be, hogy frissítéskor nyitva maradjon az ablak.
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener { refreshRoadData(view, freeOnly = false) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener { refreshRoadData(view, freeOnly = true) }
        }
        dialog.show()

        renderLayers(view, RoadDataRepository.cachedMeta(requireContext()))
        viewLifecycleOwner.lifecycleScope.launch {
            RoadDataRepository.load(requireContext())
                .onSuccess { renderLayers(view, it) }
                .onFailure {
                    if (_binding != null) {
                        Toast.makeText(requireContext(), R.string.road_offline, Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun renderLayers(view: DialogRoadLayersBinding, meta: RoadDataMeta?) {
        if (meta == null) {
            view.tvLayers.setText(R.string.road_offline)
            return
        }

        val lines = listOf(
            layerLine(meta, "cameras", R.string.road_layer_cameras, segments = false),
            layerLine(meta, "signals", R.string.road_layer_signals, segments = false),
            layerLine(meta, "shops", R.string.road_layer_shops, segments = false),
            layerLine(meta, "speedlimits", R.string.road_layer_speedlimits, segments = true),
            wazeLine(meta)
        )
        view.tvLayers.text = lines.joinToString("\n")
        view.tvLegend.text = getString(R.string.road_legend_title) + "\n" + getString(R.string.road_legend)
        view.tvArea.text = getString(R.string.road_area, "12×10 km")
    }

    /** "Fix sebességmérők: 1 db · 12 perce" */
    private fun layerLine(
        meta: RoadDataMeta,
        key: String,
        titleRes: Int,
        segments: Boolean
    ): String {
        val layer = meta.layer(key)
        val amount = when {
            layer == null -> getString(R.string.no_data)
            segments -> getString(R.string.road_layer_segments, layer.count)
            else -> getString(R.string.road_layer_count, layer.count)
        }
        val freshness = when {
            layer == null -> getString(R.string.no_data)
            layer.refreshing -> getString(R.string.road_layer_refreshing)
            layer.error != null -> getString(R.string.road_layer_error, layer.error)
            else -> Fmt.relativeTime(layer.updatedAt)
        }
        return getString(R.string.road_layer_line, getString(titleRes), amount, freshness)
    }

    private fun wazeLine(meta: RoadDataMeta): String {
        val title = getString(R.string.road_layer_waze)
        if (!meta.wazeConfigured) {
            return getString(
                R.string.road_layer_line,
                title,
                getString(R.string.road_layer_waze_off),
                getString(R.string.no_data)
            )
        }
        val layer = meta.layer("waze")
        val line = getString(
            R.string.road_layer_line,
            title,
            getString(R.string.road_layer_count, layer?.count ?: 0),
            when {
                layer == null -> getString(R.string.no_data)
                layer.refreshing -> getString(R.string.road_layer_refreshing)
                layer.error != null -> getString(R.string.road_layer_error, layer.error)
                else -> Fmt.relativeTime(layer.updatedAt)
            }
        )
        return line + "\n" + getString(R.string.road_layer_quota, meta.wazeRemaining, meta.wazeLimit)
    }

    private fun refreshRoadData(view: DialogRoadLayersBinding, freeOnly: Boolean) {
        loadAreas()
        viewLifecycleOwner.lifecycleScope.launch {
            RoadDataRepository.refresh(requireContext(), freeOnly)
                .onSuccess { meta ->
                    if (_binding == null) return@onSuccess
                    renderLayers(view, meta)
                    Toast.makeText(requireContext(), R.string.road_refresh_started, Toast.LENGTH_SHORT).show()
                    followProgress(view)
                }
                .onFailure {
                    if (_binding == null) return@onFailure
                    Toast.makeText(requireContext(), R.string.road_offline, Toast.LENGTH_LONG).show()
                }
        }
    }

    /** A szerver háttérben tölt, ezért egy darabig figyeljük a rétegek állapotát. */
    private fun followProgress(view: DialogRoadLayersBinding) {
        viewLifecycleOwner.lifecycleScope.launch {
            repeat(20) {
                kotlinx.coroutines.delay(5000)
                if (_binding == null) return@launch
                val meta = RoadDataRepository.load(requireContext()).getOrNull() ?: return@launch
                renderLayers(view, meta)
                if (!meta.anyRefreshing) return@launch
            }
        }
    }

    // --- sebességhatárok -------------------------------------------------------

    private fun updateLimitsButton() {
        binding.fabLimits.alpha = if (showLimits) 1f else 0.5f
    }

    private fun toggleLimits() {
        showLimits = !showLimits
        requireContext().getSharedPreferences(UI_PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_LIMITS, showLimits).apply()
        updateLimitsButton()

        if (showLimits) {
            Toast.makeText(requireContext(), R.string.limits_on, Toast.LENGTH_SHORT).show()
            loadSpeedLimits(download = true)
        } else {
            Toast.makeText(requireContext(), R.string.limits_off, Toast.LENGTH_SHORT).show()
            drawSpeedLimits()
        }
    }

    /** A réteg a gyorsítótárból jön; ha üres, egyszer letöltjük a szerverről. */
    private fun loadSpeedLimits(download: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val cached = SpeedLimitLayer.loadCached(requireContext())
            if (_binding == null) return@launch
            drawSpeedLimits()

            if (cached.isNotEmpty() || !download) return@launch

            // Az első bekapcsolásnál a látható terület töltődik le, nem az egész megye.
            Toast.makeText(requireContext(), R.string.limits_loading, Toast.LENGTH_SHORT).show()
            val box = binding.map.boundingBox
            SpeedLimitLayer.ensureArea(
                requireContext(),
                box.latSouth,
                box.lonWest,
                box.latNorth,
                box.lonEast
            )
                .onSuccess {
                    if (_binding == null) return@onSuccess
                    if (SpeedLimitLayer.loadedSegments().isEmpty()) {
                        Toast.makeText(requireContext(), R.string.limits_empty, Toast.LENGTH_LONG).show()
                    }
                    drawSpeedLimits()
                }
                .onFailure {
                    if (_binding == null) return@onFailure
                    Toast.makeText(requireContext(), R.string.road_offline, Toast.LENGTH_LONG).show()
                }
        }
    }

    /** A látható útszakaszok a megengedett sebesség szerint színezve. */
    private fun drawSpeedLimits() {
        if (_binding == null) return

        speedOverlays.forEach { binding.map.overlays.remove(it) }
        speedOverlays.clear()

        if (!showLimits || binding.map.zoomLevelDouble < LIMITS_MIN_ZOOM) {
            binding.map.invalidate()
            return
        }

        val box = binding.map.boundingBox
        // Megyényi adatból mindig csak a látható részt töltjük le.
        ensureSpeedArea(box)

        val visible = SpeedLimitLayer.loadedSegments()
            .filter { it.intersects(box.latSouth, box.lonWest, box.latNorth, box.lonEast) }
            // Ha sok van, a nagyobb sebességű (fontosabb) utak maradnak.
            .sortedByDescending { it.limitKmh }
            .take(MAX_DRAWN_SEGMENTS)

        for (segment in visible) {
            val polyline = Polyline(binding.map).apply {
                outlinePaint.color = ContextCompat.getColor(requireContext(), colourFor(segment.limitKmh))
                outlinePaint.strokeWidth = 7f
                outlinePaint.alpha = 190
                // A becsült (nem kiírt) határ szaggatott, hogy látszódjon a különbség.
                if (segment.guessed) {
                    outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
                }
                setPoints(segment.points.map { GeoPoint(it[0], it[1]) })
                setOnClickListener { _, _, _ ->
                    Toast.makeText(requireContext(), describe(segment), Toast.LENGTH_SHORT).show()
                    true
                }
            }
            // A saját nyomvonal alá kerül, hogy az maradjon a legjobban látható.
            binding.map.overlays.add(0, polyline)
            speedOverlays.add(polyline)
        }
        binding.map.invalidate()
    }

    /** A látható terület letöltése, ha még nem ismerjük – utána újrarajzolunk. */
    private fun ensureSpeedArea(box: org.osmdroid.util.BoundingBox) {
        if (speedAreaLoading) return
        speedAreaLoading = true

        viewLifecycleOwner.lifecycleScope.launch {
            SpeedLimitLayer.ensureArea(
                requireContext(),
                box.latSouth,
                box.lonWest,
                box.latNorth,
                box.lonEast
            ).onSuccess { changed ->
                speedAreaLoading = false
                if (changed && _binding != null) drawSpeedLimits()
            }.onFailure {
                speedAreaLoading = false
            }
        }
    }

    private fun colourFor(limitKmh: Int) = when {
        limitKmh <= 30 -> R.color.limit_30
        limitKmh <= 50 -> R.color.limit_50
        limitKmh <= 70 -> R.color.limit_70
        limitKmh <= 90 -> R.color.limit_90
        else -> R.color.limit_110
    }

    private fun describe(segment: SpeedSegment): String = getString(
        R.string.segment_info,
        segment.limitKmh,
        getString(if (segment.guessed) R.string.segment_guessed else R.string.segment_tagged),
        segment.road?.let { " · $it" } ?: ""
    )

    private fun resetPath() {
        displayGeneration++
        stopMarkers?.clear()
        observedStops.clear()
        lastStopRequestAt = 0L
        matchedDisplayLinks.clear()
        lastDisplayMatchAt = 0L
        lastDisplayMatchCount = 0
        polylines.forEach { binding.map.overlays.remove(it) }
        polylines.clear()
        currentPolyline = null
        currentSegment = -1
        drawnPointCount = 0
    }

    /** Csak az új pontokat fűzzük a vonalhoz, nem rajzoljuk újra az egészet. */
    private fun drawNewPoints() {
        if (PathBuffer.size() < drawnPointCount) resetPath()

        val newPoints = PathBuffer.from(drawnPointCount)
        if (newPoints.isEmpty()) return
        scheduleDisplayMatch()
        scheduleStopMarkers()

        var previous = if (drawnPointCount > 0) PathBuffer.from(drawnPointCount - 1).firstOrNull() else null
        for (point in newPoints) {
            if (point.segment != currentSegment || currentPolyline == null ||
                (previous != null && previous!!.time > 0 && point.time - previous!!.time !in 1..30_000)) {
                currentSegment = point.segment
                val polyline = Polyline(binding.map).apply {
                    outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.track)
                    outlinePaint.strokeWidth = 12f
                }
                currentPolyline = polyline
                polylines.add(polyline)
                binding.map.overlays.add(polyline)
            }
            currentPolyline?.addPoint(GeoPoint(point.lat, point.lon))
            previous = point
        }
        drawnPointCount += newPoints.size

        // A helyzetjelző maradjon a vonal fölött.
        bringPositionMarkerToFront()
        binding.map.invalidate()
    }

    private fun scheduleStopMarkers() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (stopLoading || now - lastStopRequestAt < 30_000) return
        val size = PathBuffer.size()
        val recent = PathBuffer.from((size - 1500).coerceAtLeast(0))
        val local = ObservedStopRepository.detect(recent)
        if (local.isEmpty()) return
        if (stopMarkers == null) stopMarkers = StopMarkers(requireContext(), binding.map)
        fun merge(stops: List<ObservedStopRepository.Stop>) {
            for (stop in stops) {
                val index = observedStops.indexOfFirst { old -> old.id == stop.id ||
                    (stop.startedAt <= old.endedAt && stop.endedAt >= old.startedAt &&
                     GeoPoint(old.lat, old.lon).distanceToAsDouble(GeoPoint(stop.lat, stop.lon)) < 100) }
                if (index >= 0) {
                    val old = observedStops[index]
                    observedStops[index] = stop.copy(id = old.id, startedAt = minOf(old.startedAt, stop.startedAt),
                        type = if (stop.type == "OTHER") old.type else stop.type,
                        name = stop.name ?: old.name, brand = stop.brand ?: old.brand,
                        areaId = stop.areaId ?: old.areaId, durationMs = stop.durationMs ?: old.durationMs,
                        signalDistanceM = stop.signalDistanceM ?: old.signalDistanceM)
                } else observedStops.add(stop)
            }
            stopMarkers?.show(observedStops.toList())
        }
        merge(local)
        lastStopRequestAt = now
        stopLoading = true
        val generation = displayGeneration
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val stops = ObservedStopRepository.load(recent)
                if (_binding != null && generation == displayGeneration) merge(stops)
            } finally { stopLoading = false }
        }
    }

    /** Élőben csak az utolsó néhány percet illesztjük, legfeljebb félpercenként. */
    private fun scheduleDisplayMatch() {
        val count = PathBuffer.size()
        val now = android.os.SystemClock.elapsedRealtime()
        if (displayMatchLoading || count < 3 || count == lastDisplayMatchCount || now - lastDisplayMatchAt < 30_000) return
        val start = (count - 480).coerceAtLeast(0)
        val snapshot = PathBuffer.from(start)
        val generation = displayGeneration
        val context = requireContext().applicationContext
        displayMatchLoading = true
        lastDisplayMatchAt = now
        lastDisplayMatchCount = count
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val display = DisplayTrackRepository.load(context, snapshot, cache = false)
                if (_binding == null || generation != displayGeneration || PathBuffer.size() < count) return@launch
                display.links.forEachIndexed { i, link -> matchedDisplayLinks[start + i] = link }
                val allPoints = PathBuffer.from(0)
                val links = DisplayTrackRepository.rawLinks(allPoints).mapIndexed { i, raw -> matchedDisplayLinks[i] ?: raw }
                polylines.forEach { binding.map.overlays.remove(it) }
                polylines.clear()
                for (path in DisplayTrackRepository.paths(links)) {
                    val line = Polyline(binding.map).apply {
                        setPoints(path)
                        outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.track)
                        outlinePaint.strokeWidth = 12f
                    }
                    polylines.add(line)
                    binding.map.overlays.add(line)
                }
                currentPolyline = if (links.lastOrNull()?.isNotEmpty() == true) polylines.lastOrNull() else null
                currentSegment = allPoints.lastOrNull()?.segment ?: -1
                drawnPointCount = allPoints.size
                bringPositionMarkerToFront()
                binding.map.invalidate()
            } finally { displayMatchLoading = false }
        }
    }

    // --- élő helyzet -----------------------------------------------------------

    private fun startPositionUpdates() {
        if (!hasLocationPermission()) return
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, positionListener
            )
            // Beltéren / hidegindításkor a hálózati helyzet gyorsabban ad valamit.
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 5000L, 0f, positionListener
                )
            }
        } catch (e: SecurityException) {
            // Elvették az engedélyt.
        } catch (e: IllegalArgumentException) {
            // Nincs ilyen provider ezen az eszközön.
        }
        showLastKnownPosition()
    }

    private fun stopPositionUpdates() {
        try {
            locationManager.removeUpdates(positionListener)
        } catch (e: SecurityException) {
            // ignoráljuk
        }
    }

    private fun onPositionUpdate(location: Location) {
        if (_binding == null) return
        val now = System.currentTimeMillis()
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFixAt = now
        } else if (now - lastGpsFixAt < GPS_TRUST_MS) {
            // Van friss GPS fixünk, a pontatlanabb hálózati helyzet ne rángassa a jelölőt.
            return
        }

        val position = GeoPoint(location.latitude, location.longitude)
        showPositionMarker(position, if (location.hasAccuracy()) location.accuracy else 0f)

        if (!initialCentreDone) {
            initialCentreDone = true
            binding.map.controller.setCenter(position)
        } else if (followMode) {
            binding.map.controller.animateTo(position)
        }

        // Mérés közben a szolgáltatás állapota a mérvadó, azt nem írjuk felül.
        if (!TrackingService.state.value.isActive) {
            binding.tvSpeed.text = Fmt.speedKmh(if (location.hasSpeed()) location.speed else 0f)
            binding.tvAltitude.text =
                Fmt.meters(if (location.hasAltitude()) location.altitude else 0.0)
            binding.tvGps.text = if (location.hasAccuracy()) {
                getString(R.string.gps_ready, location.accuracy.toInt())
            } else {
                getString(R.string.gps_waiting)
            }
        }
        binding.map.invalidate()
    }

    private fun showPositionMarker(position: GeoPoint, accuracyMeters: Float) {
        // A pontossági kör a jelölő alatt, ezért ezt hozzuk létre előbb.
        if (accuracyMeters > 0f) {
            val circle = accuracyCircle ?: Polygon(binding.map).also {
                it.fillPaint.color = ContextCompat.getColor(requireContext(), R.color.accuracy_fill)
                it.outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.accuracy_stroke)
                it.outlinePaint.strokeWidth = 2f
                it.setInfoWindow(null)
                accuracyCircle = it
                binding.map.overlays.add(it)
            }
            circle.points = Polygon.pointsAsCircle(position, accuracyMeters.toDouble())
        }

        val marker = positionMarker ?: Marker(binding.map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            it.setInfoWindow(null)
            positionMarker = it
            positionWalking = null
            binding.map.overlays.add(it)
        }
        marker.position = position
        // Gyalog bejárt területen (bolt, benzinkút…) belül sétálsz: a jelölő is ezt mutatja.
        val walking = ObservedStopRepository.visitAreaAt(position.latitude, position.longitude) != null
        if (walking != positionWalking) {
            positionWalking = walking
            marker.icon = ContextCompat.getDrawable(requireContext(),
                if (walking) R.drawable.ic_position_walk else R.drawable.ic_position)
        }
    }

    private fun bringPositionMarkerToFront() {
        val marker = positionMarker ?: return
        binding.map.overlays.remove(marker)
        binding.map.overlays.add(marker)
    }

    /** Induláskor az utolsó ismert helyzet, hogy ne Magyarország közepét bámuljuk fix-várás közben. */
    private fun showLastKnownPosition() {
        if (!hasLocationPermission()) return
        val last = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        } ?: return

        val position = GeoPoint(last.latitude, last.longitude)
        showPositionMarker(position, if (last.hasAccuracy()) last.accuracy else 0f)
        if (!initialCentreDone) {
            initialCentreDone = true
            binding.map.controller.setCenter(position)
        }
        binding.map.invalidate()
    }

    // --- gombok ----------------------------------------------------------------

    private fun setUpButtons() {
        binding.btnStart.setOnClickListener { onStartClicked() }

        binding.btnPause.setOnClickListener {
            when (TrackingService.state.value.status) {
                TrackingStatus.RUNNING -> TrackingService.pause(requireContext())
                TrackingStatus.PAUSED -> TrackingService.resume(requireContext())
                TrackingStatus.IDLE -> Unit
            }
        }

        binding.btnStop.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.stop_confirm_title)
                .setMessage(R.string.stop_confirm_message)
                .setPositiveButton(R.string.action_stop) { _, _ ->
                    TrackingService.stop(requireContext())
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        binding.fabFollow.setOnClickListener {
            followMode = !followMode
            updateFollowButton()
            if (followMode) positionMarker?.position?.let { binding.map.controller.animateTo(it) }
        }

        binding.fabRoadData.setOnClickListener { showRoadLayersDialog() }
        binding.fabLimits.setOnClickListener { toggleLimits() }

        updateFollowButton()
    }

    private fun onStartClicked() {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            beginTracking()
        }
    }

    private fun beginTracking() {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.gps_off_title)
                .setMessage(R.string.gps_off_message)
                .setPositiveButton(R.string.action_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
            return
        }
        resetPath()
        followMode = true
        updateFollowButton()
        TrackingService.start(requireContext())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun updateFollowButton() {
        binding.fabFollow.alpha = if (followMode) 1f else 0.5f
    }

    // --- állapot ---------------------------------------------------------------

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingService.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: TrackingState) {
        if (_binding == null) return
        binding.tvDistance.text = Fmt.distance(state.distanceMeters)
        binding.tvDuration.text = Fmt.duration(state.durationMillis)
        binding.tvMovingTime.text = Fmt.duration(state.movingMillis)
        binding.tvAvgSpeed.text = Fmt.speedKmhUnit(state.avgSpeedMps)
        binding.tvMaxSpeed.text = Fmt.speedKmhUnit(state.maxSpeedMps)
        binding.tvElevGain.text = Fmt.meters(state.elevationGainMeters)

        if (state.isActive) {
            binding.tvSpeed.text = Fmt.speedKmh(state.currentSpeedMps)
            binding.tvAltitude.text = Fmt.meters(state.altitudeMeters)
            binding.tvGps.text = if (state.hasFix) {
                getString(R.string.gps_accuracy, state.accuracyMeters.toInt(), state.pointCount)
            } else {
                getString(R.string.gps_searching)
            }
            if (state.telemetryEnabled) {
                val sensor = when {
                    state.telemetryQuality >= 0.65f -> getString(R.string.telemetry_live_ok)
                    state.telemetryQuality > 0f -> getString(R.string.telemetry_live_calibrating)
                    else -> getString(R.string.telemetry_live_waiting)
                }
                binding.tvGps.text = "${binding.tvGps.text} · $sensor"
            }
        }

        binding.tvStatus.setText(
            when (state.status) {
                TrackingStatus.RUNNING -> R.string.status_recording
                TrackingStatus.PAUSED -> R.string.status_paused
                TrackingStatus.IDLE -> R.string.status_idle
            }
        )

        binding.btnStart.isEnabled = state.status == TrackingStatus.IDLE
        binding.btnPause.isEnabled = state.isActive
        binding.btnStop.isEnabled = state.isActive

        // A gombokon nincs felirat, csak ikon – a szünet/folytatás ezen váltakozik.
        if (state.status == TrackingStatus.PAUSED) {
            binding.btnPause.setIconResource(R.drawable.ic_play)
            binding.btnPause.contentDescription = getString(R.string.action_resume)
        } else {
            binding.btnPause.setIconResource(R.drawable.ic_pause)
            binding.btnPause.contentDescription = getString(R.string.action_pause)
        }

        // Új túra indult (vagy a szolgáltatás mást tölt be): induljon tiszta lappal a vonal.
        if (state.isActive && state.trackId != 0L && state.trackId != renderedTrackId) {
            resetPath()
            renderedTrackId = state.trackId
        }

        drawNewPoints()
    }

    // --- életciklus ------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        startPositionUpdates()
        // A háttérben érkezett pontokat pótoljuk.
        drawNewPoints()

        // A widget "Indítás" gombja ide fut be: itt már előtérben vagyunk,
        // tehát szabad előtérszolgáltatást indítani és engedélyt kérni.
        if ((activity as? MainActivity)?.consumePendingStart() == true &&
            !TrackingService.state.value.isActive
        ) {
            onStartClicked()
        }
    }

    override fun onPause() {
        stopPositionUpdates()
        binding.map.onPause()
        super.onPause()
    }

    /**
     * Fülváltáskor a fragment nem áll le (csak elrejtjük), ezért a térképet és a
     * helyzetfigyelőt itt kell kézzel szüneteltetni – különben másik fülön is
     * pörögne a GPS.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (_binding == null) return
        if (hidden) {
            stopPositionUpdates()
            binding.map.onPause()
        } else {
            binding.map.onResume()
            startPositionUpdates()
            drawNewPoints()
        }
    }

    override fun onDestroyView() {
        displayGeneration++
        stopMarkers?.clear()
        stopMarkers = null
        areaOverlays.clear()
        stopLoading = false
        displayMatchLoading = false
        matchedDisplayLinks.clear()
        binding.map.onDetach()
        positionMarker = null
        accuracyCircle = null
        polylines.clear()
        currentPolyline = null
        placeOverlays.clear()
        roadPointOverlays.clear()
        speedOverlays.clear()
        redrawHandler.removeCallbacks(redrawRunnable)
        _binding = null
        super.onDestroyView()
    }
}
