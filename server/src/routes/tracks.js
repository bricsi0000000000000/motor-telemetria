import { observedStops } from '../observedstops.js'
import { displayTrack } from '../displaytrack.js'
import { Router } from 'express'
import { all, one, run } from '../db.js'
import { analyzeTrack } from '../analysis.js'
import { stableRoadQuality } from '../roadquality.js'
import { reverseGeocode } from '../geocode.js'
import { toGpx } from '../gpx.js'

export const tracksRouter = Router()

const trackFields = `
  id, device_uid AS deviceUid, client_id AS clientId,
  start_time AS startTime, end_time AS endTime,
  distance_m AS distanceMeters, duration_ms AS durationMillis, moving_ms AS movingMillis,
  max_speed_mps AS maxSpeedMps, elevation_gain_m AS elevationGainMeters,
  point_count AS pointCount, telemetry_version AS telemetryVersion,
  telemetry_sample_count AS telemetrySampleCount, note, updated_at AS updatedAt
`

/** A táv és a mozgásban töltött idő hányadosa – ugyanaz a képlet, mint a telefonon. */
const withAverages = (track) => ({
  ...track,
  avgSpeedMps: track.movingMillis > 0 ? track.distanceMeters / (track.movingMillis / 1000) : 0
})

const percentile = (values, ratio) => {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  return sorted[Math.min(sorted.length - 1, Math.floor((sorted.length - 1) * ratio))]
}

function personalTelemetryProfile() {
  const history = one(
    `SELECT COUNT(*) AS trackCount, COALESCE(SUM(distance_m), 0) AS distanceMeters
     FROM tracks WHERE id IN
       (SELECT DISTINCT track_id FROM telemetry_samples WHERE mount_quality >= 0.65)`
  )
  const samples = all(
    `SELECT forward_min_mps2 AS minimum, forward_max_mps2 AS maximum
     FROM telemetry_samples WHERE mount_quality >= 0.65`
  )
  return {
    eligible: history.trackCount >= 5 || history.distanceMeters >= 100000,
    accelerationThreshold: Math.max(3, percentile(samples.map((row) => row.maximum), 0.9)),
    brakingThreshold: Math.max(3, percentile(samples.map((row) => -row.minimum), 0.9))
  }
}

function persistIncidentLabels(trackId, analysis) {
  const incidents = analysis.sections?.incidents ?? []
  const now = Date.now()
  for (const incident of incidents) {
    run(
      `INSERT INTO ride_events (track_id, event_time, kind, score, created_at, updated_at)
       VALUES (?, ?, 'INCIDENT', ?, ?, ?)
       ON CONFLICT(track_id, event_time, kind) DO UPDATE SET
         score = excluded.score, updated_at = excluded.updated_at`,
      trackId, incident.startTime, incident.telemetryValue ?? 0, now, now
    )
    const event = one(
      `SELECT id, label FROM ride_events
       WHERE track_id = ? AND event_time = ? AND kind = 'INCIDENT'`,
      trackId, incident.startTime
    )
    incident.eventId = event.id
    incident.eventLabel = event.label
  }
}

function attachIncidentLabels(trackId, analysis) {
  const events = all(
    `SELECT id, event_time AS eventTime, label FROM ride_events
     WHERE track_id = ? AND kind = 'INCIDENT'`,
    trackId
  )
  const byTime = new Map(events.map((event) => [event.eventTime, event]))
  for (const incident of analysis.sections?.incidents ?? []) {
    const event = byTime.get(incident.startTime)
    if (!event) continue
    incident.eventId = event.id
    incident.eventLabel = event.label
  }
  return analysis
}

tracksRouter.post('/track/stops', async (req, res, next) => {
  try { res.json(await observedStops(req.body?.points)) }
  catch (error) {
    if (error.status === 400) res.status(400).json({ error: error.message })
    else next(error)
  }
})

tracksRouter.post('/track/display', async (req, res, next) => {
  try { res.json(await displayTrack(req.body?.points)) }
  catch (error) {
    if (error.status === 400) res.status(400).json({ error: error.message })
    else next(error)
  }
})

tracksRouter.get('/tracks', (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 200, 1000)
  const rows = all(
    `SELECT ${trackFields} FROM tracks
     WHERE end_time IS NOT NULL
     ORDER BY start_time DESC
     LIMIT ?`,
    limit
  )
  res.json(rows.map(withAverages))
})

tracksRouter.get('/tracks/:id', (req, res) => {
  const id = Number(req.params.id)
  const track = one(`SELECT ${trackFields} FROM tracks WHERE id = ?`, id)
  if (!track) {
    res.status(404).json({ error: 'Nincs ilyen túra' })
    return
  }
  const points = all(
    `SELECT seq, lat, lon, altitude, speed_mps AS speedMps, accuracy, bearing, time, segment
     FROM track_points WHERE track_id = ? ORDER BY seq ASC`,
    id
  )
  res.json({ ...withAverages(track), points })
})

