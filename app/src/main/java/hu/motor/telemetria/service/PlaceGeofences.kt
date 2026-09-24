package hu.motor.telemetria.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.util.AutoSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A helyek köreit a rendszerre bízzuk: minden helyhez kérünk egy közelségi
 * riasztást (proximity alert), és a rendszer szól, ha átléptük a határt.
 *
 * Így nincs saját, folyamatosan futó figyelő szolgáltatásunk – tehát állandó
 * értesítés sincs, és nem is fogyaszt semmit, amíg épp nem lépünk ki egy körből.
 */
object PlaceGeofences {

    private const val TAG = "PlaceGeofences"

    const val ACTION_TRANSITION = "hu.motor.telemetria.action.PLACE_TRANSITION"
    const val EXTRA_PLACE_ID = "place_id"
    const val EXTRA_PLACE_NAME = "place_name"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Az eddig regisztrált körök – kikapcsoláskor és újratöltéskor ezeket vonjuk vissza. */
    private const val PREFS = "geofence_prefs"
    private const val KEY_REGISTERED = "registered_ids"

    /**
     * A beállítás és a helyek listája szerint (újra)regisztrálja a köröket.
     * Bármikor hívható: előbb mindent visszavon, aztán felveszi az aktuálisat.
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            val places = if (AutoSettings.autoEnabled) {
                AppDatabase.get(appContext).placeDao().getActive()
            } else {
                emptyList()
            }
            register(appContext, places)
        }
    }

    private fun register(context: Context, places: List<Place>) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // A korábbi köröket ugyanazzal a PendingIntenttel lehet visszavonni,
        // ezért az azonosítókat el kell tennünk.
        val previous = prefs.getStringSet(KEY_REGISTERED, emptySet()).orEmpty()
        for (id in previous) {
            val pending = pendingIntent(context, id.toLongOrNull() ?: continue, "", update = false)
            runCatching { manager.removeProximityAlert(pending) }
            pending.cancel()
        }

        if (places.isEmpty() || !hasLocationPermission(context)) {
            prefs.edit().putStringSet(KEY_REGISTERED, emptySet()).apply()
            return
        }

        val registered = mutableSetOf<String>()
        for (place in places) {
            try {
                manager.addProximityAlert(
                    place.lat,
                    place.lon,
                    place.radiusMeters.toFloat(),
                    // -1: soha nem jár le (újraindításig; azt a BootReceiver pótolja).
                    -1L,
                    pendingIntent(context, place.id, place.name, update = true)
                )
                registered += place.id.toString()
            } catch (e: SecurityException) {
                Log.w(TAG, "Nincs jogunk közelségi riasztáshoz: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Hibás kör: ${place.name}")
            }
        }
        prefs.edit().putStringSet(KEY_REGISTERED, registered).apply()
    }

    /**
     * A riasztás a beérkező Intentbe teszi bele, hogy be- vagy kilépés történt,
     * ezért a PendingIntent nem lehet immutable.
     */
    private fun pendingIntent(
        context: Context,
        placeId: Long,
        placeName: String,
        update: Boolean
    ): PendingIntent {
        val intent = Intent(context, PlaceTransitionReceiver::class.java)
            .setAction(ACTION_TRANSITION)
            // Az adat teszi egyedivé a PendingIntentet helyenként.
            .setData(android.net.Uri.parse("motor://place/$placeId"))
            .putExtra(EXTRA_PLACE_ID, placeId)
            .putExtra(EXTRA_PLACE_NAME, placeName)

        val flags = PendingIntent.FLAG_MUTABLE or
            if (update) PendingIntent.FLAG_UPDATE_CURRENT else 0

        return PendingIntent.getBroadcast(context, placeId.toInt(), intent, flags)
    }

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
