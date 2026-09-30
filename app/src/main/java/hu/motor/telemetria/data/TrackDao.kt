package hu.motor.telemetria.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A statisztika oldal összesítője. */
data class TotalStats(
    val trackCount: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val movingMillis: Long,
    val maxSpeedMps: Float,
    /** Egy túrán mért legnagyobb szintemelkedés – az összeadott érték félrevezető. */
    val maxElevationGainMeters: Double,
    val firstTrackAt: Long,
    val lastTrackAt: Long
)

/** Egy túra első és utolsó pontja – ebből derül ki, honnan hova ment. */
data class TrackEndpoints(
    val trackId: Long,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?
)

/** Havi bontás a statisztika oldal oszlopdiagramjához. */
data class MonthStats(
    val month: String,
    val trackCount: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val movingMillis: Long
)

@Dao
interface TrackDao {

    @Insert
    suspend fun insertTrack(track: Track): Long

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun getTrack(id: Long): Track?

    @Query("SELECT * FROM tracks WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun observeFinishedTracks(): Flow<List<Track>>

    /**
     * A mérés közbeni mentés. Csak az összesítőt írja, a szinkron mezőkhöz nem
     * nyúl – így a feltöltés állapota nem vész el egy checkpointtal.
     */
    @Query(
        """UPDATE tracks SET endTime = :endTime, distanceMeters = :distanceMeters,
           durationMillis = :durationMillis, movingMillis = :movingMillis,
           maxSpeedMps = :maxSpeedMps, elevationGainMeters = :elevationGainMeters,
           pointCount = :pointCount, telemetrySampleCount = :telemetrySampleCount, dirty = 1
           WHERE id = :id"""
    )
    suspend fun updateSummary(
        id: Long,
        endTime: Long?,
        distanceMeters: Double,
        durationMillis: Long,
        movingMillis: Long,
        maxSpeedMps: Float,
        elevationGainMeters: Double,
        pointCount: Int,
        telemetrySampleCount: Int
    )

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: Long)

    /** Félbemaradt (soha le nem zárt) és pont nélküli túrák takarítása induláskor. */
    @Query("DELETE FROM tracks WHERE endTime IS NULL AND id NOT IN (SELECT DISTINCT trackId FROM track_points)")
    suspend fun deleteEmptyUnfinished()

    @Insert
    suspend fun insertPoint(point: TrackPoint): Long

