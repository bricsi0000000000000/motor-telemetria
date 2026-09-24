import { all, one, run, tx } from './db.js'
import { haversine } from './overpass.js'
import { traceAttributes } from './routing.js'
import {
  STEP_M, resample, smoothPath, curvature, cornerClass, bearing, octant, cellOf, limitClass
} from './geometry.js'
import {
  emptyHistogram, addToHistogram, invalidateModel
} from './ridermodel.js'

/**
 * A személyes modell felépítése a már megtett túrákból.
 *
 * A menet: a nyers GPS-nyomvonalat ráillesztjük az úthálózatra (Valhalla map
 * matching), az így kapott OSM-vonalból számoljuk a görbületet, és a mért
 * sebességeket ezekhez a kanyarosztályokhoz rendeljük. A nyers GPS-ből azért
 * nem lehet: a medián pontosságod 8,3 m, ami simítás nélkül hajtűkanyarokat
 * gyárt ott, ahol egyenes van.
 *
 * Minden összesítő SZÁRMAZTATOTT: a track_match-ből bármikor újraépíthető.
 * Egy túra pontosan egyszer járul hozzá - a korábbi hozzájárulását kivonjuk,
 * mielőtt újat írnánk, különben az újraszinkron csendben duplázna.
 */

/** Ennél pontatlanabb fix nem mond semmit a tempóról. */
const MAX_ACCURACY_M = 20

/** Ez alatt állsz, nem mész: a piros lámpa nem "így veszem a kanyart". */
const MIN_SPEED_MPS = 1.5

/** Minden szakasz elején/végén ennyit eldobunk: indítás/leállítás műtermék. */
const EDGE_TRIM_M = 100

/** Lámpa közelében nem tanulunk tempót. */
const SIGNAL_RADIUS_M = 60

/** A kanyartempó csak szűk kanyarban mond valamit; felette a limit korlátoz. */
const ALAT_MAX_RADIUS_M = 150

/** A dinamika hisztogramjai századokban állnak, hogy a 0..2 m/s² jól felbontott legyen. */
const DYN_SCALE = 100

/** Valhalla útosztályok - indexelve tároljuk, hogy a payload ne hízzon. */
const ROAD_CLASSES = [
  'motorway', 'trunk', 'primary', 'secondary', 'tertiary',
  'unclassified', 'residential', 'service_other', 'unknown'
]

const roadClassIndex = (value) => {
  const index = ROAD_CLASSES.indexOf(value)
  return index < 0 ? ROAD_CLASSES.length - 1 : index
}
const roadClassName = (index) => ROAD_CLASSES[index] ?? 'unknown'

const round = (value, digits) => {
  const factor = 10 ** digits
  return Math.round(value * factor) / factor
}

/**
 * A túra pontjai a szerveroldali sémából. A bearing-et szándékosan NEM
 * olvassuk: álló helyzetben az Android 0-t ad, és a mozgásirányt amúgy is
 * pontosabban kapjuk az illesztett pozíciók sorozatából.
 */
const trackPoints = (trackId) =>
  all(`SELECT seq, lat, lon, altitude, speed_mps AS speedMps, accuracy, time, segment
       FROM track_points WHERE track_id = ? ORDER BY seq`, trackId)

/** A lámpák a meglévő road_points táblából; ha üres, ez a szűrés nem tesz semmit. */
const trafficSignals = () =>
  all("SELECT lat, lon FROM road_points WHERE kind = 'TRAFFIC_SIGNALS'")

/**
 * Egy túra rávetítése az úthálózatra és a hozzájárulásának kiszámolása.
 * Nem ír összesítőt - csak a track_match sorát állítja elő.
 */
