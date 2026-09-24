package hu.motor.telemetria.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** Kezdőképernyős összesítő: össztáv, túrák száma, ez a hónap, csúcsok. */
class StatsWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        StatsWidgetRenderer.refresh(context, appWidgetManager, appWidgetIds)
    }
}
