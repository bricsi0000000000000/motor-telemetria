import { all, one, run } from './db.js'
import { NX500 } from './bike.js'
import { speedProfile } from './profile.js'
import { loadModel, targetSpeed, invalidateModel, percentileFromHistogram } from './ridermodel.js'
import { unpackSteps } from './aggregate.js'

/**
 * A modell hitelesítése a saját túráidon.
 *
 * Miért nem a szakaszonkénti percentilisek összege adja a leggyorsabb/leglassabb
 * időt: az tökéletes korrelációt feltételezne (minden kanyart egyszerre veszel
 * a legjobb napodon), és irreálisan széles sávot adna. A mért valóság az, hogy
 * a 16 km-es ismétlődő utadon a mozgás-átlagsebesség ±15%-ot szór.
 *
 * Ezért: a modell EGY menetidőt ad, és azt szorozzuk a múltbeli
 * "valóság / modell" arány percentiliseivel.
 */

/** Ennél rövidebb túrából nem lehet menetidőt tanulni. */
const MIN_DISTANCE_M = 3000

/** Az illesztésnek ennyit el kell találnia a túrából. */
const MIN_MATCH_RATIO = 0.8

/** Ezeken kívül a túra adata hibás (elfelejtett leállítás, GPS-ugrás). */
const MIN_PLAUSIBLE_KMH = 8
const MAX_PLAUSIBLE_KMH = 130

/**
 * Mozgásidő a pontsorból, ugyanazzal a szabállyal, amit az analysis.js használ.
 * A tracks.moving_ms-ben vannak lehetetlen értékek (van 31,8 km 6,9 "mozgó"
 * perc alatt), ezért azt nem használjuk.
 */
export function movingMillis(points) {
  if (points.length < 3) return { movingMs: 0, totalMs: 0 }

  const raw = points.map((point, index) => {
    if (point.speedMps > 0) return point.speedMps
    if (index === 0) return 0
    const dt = (point.time - points[index - 1].time) / 1000
    return dt > 0 && dt < 60 ? 0 : 0
  })
  // Ötpontos mozgóátlag, mint az elemzésben: a nyers GPS sebesség ugrálós.
  const smoothed = raw.map((_, index) => {
    const from = Math.max(0, index - 2)
    const to = Math.min(raw.length - 1, index + 2)
    let sum = 0
    for (let i = from; i <= to; i++) sum += raw[i]
    return sum / (to - from + 1)
  })

  let movingMs = 0
  for (let i = 1; i < points.length; i++) {
    const dt = points[i].time - points[i - 1].time
    if (dt <= 0 || dt > 30_000) continue
    if (smoothed[i] > 0.8) movingMs += dt
  }
  return { movingMs, totalMs: points[points.length - 1].time - points[0].time }
}

/**
 * Egy túra modellezett menetideje a saját, illesztett geometriájából.
 * A `model` átadható, hogy a leave-one-out visszamérés más modellel futtathassa.
 */
export function predictTrack(payload, model = loadModel(), bike = NX500) {
  const steps = unpackSteps(payload.steps)
  if (steps.length < 5) return null

  const targets = new Float64Array(steps.length)
  for (let i = 0; i < steps.length; i++) {
    targets[i] = targetSpeed(model, steps[i], bike).vMps
  }
  const rider = {
    accelMps2: model.dynamics.accelMps2,
    decelMps2: model.dynamics.decelMps2
  }
  const profile = speedProfile(steps, targets, bike, rider, { startMps: 0, endMps: 0 })
  return {
    movingMs: Math.round(profile.timeSeconds * 1000),
    distanceM: steps.reduce((sum, step) => sum + step.lengthM, 0)
  }
}

