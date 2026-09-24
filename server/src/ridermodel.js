import { all, one } from './db.js'
import { DEFAULT_LIMITS } from './overpass.js'
import { cornerClass, limitClass, cellOf, octant } from './geometry.js'
import { NX500, gripCornerSpeed, topSpeedMps } from './bike.js'

/**
 * A személyes sebességmodell: mennyivel szoktál te menni.
 *
 * Két szinten dolgozik:
 *
 *  "B" szint (cella): ezen a KONKRÉT úton, ebben az irányban mért tempód.
 *    Ez válaszol arra, hogy "kb. mennyivel szoktam ezt az utat megtenni".
 *    Korlát: a 65 153 pontod ~379 km egyedi utat fed le, és ebből csak ~79 km-en
 *    jártál többször - tehát ez főleg a rendszeres útjaidra tud válaszolni.
 *
 *  "A" szint (vödör): ilyen TÍPUSÚ úton (kanyarosztály × útosztály × limit) mért
 *    tempód. Ez olyan úton is működik, ahol még sosem jártál, és a tervezett
 *    útvonalak nagy részén ez dolgozik.
 *
 * Ahol egyik sincs, a saját kanyarszokásodból számolunk: v = sqrt(a_lat · R).
 */

/** A hisztogram 5 km/h-s vödrökben áll, 0..200 km/h. */
export const HIST_BUCKETS = 40
export const HIST_STEP_KMH = 5

export const emptyHistogram = () => new Array(HIST_BUCKETS).fill(0)

export function addToHistogram(histogram, valueKmh, weight = 1) {
  const index = Math.min(HIST_BUCKETS - 1, Math.max(0, Math.floor(valueKmh / HIST_STEP_KMH)))
  histogram[index] += weight
  return histogram
}

/**
 * Percentilis hisztogramból, a vödrön belül lineárisan interpolálva.
 * A vödrös tárolás miatt a beolvasás és az összevonás is puszta összeadás.
 */
export function percentileFromHistogram(histogram, q) {
  let total = 0
  for (const count of histogram) total += count
  if (total <= 0) return null

  const target = total * q
  let cumulative = 0
  for (let i = 0; i < histogram.length; i++) {
    if (histogram[i] <= 0) continue
    if (cumulative + histogram[i] >= target) {
      const within = (target - cumulative) / histogram[i]
      return (i + within) * HIST_STEP_KMH
    }
    cumulative += histogram[i]
  }
  return HIST_BUCKETS * HIST_STEP_KMH
}

const sumHistograms = (target, source) => {
  for (let i = 0; i < HIST_BUCKETS; i++) target[i] += source[i] ?? 0
  return target
}

/** Mennyi minta kell, hogy egy cellának higgyünk. */
const CELL_MIN_SAMPLES = 8
const CELL_MIN_TRACKS = 2

/** Egy típusvödör ennél kevesebb mintával még zajos. */
const BUCKET_MIN_SAMPLES = 30

/** Ha semmi sem tudható, a kiírt limit ennyied részével számolunk. */
const BLIND_LIMIT_FACTOR = 0.9

/**
 * Generikus vezető, amíg nincs elég saját adat. Az a_lat itt a te mért
 * mediánodnál (1,20) kicsit bátrabb, mert kezdetben nem tudjuk, ki vezet.
 */
const COLD_RIDER = {
  aLat: { p10: 0.7, p50: 1.4, p90: 2.3 },
  accelMps2: 1.1,
  decelMps2: 1.1,
  compliance: 1.0,
  ratios: { p10: 0.8, p50: 1.0, p90: 1.35 },
  calibrationTracks: 0
}

let cached = null

/** A modell csak szinkron/újraépítés után változik, ezért memóriában tartjuk. */
export function invalidateModel() {
  cached = null
}

