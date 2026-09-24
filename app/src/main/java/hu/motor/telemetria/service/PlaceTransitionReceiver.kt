package hu.motor.telemetria.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import hu.motor.telemetria.MainActivity
import hu.motor.telemetria.R
import hu.motor.telemetria.util.AutoSettings
import hu.motor.telemetria.util.BikeBluetooth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A rendszer közelségi riasztása: kiléptünk egy hely köréből, vagy beléptünk.
 *
 * Kilépéskor elindítjuk a mérést. A leállítás nem itt van: a belépés önmagában
 * még nem jelenti, hogy meg is érkeztünk (át is haladhatunk a körön), ezért azt
 * a [TrackingService] dönti el, amikor tényleg megállunk.
 */
class PlaceTransitionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PlaceTransition"
        private const val CHANNEL_ID = "auto_start"
        private const val NOTIFICATION_ID = 1003
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        val name = intent.getStringExtra(PlaceGeofences.EXTRA_PLACE_NAME).orEmpty()

        if (!AutoSettings.autoEnabled) return
        // Belépéskor nincs teendő: a megérkezést a mérés közben ismerjük fel.
        if (entering) return
        if (TrackingService.state.value.isActive) return

        BikeBluetooth.init(context)
        if (!BikeBluetooth.required) {
            startTracking(context, name)
            return
        }

        // A motor Bluetooth-a a feltétel: a lekérdezés hálózati hívás nélkül is
        // tarthat egy pillanatig, ezért a broadcastot életben tartjuk addig.
        val pending = goAsync()
        val appContext = context.applicationContext
        scope.launch {
            try {
                if (BikeBluetooth.isConnected(appContext)) {
                    startTracking(appContext, name)
                } else {
                    Log.i(TAG, "Kiléptünk innen: $name, de a motor Bluetooth-a nem csatlakozik")
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun startTracking(context: Context, name: String) {
        Log.i(TAG, "Kiléptünk innen: $name – mérés indítása")
        try {
            TrackingService.start(context)
        } catch (e: Exception) {
            // Androidon 12-től a háttérből indított előtérszolgáltatást a rendszer
            // megtagadhatja. Ilyenkor nem vész el az indulás: szólunk egy
            // koppintható értesítéssel, ami megnyitja az appot és elindítja.
            Log.w(TAG, "Nem indulhatott a mérés a háttérből: ${e.message}")
            notifyManualStart(context, name)
        }
    }

    private fun notifyManualStart(context: Context, placeName: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.auto_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.auto_channel_desc)
                }
            )
        }

        val open = PendingIntent.getActivity(
            context,
            32,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START_TRACKING, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_route)
                .setContentTitle(context.getString(R.string.auto_manual_title))
                .setContentText(context.getString(R.string.auto_manual_text, placeName))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }
}
