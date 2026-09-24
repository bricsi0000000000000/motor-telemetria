package hu.motor.telemetria.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import hu.motor.telemetria.util.AutoSettings

/**
 * Újraindítás után a rendszer elfelejti a közelségi riasztásokat, ezért itt
 * regisztráljuk újra őket.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AutoSettings.init(context)
        PlaceGeofences.refresh(context)
    }
}
