package hu.motor.telemetria.service

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.util.AutoSettings
import hu.motor.telemetria.util.BikeBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A motor Bluetooth-eszközének csatlakozása és bontása.
 *
 * Ezt a két rendszerüzenetet manifestből is meg lehet kapni, tehát nem kell
 * hozzá futó szolgáltatás. A csatlakozás állapotát eltesszük, hogy az
 * automatikus indítás el tudja dönteni: tényleg motoron ülünk-e.
 */
class BluetoothStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BikeBluetooth"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val device: BluetoothDevice? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        val address = device?.address ?: return
        BikeBluetooth.init(context)
        if (address != BikeBluetooth.deviceAddress) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                BikeBluetooth.markConnected(context, true)
                Log.i(TAG, "Motor csatlakozott")
                maybeStartAfterConnect(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                BikeBluetooth.markConnected(context, false)
                Log.i(TAG, "Motor lecsatlakozott")
            }
        }
    }

    /**
     * Ha már úton vagyunk (nem vagyunk egyik megadott helyen belül sem), és csak
     * most kapcsoltuk be a fejhallgatót, akkor is induljon a mérés – enélkül
     * elveszne a túra eleje.
     */
    private fun maybeStartAfterConnect(context: Context) {
        if (!AutoSettings.autoEnabled || !BikeBluetooth.required) return
        if (TrackingService.state.value.isActive) return

        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                val location = lastKnownLocation(appContext) ?: return@launch
                val places = AppDatabase.get(appContext).placeDao().getActive()
                if (places.isEmpty()) return@launch

                val insideSomePlace = places.any {
                    it.contains(location.latitude, location.longitude)
                }
                if (insideSomePlace) return@launch

                runCatching { TrackingService.start(appContext) }
                    .onFailure { Log.w(TAG, "Nem indulhatott a mérés: ${it.message}") }
            } finally {
                pending.finish()
            }
        }
    }

    private fun lastKnownLocation(context: Context) =
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            null
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            try {
                manager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: manager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) {
                null
            }
        }
}
