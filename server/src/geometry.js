import { haversine } from './overpass.js'

/**
 * Útvonalgeometria: újramintázás, simítás, görbület, kanyarosztályok.
 *
 * Ez a modul a tervezett útvonalra és a már megtett túrákra is ugyanígy fut le.
 * Ez az azonosság a lényeg: ha a történelmi sebességeket másképp számolt
 * kanyarosztályokhoz rendelnénk, mint amilyeneket a tervnél használunk, a
 * modell értelmetlen lenne.
 *
 * A forrásgeometria MINDIG a térképre illesztett OSM-vonal, soha nem a nyers
 * GPS: a mért medián pontosság 8,3 m, ami simítás nélkül hajtűkanyarokat gyárt
 * ott, ahol egyenes van.
 */

/** Ekkora lépésekre bontjuk az útvonalat. 20 m elég sűrű egy hajtűhöz is. */
export const STEP_M = 20

/** Az "egyenes" sugara. Efölött a görbület már nem befolyásolja a sebességet. */
export const STRAIGHT_R = 1e6

/**
 * Kanyarosztályok sugárhatárai. A mért adataidon ez a felosztás ad monoton
 * sebességsorrendet (hajtű ~31 km/h, szűk ~41, közepes ~59 mellékúton).
 */
export const CORNER_CLASSES = ['HAIRPIN', 'TIGHT', 'MEDIUM', 'GENTLE', 'STRAIGHT']

const CORNER_LIMITS = [
  { max: 30, cls: 'HAIRPIN' },
  { max: 80, cls: 'TIGHT' },
  { max: 200, cls: 'MEDIUM' },
  { max: 600, cls: 'GENTLE' }
]

/** A cellarács mérete: ~100 m széles cellák. A szélesség a szélességi körrel változik. */
const CELL_LAT = 0.0009
const CELL_LON = 0.00133

export const cornerClassIndex = (cls) => CORNER_CLASSES.indexOf(cls)

/** Sugárból kanyarosztály. */
export function cornerClass(radiusM) {
  for (const step of CORNER_LIMITS) {
    if (radiusM < step.max) return step.cls
  }
  return 'STRAIGHT'
}

/**
 * Sík vetítés méterbe. Ugyanaz a közelítés, amit a speedlimits.js
 * distanceToSegment()-je használ: néhány kilométeres távon a hiba elhanyagolható,
 * cserébe nem kell trigonometria minden lépésnél.
 */
export function projector(refLat) {
  const latScale = 111_320
  const lonScale = 111_320 * Math.cos((refLat * Math.PI) / 180)
  return (point) => [point.lon * lonScale, point.lat * latScale]
}

/**
 * Újramintázás ÍVHOSSZ szerint, nem index szerint.
 *
 * Ez nem stílus kérdése: az OSM csomópontok sűrűn állnak a kanyarban és ritkán
 * az egyenesen (mért medián 36 m, de a szórás 6 és 900 m között van), a GPS-pontok
 * pedig 1 Hz-cel jönnek, tehát a lépéstávot a sebesség szabja meg. Index szerinti
 * mintavétellel a lassú szakaszok túlsúlyba kerülnének.
 */
export function resample(points, step = STEP_M) {
  if (points.length < 2) return points.map((p) => ({ lat: p.lat, lon: p.lon, d: 0, src: 0 }))

  const out = [{ lat: points[0].lat, lon: points[0].lon, d: 0, src: 0 }]
  let carried = 0
  let distance = 0

  for (let i = 1; i < points.length; i++) {
    const a = points[i - 1]
    const b = points[i]
    const legLength = haversine(a.lat, a.lon, b.lat, b.lon)
    if (legLength <= 0) continue

    // Ahol a szakaszon belül épp tartunk: a carried az előző szakaszból
    // áthozott maradék, hogy a lépésköz a szakaszhatárokon se csússzon el.
    let offset = step - carried
    while (offset <= legLength) {
      const ratio = offset / legLength
      out.push({
        lat: a.lat + (b.lat - a.lat) * ratio,
        lon: a.lon + (b.lon - a.lon) * ratio,
        d: distance + offset,
        src: i
      })
      offset += step
    }
    carried = (carried + legLength) % step
    distance += legLength
  }

  const last = points[points.length - 1]
  const tail = out[out.length - 1]
  // Az utolsó pont mindig kerüljön be, különben az útvonal vége lecsapódik.
  if (haversine(tail.lat, tail.lon, last.lat, last.lon) > step / 4) {
    out.push({ lat: last.lat, lon: last.lon, d: distance, src: points.length - 1 })
  }
  return out
}

