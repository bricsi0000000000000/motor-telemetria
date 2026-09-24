/**
 * Sebességhatárok az OpenStreetMap-ből.
 *
 * A túra nyomvonala mentén (nem a befoglaló téglalapra!) kérdezzük le az utakat
 * az Overpass API-tól, majd minden mintavett ponthoz megkeressük a legközelebbi
 * útszakaszt. Ahol nincs kiírva `maxspeed`, ott az úttípus szerinti magyar
 * alapértéket vesszük – ez becslés, de a nagyságrend stimmel.
 *
 * A lekérdező és a sebességhatár-szabályok az `overpass.js`-ben laknak, mert a
 * térképréteg is ugyanazokat használja.
 */

import { DEFAULT_LIMITS, haversine, overpass, parseMaxspeed } from './overpass.js'

export { haversine }

/** Ilyen sűrűn mintázzuk a nyomvonalat az Overpass kéréshez. */
const SAMPLE_DISTANCE_M = 150

/** Ennél több koordinátát nem teszünk a lekérdezésbe (hosszú túránál ritkítunk). */
const MAX_QUERY_POINTS = 400

/** Ennél messzebb lévő utat már nem tekintünk találatnak. */
const MATCH_RADIUS_M = 30

/** A nyomvonal ritkítása: elég 150 méterenként egy pont az útkereséshez. */
function sampleRoute(points) {
  const samples = []
  let lastLat = null
  let lastLon = null
  for (const point of points) {
    if (
      lastLat === null ||
      haversine(lastLat, lastLon, point.lat, point.lon) >= SAMPLE_DISTANCE_M
    ) {
      samples.push(point)
      lastLat = point.lat
      lastLon = point.lon
    }
  }
  if (samples.length === 0 && points.length > 0) samples.push(points[0])

  if (samples.length <= MAX_QUERY_POINTS) return samples
  const step = Math.ceil(samples.length / MAX_QUERY_POINTS)
  return samples.filter((_, index) => index % step === 0)
}

/** Rácsos index: pontkeresésnél csak a szomszédos cellák szakaszait nézzük. */
const CELL = 0.005

function cellKey(lat, lon) {
  return `${Math.floor(lat / CELL)}:${Math.floor(lon / CELL)}`
}

function buildIndex(ways) {
  const grid = new Map()
  for (const way of ways) {
    const geometry = way.geometry ?? []
    const limit =
      parseMaxspeed(way.tags?.maxspeed) ?? DEFAULT_LIMITS[way.tags?.highway] ?? null
    if (limit === null) continue
    const info = {
      limit,
      guessed: parseMaxspeed(way.tags?.maxspeed) === null,
      name: way.tags?.name || way.tags?.ref || null,
      highway: way.tags?.highway ?? null
    }
    for (let i = 1; i < geometry.length; i++) {
      const segment = { a: geometry[i - 1], b: geometry[i], info }
      // A szakasz mindkét végpontjának cellájába bejegyezzük.
      for (const end of [segment.a, segment.b]) {
        const key = cellKey(end.lat, end.lon)
        const bucket = grid.get(key)
        if (bucket) bucket.push(segment)
        else grid.set(key, [segment])
      }
    }
  }
  return grid
}

/** Pont és szakasz távolsága méterben (kis távon a sík közelítés bőven elég). */
function distanceToSegment(lat, lon, a, b) {
  const latScale = 111320
  const lonScale = 111320 * Math.cos((lat * Math.PI) / 180)
  const px = (lon - a.lon) * lonScale
  const py = (lat - a.lat) * latScale
  const bx = (b.lon - a.lon) * lonScale
  const by = (b.lat - a.lat) * latScale
  const lengthSq = bx * bx + by * by
  if (lengthSq === 0) return Math.hypot(px, py)
  const t = Math.max(0, Math.min(1, (px * bx + py * by) / lengthSq))
  return Math.hypot(px - t * bx, py - t * by)
}

/**
 * Az útosztály kis előnyt kap a párosításnál: egy főút mellett futó szerviz- vagy
 * lakóutca sokszor pár méterrel közelebb esik a GPS-nyomhoz, pedig nyilván a
 * főúton mentünk.
 */
const CLASS_BONUS_M = {
  motorway: 12,
  motorway_link: 8,
  trunk: 10,
  trunk_link: 7,
  primary: 8,
  primary_link: 6,
  secondary: 6,
  secondary_link: 5,
  tertiary: 4,
  tertiary_link: 3,
  unclassified: 2,
  residential: 1,
  living_street: 0,
  service: 0
}

function nearestRoad(grid, lat, lon) {
  let best = null
  let bestScore = Infinity
  const baseLat = Math.floor(lat / CELL)
  const baseLon = Math.floor(lon / CELL)
  for (let dLat = -1; dLat <= 1; dLat++) {
    for (let dLon = -1; dLon <= 1; dLon++) {
      const bucket = grid.get(`${baseLat + dLat}:${baseLon + dLon}`)
      if (!bucket) continue
      for (const segment of bucket) {
        const distance = distanceToSegment(lat, lon, segment.a, segment.b)
        if (distance > MATCH_RADIUS_M) continue
        const score = distance - (CLASS_BONUS_M[segment.info.highway] ?? 0)
        if (score < bestScore) {
          bestScore = score
          best = segment.info
        }
      }
    }
  }
  return best
}

/**
 * Minden ponthoz megadja a becsült sebességhatárt (km/h) és az út nevét.
 * Ahol nem talált utat, ott null marad – a hívó ezt kihagyja az értékelésből.
 */
export async function fetchSpeedLimits(points) {
  const samples = sampleRoute(points)
  if (samples.length === 0) return { limits: [], names: [], coverage: 0, source: 'üres túra' }

  const coordinates = samples.map((point) => `${point.lat.toFixed(5)},${point.lon.toFixed(5)}`).join(',')
  const query = `[out:json][timeout:120];
way(around:${MATCH_RADIUS_M + 20},${coordinates})
  ["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service)(_link)?$"];
out tags geom;`

  const response = await overpass(query)
  const grid = buildIndex(response.elements ?? [])

  const limits = new Array(points.length).fill(null)
  const names = new Array(points.length).fill(null)
  const guessed = new Array(points.length).fill(false)

  // A találatot csak a mintavett pontokra keressük, a többi pont a legutóbbi
  // egyezést örökli – két minta között úgysem változik az út.
  let current = null
  let sampleIndex = 0
  for (let i = 0; i < points.length; i++) {
    if (sampleIndex < samples.length && points[i] === samples[sampleIndex]) {
      current = nearestRoad(grid, points[i].lat, points[i].lon) ?? current
      sampleIndex++
    }
    if (current) {
      limits[i] = current.limit
      names[i] = current.name
      guessed[i] = current.guessed
    }
  }

  const matched = limits.filter((value) => value !== null).length
  return {
    limits,
    names,
    guessed,
    coverage: points.length > 0 ? matched / points.length : 0,
    source: 'OpenStreetMap (Overpass)'
  }
}
