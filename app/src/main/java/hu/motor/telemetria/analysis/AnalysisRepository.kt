package hu.motor.telemetria.analysis

import android.content.Context
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.data.TrackAnalysisEntity
import hu.motor.telemetria.net.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/**
 * Az elemzés lekérése és tárolása.
 *
 * A szerver számol (az OSM sebességhatárokhoz net kell), a telefon eltárolja az
 * eredményt – így egy egyszer megnézett túra elemzése offline is előjön.
 */
object AnalysisRepository {

    class NotSyncedException :
        IOException("A túra még nem került fel a szerverre, ezért nincs mit elemezni.")

    suspend fun cached(context: Context, trackId: Long): TrackAnalysis? =
        withContext(Dispatchers.IO) {
            AppDatabase.get(context).trackDao().getAnalysis(trackId)?.let { parse(it) }
        }

    /** A már letöltött elemzések több túrához – a statisztika listájához. */
    suspend fun cachedAll(context: Context, trackIds: List<Long>): Map<Long, TrackAnalysis> =
        withContext(Dispatchers.IO) {
            if (trackIds.isEmpty()) return@withContext emptyMap()
            AppDatabase.get(context).trackDao().getAnalyses(trackIds)
                .mapNotNull { entity -> parse(entity)?.let { entity.trackId to it } }
                .toMap()
        }

    /**
     * Elemzés a tárolóból, vagy ha ott nincs (illetve elavult), a szervertől.
     *
     * @param force újraszámolást kér a szervertől is – akkor kell, ha a
     *        sebességhatárok időközben javultak az OSM-ben.
     */
    suspend fun load(context: Context, track: Track, force: Boolean = false): Result<TrackAnalysis> =
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(context).trackDao()

            if (!force) {
                val stored = dao.getAnalysis(track.id)
                // Ha a túra azóta nem nőtt, a tárolt elemzés érvényes.
                if (stored != null && stored.pointCount >= track.pointCount) {
                    parse(stored)?.let { analysis ->
                        val telemetryCount = analysis.summary?.telemetry?.sampleCount ?: 0
                        if (track.telemetrySampleCount == 0 || telemetryCount >= track.telemetrySampleCount) {
                            return@withContext Result.success(analysis)
                        }
                    }
                }
            }

            val remoteId = track.remoteId
                ?: return@withContext Result.failure(NotSyncedException())

            runCatching {
                val path = "/api/tracks/$remoteId/analysis" + if (force) "?refresh=1" else ""
                // Az első kérés a szerveren számol (OSM lekérdezéssel), ez lassú lehet.
                val json = ApiClient.getJson(path, readTimeoutMs = 150_000)
                val analysis = TrackAnalysis.parse(json)
                dao.saveAnalysis(
                    TrackAnalysisEntity(
                        trackId = track.id,
                        json = json.toString(),
                        pointCount = analysis.pointCount,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                analysis
            }
        }

    suspend fun labelIncident(
        context: Context,
        track: Track,
        eventId: Long,
        label: String
    ): Result<TrackAnalysis> = withContext(Dispatchers.IO) {
        val remoteId = track.remoteId
            ?: return@withContext Result.failure(NotSyncedException())
        runCatching {
            ApiClient.postJson(
                "/api/tracks/$remoteId/events/$eventId/label",
                JSONObject().put("label", label)
            )
            val json = ApiClient.getJson("/api/tracks/$remoteId/analysis")
            val analysis = TrackAnalysis.parse(json)
            AppDatabase.get(context).trackDao().saveAnalysis(
                TrackAnalysisEntity(
                    trackId = track.id,
                    json = json.toString(),
                    pointCount = analysis.pointCount,
                    updatedAt = System.currentTimeMillis()
                )
            )
            analysis
        }
    }

    private fun parse(entity: TrackAnalysisEntity): TrackAnalysis? =
        runCatching { TrackAnalysis.parse(JSONObject(entity.json)) }.getOrNull()
}
