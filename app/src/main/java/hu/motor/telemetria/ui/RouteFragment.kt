package hu.motor.telemetria.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.CommutePreferences
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import hu.motor.telemetria.App
import hu.motor.telemetria.R
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.SavedRoute
import hu.motor.telemetria.databinding.FragmentRouteBinding
import hu.motor.telemetria.databinding.ItemRouteTimeBinding
import hu.motor.telemetria.net.ApiClient
import hu.motor.telemetria.net.PolylineCodec
import hu.motor.telemetria.net.RoutePlan
import hu.motor.telemetria.net.RouteRepository
import hu.motor.telemetria.net.RouteSection
import hu.motor.telemetria.net.Waypoint
import hu.motor.telemetria.util.GoogleMapsLink
import hu.motor.telemetria.util.ThemeSettings
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay

/**
 * Útvonaltervezés köztes pontokkal.
 *
 * A számolás a szerveren fut: ott van a megtett túráidból épített személyes
 * sebességmodell, és ott van a térképre illesztés is. Ez a felület a pontokat
 * veszi fel, és a kapott tervet rajzolja ki - a vonalat kanyartípus szerint
 * színezve, hogy egy pillantásra látszódjon, hol lesz élvezetes az út.
 */
class RouteFragment : Fragment() {

    private var _binding: FragmentRouteBinding? = null
    private val binding get() = _binding!!

    private val waypoints = mutableListOf<Waypoint>()
    private lateinit var waypointAdapter: RouteWaypointAdapter
    private lateinit var sectionAdapter: RouteSectionAdapter

    private val routeOverlays = mutableListOf<Polyline>()
    private val markerOverlays = mutableListOf<Marker>()
    private var highlight: Polyline? = null

    private var plan: RoutePlan? = null
    private var shapePoints: List<GeoPoint> = emptyList()
    private var planning = false
    private var revision = 0
    private var selectedFamiliar: Waypoint? = null
    private val familiarOverlays = mutableListOf<Polyline>()

    private val commutePrefs by lazy { CommutePreferences(requireContext()) }
    private var places: List<Place> = emptyList()
    private var commuteKey: String? = null
    private var commuteEnds: List<Waypoint> = emptyList()
    private var mapPick: String? = null
    private var sameSide = true

    private val database by lazy { AppDatabase.get(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val previousPlan = plan
        savedInstanceState?.let { state ->
            state.getString("route_points")?.let { waypoints.clear(); waypoints.addAll(Waypoint.parseList(JSONArray(it))) }
            commuteKey = state.getString("commute_key")
            commuteEnds = Waypoint.parseList(JSONArray(state.getString("commute_ends") ?: "[]"))
            sameSide = state.getBoolean("same_side", true)
        }
        setUpMap()
        setUpLists()
        setUpControls()
        setUpSheetHeight()
        drawWaypointMarkers()
        loadFamiliarRoutes()
        savedInstanceState?.getString("route_style")?.let { style ->
            binding.styleGroup.check(when (style) {
                "CURVY" -> R.id.btnStyleCurvy
                "COMFORT" -> R.id.btnStyleComfort
                "RATRUN" -> R.id.btnStyleRatRun
                else -> R.id.btnStyleFast
            })
        }
        if (previousPlan != null) showPlan(previousPlan)
        loadPlaceChips()
    }

    private fun setUpSheetHeight() {
        val sheet = binding.bottomSheet
        val behavior = BottomSheetBehavior.from(sheet)
        // A tabContent már a fülsáv fölött végződik. A lap teljes magasságából
        // a nyitott állapot felső eltolását is le kell vonni, különben az alja
        // a konténeren kívülre csúszik, és a görgetés sem tudja megmutatni.
        binding.root.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val availableHeight = (view.height - behavior.expandedOffset).coerceAtLeast(1)
            if (behavior.maxHeight != availableHeight) {
                behavior.maxHeight = availableHeight
                sheet.requestLayout()
            }
        }
    }

