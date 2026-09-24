package hu.motor.telemetria.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import hu.motor.telemetria.service.TrackingService

/** Kompakt widget: egy indít/szünet váltógomb és leállítás, minimális helyen. */
class TrackingControlWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = TrackingService.state.value
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, WidgetRenderer.buildCompact(context, state))
        }
    }
}
