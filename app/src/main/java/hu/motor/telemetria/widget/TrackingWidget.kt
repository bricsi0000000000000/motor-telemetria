package hu.motor.telemetria.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import hu.motor.telemetria.service.TrackingService

/** Nagy widget: sebesség, táv, idő, átlag és a három vezérlőgomb. */
class TrackingWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = TrackingService.state.value
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, WidgetRenderer.buildFull(context, state))
        }
    }
}