export function loadModel() {
  if (cached) return cached

  const buckets = new Map()
  for (const row of all('SELECT * FROM ride_profile')) {
    buckets.set(`${row.corner_class}|${row.road_class}|${row.limit_class}`, {
      samples: row.samples,
      histogram: JSON.parse(row.histogram)
    })
  }

  const cells = new Map()
  for (const row of all('SELECT * FROM ride_cells WHERE samples >= ? AND tracks >= ?',
    CELL_MIN_SAMPLES, CELL_MIN_TRACKS)) {
    cells.set(`${row.cell_lat}|${row.cell_lon}|${row.octant}`, {
      samples: row.samples,
      tracks: row.tracks,
      histogram: JSON.parse(row.histogram)
    })
  }

  cached = { buckets, cells, dynamics: loadDynamics(), ...loadRatios() }
  return cached
}

/**
 * A vezetési dinamika (kanyartempó, gyorsítás, fékezés, limitmegfelelés) a
 * túránként eltett hisztogramokból áll össze. Azért innen és nem külön táblából,
 * mert így a track_match-ből bármikor pontosan újraszámolható, és egy túra
 * kivonása sem ronthatja el.
 */
function loadDynamics() {
  const pooled = {
    aLat: emptyHistogram(),
    accel: emptyHistogram(),
    decel: emptyHistogram(),
    compliance: emptyHistogram()
  }
  let tracks = 0

  for (const row of all("SELECT payload FROM track_match WHERE status = 'ok'")) {
    let payload
    try {
      payload = JSON.parse(row.payload)
    } catch {
      continue
    }
    const dynamics = payload.dynamics
    if (!dynamics) continue
    tracks++
    sumHistograms(pooled.aLat, dynamics.aLat ?? [])
    sumHistograms(pooled.accel, dynamics.accel ?? [])
    sumHistograms(pooled.decel, dynamics.decel ?? [])
    sumHistograms(pooled.compliance, dynamics.compliance ?? [])
  }

  if (tracks === 0) return { ...COLD_RIDER, cold: true }

  // A dinamika hisztogramjai századokban állnak (0,05 m/s² felbontás), mert a
  // hisztogram vödre 5 egység - így a 0..2 m/s² tartomány jól felbontott.
  const scale = (value) => (value === null ? null : value / 100)

  const aLat = {
    p10: scale(percentileFromHistogram(pooled.aLat, 0.1)) ?? COLD_RIDER.aLat.p10,
    p50: scale(percentileFromHistogram(pooled.aLat, 0.5)) ?? COLD_RIDER.aLat.p50,
    p90: scale(percentileFromHistogram(pooled.aLat, 0.9)) ?? COLD_RIDER.aLat.p90
  }

  return {
    aLat,
    // A p90 a kényelmi korlát: a medián gyorsítással a modell minden faluból
    // araszolva indulna, a p99-cel viszont versenyzőként.
    accelMps2: scale(percentileFromHistogram(pooled.accel, 0.9)) ?? COLD_RIDER.accelMps2,
    decelMps2: scale(percentileFromHistogram(pooled.decel, 0.9)) ?? COLD_RIDER.decelMps2,
    // A limitmegfelelés hisztogramja százalékban áll (100 = pont a limiten).
    compliance: (percentileFromHistogram(pooled.compliance, 0.5) ?? 100) / 100,
    tracks,
    cold: false
  }
}

/**
 * A modell és a valóság arányának percentilisei. Kis mintánál a sávot a
 * mediánhoz húzzuk: három túrával nem illik magabiztos 10. percentilist mondani.
 */
function loadRatios() {
  const rows = all('SELECT ratio FROM model_calibration WHERE usable = 1 ORDER BY ratio')
  const n = rows.length
  if (n < 3) return { ratios: COLD_RIDER.ratios, calibrationTracks: n }

  const at = (q) => {
    const position = (n - 1) * q
    const low = Math.floor(position)
    const high = Math.min(n - 1, low + 1)
    const weight = position - low
    return rows[low].ratio * (1 - weight) + rows[high].ratio * weight
  }

  const p50 = at(0.5)
  const shrink = Math.min(1, n / 15)
  return {
    ratios: {
      p10: p50 - (p50 - at(0.1)) * shrink,
      p50,
      p90: p50 + (at(0.9) - p50) * shrink
    },
    calibrationTracks: n
  }
}