    @Insert
    suspend fun insertPoints(points: List<TrackPoint>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTelemetry(sample: TelemetrySample)

    @Query("SELECT * FROM telemetry_samples WHERE trackId = :trackId ORDER BY seq ASC")
    suspend fun getTelemetry(trackId: Long): List<TelemetrySample>

    @Query("SELECT COUNT(*) FROM telemetry_samples WHERE trackId = :trackId")
    suspend fun countTelemetry(trackId: Long): Int

    @Query("UPDATE tracks SET telemetryVersion = 1, dirty = 1 WHERE id = :trackId")
    suspend fun markTelemetryEnabled(trackId: Long)

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY time ASC")
    suspend fun getPoints(trackId: Long): List<TrackPoint>

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId")
    suspend fun countPoints(trackId: Long): Int

    /**
     * Minden lezárt túra kezdő- és végpontja egyetlen lekérdezésben. A pontokra
     * mutató index miatt ez akkor is gyors, ha sok száz túra van.
     */
    @Query(
        """SELECT t.id AS trackId,
                  (SELECT p.lat FROM track_points p WHERE p.trackId = t.id ORDER BY p.id ASC LIMIT 1) AS startLat,
                  (SELECT p.lon FROM track_points p WHERE p.trackId = t.id ORDER BY p.id ASC LIMIT 1) AS startLon,
                  (SELECT p.lat FROM track_points p WHERE p.trackId = t.id ORDER BY p.id DESC LIMIT 1) AS endLat,
                  (SELECT p.lon FROM track_points p WHERE p.trackId = t.id ORDER BY p.id DESC LIMIT 1) AS endLon
           FROM tracks t WHERE t.endTime IS NOT NULL"""
    )
    suspend fun getTrackEndpoints(): List<TrackEndpoints>

    // --- szinkron --------------------------------------------------------------

    /**
     * Amit még fel kell tölteni: vagy változott az összesítő, vagy van olyan pont,
     * amit a szerver még nem vett át. A régebbi túra megy előbb.
     */
    @Query(
        """SELECT * FROM tracks
           WHERE dirty = 1
              OR syncedPoints < (SELECT COUNT(*) FROM track_points WHERE trackId = tracks.id)
              OR syncedTelemetrySamples < (SELECT COUNT(*) FROM telemetry_samples WHERE trackId = tracks.id)
           ORDER BY startTime ASC"""
    )
    suspend fun getPendingTracks(): List<Track>

    @Query(
        """SELECT COUNT(*) FROM tracks
           WHERE dirty = 1
              OR syncedPoints < (SELECT COUNT(*) FROM track_points WHERE trackId = tracks.id)
              OR syncedTelemetrySamples < (SELECT COUNT(*) FROM telemetry_samples WHERE trackId = tracks.id)"""
    )
    suspend fun countPendingTracks(): Int

    /** A pont sorrendje az id: az [offset] a szerver által már átvett pontok száma. */
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY id ASC LIMIT :limit OFFSET :offset")
    suspend fun getPointsFrom(trackId: Long, offset: Int, limit: Int): List<TrackPoint>

    @Query("SELECT * FROM telemetry_samples WHERE trackId = :trackId ORDER BY seq ASC LIMIT :limit OFFSET :offset")
    suspend fun getTelemetryFrom(trackId: Long, offset: Int, limit: Int): List<TelemetrySample>

    @Query(
        """UPDATE tracks SET remoteId = :remoteId, syncedPoints = :syncedPoints,
           syncedTelemetrySamples = :syncedTelemetrySamples WHERE id = :id"""
    )
    suspend fun markUploaded(
        id: Long,
        remoteId: Long?,
        syncedPoints: Int,
        syncedTelemetrySamples: Int
    )

    /**
     * A "kész" jelzést csak akkor tesszük ki, ha az összesítő azóta sem változott:
     * mérés közben a szolgáltatás bármikor beleírhat, azt nem szabad elveszíteni.
     */
    @Query(
        """UPDATE tracks SET dirty = 0
           WHERE id = :id AND distanceMeters = :distanceMeters
             AND durationMillis = :durationMillis AND endTime IS :endTime"""
    )
    suspend fun clearDirtyIfUnchanged(
        id: Long,
        distanceMeters: Double,
        durationMillis: Long,
        endTime: Long?
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPendingDelete(pending: PendingDelete)

    @Query("SELECT trackId FROM pending_deletes")
    suspend fun getPendingDeletes(): List<Long>

    @Query("DELETE FROM pending_deletes WHERE trackId IN (:trackIds)")
    suspend fun clearPendingDeletes(trackIds: List<Long>)

    // --- elemzés ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAnalysis(analysis: TrackAnalysisEntity)

    @Query("SELECT * FROM track_analysis WHERE trackId = :trackId")
    suspend fun getAnalysis(trackId: Long): TrackAnalysisEntity?

    @Query("SELECT * FROM track_analysis WHERE trackId IN (:trackIds)")
    suspend fun getAnalyses(trackIds: List<Long>): List<TrackAnalysisEntity>

    // --- statisztika -----------------------------------------------------------

    @Query(
        """SELECT COUNT(*) AS trackCount,
                  COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
                  COALESCE(SUM(durationMillis), 0) AS durationMillis,
                  COALESCE(SUM(movingMillis), 0) AS movingMillis,
                  COALESCE(MAX(maxSpeedMps), 0) AS maxSpeedMps,
                  COALESCE(MAX(elevationGainMeters), 0) AS maxElevationGainMeters,
                  COALESCE(MIN(startTime), 0) AS firstTrackAt,
                  COALESCE(MAX(startTime), 0) AS lastTrackAt
           FROM tracks WHERE endTime IS NOT NULL"""
    )
    suspend fun getTotals(): TotalStats

    /** Havi bontás; a hónap a telefon időzónája szerint értendő. */
    @Query(
        """SELECT strftime('%Y-%m', startTime / 1000, 'unixepoch', 'localtime') AS month,
                  COUNT(*) AS trackCount,
                  COALESCE(SUM(distanceMeters), 0) AS distanceMeters,
                  COALESCE(SUM(durationMillis), 0) AS durationMillis,
                  COALESCE(SUM(movingMillis), 0) AS movingMillis
           FROM tracks WHERE endTime IS NOT NULL
           GROUP BY month ORDER BY month DESC LIMIT :limit"""
    )
    suspend fun getMonthlyStats(limit: Int): List<MonthStats>

    /** A legutóbbi túrák a statisztika oldal elemzés-listájához. */
    @Query("SELECT * FROM tracks WHERE endTime IS NOT NULL ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentTracks(limit: Int): List<Track>

    /** A leghosszabb túra – a statisztika "rekordok" szakaszához. */
    @Query("SELECT * FROM tracks WHERE endTime IS NOT NULL ORDER BY distanceMeters DESC LIMIT 1")
    suspend fun getLongestTrack(): Track?

    @Query("SELECT * FROM tracks WHERE endTime IS NOT NULL ORDER BY maxSpeedMps DESC LIMIT 1")
    suspend fun getFastestTrack(): Track?
}
