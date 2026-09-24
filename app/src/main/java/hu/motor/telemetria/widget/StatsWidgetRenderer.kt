package hu.motor.telemetria.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import hu.motor.telemetria.MainActivity
import hu.motor.telemetria.R
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.util.Fmt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A statisztika widget tartalma.
 *
 * A többi widgettől eltérően ez adatbázisból dolgozik, ezért az összesítőt
 * háttérszálon kérjük le, és csak a kész értékekkel rajzolunk. Ritkán változik
 * (új túra vagy törlés után), így nem kell mérés közben frissítgetni.
 */
object StatsWidgetRenderer {

    private const val RC_OPEN_STATS = 20

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A widget frissítése; ha nincs kirakva egy sem, nem nyúlunk az adatbázishoz. */
    fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, StatsWidget::class.java))
        if (ids.isEmpty()) return

        val appContext = context.applicationContext
        scope.launch {
            val views = runCatching { build(appContext) }.getOrNull() ?: return@launch
            manager.updateAppWidget(ids, views)
        }
    }

    /** Csak a widget saját frissítési körének (onUpdate) – ott már megvannak az id-k. */
    fun refresh(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (ids.isEmpty()) return
        val appContext = context.applicationContext
        scope.launch {
            val views = runCatching { build(appContext) }.getOrNull() ?: return@launch
            manager.updateAppWidget(ids, views)
        }
    }

    private suspend fun build(context: Context): RemoteViews {
        val dao = AppDatabase.get(context).trackDao()
        val totals = dao.getTotals()
        // A havi bontásból az első sor a legutóbbi hónap – csak akkor mutatjuk,
        // ha tényleg az aktuális, különben a múlt havi táv látszana "ez a hónap"-ként.
        val currentMonthKey = SimpleDateFormat("yyyy-MM", Locale("hu", "HU")).format(Date())
        val thisMonth = dao.getMonthlyStats(1).firstOrNull()?.takeIf { it.month == currentMonthKey }

        val views = RemoteViews(context.packageName, R.layout.widget_stats)

        views.setTextViewText(R.id.wStatsDistance, Fmt.distance(totals.distanceMeters))
        views.setTextViewText(
            R.id.wStatsSummary,
            context.getString(
                R.string.widget_stats_summary,
                totals.trackCount,
                Fmt.longDuration(totals.durationMillis)
            )
        )
        views.setTextViewText(
            R.id.wStatsLast,
            if (totals.lastTrackAt > 0) Fmt.shortDate(totals.lastTrackAt) else ""
        )
        views.setTextViewText(R.id.wStatsMonth, Fmt.distance(thisMonth?.distanceMeters ?: 0.0))
        views.setTextViewText(R.id.wStatsMaxSpeed, Fmt.speedKmhUnit(totals.maxSpeedMps))
        views.setTextViewText(R.id.wStatsElevation, Fmt.meters(totals.maxElevationGainMeters))

        views.setOnClickPendingIntent(R.id.wStatsRoot, openStatsIntent(context))
        return views
    }

    /** Koppintásra egyből a Statisztika fül nyíljon meg. */
    private fun openStatsIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            RC_OPEN_STATS,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.TAB_STATS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
