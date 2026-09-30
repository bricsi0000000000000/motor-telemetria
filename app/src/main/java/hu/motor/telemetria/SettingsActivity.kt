package hu.motor.telemetria

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.PlaceType
import hu.motor.telemetria.databinding.ActivitySettingsBinding
import hu.motor.telemetria.databinding.ItemPlaceBinding
import hu.motor.telemetria.net.ServerSettings
import hu.motor.telemetria.net.ServiceCheck
import hu.motor.telemetria.service.PlaceGeofences
import hu.motor.telemetria.service.SensorTelemetryCollector
import hu.motor.telemetria.sync.SyncManager
import hu.motor.telemetria.sync.SyncStatus
import hu.motor.telemetria.util.AutoSettings
import hu.motor.telemetria.util.BikeBluetooth
import hu.motor.telemetria.util.Fmt
import hu.motor.telemetria.util.ThemeSettings
import hu.motor.telemetria.util.TelemetrySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Egy helyen minden beállítás: otthon és automatikus indítás, szerverkapcsolat,
 * valamint a nappali/éjszakai mód.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val placeDao by lazy { AppDatabase.get(this).placeDao() }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            chooseBluetoothDevice()
        } else {
            binding.switchBluetooth.isChecked = false
            Toast.makeText(this, R.string.bt_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            enableAuto()
        } else {
            binding.switchAuto.isChecked = false
            Toast.makeText(this, R.string.error_no_location_permission, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setUpAuto()
        setUpTelemetry()
        setUpServer()
        setUpSelfTest()
        setUpTheme()
    }

    private fun setUpTelemetry() {
        binding.switchTelemetry.isChecked = TelemetrySettings.enabled
        binding.switchTelemetry.setOnCheckedChangeListener { _, checked ->
            TelemetrySettings.enabled = checked
        }
        fun renderCalibration() {
            val quality = TelemetrySettings.calibrationQuality
            binding.tvTelemetryCalibration.text = if (quality >= 1f) {
                getString(R.string.telemetry_calibrated)
            } else {
                getString(R.string.telemetry_calibrating, (quality * 100).toInt())
            }
        }
        binding.btnSensorInventory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.telemetry_inventory_title)
                .setMessage(SensorTelemetryCollector.inventory(this))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.btnResetCalibration.setOnClickListener {
            TelemetrySettings.resetCalibration()
            renderCalibration()
            Toast.makeText(this, R.string.telemetry_reset_done, Toast.LENGTH_SHORT).show()
        }
        binding.btnManualMount.setOnClickListener {
            val current = (TelemetrySettings.manualMountQuarterTurns ?: -1) + 1
            AlertDialog.Builder(this)
                .setTitle(R.string.telemetry_manual_mount_title)
                .setSingleChoiceItems(R.array.telemetry_manual_mount_choices, current) { dialog, which ->
                    TelemetrySettings.manualMountQuarterTurns = (which - 1).takeIf { it >= 0 }
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        renderCalibration()
    }

    override fun onResume() {
        super.onResume()
        refreshBluetoothStatus()
        // A helyek szerkesztése külön képernyőn zajlik, ezért visszatéréskor újratöltjük.
        loadPlaces()
    }

    // --- helyek és automatika --------------------------------------------------

    private fun setUpAuto() {
        binding.switchAuto.isChecked = AutoSettings.autoEnabled
        binding.switchAuto.setOnCheckedChangeListener { _, checked ->
            if (checked) requestAuto() else disableAuto()
        }
        binding.btnAddPlace.setOnClickListener { openPlace(0L) }

        binding.switchBluetooth.isChecked = BikeBluetooth.required
        binding.switchBluetooth.setOnCheckedChangeListener { _, checked ->
            BikeBluetooth.required = checked
            // Feltételnek csak akkor van értelme, ha eszköz is van hozzá.
            if (checked && BikeBluetooth.deviceAddress == null) requestBluetoothDevice()
            renderBluetooth()
        }
        binding.btnBluetoothDevice.setOnClickListener { requestBluetoothDevice() }
        // Koppintás: újraellenőrzés. Hosszú nyomás: részletes állapot.
        binding.tvBluetoothStatus.setOnClickListener { refreshBluetoothStatus() }
        binding.tvBluetoothStatus.setOnLongClickListener {
            showBluetoothDiagnostics()
            true
        }
        renderBluetooth()
    }

    // --- motor Bluetooth -------------------------------------------------------

    private fun renderBluetooth() {
        val name = BikeBluetooth.deviceName
        binding.btnBluetoothDevice.text = if (name.isNullOrBlank()) {
            getString(R.string.bt_device_none)
        } else {
            getString(R.string.bt_device_selected, name)
        }
        refreshBluetoothStatus()
    }

    /** Látja-e épp a telefon a motor eszközét – ugyanaz, mint a Térkép fülön. */
    private fun refreshBluetoothStatus() {
        val name = BikeBluetooth.deviceName
        if (name.isNullOrBlank()) {
            binding.tvBluetoothStatus.visibility = View.GONE
            return
        }
        binding.tvBluetoothStatus.visibility = View.VISIBLE

        if (!BikeBluetooth.hasPermission(this)) {
            showBluetoothStatus(getString(R.string.bt_status_no_permission), connected = false)
            return
        }

        // Előbb az utoljára látott állapot, aztán a rendszertől kért pontos válasz.
        val cached = BikeBluetooth.wasConnected(this)
        showBluetoothStatus(bluetoothStatusText(name, cached), cached)

        lifecycleScope.launch {
            val connected = BikeBluetooth.isConnected(this@SettingsActivity)
            showBluetoothStatus(bluetoothStatusText(name, connected), connected)
        }
    }

    private fun bluetoothStatusText(name: String, connected: Boolean) = getString(
        if (connected) R.string.bt_status_connected else R.string.bt_status_disconnected,
        name
    )

    private fun showBluetoothStatus(text: String, connected: Boolean) {
        val colour = ContextCompat.getColor(
            this,
            if (connected) R.color.place_home else R.color.on_surface
        )
        val dot = ResourcesCompat.getDrawable(resources, R.drawable.dot_status, theme)
        dot?.setTint(colour)
        dot?.setBounds(0, 0, dot.intrinsicWidth, dot.intrinsicHeight)

        binding.tvBluetoothStatus.setCompoundDrawables(dot, null, null, null)
        binding.tvBluetoothStatus.text = text
        binding.tvBluetoothStatus.alpha = if (connected) 1f else 0.6f
    }

    private fun showBluetoothDiagnostics() {
        lifecycleScope.launch {
            val report = BikeBluetooth.diagnostics(this@SettingsActivity)
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle(R.string.bt_diagnostics_title)
                .setMessage(report)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun requestBluetoothDevice() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !BikeBluetooth.hasPermission(this)) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        chooseBluetoothDevice()
    }

    /**
     * A párosított eszközök közül lehet kiválasztani a motor kihangosítóját.
     * A Bluetooth-engedélyt a hívó (requestBluetoothDevice) ellenőrzi.
     */
    @SuppressLint("MissingPermission")
    private fun chooseBluetoothDevice() {
        val devices = BikeBluetooth.pairedDevices(this)
        if (devices.isEmpty()) {
            Toast.makeText(this, R.string.bt_no_paired, Toast.LENGTH_LONG).show()
            return
        }
        val labels = devices.map { device ->
            runCatching { device.name }.getOrNull() ?: device.address
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.bt_choose)
            .setItems(labels) { _, index ->
                val device = devices[index]
                BikeBluetooth.setDevice(device.address, labels[index])
                renderBluetooth()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadPlaces() {
        lifecycleScope.launch {
            val places = withContext(Dispatchers.IO) { placeDao.getAll() }
            renderPlaces(places)
            renderAutoState(places)
        }
    }

    private fun renderPlaces(places: List<Place>) {
        val container = binding.placesList
        container.removeAllViews()

        if (places.isEmpty()) {
            val empty = android.widget.TextView(this)
            empty.setText(R.string.place_list_empty)
            empty.textSize = 13f
            empty.alpha = 0.6f
            container.addView(empty)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (place in places) {
            val row = ItemPlaceBinding.inflate(inflater, container, false)
            row.ivPlace.setImageResource(iconFor(place.placeType))
            row.tvPlaceName.text = place.name
            row.tvPlaceDetail.text = getString(
                R.string.place_row_detail,
                getString(labelFor(place.placeType)),
                place.radiusMeters,
                String.format(Locale.US, "%.5f", place.lat),
                String.format(Locale.US, "%.5f", place.lon)
            )
            row.root.setOnClickListener { openPlace(place.id) }
            container.addView(row.root)
        }
    }

    private fun renderAutoState(places: List<Place>) {
        binding.tvAutoState.text = when {
            places.isEmpty() -> getString(R.string.auto_state_no_places)
            !AutoSettings.autoEnabled -> getString(R.string.auto_state_off)
            !hasBackgroundPermission() -> getString(R.string.auto_state_needs_background)
            else -> getString(R.string.auto_state_on)
        }
    }

    private fun iconFor(type: PlaceType) = when (type) {
        PlaceType.HOME -> R.drawable.ic_place_home
        PlaceType.WORK -> R.drawable.ic_place_work
        PlaceType.DESTINATION -> R.drawable.ic_place_destination
    }

    private fun labelFor(type: PlaceType) = when (type) {
        PlaceType.HOME -> R.string.place_type_home
        PlaceType.WORK -> R.string.place_type_work
        PlaceType.DESTINATION -> R.string.place_type_destination
    }

    private fun openPlace(id: Long) {
        startActivity(
            Intent(this, PlaceEditActivity::class.java)
                .putExtra(PlaceEditActivity.EXTRA_PLACE_ID, id)
        )
    }

    private fun requestAuto() {
        if (!hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        enableAuto()
    }

    private fun enableAuto() {
        AutoSettings.autoEnabled = true
        PlaceGeofences.refresh(this)
        loadPlaces()

        // Háttérhelyzet nélkül csak addig figyel, amíg egyszer megnyitottad az
        // appot; újraindítás után nem indulna magától.
        if (!hasBackgroundPermission()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.auto_background_title)
                .setMessage(R.string.auto_background_message)
                .setPositiveButton(R.string.action_settings) { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)
                        )
                    )
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun disableAuto() {
        AutoSettings.autoEnabled = false
        PlaceGeofences.refresh(this)
        loadPlaces()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    // --- szolgáltatások tesztje ------------------------------------------------

    private fun setUpSelfTest() {
        binding.btnSelfTest.setOnClickListener { runSelfTest(includeWaze = false) }
        binding.btnSelfTestWaze.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.selftest_run_waze)
                .setMessage(R.string.selftest_waze_confirm)
                .setPositiveButton(R.string.selftest_run) { _, _ -> runSelfTest(includeWaze = true) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    /**
     * Az eredmények soronként jelennek meg, ahogy megérkeznek; közben látszik,
     * hányadik lépésnél tartunk, a végén pedig egy összegző sor.
     */
    private fun runSelfTest(includeWaze: Boolean) {
        binding.btnSelfTest.isEnabled = false
        binding.btnSelfTestWaze.isEnabled = false
        binding.progressSelfTest.visibility = View.VISIBLE
        binding.tvSelfTestStatus.visibility = View.VISIBLE
        binding.tvSelfTestStatus.setText(R.string.selftest_running)
        binding.tvSelfTest.text = ""

        val lines = mutableListOf<String>()
        val results = mutableListOf<hu.motor.telemetria.net.CheckResult>()

        lifecycleScope.launch {
            ServiceCheck.run(
                context = this@SettingsActivity,
                includeWaze = includeWaze,
                onStep = { index, name ->
                    // A háttérszálról jön, ezért a kiírás a fő szálra megy.
                    binding.tvSelfTestStatus.post {
                        binding.tvSelfTestStatus.text = getString(
                            R.string.selftest_step,
                            index + 1,
                            ServiceCheck.steps.size,
                            name
                        )
                    }
                },
                onResult = { result ->
                    results += result
                    lines += getString(
                        R.string.selftest_line,
                        if (result.ok) "✓" else "✗",
                        result.name,
                        if (result.millis > 0) " " + getString(R.string.selftest_ms, result.millis) else "",
                        result.detail
                    )
                    binding.tvSelfTest.post { binding.tvSelfTest.text = lines.joinToString("\n") }
                }
            )

            val failed = results.filterNot { it.ok }
            binding.tvSelfTestStatus.text = if (failed.isEmpty()) {
                getString(R.string.selftest_all_ok, results.size)
            } else {
                getString(R.string.selftest_failed, failed.size, failed.joinToString { it.name })
            }
            binding.tvSelfTestStatus.setTextColor(
                ContextCompat.getColor(
                    this@SettingsActivity,
                    if (failed.isEmpty()) R.color.place_home else R.color.warning
                )
            )
            binding.progressSelfTest.visibility = View.GONE
            binding.btnSelfTest.isEnabled = true
            binding.btnSelfTestWaze.isEnabled = true
            Toast.makeText(this@SettingsActivity, binding.tvSelfTestStatus.text, Toast.LENGTH_LONG).show()
        }
    }

    // --- szerver ---------------------------------------------------------------

    private fun setUpServer() {
        binding.etServerUrl.setText(ServerSettings.baseUrl)
        binding.etToken.setText(ServerSettings.token)
        binding.tvDevice.text = getString(R.string.device_uid, ServerSettings.deviceUid)

        binding.btnSaveServer.setOnClickListener { saveServerSettings() }
        binding.btnSyncNow.setOnClickListener {
            SyncManager.requestSync()
            Toast.makeText(this, R.string.sync_started, Toast.LENGTH_SHORT).show()
        }
        binding.btnTestServer.setOnClickListener { testConnection() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyncManager.status.collectLatest { renderSyncStatus(it) }
            }
        }
        SyncManager.refreshPending()
    }

    private fun renderSyncStatus(status: SyncStatus) {
        binding.tvSyncState.text = when {
            status.syncing -> getString(R.string.sync_running)
            status.pendingTracks > 0 -> resources.getQuantityString(
                R.plurals.sync_pending, status.pendingTracks, status.pendingTracks
            )
            else -> getString(R.string.sync_up_to_date)
        }

        binding.tvLastSync.text = if (status.lastSyncAt > 0) {
            getString(R.string.sync_last, Fmt.dateTime(status.lastSyncAt))
        } else {
            getString(R.string.sync_never)
        }

        binding.tvSyncError.visibility = if (status.lastError == null) View.GONE else View.VISIBLE
        binding.tvSyncError.text = status.lastError?.let { getString(R.string.sync_error, it) }
    }

    private fun saveServerSettings() {
        val url = binding.etServerUrl.text?.toString().orEmpty().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, R.string.server_url_invalid, Toast.LENGTH_LONG).show()
            return
        }
        ServerSettings.baseUrl = url
        ServerSettings.token = binding.etToken.text?.toString().orEmpty().trim()
        binding.etServerUrl.setText(ServerSettings.baseUrl)
        Toast.makeText(this, R.string.server_saved, Toast.LENGTH_SHORT).show()
        SyncManager.requestSync()
    }

    private fun testConnection() {
        binding.btnTestServer.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { SyncManager.ping() }
            binding.btnTestServer.isEnabled = true
            val message = result.getOrElse { getString(R.string.server_unreachable, it.message) }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    // --- megjelenés ------------------------------------------------------------

    private fun setUpTheme() {
        val checkedId = when (ThemeSettings.mode) {
            ThemeSettings.Mode.LIGHT -> R.id.btnThemeLight
            ThemeSettings.Mode.DARK -> R.id.btnThemeDark
            ThemeSettings.Mode.SYSTEM -> R.id.btnThemeSystem
        }
        binding.themeToggle.check(checkedId)

        // A figyelőt csak a kezdeti beállítás után kötjük be, különben a
        // visszaállítás azonnal újraindítaná a témaváltást.
        binding.themeToggle.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (buttonId) {
                R.id.btnThemeLight -> ThemeSettings.Mode.LIGHT
                R.id.btnThemeDark -> ThemeSettings.Mode.DARK
                else -> ThemeSettings.Mode.SYSTEM
            }
            if (mode != ThemeSettings.mode) ThemeSettings.mode = mode
        }
    }
}
