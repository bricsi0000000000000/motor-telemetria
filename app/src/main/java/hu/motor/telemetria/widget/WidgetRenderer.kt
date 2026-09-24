package hu.motor.telemetria.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import hu.motor.telemetria.MainActivity
import hu.motor.telemetria.R
import hu.motor.telemetria.service.TrackingService
import hu.motor.telemetria.service.TrackingState
import hu.motor.telemetria.service.TrackingStatus
import hu.motor.telemetria.util.Fmt

/**
 * A kezdőképernyős widgetek tartalmát állítja elő.
 *
 * Fontos részlet: az **Indítás** nem közvetlenül a szolgáltatásnak megy, hanem
 * megnyitja az alkalmazást, ami előtérből indítja a mérést. Androidon 12-től
 * ugyanis háttérből nem lehet előtérszolgáltatást indítani, ráadásul így az
 * esetleg hiányzó engedélyeket is meg tudjuk kérni. A Szünet/Folytatás/Leállítás
 * viszont mehet egyenesen a szolgáltatásnak, hiszen az ilyenkor már fut.
 */
object WidgetRenderer {

    private const val RC_START = 10
    private const val RC_PAUSE = 11
    private const val RC_RESUME = 12
    private const val RC_STOP = 13
    private const val RC_OPEN = 14

    /** Minden kirakott widget frissítése az aktuális állapottal. */
    fun updateAll(context: Context) {
        val state = TrackingService.state.value
        val manager = AppWidgetManager.getInstance(context) ?: return

        push(context, manager, TrackingWidget::class.java) { buildFull(context, state) }
        push(context, manager, TrackingControlWidget::class.java) { buildCompact(context, state) }
    }

    private fun push(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<*>,
        build: () -> RemoteViews
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids.isEmpty()) return
        manager.updateAppWidget(ids, build())
    }

    // --- nagy widget: műszerfal + három gomb -----------------------------------

    fun buildFull(context: Context, state: TrackingState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_tracking)

        views.setTextViewText(R.id.wSpeed, Fmt.speedKmh(state.currentSpeedMps))
        views.setTextViewText(R.id.wDistance, Fmt.distance(state.distanceMeters))
        views.setTextViewText(R.id.wDuration, Fmt.duration(state.durationMillis))
        views.setTextViewText(R.id.wAvgSpeed, Fmt.speedKmhUnit(state.avgSpeedMps))
        views.setTextViewText(R.id.wStatus, context.getString(statusLabel(state.status)))

        val paused = state.status == TrackingStatus.PAUSED
        views.setTextViewText(
            R.id.wBtnPause,
            context.getString(if (paused) R.string.action_resume else R.string.action_pause)
        )

        views.setBoolean(R.id.wBtnStart, "setEnabled", state.status == TrackingStatus.IDLE)
        views.setBoolean(R.id.wBtnPause, "setEnabled", state.isActive)
        views.setBoolean(R.id.wBtnStop, "setEnabled", state.isActive)

        views.setOnClickPendingIntent(R.id.wBtnStart, startIntent(context))
        views.setOnClickPendingIntent(
            R.id.wBtnPause,
            if (paused) serviceIntent(context, TrackingService.ACTION_RESUME, RC_RESUME)
            else serviceIntent(context, TrackingService.ACTION_PAUSE, RC_PAUSE)
        )
        views.setOnClickPendingIntent(
            R.id.wBtnStop,
            serviceIntent(context, TrackingService.ACTION_STOP, RC_STOP)
        )
        views.setOnClickPendingIntent(R.id.wHeader, openIntent(context))

        return views
    }

    // --- kompakt widget: egy váltógomb + leállítás ------------------------------

    fun buildCompact(context: Context, state: TrackingState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_compact)

        views.setTextViewText(R.id.wStatus, context.getString(statusLabel(state.status)))
        views.setTextViewText(
            R.id.wValue,
            if (state.isActive) Fmt.distance(state.distanceMeters)
            else Fmt.speedKmhUnit(state.currentSpeedMps)
        )

        when (state.status) {
            TrackingStatus.RUNNING -> {
                views.setImageViewResource(R.id.wBtnToggle, R.drawable.ic_pause)
                views.setContentDescription(R.id.wBtnToggle, context.getString(R.string.action_pause))
                views.setOnClickPendingIntent(
                    R.id.wBtnToggle,
                    serviceIntent(context, TrackingService.ACTION_PAUSE, RC_PAUSE)
                )
            }
            TrackingStatus.PAUSED -> {
                views.setImageViewResource(R.id.wBtnToggle, R.drawable.ic_play)
                views.setContentDescription(R.id.wBtnToggle, context.getString(R.string.action_resume))
                views.setOnClickPendingIntent(
                    R.id.wBtnToggle,
                    serviceIntent(context, TrackingService.ACTION_RESUME, RC_RESUME)
                )
            }
            TrackingStatus.IDLE -> {
                views.setImageViewResource(R.id.wBtnToggle, R.drawable.ic_play)
                views.setContentDescription(R.id.wBtnToggle, context.getString(R.string.action_start))
                views.setOnClickPendingIntent(R.id.wBtnToggle, startIntent(context))
            }
        }

        views.setViewVisibility(R.id.wBtnStop, if (state.isActive) View.VISIBLE else View.GONE)
        views.setOnClickPendingIntent(
            R.id.wBtnStop,
            serviceIntent(context, TrackingService.ACTION_STOP, RC_STOP)
        )
        views.setOnClickPendingIntent(R.id.wInfo, openIntent(context))

        return views
    }

    private fun statusLabel(status: TrackingStatus) = when (status) {
        TrackingStatus.RUNNING -> R.string.status_recording
        TrackingStatus.PAUSED -> R.string.status_paused
        TrackingStatus.IDLE -> R.string.status_idle
    }

    // --- szándékok -------------------------------------------------------------

    private fun startIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            RC_START,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_START_TRACKING, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            RC_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, TrackingService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, requestCode, intent, flags)
        } else {
            PendingIntent.getService(context, requestCode, intent, flags)
        }
    }
}