/**
 * Középre igazított mozgóátlag a koordinátákon. 5 pont 20 méteres lépésnél
 * ±40 m ablak: kiveszi a csomópont-elhelyezés zaját, de a valódi szűk kanyart
 * még nem mossa el (9 pontos ablakkal már a hajtűk is eltűnnek).
 */
export function smoothPath(points, window = 5) {
  if (points.length < window) return points
  const half = Math.floor(window / 2)
  return points.map((point, index) => {
    // A két végén nem simítunk, hogy az útvonal vége ne vándoroljon el.
    if (index < half || index >= points.length - half) return point
    let lat = 0
    let lon = 0
    for (let i = index - half; i <= index + half; i++) {
      lat += points[i].lat
      lon += points[i].lon
    }
    return { ...point, lat: lat / window, lon: lon / window }
  })
}

/**
 * Menger-görbületből sugár, két léptéken, a szorosabb olvasat nyer.
 *
 * A k=1 lépték (40 m húr) bontja fel a hajtűket; a k=3 (120 m) akadályozza meg,
 * hogy egy hosszú, állandó sugarú ív egyenesnek látsszon csak azért, mert három
 * egymás utáni csomópont véletlenül egy vonalba esik. A minimum azért helyes,
 * mert a szorosabb olvasat a kötő korlát: az a kanyar, amit be kell venni.
 */
export function curvature(points) {
  const n = points.length
  const radii = new Float64Array(n).fill(STRAIGHT_R)
  if (n < 3) return radii

  const project = projector(points[Math.floor(n / 2)].lat)
  const xy = points.map(project)

  for (let i = 0; i < n; i++) {
    let best = STRAIGHT_R
    for (const k of [1, 3]) {
      if (i - k < 0 || i + k >= n) continue
      const [ax, ay] = xy[i - k]
      const [bx, by] = xy[i]
      const [cx, cy] = xy[i + k]

      const ab = Math.hypot(bx - ax, by - ay)
      const bc = Math.hypot(cx - bx, cy - by)
      const ca = Math.hypot(ax - cx, ay - cy)
      // Kétszeres háromszögterület előjel nélkül.
      const area2 = Math.abs((bx - ax) * (cy - ay) - (cx - ax) * (by - ay))
      if (area2 < 1e-6 || ab === 0 || bc === 0) continue

      const radius = (ab * bc * ca) / (2 * area2)
      if (radius < best) best = radius
    }
    radii[i] = Math.min(best, STRAIGHT_R)
  }
  return radii
}

/** Irány két pont között, fokban (0 = észak, óramutató szerint). */
export function bearing(aLat, aLon, bLat, bLon) {
  const rad = Math.PI / 180
  const dLon = (bLon - aLon) * rad
  const y = Math.sin(dLon) * Math.cos(bLat * rad)
  const x =
    Math.cos(aLat * rad) * Math.sin(bLat * rad) -
    Math.sin(aLat * rad) * Math.cos(bLat * rad) * Math.cos(dLon)
  return (Math.atan2(y, x) / rad + 360) % 360
}

/** Az irány 45 fokos szektora. A szembe irány külön vödör: más ív, más elsőbbség. */
export const octant = (bearingDeg) => Math.round(((bearingDeg % 360) + 360) % 360 / 45) % 8

/** A ~100 méteres cellarács azonosítója. */
export const cellOf = (lat, lon) => ({
  cellLat: Math.round(lat / CELL_LAT),
  cellLon: Math.round(lon / CELL_LON)
})

/**
 * A sebességhatár vödre. A modell nem a pontos limitre tanul (abból túl sok
 * változat van), hanem a magyar úton előforduló néhány szintre.
 */