export async function matchTrack(trackId, { force = false } = {}) {
  const track = one('SELECT * FROM tracks WHERE id = ?', trackId)
  if (!track) return { trackId, status: 'skipped', reason: 'nincs ilyen túra' }

  const existing = one('SELECT point_count, status FROM track_match WHERE track_id = ?', trackId)
  if (!force && existing && existing.point_count === track.point_count) {
    return { trackId, status: 'cached' }
  }

  const points = trackPoints(trackId)
  if (points.length < 30) {
    saveMatch(trackId, null, points.length, { status: 'skipped', error: 'túl kevés pont' })
    return { trackId, status: 'skipped', reason: 'túl kevés pont' }
  }

  let trace
  try {
    trace = await traceAttributes(points, 'map_snap')
  } catch (error) {
    saveMatch(trackId, null, points.length, { status: 'failed', error: error.message })
    return { trackId, status: 'failed', reason: error.message }
  }

  if (trace.shape.length < 3) {
    saveMatch(trackId, null, points.length, { status: 'failed', error: 'nem sikerült illeszteni' })
    return { trackId, status: 'failed', reason: 'nem sikerült illeszteni' }
  }

  const payload = buildContribution(track, points, trace)
  saveMatch(trackId, payload, points.length, {
    status: 'ok',
    matchedM: payload.matchedM,
    unmatched: payload.unmatched
  })
  return { trackId, status: 'ok', matchedM: payload.matchedM, unmatched: payload.unmatched }
}

/**
 * A lépéssor és a hozzájárulások előállítása. Ez a függvény a modell szíve:
 * itt dől el, melyik mért sebesség melyik kanyarosztályhoz tartozik.
 */
function buildContribution(track, points, trace) {
  // 1. A geometria: az ILLESZTETT vonal, nem a nyers GPS.
  const sampled = resample(trace.shape, STEP_M)
  const smoothed = smoothPath(sampled, 5)
  const radii = curvature(smoothed)

  // 2. Élenkénti tulajdonságok rávetítése a lépésekre.
  const edgeForShapeIndex = new Int32Array(trace.shape.length).fill(-1)
  for (let e = 0; e < trace.edges.length; e++) {
    const edge = trace.edges[e]
    for (let i = edge.beginShapeIndex; i <= Math.min(edge.endShapeIndex, trace.shape.length - 1); i++) {
      edgeForShapeIndex[i] = e
    }
  }

  const steps = sampled.map((point, index) => {
    const edgeIndex = edgeForShapeIndex[Math.min(point.src, edgeForShapeIndex.length - 1)]
    const edge = edgeIndex >= 0 ? trace.edges[edgeIndex] : null
    const next = sampled[Math.min(index + 1, sampled.length - 1)]
    const prev = sampled[Math.max(index - 1, 0)]
    return {
      lat: point.lat,
      lon: point.lon,
      d: point.d,
      radius: radii[index],
      cornerClass: cornerClass(radii[index]),
      roadClass: edge?.roadClass ?? 'unknown',
      limitKmh: edge?.speedLimitKmh ?? null,
      surface: edge?.surface ?? null,
      road: edge?.name ?? null,
      bearing: bearing(prev.lat, prev.lon, next.lat, next.lon),
      lengthM: index === 0 ? 0 : point.d - sampled[index - 1].d
    }
  })

  // 3. Minden GPS-pontot a hozzá tartozó lépéshez rendelünk.
  //
  //    NEM futó kurzorral: az elsőre kézenfekvő "haladjunk együtt a nyomvonallal"
  //    megoldás oda-vissza túrán elveszik, és onnantól minden pontot eldob (a
  //    2-es túrán ez 8349-ből 4221 minta elvesztése volt, 2,2 km-es
  //    mediántávolsággal). Helyette azt használjuk, amit a Valhalla úgyis
  //    megmond: melyik ÉLRE illesztette a pontot. Az él ismeri a saját
  //    vonalszakaszát, azon belül már csak a legközelebbi lépést kell megkeresni.
  const stepsForShape = new Map()
  for (let i = 0; i < steps.length; i++) {
    const key = sampled[i].src
    if (!stepsForShape.has(key)) stepsForShape.set(key, [])
    stepsForShape.get(key).push(i)
  }
  const signals = trafficSignals()
  const buckets = new Map()
  const cells = new Map()
  const cellTracks = new Set()
  const dynamics = {
    aLat: emptyHistogram(),
    accel: emptyHistogram(),
    decel: emptyHistogram(),
    compliance: emptyHistogram()
  }

  const totalDistance = steps.length ? steps[steps.length - 1].d : 0
  let unmatched = 0
  let previous = null

  for (let i = 0; i < points.length; i++) {
    const point = points[i]
    const matched = trace.matched[i]
    if (!matched || matched.type === 'unmatched' || matched.lat === null) {
      unmatched++
      continue
    }

    const candidates = stepCandidates(matched, trace, stepsForShape, steps.length)
    let best = -1
    let bestDistance = Infinity
    for (const s of candidates) {
      const distance = haversine(matched.lat, matched.lon, steps[s].lat, steps[s].lon)
      if (distance < bestDistance) {
        bestDistance = distance
        best = s
      }
    }
    if (best < 0 || bestDistance > 60) {
      unmatched++
      continue
    }

    const step = steps[best]
    const usable = isUsableSample(point, step, previous, totalDistance, signals)
    previous = { point, step, matched }
    if (!usable.speed) continue

    const speedKmh = point.speedMps * 3.6

    // Dinamika: ezek a te szokásaid, ezekből lesz a_lat és a kényelmi gyorsulás.
    if (step.radius < ALAT_MAX_RADIUS_M) {
      addToHistogram(dynamics.aLat, ((point.speedMps ** 2) / step.radius) * DYN_SCALE)
    }
    if (usable.pair) {
      const dt = (point.time - usable.pair.time) / 1000
      if (dt > 0 && dt < 6) {
        const a = (point.speedMps - usable.pair.speedMps) / dt
        if (a > 0) addToHistogram(dynamics.accel, a * DYN_SCALE)
        else addToHistogram(dynamics.decel, -a * DYN_SCALE)
      }
    }
    if (step.limitKmh > 0) {
      addToHistogram(dynamics.compliance, (speedKmh / step.limitKmh) * 100)
    }

    // "B" szint: ez a konkrét út, ebben az irányban.
    const { cellLat, cellLon } = cellOf(matched.lat, matched.lon)
    const cellKey = `${cellLat}|${cellLon}|${octant(step.bearing)}`
    if (!cells.has(cellKey)) cells.set(cellKey, emptyHistogram())
    addToHistogram(cells.get(cellKey), speedKmh)
    cellTracks.add(cellKey)

    // "A" szint: ilyen típusú út. Lámpa közelében nem tanulunk, mert a piros
    // lámpa különben megmérgezné az "egyenes, lakott terület" vödröt.
    if (usable.bucket) {
      const key = `${step.cornerClass}|${step.roadClass}|${limitClass(step.limitKmh)}`
      if (!buckets.has(key)) buckets.set(key, emptyHistogram())
      addToHistogram(buckets.get(key), speedKmh)
    }
  }

  return {
    matchedM: Math.round(totalDistance),
    unmatched,
    steps: packSteps(steps),
    buckets: Object.fromEntries(buckets),
    cells: Object.fromEntries(cells),
    dynamics
  }
}