    private fun setUpMap() = with(binding.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        // Éjszakai módban invertáljuk a csempéket, ahogy a többi térképen is.
        if (ThemeSettings.isNight(requireContext())) {
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        }
        controller.setZoom(11.0)
        // Magyarország közepe, amíg nincs GPS fix vagy betöltött útvonal.
        controller.setCenter(GeoPoint(47.1625, 19.5033))

        // Hosszú nyomás = új pont. A rövid koppintás a kiemelést veszi le, hogy
        // a szakaszlistából való visszalépés kézenfekvő legyen.
        overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                clearHighlight()
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p != null) pickMapPoint(Waypoint(p.latitude, p.longitude))
                return true
            }
        }))
    }

    private fun setUpLists() {
        waypointAdapter = RouteWaypointAdapter(
            points = waypoints,
            onChanged = {
                drawWaypointMarkers()
                // Az útvonal a módosítás után már nem érvényes, de a tervezés
                // kézi: több jelöltet számol végig, ezt nem illik automatikusan
                // minden apró mozdításra elindítani.
                clearPlan()
            },
            onStartDrag = { holder -> dragHelper.startDrag(holder) }
        )
        binding.rvWaypoints.layoutManager = LinearLayoutManager(requireContext())
        binding.rvWaypoints.adapter = waypointAdapter
        dragHelper.attachToRecyclerView(binding.rvWaypoints)

        sectionAdapter = RouteSectionAdapter { section -> focusSection(section) }
        binding.rvSections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSections.adapter = sectionAdapter
    }

    private val dragHelper by lazy { RouteWaypointAdapter.touchHelper(waypointAdapter) }

    private fun setUpControls() {
        binding.styleGroup.check(R.id.btnStyleRatRun)
        binding.arriveSameSide.isChecked = sameSide
        binding.arriveSameSide.setOnCheckedChangeListener { _, checked ->
            sameSide = checked
            invalidateResult()
        }
        binding.styleGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) invalidateResult() }

        binding.btnPlan.setOnClickListener { requestPlan() }

        binding.btnGoogleMaps.setOnClickListener { openInGoogleMaps() }

        binding.fabAddHere.setOnClickListener {
            val centre = binding.map.mapCenter
            pickMapPoint(Waypoint(centre.latitude, centre.longitude))
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (planning) return@setOnMenuItemClickListener true
            when (item.itemId) {
                R.id.action_save_route -> { saveRoute(); true }
                R.id.action_my_routes -> { showSavedRoutes(); true }
                R.id.action_clear_route -> { clearAll(); true }
                else -> false
            }
        }
    }

    /** A mentett helyek gyorsválasztóként: otthon, munkahely, célpontok. */
    private fun loadPlaceChips() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loadedPlaces = withContext(Dispatchers.IO) { database.placeDao().getAll() }
            if (_binding == null) return@launch
            places = loadedPlaces
            binding.placeChips.removeAllViews()
            for (place in loadedPlaces) {
                val chip = Chip(requireContext()).apply {
                    text = place.name
                    isCheckable = false
                    setOnClickListener { addWaypoint(Waypoint(place.lat, place.lon, place.name)) }
                }
                binding.placeChips.addView(chip)
            }
            renderCommuteControls()
        }
    }

    private fun invalidateResult() {
        revision++
        plan = null
        shapePoints = emptyList()
        routeOverlays.forEach { binding.map.overlays.remove(it) }
        routeOverlays.clear()
        clearHighlight()
        binding.resultBox.visibility = View.GONE
    }

    private fun checkCommuteEndpoints() {
        if (commuteKey != null && (waypoints.size < 2 || commuteEnds.size != 2 ||
                waypoints.first() != commuteEnds.first() || waypoints.last() != commuteEnds.last())) {
            commuteKey = null
        }
    }

    private fun startCommute(from: Place, to: Place) {
        if (planning) return
        commuteKey = "${from.id}_${to.id}"
        commuteEnds = listOf(Waypoint(from.lat, from.lon, from.name), Waypoint(to.lat, to.lon, to.name))
        val rules = commutePrefs.load(commuteKey!!)
        mapPick = null
        waypoints.clear()
        waypoints.add(commuteEnds.first())
        waypoints.addAll(rules.fixed)
        waypoints.add(commuteEnds.last())
        waypointAdapter.notifyDataSetChanged()
        drawWaypointMarkers()
        binding.styleGroup.check(R.id.btnStyleRatRun)
        clearPlan()
        fitToSheet(waypoints.map { GeoPoint(it.lat, it.lon) }, 0.9)
    }

    private fun renderCommuteControls() {
        if (_binding == null) return
        val box = binding.commuteControls
        box.removeAllViews()
        fun button(label: String, action: () -> Unit) {
            box.addView(Button(requireContext()).apply {
                text = label
                isAllCaps = false
                setOnClickListener { if (!planning) action() }
            })
        }
        val homes = places.filter { it.type == "HOME" }
        val works = places.filter { it.type == "WORK" }
        for (home in homes) for (work in works) {
            button("${home.name} → ${work.name}") { startCommute(home, work) }
            button("${work.name} → ${home.name}") { startCommute(work, home) }
        }
        if (waypoints.size < 2) return
        button("Kötelező út / átmenő pont kijelölése") {
            mapPick = "via"
            Toast.makeText(requireContext(), "Nyomj hosszan a kívánt útszakaszra. Több ponttal a teljes útvonalat kijelölheted.", Toast.LENGTH_LONG).show()
            sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        val key = commuteKey
        if (key == null) {
            box.addView(TextView(requireContext()).apply {
                text = "Az irányhoz kötött bolt és útvonal mentéséhez válaszd az Otthon → Munkahely vagy a visszaút gombot."
            })
            return
        }
        val rules = commutePrefs.load(key)
        box.addView(TextView(requireContext()).apply {
            text = "${commuteEnds.first().name} → ${commuteEnds.last().name} · ${rules.fixed.size} mentett kötelező pont"
        })
        button("Jelenlegi út rögzítése ehhez az irányhoz") {
            val fixed = waypoints.drop(1).dropLast(1).filter { p -> rules.shops.none { samePoint(it, p) } }.toMutableList()
            selectedFamiliar?.let { if (fixed.none { p -> samePoint(p, it) }) fixed.add(it) }
            if (fixed.size > 10) {
                Toast.makeText(requireContext(), "Legfeljebb 10 köztes pont menthető.", Toast.LENGTH_LONG).show()
            } else {
                commutePrefs.save(key, rules.copy(fixed = fixed))
                renderCommuteControls()
                Toast.makeText(requireContext(), "Rögzítve ehhez az irányhoz. Legközelebb automatikusan betöltődik.", Toast.LENGTH_LONG).show()
            }
        }
        if (rules.fixed.isNotEmpty()) button("Rögzített út törlése ebből az irányból") {
            commutePrefs.save(key, rules.copy(fixed = emptyList()))
            waypoints.removeAll { p -> rules.fixed.any { samePoint(it, p) } && p != waypoints.first() && p != waypoints.last() }
            waypointAdapter.notifyDataSetChanged()
            drawWaypointMarkers()
            clearPlan()
        }
        button("Bolt / választható megálló hozzáadása") { chooseShop() }
        rules.shops.forEach { shop ->
            box.addView(Chip(requireContext()).apply {
                text = "Útba ejtem: ${shop.name}"
                isCheckable = true
                isEnabled = !planning
                isChecked = waypoints.any { samePoint(it, shop) }
                isCloseIconVisible = true
                setOnCheckedChangeListener { _, checked ->
                    if (planning) return@setOnCheckedChangeListener
                    if (checked && waypoints.size >= 12) {
                        Toast.makeText(requireContext(), "Legfeljebb 12 pont tervezhető.", Toast.LENGTH_LONG).show()
                        post { renderCommuteControls() }
                        return@setOnCheckedChangeListener
                    }
                    if (checked) waypoints.add(waypoints.lastIndex, shop)
                    else waypoints.removeAll { samePoint(it, shop) }
                    waypointAdapter.notifyDataSetChanged()
                    drawWaypointMarkers()
                    clearPlan()
                }
                setOnCloseIconClickListener {
                    if (!planning) {
                        commutePrefs.save(key, rules.copy(shops = rules.shops.filterNot { samePoint(it, shop) }))
                        waypoints.removeAll { samePoint(it, shop) }
                        waypointAdapter.notifyDataSetChanged()
                        drawWaypointMarkers()
                        clearPlan()
                    }
                }
            })
        }
    }

    private fun samePoint(a: Waypoint, b: Waypoint) =
        kotlin.math.abs(a.lat - b.lat) < 0.00001 && kotlin.math.abs(a.lon - b.lon) < 0.00001

    private fun chooseShop() {
        val choices = places.filter { place -> commuteEnds.none { samePoint(it, Waypoint(place.lat, place.lon)) } }
        AlertDialog.Builder(requireContext()).setTitle("Választható bolti megálló")
            .setItems((choices.map { it.name } + "Új hely kijelölése a térképen").toTypedArray()) { _, i ->
                if (i < choices.size) {
                    val p = choices[i]
                    saveShop(Waypoint(p.lat, p.lon, p.name))
                } else {
                    mapPick = "shop"
                    sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                    Toast.makeText(requireContext(), "Nyomj hosszan a bolt bejáratára, az út megfelelő oldalán.", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun saveShop(point: Waypoint) {
        val key = commuteKey ?: return
        val rules = commutePrefs.load(key)
        if (commuteEnds.any { samePoint(it, point) }) return
        if (rules.shops.none { samePoint(it, point) }) commutePrefs.save(key, rules.copy(shops = rules.shops + point.copy(via = false)))
        renderCommuteControls()
    }

    private fun pickMapPoint(point: Waypoint) {
        if (planning) return
        val mode = mapPick
        mapPick = null
        if (mode == "shop" && commuteKey != null) {
            val input = EditText(requireContext()).apply { hint = "Bolt neve" }
            AlertDialog.Builder(requireContext()).setTitle("Bolti megálló mentése").setView(input)
                .setPositiveButton("Mentés") { _, _ -> saveShop(point.copy(name = input.text.toString().trim().ifBlank { "Bolt" })) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        if (waypoints.size >= 12) {
            Toast.makeText(requireContext(), "Legfeljebb 12 pont adható meg.", Toast.LENGTH_LONG).show()
            return
        }
        if (mode == "via" && waypoints.size >= 2) {
            waypoints.add(waypoints.lastIndex, point.copy(name = "Kötelező átmenő pont", via = true))
            waypointAdapter.notifyDataSetChanged()
            drawWaypointMarkers()
            clearPlan()
        } else addWaypoint(point)
    }

    private fun addWaypoint(point: Waypoint) {
        if (planning) return
        if (waypoints.size >= 12) {
            Toast.makeText(requireContext(), "Legfeljebb 12 pont adható meg.", Toast.LENGTH_LONG).show()
            return
        }
        waypoints.add(point)
        waypointAdapter.notifyItemInserted(waypoints.size - 1)
        drawWaypointMarkers()
        clearPlan()
    }

    private fun drawWaypointMarkers() {
        markerOverlays.forEach { binding.map.overlays.remove(it) }
        markerOverlays.clear()

        for ((index, point) in waypoints.withIndex()) {
            val marker = Marker(binding.map).apply {
                position = GeoPoint(point.lat, point.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_waypoint)
                title = point.name ?: "${index + 1}. pont"
            }
            markerOverlays.add(marker)
            binding.map.overlays.add(marker)
        }
        binding.map.invalidate()
    }

    private fun requestPlan() {
        if (planning) return
        if (waypoints.size < 2) {
            Toast.makeText(requireContext(), R.string.route_need_two, Toast.LENGTH_SHORT).show()
            return
        }

        val style = when (binding.styleGroup.checkedButtonId) {
            R.id.btnStyleCurvy -> "CURVY"
            R.id.btnStyleComfort -> "COMFORT"
            R.id.btnStyleRatRun -> "RATRUN"
            else -> "FAST"
        }
        val requestRevision = revision
        val requestPoints = waypoints.toMutableList()
        if (style == "RATRUN" && requestPoints.size == 2) {
            selectedFamiliar?.let { requestPoints.add(1, it) }
        }
        planning = true
        renderCommuteControls()
        binding.progress.visibility = View.VISIBLE
        binding.btnPlan.isEnabled = false
        binding.btnPlan.setText(R.string.route_planning)

        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { RouteRepository.plan(requestPoints, style, arriveSameSide = sameSide) }
            if (_binding == null) return@launch

            planning = false
            renderCommuteControls()
            binding.progress.visibility = View.GONE
            binding.btnPlan.isEnabled = true
            binding.btnPlan.setText(R.string.route_plan_action)

            if (requestRevision != revision) return@launch
            result
                .onSuccess { plan -> showPlan(plan); autoSavePlan(requestPoints, plan) }
                .onFailure { error ->
                    val message = (error as? ApiClient.ApiException)?.message
                        ?: error.message
                        ?: "Ismeretlen hiba"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                }
        }
    }

    /**
     * A kész terv átadása a Google Maps-nek.
     *
     * A Maps csak pontokat vesz át, nyomvonalat nem: a felvett pontok mellé
     * ezért a tervezett vonalról vett mintákat is átadjuk, hogy a Maps ne a
     * saját leggyorsabb útját rakja ki a miénk helyett.
     */
    private fun openInGoogleMaps() {
        if (waypoints.size < 2) {
            Toast.makeText(requireContext(), R.string.route_need_two, Toast.LENGTH_SHORT).show()
            return
        }
        val shape = shapePoints.map { GoogleMapsLink.Point(it.latitude, it.longitude) }
        val link = GoogleMapsLink.build(waypoints, shape) ?: return

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
        val opened = runCatching {
            startActivity(Intent(intent).setPackage("com.google.android.apps.maps"))
        }.isSuccess || runCatching { startActivity(intent) }.isSuccess

        if (!opened) {
            Toast.makeText(requireContext(), R.string.route_gmaps_missing, Toast.LENGTH_LONG).show()
        } else if (link.droppedWaypoints) {
            Toast.makeText(
                requireContext(),
                getString(R.string.route_gmaps_simplified, GoogleMapsLink.MAX_WAYPOINTS),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Minden sikeres terv eltevése névkérés nélkül.
     *
     * A kézi mentés a megtartani való utakhoz van; ez viszont arra jó, hogy a
     * tegnap kiszámolt út holnap - akár net nélkül - is előkerüljön a Mentett
     * útvonalak közül. Csak a legutóbbi néhány marad meg.
     */
    private fun autoSavePlan(points: List<Waypoint>, result: RoutePlan) {
        val unknown = getString(R.string.route_unknown)
        val name = getString(
            R.string.route_line,
            points.first().name ?: unknown,
            points.last().name ?: unknown
        )
        // Nem a nézet életciklusán: a mentés akkor is fusson le, ha közben
        // másik fülre lépsz.
        App.backgroundScope.launch {
            runCatching { RouteRepository.autoSave(database, name, points, result) }
        }
    }

    private fun showPlan(result: RoutePlan) {
        plan = result
        shapePoints = PolylineCodec.decode(result.shape)
        drawRoute(result)

        binding.resultBox.visibility = View.VISIBLE
        binding.tvSummary.text = getString(
            R.string.route_summary,
            result.distanceMeters / 1000.0,
            formatDuration(result.times.totalAverage)
        )

        bindTime(binding.timeFastest, R.string.route_fastest,
            result.times.totalFastest, result.times.movingFastest, result.times.stopsFastest)
        bindTime(binding.timeAverage, R.string.route_average,
            result.times.totalAverage, result.times.movingAverage, result.times.stopsAverage)
        bindTime(binding.timeSlowest, R.string.route_slowest,
            result.times.totalSlowest, result.times.movingSlowest, result.times.stopsSlowest)

        val raw = JSONObject(result.raw)
        val details = mutableListOf(result.confidence.reason)
        raw.optJSONObject("avoidance")?.let { avoidance ->
            details += "${avoidance.optInt("trafficLights")} ismert lámpa · ${avoidance.optInt("leftTurns")} balra kanyar"
        }
        raw.optJSONObject("comfort")?.let { comfort ->
            val coverage = (comfort.optDouble("coverage") * 100).toInt()
            details += if (coverage > 0) getString(R.string.route_comfort_coverage, coverage)
            else getString(R.string.route_comfort_no_data)
        }
        binding.tvConfidence.text = details.joinToString("\n")
        if (result.warnings.isEmpty()) {
            binding.tvWarnings.visibility = View.GONE
        } else {
            binding.tvWarnings.visibility = View.VISIBLE
            binding.tvWarnings.text = result.warnings.joinToString("\n") { "• $it" }
        }

        showCornerSummary(result)
        sectionAdapter.submit(result.sections)

        if (shapePoints.isNotEmpty()) fitToSheet(shapePoints, hiddenShare = 0.9)
        // A tervezés után az útvonalat kell látni, nem a listát: a lapot
        // félmagasságba engedjük vissza.
        sheetBehavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }

    private val sheetBehavior get() = BottomSheetBehavior.from(binding.bottomSheet)

    private fun bindTime(
        column: ItemRouteTimeBinding,
        labelRes: Int,
        total: Long,
        moving: Long,
        stops: Long
    ) {
        column.tvTimeLabel.setText(labelRes)
        column.tvTimeValue.text = formatDuration(total)
        column.tvTimeBreakdown.text =
            getString(R.string.route_moving_stops, formatDuration(moving), formatDuration(stops))
    }

    private fun showCornerSummary(result: RoutePlan) {
        binding.cornerSummary.removeAllViews()
        val summary = result.cornerSummary
        val rows = listOf(
            Triple("HAIRPIN", summary.hairpin.count, summary.hairpin.meters),
            Triple("TIGHT", summary.tight.count, summary.tight.meters),
            Triple("MEDIUM", summary.medium.count, summary.medium.meters),
            Triple("GENTLE", summary.gentle.count, summary.gentle.meters),
            Triple("STRAIGHT", summary.straight.count, summary.straight.meters)
        )

        for ((type, count, meters) in rows) {
            if (count == 0) continue
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            val bar = View(requireContext()).apply {
                setBackgroundColor(ContextCompat.getColor(requireContext(), CornerStyle.colourOf(type)))
                layoutParams = LinearLayout.LayoutParams(18, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            val label = TextView(requireContext()).apply {
                text = getString(
                    R.string.route_corner_row,
                    getString(CornerStyle.labelOf(type)),
                    count,
                    if (meters >= 1000) String.format("%.1f km", meters / 1000.0) else "$meters m"
                )
                setPadding(20, 0, 0, 0)
            }
            row.addView(bar)
            row.addView(label)
            binding.cornerSummary.addView(row)
        }
    }

    /**
     * A vonal kanyartípus szerint színezve.
     *
     * Osztályonként egy-egy összefüggő futam kap saját vonalat - 20 méterenként
     * külön objektum egy 100 km-es úton 5000 overlay lenne, amitől az osmdroid
     * használhatatlanul belassulna. Futamokra vonva pár száz marad.
     */
    private fun drawRoute(result: RoutePlan) {
        routeOverlays.forEach { binding.map.overlays.remove(it) }
        routeOverlays.clear()
        clearHighlight()

        val classes = result.profile.cornerClass
        if (shapePoints.isEmpty() || classes.isEmpty()) return

        var runStart = 0
        var runClass = classes[0]

        fun flush(endExclusive: Int) {
            val from = runStart
            val to = minOf(endExclusive, shapePoints.size - 1)
            if (to <= from) return
            val line = Polyline().apply {
                // Egy ponttal túlnyúlunk, hogy a futamok között ne legyen rés.
                setPoints(shapePoints.subList(from, minOf(to + 1, shapePoints.size)))
                outlinePaint.strokeWidth = 12f
                outlinePaint.color =
                    ContextCompat.getColor(requireContext(), CornerStyle.colourOf(CornerStyle.typeOf(runClass)))
            }
            routeOverlays.add(line)
            binding.map.overlays.add(line)
        }

        for (i in 1 until minOf(classes.size, shapePoints.size)) {
            if (classes[i] != runClass) {
                flush(i)
                runStart = i
                runClass = classes[i]
            }
        }
        flush(minOf(classes.size, shapePoints.size))

        // A pontjelölők maradjanak legfelül.
        markerOverlays.forEach {
            binding.map.overlays.remove(it)
            binding.map.overlays.add(it)
        }
        binding.map.invalidate()
    }

    /** A listából kiválasztott szakasz kiemelése és ráállás. */
    private fun focusSection(section: RouteSection) {
        clearHighlight()
        if (shapePoints.isEmpty()) return

        val from = section.fromStep.coerceIn(0, shapePoints.size - 1)
        val to = section.toStep.coerceIn(from, shapePoints.size - 1)
        if (to <= from) return

        val line = Polyline().apply {
            setPoints(shapePoints.subList(from, to + 1))
            outlinePaint.strokeWidth = 22f
            outlinePaint.color = ContextCompat.getColor(requireContext(), R.color.route_highlight)
        }
        highlight = line
        binding.map.overlays.add(line)

        // Enélkül a kiemelt szakasz a lap alatt maradna.
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        fitToSheet(shapePoints.subList(from, to + 1), hiddenShare = 0.25)
    }

    /**
     * Ráállás úgy, hogy az útvonal a lap FÖLÖTT legyen.
     *
     * Az osmdroid a teljes nézetre igazít, a lap viszont az alját takarja, így
     * a beigazított útvonal fele takarásba kerülne. Ezért a dobozt délre
     * nyújtjuk: a középpont lejjebb csúszik, az útvonal pedig feljebb.
     */
    private fun fitToSheet(points: List<GeoPoint>, hiddenShare: Double) {
        if (points.isEmpty()) return
        val bounds = BoundingBox.fromGeoPointsSafe(points)
        val height = (bounds.latNorth - bounds.latSouth).coerceAtLeast(0.002)
        val adjusted = BoundingBox(
            bounds.latNorth,
            bounds.lonEast,
            bounds.latSouth - height * hiddenShare,
            bounds.lonWest
        )
        val map = binding.map
        map.post { if (_binding?.map === map) map.zoomToBoundingBox(adjusted, true, 60) }
    }

    private fun clearHighlight() {
        highlight?.let { binding.map.overlays.remove(it) }
        highlight = null
        binding.map.invalidate()
    }

    private fun clearPlan() {
        checkCommuteEndpoints()
        renderCommuteControls()
        revision++
        selectedFamiliar = null
        loadFamiliarRoutes()
        plan = null
        shapePoints = emptyList()
        routeOverlays.forEach { binding.map.overlays.remove(it) }
        routeOverlays.clear()
        clearHighlight()
        binding.resultBox.visibility = View.GONE
    }

    private fun loadFamiliarRoutes() {
        familiarOverlays.forEach { binding.map.overlays.remove(it) }
        familiarOverlays.clear()
        binding.familiarRoutes.removeAllViews()
        if (waypoints.size != 2) return
        val version = revision
        val endpoints = waypoints.toList()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { RouteRepository.familiar(endpoints) }
            if (_binding == null || revision != version) return@launch
            result.onFailure {
                binding.familiarRoutes.addView(TextView(requireContext()).apply {
                    text = "A megszokott utak nem tölthetők be. A Tervezés továbbra is használható."
                })
            }.onSuccess { routes ->
                if (routes.isEmpty()) {
                    binding.familiarRoutes.addView(TextView(requireContext()).apply {
                        text = "Ehhez az irányhoz még nincs megfelelő rögzített út."
                    })
                }
                val fastest = routes.minOfOrNull { it.optLong("averageMs") } ?: 0L
                val shortest = routes.minOfOrNull { it.optDouble("distanceMeters") } ?: 0.0
                val chips = mutableListOf<Chip>()
                routes.forEachIndexed { index, route ->
                    val faded = route.optLong("averageMs") > fastest || route.optDouble("distanceMeters") > shortest
                    val line = Polyline().apply {
                        setPoints(PolylineCodec.decode(route.getString("shape")))
                        outlinePaint.color = if (index == 0) android.graphics.Color.rgb(30, 136, 229) else android.graphics.Color.rgb(0, 137, 123)
                        outlinePaint.alpha = if (faded) 90 else 220
                        outlinePaint.strokeWidth = 10f
                    }
                    val chip = Chip(requireContext()).apply {
                        text = "${route.optString("label")} · ${route.optInt("count")} út · " +
                            String.format("%.1f km", route.optDouble("distanceMeters") / 1000) +
                            " · ${formatDuration(route.optLong("averageMs"))}"
                        isCheckable = true
                        alpha = if (faded) 0.55f else 1f
                    }
                    val select = select@{
                        if (planning) return@select
                        selectedFamiliar = Waypoint.parse(route.getJSONObject("anchor"))
                        chips.forEach { it.isChecked = it === chip }
                        familiarOverlays.forEach { it.outlinePaint.strokeWidth = if (it === line) 16f else 8f }
                        binding.styleGroup.check(R.id.btnStyleRatRun)
                        requestPlan()
                    }
                    chip.setOnClickListener { select() }
                    line.setOnClickListener { _, _, _ -> select(); true }
                    chips.add(chip)
                    familiarOverlays.add(line)
                    binding.map.overlays.add(line)
                    binding.familiarRoutes.addView(chip)
                }
                binding.map.invalidate()
                if (routes.isNotEmpty()) fitToSheet(routes.flatMap { PolylineCodec.decode(it.getString("shape")) }, 0.9)
            }
        }
    }

    private fun clearAll() {
        commuteKey = null
        mapPick = null
        waypoints.clear()
        waypointAdapter.notifyDataSetChanged()
        drawWaypointMarkers()
        clearPlan()
    }

    private fun saveRoute() {
        if (waypoints.size < 2) {
            Toast.makeText(requireContext(), R.string.route_need_two, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext()).apply {
            setHint(R.string.route_save_hint)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.route_save_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifBlank {
                    waypoints.last().name ?: "Útvonal"
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    RouteRepository.save(database, name, plan?.let { Waypoint.parseList(JSONObject(it.raw).optJSONArray("waypoints")) } ?: waypoints.toList(), plan)
                    if (_binding == null) return@launch
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.route_saved_ok, name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSavedRoutes() {
        viewLifecycleOwner.lifecycleScope.launch {
            val routes = withContext(Dispatchers.IO) { database.routeDao().getAll() }
            if (_binding == null) return@launch
            if (routes.isEmpty()) {
                Toast.makeText(requireContext(), R.string.route_no_saved, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = routes.map { route ->
                val prefix = if (route.autoSaved) getString(R.string.route_autosaved) + " · " else ""
                prefix + route.name + " · " + String.format("%.1f km", route.distanceMeters / 1000.0)
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.route_saved)
                .setItems(labels) { _, index -> loadSaved(routes[index]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun loadSaved(route: SavedRoute) {
        commuteKey = null
        waypoints.clear()
        waypoints.addAll(Waypoint.parseList(JSONArray(route.waypointsJson)))
        waypointAdapter.notifyDataSetChanged()
        drawWaypointMarkers()

        clearPlan()
        binding.styleGroup.check(
            when (route.style) {
                "CURVY" -> R.id.btnStyleCurvy
                "COMFORT" -> R.id.btnStyleComfort
                "RATRUN" -> R.id.btnStyleRatRun
                else -> R.id.btnStyleFast
            }
        )
        route.planJson?.let {
            binding.arriveSameSide.isChecked = runCatching { JSONObject(it).optJSONObject("options")?.optBoolean("arriveSameSide", true) ?: true }.getOrDefault(true)
        }
        // Az eltett terv net nélkül is megjeleníthető - pontosan az, amit
        // utoljára láttál.
        val stored = route.planJson
        if (stored != null) {
            runCatching { RoutePlan.parse(JSONObject(stored)) }
                .onSuccess { showPlan(it) }
                .onFailure { clearPlan() }
        } else {
            clearPlan()
        }

    }

    private fun formatDuration(millis: Long): String {
        val minutes = (millis / 60_000).toInt()
        return if (minutes >= 60) "${minutes / 60} ó ${minutes % 60} p" else "$minutes p"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("route_points", JSONArray().apply { waypoints.forEach { put(it.toJson()) } }.toString())
        outState.putString("commute_key", commuteKey)
        outState.putString("commute_ends", JSONArray().apply { commuteEnds.forEach { put(it.toJson()) } }.toString())
        outState.putBoolean("same_side", sameSide)
        outState.putString("route_style", when (binding.styleGroup.checkedButtonId) {
            R.id.btnStyleFast -> "FAST"
            R.id.btnStyleCurvy -> "CURVY"
            R.id.btnStyleComfort -> "COMFORT"
            else -> "RATRUN"
        })
    }

    override fun onResume() {
        super.onResume()
        binding.map.onResume()
        loadPlaceChips()
    }

    override fun onPause() {
        binding.map.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        revision++
        planning = false
        familiarOverlays.clear()
        binding.map.onDetach()
        routeOverlays.clear()
        markerOverlays.clear()
        highlight = null
        _binding = null
        super.onDestroyView()
    }
}
