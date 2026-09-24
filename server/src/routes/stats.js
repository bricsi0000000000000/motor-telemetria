import { Router } from 'express'
import { all, one } from '../db.js'

export const statsRouter = Router()

/**
 * Összesítők a statisztika oldalhoz. Mindent SQL-ben számolunk, hogy ne kelljen
 * több ezer túrapontot a Node-ba húzni.
 */
statsRouter.get('/stats', (_req, res) => {
  const totals = one(`
    SELECT COUNT(*)                        AS tracks,
           COALESCE(SUM(distance_m), 0)    AS distanceMeters,
           COALESCE(SUM(duration_ms), 0)   AS durationMillis,
           COALESCE(SUM(moving_ms), 0)     AS movingMillis,
           COALESCE(MAX(max_speed_mps), 0) AS maxSpeedMps,
           -- Az emelkedők összeadása félrevezető, ezért az egy túrán mért
           -- legnagyobb szintemelkedés a mérvadó szám.
           COALESCE(MAX(elevation_gain_m), 0) AS maxElevationGainMeters,
           COALESCE(SUM(elevation_gain_m), 0) AS elevationGainMeters,
           COALESCE(SUM(point_count), 0)   AS pointCount,
           MIN(start_time)                 AS firstTrackAt,
           MAX(start_time)                 AS lastTrackAt
    FROM tracks WHERE end_time IS NOT NULL
  `)

  // A SQLite unixepoch-ot vár, a mi időbélyegünk viszont ezredmásodperc.
  const months = all(`
    SELECT strftime('%Y-%m', start_time / 1000, 'unixepoch', 'localtime') AS month,
           COUNT(*)                      AS tracks,
           COALESCE(SUM(distance_m), 0)  AS distanceMeters,
           COALESCE(SUM(duration_ms), 0) AS durationMillis,
           COALESCE(SUM(moving_ms), 0)   AS movingMillis
    FROM tracks WHERE end_time IS NOT NULL
    GROUP BY month
    ORDER BY month DESC
    LIMIT 24
  `)

  const best = {
    longest: one(`
      SELECT id, start_time AS startTime, distance_m AS distanceMeters
      FROM tracks WHERE end_time IS NOT NULL
      ORDER BY distance_m DESC LIMIT 1
    `),
    fastest: one(`
      SELECT id, start_time AS startTime, max_speed_mps AS maxSpeedMps
      FROM tracks WHERE end_time IS NOT NULL
      ORDER BY max_speed_mps DESC LIMIT 1
    `),
    longestTime: one(`
      SELECT id, start_time AS startTime, duration_ms AS durationMillis
      FROM tracks WHERE end_time IS NOT NULL
      ORDER BY duration_ms DESC LIMIT 1
    `)
  }

  res.json({
    totals: {
      ...totals,
      avgSpeedMps: totals.movingMillis > 0 ? totals.distanceMeters / (totals.movingMillis / 1000) : 0,
      avgDistanceMeters: totals.tracks > 0 ? totals.distanceMeters / totals.tracks : 0
    },
    months,
    best
  })
})