/**
 * Célsebesség egy lépésre (m/s), és hogy honnan tudjuk.
 *
 * A sorrend szándékos: a konkrét út tudása veri a típus szerinti átlagot, az
 * pedig a puszta geometriát. A végén minden korlát egyszerre érvényes - a
 * legkisebb nyer.
 */
export function targetSpeed(model, step, bike = NX500) {
  const radius = step.radius
  const dynamics = model.dynamics
  let source = 'CURVE'
  let samples = 0
  let history = null

  const { cellLat, cellLon } = cellOf(step.lat, step.lon)
  const cell = model.cells.get(`${cellLat}|${cellLon}|${octant(step.bearing)}`)
  if (cell) {
    const value = percentileFromHistogram(cell.histogram, 0.5)
    if (value !== null) {
      history = value / 3.6
      source = 'CELL'
      samples = cell.samples
    }
  }

  if (history === null) {
    const key = `${step.cornerClass}|${step.roadClass ?? 'unknown'}|${limitClass(step.limitKmh)}`
    const bucket = model.buckets.get(key)
    if (bucket && bucket.samples >= BUCKET_MIN_SAMPLES) {
      const value = percentileFromHistogram(bucket.histogram, 0.5)
      if (value !== null) {
        history = value / 3.6
        source = 'BUCKET'
        samples = bucket.samples
      }
    }
  }

  const limits = []
  if (history !== null) limits.push(history)

  // A saját kanyartempód. Ez akkor is korlátoz, ha van történelmi adat: egy
  // szűk kanyart nem lehet gyorsabban bevenni attól, hogy az út többi részén
  // gyorsan mész.
  limits.push(Math.sqrt(dynamics.aLat.p50 * Math.min(radius, 1e5)))

  if (Number.isFinite(step.limitKmh) && step.limitKmh > 0) {
    limits.push((step.limitKmh / 3.6) * dynamics.compliance)
  } else if (history === null) {
    const fallback = DEFAULT_LIMITS[step.roadClass] ?? 50
    limits.push((fallback / 3.6) * BLIND_LIMIT_FACTOR)
    source = 'DEFAULT'
  }

  limits.push(topSpeedMps(bike))
  limits.push(gripCornerSpeed(bike, radius))

  // Burkolatbüntetés: az NX500 tud földúton menni, de nem ugyanolyan tempóban.
  const unpaved = step.surface && !['paved', 'asphalt', 'concrete', 'paved_smooth'].includes(step.surface)
  let target = Math.min(...limits)
  if (unpaved) target *= 0.55

  return { vMps: Math.max(target, 2), source, samples, history: history !== null }
}

/** A kanyarosztály ugyanúgy számolódik a tervnél és a múltbeli túránál. */
export const classify = (radiusM) => cornerClass(radiusM)

/** Diagnosztika a /api/route/status-hoz. */
export function modelSummary() {
  const model = loadModel()
  const profile = one('SELECT COUNT(*) AS buckets, SUM(samples) AS samples FROM ride_profile')
  const cells = one('SELECT COUNT(*) AS cells, SUM(samples) AS samples FROM ride_cells')
  const matched = one("SELECT COUNT(*) AS ok FROM track_match WHERE status = 'ok'")
  return {
    buckets: profile?.buckets ?? 0,
    bucketSamples: profile?.samples ?? 0,
    cells: cells?.cells ?? 0,
    cellSamples: cells?.samples ?? 0,
    matchedTracks: matched?.ok ?? 0,
    calibrationTracks: model.calibrationTracks,
    ratios: model.ratios,
    dynamics: {
      aLat: model.dynamics.aLat,
      accelMps2: model.dynamics.accelMps2,
      decelMps2: model.dynamics.decelMps2,
      compliance: model.dynamics.compliance,
      cold: model.dynamics.cold
    }
  }
}
