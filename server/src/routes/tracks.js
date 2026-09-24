import { observedStops } from '../observedstops.js'
import { displayTrack } from '../displaytrack.js'
import { Router } from 'express'
import { all, one, run } from '../db.js'
import { analyzeTrack } from '../analysis.js'
import { reverseGeocode } from '../geocode.js'
import { toGpx } from '../gpx.js'

export const tracksRouter = Router()

const trackFields = `
  id, device_uid AS deviceUid, client_id AS clientId,
  start_time AS startTime, end_time AS endTime,
  distance_m AS distanceMeters, duration_ms AS durationMillis, moving_ms AS movingMillis,
  max_speed_mps AS maxSpeedMps, elevation_gain_m AS elevationGainMeters,
  point_count AS pointCount, note, updated_at AS updatedAt
`

/** A táv és a mozgásban töltött idő hányadosa – ugyanaz a képlet, mint a telefonon. */
const withAverages = (track) => ({
  ...track,
  avgSpeedMps: track.movingMillis > 0 ? track.distanceMeters / (track.movingMillis / 1000) : 0
})

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

    const cached = one(
      'SELECT payload, point_count AS pointCount FROM track_analysis WHERE track_id = ?',
      id
    )
    if (cached && cached.pointCount === points.length && req.query.refresh !== '1') {
      res.json({ ...JSON.parse(cached.payload), cached: true })
      return
    }

    const analysis = await analyzeTrack(track, points)
    run(
      `INSERT INTO track_analysis (track_id, payload, point_count, limit_coverage, created_at)
       VALUES (?, ?, ?, ?, ?)
       ON CONFLICT (track_id) DO UPDATE SET payload = excluded.payload,
                                            point_count = excluded.point_count,
                                            limit_coverage = excluded.limit_coverage,
                                            created_at = excluded.created_at`,
      id,
      JSON.stringify(analysis),
      points.length,
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