/** A model_calibration sorainak újraszámolása minden illesztett túrára. */
export function rebuildCalibration() {
  const model = loadModel()
  run('DELETE FROM model_calibration')

  let written = 0
  for (const row of all("SELECT track_id, payload, matched_m FROM track_match WHERE status = 'ok'")) {
    const track = one('SELECT * FROM tracks WHERE id = ?', row.track_id)
    if (!track) continue

    let payload
    try {
      payload = JSON.parse(row.payload)
    } catch {
      continue
    }

    const points = all(
      'SELECT speed_mps AS speedMps, time FROM track_points WHERE track_id = ? ORDER BY seq',
      row.track_id
    )
    const measured = movingMillis(points)
    const predicted = predictTrack(payload, model)
    if (!predicted || predicted.movingMs <= 0 || measured.movingMs <= 0) continue

    const ratio = measured.movingMs / predicted.movingMs
    const reason = exclusionReason(track, row, measured, predicted)

    run(
      `INSERT INTO model_calibration
         (track_id, predicted_ms, actual_ms, stopped_ms, distance_m, ratio, usable, reason, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      row.track_id, predicted.movingMs, measured.movingMs,
      Math.max(0, measured.totalMs - measured.movingMs),
      track.distance_m, ratio, reason ? 0 : 1, reason, Date.now()
    )
    written++
  }

  invalidateModel()
  return { tracks: written, usable: one('SELECT COUNT(*) AS n FROM model_calibration WHERE usable = 1')?.n ?? 0 }
}

/** Miért nem használható egy túra a kalibrációhoz. Null = használható. */
function exclusionReason(track, matchRow, measured, predicted) {
  if (track.distance_m < MIN_DISTANCE_M) return 'túl rövid'
  if (track.distance_m > 0 && matchRow.matched_m / track.distance_m < MIN_MATCH_RATIO) {
    return 'gyenge illesztés'
  }
  const kmh = (track.distance_m / 1000) / (measured.movingMs / 3_600_000)
  if (!Number.isFinite(kmh) || kmh < MIN_PLAUSIBLE_KMH) return 'hihetetlenül lassú mozgásidő'
  if (kmh > MAX_PLAUSIBLE_KMH) return 'hihetetlenül gyors mozgásidő'
  if (measured.totalMs > 4 * measured.movingMs) return 'elfelejtett leállítás'
  return null
}

/**
 * Leave-one-out visszamérés: minden túrát olyan modellel jósolunk meg, ami nem
 * látta azt a túrát. Enélkül a modell önmagát igazolná.
 *
 * Mivel az összesítők összeadhatók, a kihagyás pontos: a túra hozzájárulását
 * kivonjuk a hisztogramokból, nem kell újraépíteni az egészet.
 */
export function backtest() {
  const rows = all(`SELECT c.track_id, c.actual_ms, c.distance_m, c.usable, m.payload
                    FROM model_calibration c
                    JOIN track_match m ON m.track_id = c.track_id
                    WHERE c.usable = 1`)
  if (rows.length < 3) {
    return { ok: false, reason: 'Kevés használható túra a visszaméréshez.', tracks: rows.length }
  }

  const full = loadModel()
  const results = []
  let baselineErrorSum = 0
  let modelErrorSum = 0

  // A referencia, amit verni kell: a teljes adathalmaz átlagsebessége.
  const totals = one('SELECT SUM(distance_m) AS d, SUM(actual_ms) AS t FROM model_calibration WHERE usable = 1')
  const baselineKmh = (totals.d / 1000) / (totals.t / 3_600_000)

  for (const row of rows) {
    let payload
    try {
      payload = JSON.parse(row.payload)
    } catch {
      continue
    }

    const model = modelWithout(full, payload)
    const predicted = predictTrack(payload, model)
    if (!predicted || predicted.movingMs <= 0) continue

    const ratios = ratiosWithout(row.track_id)
    const average = predicted.movingMs * ratios.p50
    const fastest = predicted.movingMs * ratios.p10
    const slowest = predicted.movingMs * ratios.p90

    const baseline = (row.distance_m / 1000) / baselineKmh * 3_600_000
    const modelError = Math.abs(average - row.actual_ms) / row.actual_ms
    const baselineError = Math.abs(baseline - row.actual_ms) / row.actual_ms

    modelErrorSum += modelError
    baselineErrorSum += baselineError

    results.push({
      trackId: row.track_id,
      distanceKm: Math.round(row.distance_m / 100) / 10,
      actualMin: Math.round(row.actual_ms / 6000) / 10,
      predictedMin: Math.round(average / 6000) / 10,
      errorPercent: Math.round(modelError * 1000) / 10,
      inBand: row.actual_ms >= fastest && row.actual_ms <= slowest
    })
  }

  const n = results.length || 1
  const inBand = results.filter((r) => r.inBand).length
  const errors = results.map((r) => r.errorPercent).sort((a, b) => a - b)

  return {
    ok: true,
    tracks: results.length,
    mapePercent: Math.round((modelErrorSum / n) * 1000) / 10,
    medianErrorPercent: errors[Math.floor(errors.length / 2)] ?? 0,
    baselineMapePercent: Math.round((baselineErrorSum / n) * 1000) / 10,
    baselineKmh: Math.round(baselineKmh * 10) / 10,
    bandCoverage: Math.round((inBand / n) * 100) / 100,
    verdict: verdict(modelErrorSum / n, baselineErrorSum / n, inBand / n),
    results: results.sort((a, b) => b.errorPercent - a.errorPercent)
  }
}

function verdict(mape, baselineMape, coverage) {
  const notes = []
  if (mape <= 0.1) notes.push('A menetidő hibája jó (≤10%).')
  else if (mape <= 0.15) notes.push('A menetidő hibája elfogadható (≤15%).')
  else notes.push(`A menetidő hibája túl nagy (${Math.round(mape * 100)}%).`)

  if (mape < baselineMape * 0.85) notes.push('A modell érdemben veri a konstans átlagsebességet.')
  else notes.push('A modell NEM veri érdemben a konstans átlagsebességet - a kanyarmodellezés itt nem termeli ki az árát.')

  if (coverage >= 0.65 && coverage <= 0.9) notes.push('A leggyorsabb/leglassabb sáv reális.')
  else if (coverage > 0.9) notes.push('A sáv túl széles: szinte minden túra belefér, tehát keveset mond.')
  else notes.push('A sáv túl szűk: a túrák többsége kilóg belőle.')

  return notes.join(' ')
}

/** Egy túra hozzájárulásának kivonása a memóriabeli modellből. */
function modelWithout(model, payload) {
  const buckets = new Map()
  for (const [key, value] of model.buckets) {
    const minus = payload.buckets?.[key]
    if (!minus) {
      buckets.set(key, value)
      continue
    }
    const histogram = value.histogram.map((count, i) => Math.max(0, count - (minus[i] ?? 0)))
    const samples = histogram.reduce((sum, count) => sum + count, 0)
    buckets.set(key, { samples, histogram })
  }

  const cells = new Map()
  for (const [key, value] of model.cells) {
    const minus = payload.cells?.[key]
    if (!minus) {
      cells.set(key, value)
      continue
    }
    // Ha a cellát csak ez a túra látta, a kihagyás után nem tudhatunk róla.
    if (value.tracks <= 1) continue
    const histogram = value.histogram.map((count, i) => Math.max(0, count - (minus[i] ?? 0)))
    const samples = histogram.reduce((sum, count) => sum + count, 0)
    if (samples <= 0) continue
    cells.set(key, { samples, tracks: value.tracks - 1, histogram })
  }

  return { ...model, buckets, cells }
}

/** Az arány-percentilisek a vizsgált túra nélkül. */
function ratiosWithout(trackId) {
  const rows = all('SELECT ratio FROM model_calibration WHERE usable = 1 AND track_id <> ? ORDER BY ratio', trackId)
  const n = rows.length
  if (n < 3) return { p10: 0.85, p50: 1.0, p90: 1.25 }
  const at = (q) => {
    const position = (n - 1) * q
    const low = Math.floor(position)
    const high = Math.min(n - 1, low + 1)
    const weight = position - low
    return rows[low].ratio * (1 - weight) + rows[high].ratio * weight
  }
  return { p10: at(0.1), p50: at(0.5), p90: at(0.9) }
}

export { percentileFromHistogram }