/**
 * Egy illesztett ponthoz tartozó lépésjelöltek.
 *
 * Elsősorban az él vonalszakaszából, mert azt a Valhalla mondta meg. Ha az él
 * ismeretlen (interpolált pont), akkor a teljes lépéssor helyett sem keresünk
 * vakon: az ilyen pontot inkább eldobjuk, mint hogy rossz kanyarhoz rendeljük.
 */
function stepCandidates(matched, trace, stepsForShape, stepCount) {
  if (!Number.isInteger(matched.edgeIndex)) return []
  const edge = trace.edges[matched.edgeIndex]
  if (!edge) return []

  const out = []
  for (let shapeIndex = edge.beginShapeIndex; shapeIndex <= edge.endShapeIndex + 1; shapeIndex++) {
    for (const step of stepsForShape.get(shapeIndex) ?? []) {
      if (step < stepCount) out.push(step)
    }
  }
  return out
}

/** A minta higiéniája: mit szabad megtanulni és mit nem. */
function isUsableSample(point, step, previous, totalDistance, signals) {
  const result = { speed: false, bucket: false, pair: null }

  if (point.accuracy > MAX_ACCURACY_M) return result
  if (point.speedMps < MIN_SPEED_MPS) return result
  // A szakasz eleje/vége: indításkor és leállításkor a sebesség nem a tempódról szól.
  if (step.d < EDGE_TRIM_M || totalDistance - step.d < EDGE_TRIM_M) return result

  result.speed = true
  if (previous && previous.point.segment === point.segment) result.pair = previous.point

  result.bucket = !signals.some(
    (signal) => haversine(signal.lat, signal.lon, step.lat, step.lon) < SIGNAL_RADIUS_M
  )
  return result
}

/** Párhuzamos tömbök kerekítve: így a payload töredéke a naiv JSON-nak. */
const packSteps = (steps) => ({
  lat: steps.map((s) => round(s.lat, 5)),
  lon: steps.map((s) => round(s.lon, 5)),
  r: steps.map((s) => Math.min(99999, Math.round(s.radius))),
  rc: steps.map((s) => roadClassIndex(s.roadClass)),
  lim: steps.map((s) => s.limitKmh ?? 0),
  brg: steps.map((s) => Math.round(s.bearing)),
  len: steps.map((s) => round(s.lengthM, 1))
})

