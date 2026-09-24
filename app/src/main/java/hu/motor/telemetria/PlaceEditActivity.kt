package hu.motor.telemetria

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.PlaceType
import hu.motor.telemetria.databinding.ActivityPlaceEditBinding
import hu.motor.telemetria.service.PlaceGeofences
import hu.motor.telemetria.util.AutoSettings
import hu.motor.telemetria.util.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.TilesOverlay
import java.util.Locale

/**
 * Hely felvétele és szerkesztése térképen.
 *
 * A kör közepe mindig a térkép közepe (célkereszt), így egy célpontot is meg
 * lehet adni – nem csak azt, ahol épp állunk.
 */
class PlaceEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
    }

    private lateinit var binding: ActivityPlaceEditBinding
    private val dao by lazy { AppDatabase.get(this).placeDao() }

    private var placeId = 0L
    private var existing: Place? = null
    private var circle: Polygon? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaceEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        placeId = intent.getLongExtra(EXTRA_PLACE_ID, 0L)

        with(binding.map) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            if (ThemeSettings.isNight(this@PlaceEditActivity)) {
                overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            }
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(47.1625, 19.5033))
            // Mozgatás közben rajzoljuk újra a kört, hogy látszódjon a mérete.
            addMapListener(object : org.osmdroid.events.MapListener {
                override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                    drawCircle()
                    return false
                }

                override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                    drawCircle()
                    return false
                }
            })
        }

        binding.etRadius.setText(AutoSettings.defaultRadiusMeters.toString())
        binding.typeToggle.check(R.id.btnTypeDestination)
        binding.typeToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (isChecked) onTypeChanged(buttonId)
        }
        binding.fabHere.setOnClickListener { centreOnCurrentLocation() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }

        if (placeId > 0) loadExisting() else centreOnCurrentLocation()
    }

    private fun loadExisting() {
        lifecycleScope.launch {
            val place = withContext(Dispatchers.IO) { dao.get(placeId) }
            if (place == null) {
                finish()
                return@launch
            }
            existing = place
            binding.etName.setText(place.name)
            binding.etRadius.setText(place.radiusMeters.toString())
            binding.typeToggle.check(
                when (place.placeType) {
                    PlaceType.HOME -> R.id.btnTypeHome
                    PlaceType.WORK -> R.id.btnTypeWork
                    PlaceType.DESTINATION -> R.id.btnTypeDestination
                }
            )
            binding.btnDelete.visibility = View.VISIBLE
            binding.map.controller.setCenter(GeoPoint(place.lat, place.lon))
            drawCircle()
        }
    }

    /** Új helynél a típus adja az alapértelmezett nevet is. */
    private fun onTypeChanged(buttonId: Int) {
        drawCircle()
        if (existing != null || binding.etName.text?.isNotBlank() == true) return
        binding.etName.setText(
            when (buttonId) {
                R.id.btnTypeHome -> getString(R.string.place_type_home)
                R.id.btnTypeWork -> getString(R.string.place_type_work)
                else -> ""
            }
        )
    }

    private fun selectedType(): PlaceType = when (binding.typeToggle.checkedButtonId) {
        R.id.btnTypeHome -> PlaceType.HOME
        R.id.btnTypeWork -> PlaceType.WORK
        else -> PlaceType.DESTINATION
    }

    private fun currentRadius(): Int =
        binding.etRadius.text?.toString()?.trim()?.toIntOrNull()
            ?.coerceIn(AutoSettings.MIN_RADIUS_M, AutoSettings.MAX_RADIUS_M)
            ?: AutoSettings.defaultRadiusMeters

    private fun drawCircle() {
        val centre = binding.map.mapCenter
        circle?.let { binding.map.overlays.remove(it) }
        val polygon = Polygon(binding.map).apply {
            fillPaint.color = ContextCompat.getColor(this@PlaceEditActivity, R.color.place_circle_fill)
            outlinePaint.color = ContextCompat.getColor(this@PlaceEditActivity, R.color.place_circle_stroke)
            outlinePaint.strokeWidth = 4f
            setInfoWindow(null)
            points = Polygon.pointsAsCircle(
                GeoPoint(centre.latitude, centre.longitude),
                currentRadius().toDouble()
            )
        }
        circle = polygon
        binding.map.overlays.add(polygon)
        binding.map.invalidate()

        binding.tvCoords.text = getString(
            R.string.place_coords,
            String.format(Locale.US, "%.5f", centre.latitude),
            String.format(Locale.US, "%.5f", centre.longitude)
        )
    }

    private fun centreOnCurrentLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            drawCircle()
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = try {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            null
        }
        if (last != null) {
            binding.map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
        } else {
            Toast.makeText(this, R.string.auto_no_fix, Toast.LENGTH_SHORT).show()
        }
        drawCircle()
    }

    private fun save() {
        val centre = binding.map.mapCenter
        val radius = binding.etRadius.text?.toString()?.trim()?.toIntOrNull()
        if (radius == null || radius < AutoSettings.MIN_RADIUS_M || radius > AutoSettings.MAX_RADIUS_M) {
            Toast.makeText(
                this,
                getString(
                    R.string.auto_radius_invalid,
                    AutoSettings.MIN_RADIUS_M,
                    AutoSettings.MAX_RADIUS_M
                ),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val type = selectedType()
        val name = binding.etName.text?.toString()?.trim().orEmpty().ifBlank {
            getString(
                when (type) {
                    PlaceType.HOME -> R.string.place_type_home
                    PlaceType.WORK -> R.string.place_type_work
                    PlaceType.DESTINATION -> R.string.place_type_destination
                }
            )
        }

        val place = (existing ?: Place(name = name, lat = 0.0, lon = 0.0)).copy(
            name = name,
            type = type.name,
            lat = centre.latitude,
            lon = centre.longitude,
            radiusMeters = radius
        )

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (place.id > 0) dao.update(place) else dao.insert(place)
            }
            AutoSettings.defaultRadiusMeters = radius
            // A készenlét azonnal az új listával dolgozzon tovább.
            PlaceGeofences.refresh(this@PlaceEditActivity)
            finish()
        }
    }

    private fun confirmDelete() {
        val place = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.place_delete_title)
            .setMessage(getString(R.string.place_delete_message, place.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { dao.delete(place) }
                    PlaceGeofences.refresh(this@PlaceEditActivity)
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