/** Ritkítható szenzorfolyam; nem terheli a meglévő túraválaszt. */
tracksRouter.get('/tracks/:id/telemetry', (req, res) => {
  const id = Number(req.params.id)
  if (!one('SELECT id FROM tracks WHERE id = ?', id)) {
    res.status(404).json({ error: 'Nincs ilyen túra' })
    return
  }
  const from = Number(req.query.from) || 0
  const to = Number(req.query.to) || Number.MAX_SAFE_INTEGER
  const resolutionSeconds = Math.min(
    60,
    Math.max(1, Number.parseFloat(String(req.query.resolution ?? '1').replace(/s$/i, '')) || 1)
  )
  const bucket = resolutionSeconds * 1000
  const rows = all(
    `SELECT MIN(seq) AS seq, MIN(time) AS time, AVG(lat) AS lat, AVG(lon) AS lon,
            AVG(speed_mps) AS speedMps, AVG(bearing_deg) AS bearingDegrees,
            AVG(pressure_hpa) AS pressureHpa,
            AVG(fused_altitude_m) AS fusedAltitudeMeters,
            AVG(forward_mean_mps2) AS forwardMeanMps2,
            MIN(forward_min_mps2) AS forwardMinMps2,
            MAX(forward_max_mps2) AS forwardMaxMps2,
            AVG(lateral_rms_mps2) AS lateralRmsMps2,
            MAX(lateral_peak_mps2) AS lateralPeakMps2,
            AVG(vertical_rms_mps2) AS verticalRmsMps2,
            MAX(vertical_peak_mps2) AS verticalPeakMps2,
            MAX(yaw_peak_rads) AS yawPeakRadS, MAX(roll_peak_rads) AS rollPeakRadS,
            AVG(lean_degrees) AS leanDegrees, AVG(mount_quality) AS mountQuality,
            SUM(sample_count) AS sampleCount,
            (MAX(flags & 1) | MAX(flags & 2) | MAX(flags & 4) |
             MAX(flags & 8) | MAX(flags & 16) | MAX(flags & 32)) AS flags
     FROM telemetry_samples
     WHERE track_id = ? AND time BETWEEN ? AND ?
     GROUP BY CAST(time / ? AS INTEGER) ORDER BY time`,
    id, from, to, bucket
  )
  res.json({ trackId: id, resolutionSeconds, samples: rows })
})

/**
 * 40 méter körüli cellák útminősége. Csak legalább három külön túrából
 * származó, jó tartóminőségű minta válik stabil térképréteggé.
 */
tracksRouter.get('/road-quality', (req, res) => {
  const parts = String(req.query.bbox || '').split(',').map(Number)
  if (parts.length !== 4 || parts.some((value) => !Number.isFinite(value))) {
    res.status(400).json({ error: 'A bbox alakja: dél,nyugat,észak,kelet' })
    return
  }
  const [south, west, north, east] = parts
  const cells = stableRoadQuality({ south, west, north, east })
    .sort((a, b) => b.roughness - a.roughness)
  res.json({ cells })
})

tracksRouter.post('/tracks/:id/events/:eventId/label', (req, res) => {
  const trackId = Number(req.params.id)
  const eventId = Number(req.params.eventId)
  const label = String(req.body?.label || '').toUpperCase()
  const allowed = new Set(['REAL_EVENT', 'POTHOLE', 'PHONE_MOVED', 'FALSE_POSITIVE'])
  if (!allowed.has(label)) {
    res.status(400).json({ error: 'Ismeretlen eseménycímke' })
    return
  }
  const event = one('SELECT id FROM ride_events WHERE id = ? AND track_id = ?', eventId, trackId)
  if (!event) {
    res.status(404).json({ error: 'Nincs ilyen esemény' })
    return
  }
  run('UPDATE ride_events SET label = ?, updated_at = ? WHERE id = ?', label, Date.now(), eventId)
  res.json({ id: eventId, trackId, label })
})

tracksRouter.get('/tracks/:id/gpx', (req, res) => {
  const id = Number(req.params.id)
  const track = one(`SELECT ${trackFields} FROM tracks WHERE id = ?`, id)
  if (!track) {
    res.status(404).json({ error: 'Nincs ilyen túra' })
    return
  }
  const points = all(
    `SELECT lat, lon, altitude, speed_mps AS speedMps, time, segment
     FROM track_points WHERE track_id = ? ORDER BY seq ASC`,
    id
  )
  const stamp = new Date(track.startTime).toISOString().slice(0, 16).replace(/[-:T]/g, '')
  res.type('application/gpx+xml')
  res.set('Content-Disposition', `attachment; filename="motortura_${stamp}.gpx"`)
  res.send(toGpx(track, points))
})