/** A kicsomagolás a kalibrációhoz kell: ugyanazt a lépéssort újra le kell futtatni. */
export function unpackSteps(packed) {
  const out = []
  for (let i = 0; i < packed.lat.length; i++) {
    const radius = packed.r[i]
    out.push({
      lat: packed.lat[i],
      lon: packed.lon[i],
      radius,
      cornerClass: cornerClass(radius),
      roadClass: roadClassName(packed.rc[i]),
      limitKmh: packed.lim[i] || null,
      surface: null,
      bearing: packed.brg[i],
      lengthM: packed.len[i],
      gradePercent: 0
    })
  }
  return out
}

function saveMatch(trackId, payload, pointCount, { status, error = null, matchedM = 0, unmatched = 0 }) {
  tx(() => {
    subtractContribution(trackId)
    run(
      `INSERT INTO track_match (track_id, payload, point_count, matched_m, unmatched, status, error, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(track_id) DO UPDATE SET
         payload = excluded.payload, point_count = excluded.point_count,
         matched_m = excluded.matched_m, unmatched = excluded.unmatched,
         status = excluded.status, error = excluded.error, created_at = excluded.created_at`,
      trackId, JSON.stringify(payload ?? {}), pointCount, matchedM, unmatched, status, error, Date.now()
    )
    if (payload) addContribution(payload)
  })
  invalidateModel()
}

/**
 * A korábbi hozzájárulás kivonása. Ez az a lépés, ami nélkül az újraszinkron
 * csendben tönkretenné a modellt: ugyanaz a túra kétszer számítana bele.
 */
function subtractContribution(trackId) {
  const row = one("SELECT payload FROM track_match WHERE track_id = ? AND status = 'ok'", trackId)
  if (!row) return
  let payload
  try {
    payload = JSON.parse(row.payload)
  } catch {
    return
  }
  applyContribution(payload, -1)
}

const addContribution = (payload) => applyContribution(payload, 1)

function applyContribution(payload, sign) {
  const now = Date.now()

  for (const [key, histogram] of Object.entries(payload.buckets ?? {})) {
    const [cornerClassName, roadClass, limit] = key.split('|')
    const existing = one(
      'SELECT histogram, samples, sum_v, sum_v2 FROM ride_profile WHERE corner_class = ? AND road_class = ? AND limit_class = ?',
      cornerClassName, roadClass, Number(limit)
    )
    const merged = existing ? JSON.parse(existing.histogram) : emptyHistogram()
    let samples = existing?.samples ?? 0
    let sumV = existing?.sum_v ?? 0
    let sumV2 = existing?.sum_v2 ?? 0

    for (let i = 0; i < merged.length; i++) {
      const count = (histogram[i] ?? 0) * sign
      merged[i] = Math.max(0, merged[i] + count)
      const mid = (i + 0.5) * 5
      samples += count
      sumV += count * mid
      sumV2 += count * mid * mid
    }
    samples = Math.max(0, samples)

    run(
      `INSERT INTO ride_profile (corner_class, road_class, limit_class, samples, histogram, sum_v, sum_v2, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(corner_class, road_class, limit_class) DO UPDATE SET
         samples = excluded.samples, histogram = excluded.histogram,
         sum_v = excluded.sum_v, sum_v2 = excluded.sum_v2, updated_at = excluded.updated_at`,
      cornerClassName, roadClass, Number(limit), samples, JSON.stringify(merged),
      Math.max(0, sumV), Math.max(0, sumV2), now
    )
  }

  for (const [key, histogram] of Object.entries(payload.cells ?? {})) {
    const [cellLat, cellLon, oct] = key.split('|').map(Number)
    const existing = one(
      'SELECT histogram, samples, tracks FROM ride_cells WHERE cell_lat = ? AND cell_lon = ? AND octant = ?',
      cellLat, cellLon, oct
    )
    const merged = existing ? JSON.parse(existing.histogram) : emptyHistogram()
    let samples = existing?.samples ?? 0
    for (let i = 0; i < merged.length; i++) {
      const count = (histogram[i] ?? 0) * sign
      merged[i] = Math.max(0, merged[i] + count)
      samples += count
    }
    const tracks = Math.max(0, (existing?.tracks ?? 0) + sign)
    samples = Math.max(0, samples)

    if (samples <= 0) {
      run('DELETE FROM ride_cells WHERE cell_lat = ? AND cell_lon = ? AND octant = ?', cellLat, cellLon, oct)
      continue
    }
    run(
      `INSERT INTO ride_cells (cell_lat, cell_lon, octant, samples, tracks, histogram, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(cell_lat, cell_lon, octant) DO UPDATE SET
         samples = excluded.samples, tracks = excluded.tracks,
         histogram = excluded.histogram, updated_at = excluded.updated_at`,
      cellLat, cellLon, oct, samples, tracks, JSON.stringify(merged), now
    )
  }
}