export function limitClass(limitKmh) {
  if (!Number.isFinite(limitKmh) || limitKmh <= 0) return 0
  for (const level of [30, 50, 70, 90, 110, 130]) {
    if (limitKmh <= level) return level
  }
  return 130
}

/** Ennél rövidebb futam nem érdemel külön szakaszt a listában. */
const MIN_RUN_M = 100

/**
 * Rövid futamok elnyelése a szomszédjukba.
 *
 * Enélkül egyetlen főút öt-hat "egyenes" szakaszra esne szét, mert közéjük
 * ékelődik pár 20-40 méteres enyhe ív - a listában ez úgy néz ki, mintha
 * ugyanaz az út többször szerepelne.
 *
 * Amit NEM nyelünk el: a ténylegesen ÉLES rövid kanyart (hajtű, szűk, közepes),
 * ha mindkét szomszédjánál élesebb. Egy 60 méteres hajtű két egyenes között
 * pontosan az, amit látni akarsz - egy 60 méteres enyhe ív viszont nem, az
 * csak kettévágná az egyenest a listában.
 */
export function mergeShortRuns(steps, minRunM = MIN_RUN_M) {
  const runs = []
  for (let i = 0; i < steps.length; i++) {
    const last = runs[runs.length - 1]
    if (last && last.cls === steps[i].cornerClass) {
      last.to = i
      last.meters += steps[i].lengthM ?? 0
    } else {
      runs.push({ cls: steps[i].cornerClass, from: i, to: i, meters: steps[i].lengthM ?? 0 })
    }
  }

  const severity = (cls) => CORNER_CLASSES.indexOf(cls)
  const SHARP = new Set(['HAIRPIN', 'TIGHT', 'MEDIUM'])

  for (let r = 1; r < runs.length - 1; r++) {
    const run = runs[r]
    if (run.meters >= minRunM) continue

    const before = runs[r - 1]
    const after = runs[r + 1]
    // Valódi éles kanyar a szomszédjai között: marad, akármilyen rövid.
    if (SHARP.has(run.cls) &&
        severity(run.cls) < severity(before.cls) &&
        severity(run.cls) < severity(after.cls)) continue

    // Az ÉLESEBB szomszédba nyeljük, nem a hosszabbikba. Így a szakasz
    // címkéje soha nem lesz enyhébb annál, mint amilyen a benne lévő
    // legszorosabb kanyar - motoron az alábecslés a rossz irány.
    const winner = severity(before.cls) <= severity(after.cls) ? before : after
    run.cls = winner.cls
    winner.meters += run.meters
    for (let i = run.from; i <= run.to; i++) steps[i].cornerClass = winner.cls
  }
  return steps
}

/**
 * Azonos kanyarosztályú, egymás utáni lépések összevonása listázható szakasszá.
 * A térképen is ezek alapján rajzolunk: 20 méterenként külön vonal egy 100 km-es
 * úton 5000 objektum lenne, futamokra vonva ~200.
 */
export function groupSections(steps) {
  const sections = []
  let current = null

  for (let i = 0; i < steps.length; i++) {
    const step = steps[i]
    if (!current || current.type !== step.cornerClass) {
      if (current) sections.push(current)
      current = {
        type: step.cornerClass,
        fromStep: i,
        toStep: i,
        minRadiusMeters: step.radius,
        distanceMeters: 0,
        durationMillis: 0,
        roads: [],
        limits: [],
        sources: [],
        samples: 0,
        speedSum: 0,
        speedCount: 0
      }
    }
    current.toStep = i
    current.minRadiusMeters = Math.min(current.minRadiusMeters, step.radius)
    current.distanceMeters += step.lengthM ?? 0
    current.durationMillis += step.durationMs ?? 0
    if (step.road) current.roads.push(step.road)
    if (step.limitKmh) current.limits.push(step.limitKmh)
    current.sources.push(step.source)
    current.samples = Math.max(current.samples, step.samples ?? 0)
    if (step.speedKmh) {
      current.speedSum += step.speedKmh
      current.speedCount++
    }
  }
  if (current) sections.push(current)
  return sections
}