/**
 * Részletes elemzés. Drága (Overpass hívás), ezért az eredményt eltesszük, és
 * csak akkor számoljuk újra, ha új pont érkezett vagy a hívó kifejezetten kéri.
 */
tracksRouter.get('/tracks/:id/analysis', async (req, res, next) => {
  try {
    const id = Number(req.params.id)
    const track = one(`SELECT ${trackFields} FROM tracks WHERE id = ?`, id)
    if (!track) {
      res.status(404).json({ error: 'Nincs ilyen túra' })
      return
    }

    const points = all(
      `SELECT seq, lat, lon, altitude, speed_mps AS speedMps, accuracy, time, segment
       FROM track_points WHERE track_id = ? ORDER BY seq ASC`,
      id
    )
    const telemetry = all(
      `SELECT seq, time, lat, lon, speed_mps AS speedMps, bearing_deg AS bearingDegrees,
              pressure_hpa AS pressureHpa, fused_altitude_m AS fusedAltitudeMeters,
              forward_mean_mps2 AS forwardMeanMps2, forward_min_mps2 AS forwardMinMps2,
              forward_max_mps2 AS forwardMaxMps2, lateral_rms_mps2 AS lateralRmsMps2,
              lateral_peak_mps2 AS lateralPeakMps2, vertical_rms_mps2 AS verticalRmsMps2,
              vertical_peak_mps2 AS verticalPeakMps2, yaw_peak_rads AS yawPeakRadS,
              roll_peak_rads AS rollPeakRadS, lean_degrees AS leanDegrees,
              mount_quality AS mountQuality, sample_count AS sampleCount, flags
       FROM telemetry_samples WHERE track_id = ? ORDER BY seq`,
      id
    )

    const cached = one(
      `SELECT payload, point_count AS pointCount, telemetry_count AS telemetryCount
       FROM track_analysis WHERE track_id = ?`,
      id
    )
    if (cached && cached.pointCount === points.length && cached.telemetryCount === telemetry.length && req.query.refresh !== '1') {
      res.json({ ...attachIncidentLabels(id, JSON.parse(cached.payload)), cached: true })
      return
    }

    const analysis = await analyzeTrack(track, points, telemetry, personalTelemetryProfile())
    persistIncidentLabels(id, analysis)
    run(
      `INSERT INTO track_analysis (track_id, payload, point_count, telemetry_count, limit_coverage, created_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT (track_id) DO UPDATE SET payload = excluded.payload,
                                            point_count = excluded.point_count,
                                            telemetry_count = excluded.telemetry_count,
                                            limit_coverage = excluded.limit_coverage,
                                            created_at = excluded.created_at`,
      id,
      JSON.stringify(analysis),
      points.length,
      telemetry.length,
      analysis.summary?.limit?.coverage ?? 0,
      Date.now()
    )
    res.json({ ...analysis, cached: false })
  } catch (error) {
    next(error)
  }
})

/**
 * Fordított geokódolás a telefonnak: mi van ezen a koordinátán?
 * Az eredmény a szerveren is gyorsítótárba kerül, így a Nominatimot csak
 * egyszer terheljük ugyanazzal a hellyel.
 */
tracksRouter.get('/geocode', async (req, res, next) => {
  try {
    const lat = Number(req.query.lat)
    const lon = Number(req.query.lon)
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
      res.status(400).json({ error: 'Hiányzó lat/lon' })
      return
    }
    res.json(await reverseGeocode(lat, lon))
  } catch (error) {
    next(error)
  }
})

tracksRouter.delete('/tracks/:id', (req, res) => {
  const result = run('DELETE FROM tracks WHERE id = ?', Number(req.params.id))
  res.json({ deleted: result.changes })
})

/** Az utolsó élő állapot: a webes térkép ezt kérdezgeti mérés közben. */
tracksRouter.get('/live', (_req, res) => {
  const rows = all('SELECT device_uid AS deviceUid, payload, updated_at AS updatedAt FROM live_state')
  const states = rows.map((row) => {
    const payload = JSON.parse(row.payload)
    // A telefon a saját túraazonosítóját küldi; a webes térkép a szerverit kéri,
    // hogy menet közben is le tudja húzni az eddigi nyomvonalat.
    const track = payload.trackClientId
      ? one(
          'SELECT id FROM tracks WHERE device_uid = ? AND client_id = ?',
          row.deviceUid,
          payload.trackClientId
        )
      : null
    return {
      deviceUid: row.deviceUid,
      updatedAt: row.updatedAt,
      // Mérés közben másodpercenként frissül; ha régebbi, már nem "élő".
      stale: Date.now() - row.updatedAt > 30_000,
      ...payload,
      trackId: track?.id ?? null
    }
  })
  // A legfrissebb eszköz kerül előre – egy telefonnál ez mindig ugyanaz.
  states.sort((a, b) => b.updatedAt - a.updatedAt)
  res.json({ live: states[0] ?? null, devices: states })
})