/**
 * Az összesítők teljes újraépítése a már eltett illesztésekből. Hálózat nélkül
 * fut - ezért olcsó bármikor meghívni, ha gyanús az eredmény.
 */
export function rebuildAggregates() {
  tx(() => {
    run('DELETE FROM ride_profile')
    run('DELETE FROM ride_cells')
    for (const row of all("SELECT payload FROM track_match WHERE status = 'ok' ORDER BY track_id")) {
      try {
        addContribution(JSON.parse(row.payload))
      } catch {
        // Egy sérült payload ne akassza meg a többit.
      }
    }
  })
  invalidateModel()
  return aggregateState()
}

export function aggregateState() {
  const match = one(`SELECT COUNT(*) AS total,
                            SUM(CASE WHEN status = 'ok' THEN 1 ELSE 0 END) AS ok,
                            SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END) AS failed
                     FROM track_match`)
  const profile = one('SELECT COUNT(*) AS buckets, SUM(samples) AS samples FROM ride_profile')
  const cells = one('SELECT COUNT(*) AS cells, SUM(samples) AS samples FROM ride_cells')
  return {
    matched: match?.ok ?? 0,
    failed: match?.failed ?? 0,
    total: match?.total ?? 0,
    buckets: profile?.buckets ?? 0,
    bucketSamples: profile?.samples ?? 0,
    cells: cells?.cells ?? 0,
    cellSamples: cells?.samples ?? 0
  }
}

/** A még nem (vagy elavultan) illesztett túrák. */
export const pendingTracks = () =>
  all(`SELECT t.id FROM tracks t
       LEFT JOIN track_match m ON m.track_id = t.id
       WHERE t.end_time IS NOT NULL
         AND t.point_count >= 30
         AND (m.track_id IS NULL OR m.point_count <> t.point_count)
       ORDER BY t.start_time DESC`).map((row) => row.id)

/** Egyszerre egy modellfrissítés fut; a szinkron nem várhat rá. */
let aggregating = false

const markModelLayer = (status, count, error = null) =>
  run(
    `INSERT INTO layer_state (layer, updated_at, count, status, error) VALUES ('ridemodel', ?, ?, ?, ?)
     ON CONFLICT (layer) DO UPDATE SET updated_at = excluded.updated_at, count = excluded.count,
                                       status = excluded.status, error = excluded.error`,
    status === 'idle' ? Date.now() : (one("SELECT updated_at AS t FROM layer_state WHERE layer = 'ridemodel'")?.t ?? 0),
    count, status, error
  )

/**
 * A modell frissítése a most beérkezett túrákkal. Tűzd-és-felejtsd: a telefon
 * szinkronja nem várhat egy térképre illesztésre, az állapot viszont látszódjon
 * a layer_state-ben, ugyanúgy, ahogy a közúti rétegeké.
 */
export function scheduleAggregate() {
  if (aggregating) return false
  const pending = pendingTracks()
  if (pending.length === 0) return false

  aggregating = true
  markModelLayer('running', pending.length)

  ;(async () => {
    let done = 0
    for (const id of pending) {
      const result = await matchTrack(id)
      if (result.status === 'ok') done++
    }
    // A kalibráció csak azután érvényes, hogy minden új túra bekerült.
    const { rebuildCalibration } = await import('./calibration.js')
    rebuildCalibration()
    return done
  })()
    .then((done) => markModelLayer('idle', done))
    .catch((error) => {
      console.warn('Az útvonalmodell frissítése nem sikerült:', error.message)
      markModelLayer('error', 0, error.message)
    })
    .finally(() => {
      aggregating = false
    })

  return true
}
