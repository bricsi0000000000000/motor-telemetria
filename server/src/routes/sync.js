import { Router } from 'express'
import { db, one, run, tx } from '../db.js'
import { scheduleAggregate } from '../aggregate.js'

export const syncRouter = Router()

const num = (value, fallback = 0) => (Number.isFinite(Number(value)) ? Number(value) : fallback)
const nullableNum = (value) => (value === null || value === undefined ? null : num(value))

/**
 * A telefon szinkron végpontja.
 *
 * Mindent idempotensen fogadunk: a túrát a (device_uid, client_id) páros
 * azonosítja, a pontokat a túrán belüli sorszámuk. Így ha a válasz elveszik,
 * a telefon nyugodtan újraküldheti ugyanazt a csomagot – nem lesz duplikátum.
 */
syncRouter.post('/sync', (req, res) => {
  const deviceUid = String(req.body?.deviceUid || '').trim()
  if (!deviceUid) {
    res.status(400).json({ error: 'Hiányzó deviceUid' })
    return
  }

  const tracks = Array.isArray(req.body?.tracks) ? req.body.tracks : []
  const deleted = Array.isArray(req.body?.deletedClientIds) ? req.body.deletedClientIds : []
  const now = Date.now()

  const results = tx(() => {
    for (const clientId of deleted) {
      run('DELETE FROM tracks WHERE device_uid = ? AND client_id = ?', deviceUid, num(clientId))
    }

    const insertPoint = db.prepare(`
      INSERT OR IGNORE INTO track_points
        (track_id, seq, lat, lon, altitude, speed_mps, accuracy, bearing, time, segment)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `)
    const insertTelemetry = db.prepare(`
      INSERT OR IGNORE INTO telemetry_samples
        (track_id, seq, time, lat, lon, speed_mps, bearing_deg, pressure_hpa, fused_altitude_m,
         forward_mean_mps2, forward_min_mps2, forward_max_mps2,
         lateral_mean_mps2, lateral_rms_mps2, lateral_peak_mps2,
         vertical_rms_mps2, vertical_peak_mps2, yaw_peak_rads, roll_peak_rads,
         lean_degrees, mount_quality, sample_count, flags)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `)

    return tracks.map((track) => {
      const clientId = num(track?.clientId)
      // Részleges csomag esetén (pl. csak új pontok jönnek) a korábbi összesítő
      // marad érvényben, nem nullázzuk le a hiányzó mezőket.
      const previous = one(
        `SELECT start_time, end_time, distance_m, duration_ms, moving_ms, max_speed_mps,
                elevation_gain_m, point_count, telemetry_version, telemetry_sample_count
         FROM tracks WHERE device_uid = ? AND client_id = ?`,
        deviceUid,
        clientId
      )
      const startTime = num(track?.startTime, previous?.start_time ?? now)
      const endTime =
        track?.endTime === undefined
          ? previous?.end_time ?? null
          : track.endTime === null
            ? null
            : num(track.endTime)

      run(
        `INSERT INTO tracks (device_uid, client_id, start_time, end_time, distance_m,
                             duration_ms, moving_ms, max_speed_mps, elevation_gain_m,
                             point_count, telemetry_version, telemetry_sample_count,
                             created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT (device_uid, client_id) DO UPDATE SET
           start_time       = excluded.start_time,
           end_time         = excluded.end_time,
           distance_m       = excluded.distance_m,
           duration_ms      = excluded.duration_ms,
           moving_ms        = excluded.moving_ms,
           max_speed_mps    = excluded.max_speed_mps,
           elevation_gain_m = excluded.elevation_gain_m,
           point_count      = excluded.point_count,
           telemetry_version = excluded.telemetry_version,
           telemetry_sample_count = excluded.telemetry_sample_count,
           updated_at       = excluded.updated_at`,
        deviceUid,
        clientId,
        startTime,
        endTime,
        num(track?.distanceMeters, previous?.distance_m ?? 0),
        num(track?.durationMillis, previous?.duration_ms ?? 0),
        num(track?.movingMillis, previous?.moving_ms ?? 0),
        num(track?.maxSpeedMps, previous?.max_speed_mps ?? 0),
        num(track?.elevationGainMeters, previous?.elevation_gain_m ?? 0),
        num(track?.pointCount, previous?.point_count ?? 0),
        num(track?.telemetryVersion, previous?.telemetry_version ?? 0),
        num(track?.telemetrySampleCount, previous?.telemetry_sample_count ?? 0),
        now,
        now
      )

      const row = one(
        'SELECT id FROM tracks WHERE device_uid = ? AND client_id = ?',
        deviceUid,
        clientId
      )
      const trackId = row.id

      const points = Array.isArray(track?.points) ? track.points : []
      for (const point of points) {
        insertPoint.run(
          trackId,
          num(point?.seq),
          num(point?.lat),
          num(point?.lon),
          num(point?.altitude),
          num(point?.speedMps),
          num(point?.accuracy),
          num(point?.bearing),
          num(point?.time, now),
          num(point?.segment)
        )
      }

      const telemetry = Array.isArray(track?.telemetrySamples) ? track.telemetrySamples : []
      for (const sample of telemetry) {
        insertTelemetry.run(
          trackId,
          num(sample?.seq),
          num(sample?.time, now),
          nullableNum(sample?.lat),
          nullableNum(sample?.lon),
          num(sample?.speedMps),
          num(sample?.bearingDegrees),
          nullableNum(sample?.pressureHpa),
          nullableNum(sample?.fusedAltitudeMeters),
          num(sample?.forwardMeanMps2),
          num(sample?.forwardMinMps2),
          num(sample?.forwardMaxMps2),
          num(sample?.lateralMeanMps2),
          num(sample?.lateralRmsMps2),
          num(sample?.lateralPeakMps2),
          num(sample?.verticalRmsMps2),
          num(sample?.verticalPeakMps2),
          num(sample?.yawPeakRadS),
          num(sample?.rollPeakRadS),
          nullableNum(sample?.leanDegrees),
          num(sample?.mountQuality),
          num(sample?.sampleCount),
          num(sample?.flags)
        )
      }

      const stored = one('SELECT COUNT(*) AS c FROM track_points WHERE track_id = ?', trackId)
      const storedTelemetry = one(
        'SELECT COUNT(*) AS c FROM telemetry_samples WHERE track_id = ?',
        trackId
      )

      return {
        clientId,
        serverId: trackId,
        // A telefon ebből tudja, hány pontot nem kell többé küldenie.
        storedPoints: stored.c,
        storedTelemetrySamples: storedTelemetry.c,
        sentPoints: points.length
      }
    })
  })

  res.json({ ok: true, serverTime: now, results })

  // A válasz már elment: a személyes sebességmodell frissítése a háttérben fut,
  // a telefon nem várhat egy térképre illesztésre.
  scheduleAggregate()
})

/**
 * Élő állapot: mérés közben másodpercenkénti nagyságrendben érkezik, ezért
 * nem naplózzuk, csak az utolsót tartjuk meg eszközönként. Ha nincs net,
 * a telefon el sem teszi – az elavult "élő" adat úgyis értéktelen.
 */
syncRouter.post('/live', (req, res) => {
  const deviceUid = String(req.body?.deviceUid || '').trim()
  if (!deviceUid) {
    res.status(400).json({ error: 'Hiányzó deviceUid' })
    return
  }
  run(
    `INSERT INTO live_state (device_uid, payload, updated_at) VALUES (?, ?, ?)
     ON CONFLICT (device_uid) DO UPDATE SET payload = excluded.payload,
                                            updated_at = excluded.updated_at`,
    deviceUid,
    JSON.stringify(req.body ?? {}),
    Date.now()
  )
  res.status(204).end()
})
