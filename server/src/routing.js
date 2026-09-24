import { routingLocations } from './routepreferences.js'
/**
 * Valhalla kliens.
 *
 * Miért saját példány (server/valhalla/docker-compose.yml) és nem a nyilvános
 * FOSSGIS: a nyilvános build nem adja vissza a sebességhatárt (lemérve: 0/319
 * élen jött speed_limit), a kanyargós stílus pedig tervenként 5-6 hívást
 * igényel, ami fair-use alatt nem fér bele. Az URL env változóból jön, tehát
 * a nyilvános példány bármikor tartalék marad.
 */

export const VALHALLA_URL = (process.env.VALHALLA_URL || 'http://127.0.0.1:8002').replace(/\/$/, '')

/** A motoros profil. Ha a csempe valamiért nem tudja, az autósra esünk vissza. */
export const VALHALLA_COSTING = process.env.VALHALLA_COSTING || 'motorcycle'

const ROUTE_TIMEOUT_MS = Number(process.env.VALHALLA_TIMEOUT_MS || 25_000)

/** A szerver a saját gépen fut, de a map matching nagy túrán tovább tart. */
const TRACE_TIMEOUT_MS = Number(process.env.VALHALLA_TRACE_TIMEOUT_MS || 120_000)

/** Egy trace_attributes hívásba ennyi pontot küldünk, átfedéssel. */
const TRACE_CHUNK = 4000
const TRACE_OVERLAP = 40

export class RoutingError extends Error {
  constructor(message, { status = 502, cause } = {}) {
    super(message)
    this.name = 'RoutingError'
    this.status = status
    this.cause = cause
  }
}

async function callValhalla(action, body, timeoutMs) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  let response
  try {
    response = await fetch(`${VALHALLA_URL}/${action}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal
    })
  } catch (error) {
    throw new RoutingError(
      `Az útvonaltervező nem érhető el (${VALHALLA_URL}). Fut a Valhalla konténer?`,
      { status: 503, cause: error }
    )
  } finally {
    clearTimeout(timer)
  }

  const text = await response.text()
  let payload
  try {
    payload = JSON.parse(text)
  } catch {
    throw new RoutingError(`Az útvonaltervező érthetetlen választ adott (${response.status}).`)
  }

  if (!response.ok || payload.error) {
    // A Valhalla 442-t ad, ha a pontok között nincs út - ez a felhasználó
    // hibája, nem a szerveré, ezért 400-zal megy vissza a telefonra.
    const code = payload.error_code
    const noRoute = code === 442 || code === 171 || code === 170
    throw new RoutingError(
      payload.error || `Az útvonaltervező hibát adott (${response.status}).`,
      { status: noRoute ? 400 : 502 }
    )
  }
  return payload
}

/** Valhalla polyline: ugyanaz, mint a Google-féle, csak állítható pontossággal. */
export function decodePolyline(encoded, precision = 6) {
  const factor = 10 ** precision
  const points = []
  let index = 0
  let lat = 0
  let lon = 0

  while (index < encoded.length) {
    let result = 0
    let shift = 0
    let byte
    do {
      byte = encoded.charCodeAt(index++) - 63
      result |= (byte & 0x1f) << shift
      shift += 5
    } while (byte >= 0x20)
    lat += result & 1 ? ~(result >> 1) : result >> 1

    result = 0
    shift = 0
    do {
      byte = encoded.charCodeAt(index++) - 63
      result |= (byte & 0x1f) << shift
      shift += 5
    } while (byte >= 0x20)
    lon += result & 1 ? ~(result >> 1) : result >> 1

    points.push({ lat: lat / factor, lon: lon / factor })
  }
  return points
}

/** Kódolás az Androidnak: polyline5-ben egy 100 km-es útvonal ~30 kB, nyersen 400. */
export function encodePolyline(points, precision = 5) {
  const factor = 10 ** precision
  let output = ''
  let prevLat = 0
  let prevLon = 0

  const write = (value) => {
    let number = value < 0 ? ~(value << 1) : value << 1
    while (number >= 0x20) {
      output += String.fromCharCode((0x20 | (number & 0x1f)) + 63)
      number >>= 5
    }
    output += String.fromCharCode(number + 63)
  }

  for (const point of points) {
    const lat = Math.round(point.lat * factor)
    const lon = Math.round(point.lon * factor)
    write(lat - prevLat)
    write(lon - prevLon)
    prevLat = lat
    prevLon = lon
  }
  return output
}

/**
 * Útvonal a köztes pontokon át.
 *
 * A trip.summary.time-ot SZÁNDÉKOSAN eldobjuk: a Valhalla motoros költsége
 * útvonalválasztásra jó, menetidőnek nem (egy 113 km-es útra 198 percet adott,
 * ami 34 km/h). Az időt a saját modellünk számolja.
 */
export async function valhallaRoute(waypoints, options = {}) {
  const costingOptions = {
    // A fizetős utak elkerülése alapból be van kapcsolva: matrica nélkül
    // az M-utak nem használhatók.
    use_tolls: options.avoidTolls === false ? 1 : 0,
    use_highways: options.useHighways ?? 1,
    use_trails: 0,
    use_tracks: options.avoidUnpaved ? 0 : 0.2
  }
  if (Number.isFinite(options.topSpeed)) costingOptions.top_speed = options.topSpeed

  const body = {
    locations: routingLocations(waypoints, options),
    // Az egérút ezzel kerüli el a lámpákat és a balra kanyarodásokat: a
    // Valhalla nem tud ilyen költséget, konkrét pontok kizárását viszont igen.
    ...(options.excludeLocations?.length
      ? { exclude_locations: options.excludeLocations.slice(0, 45).map((p) => ({ lat: p.lat, lon: p.lon })) }
      : {}),
    costing: VALHALLA_COSTING,
    costing_options: { [VALHALLA_COSTING]: costingOptions },
    directions_options: { units: 'kilometers', language: 'hu-HU' },
    shape_format: 'polyline6'
  }
  if (options.alternates) body.alternates = options.alternates
  if (options.shortest) body.costing_options[VALHALLA_COSTING].shortest = true

  let payload
  let arrivalFallback = false
  try {
    payload = await callValhalla('route', body, ROUTE_TIMEOUT_MS)
  } catch (error) {
    if (error.status !== 400 || !body.locations.some(p => p.preferred_side === 'same')) throw error
    // A helyes oldal preferencia: ha így nincs út, a kötelező pontok maradnak.
    body.locations.forEach(p => { delete p.preferred_side })
    payload = await callValhalla('route', body, ROUTE_TIMEOUT_MS)
    arrivalFallback = true
  }
  const trips = [payload.trip, ...(payload.alternates ?? []).map((alt) => alt.trip)].filter(Boolean)
  return trips.map((trip) => ({ ...toTrip(trip), arrivalFallback }))
}

function toTrip(trip) {
  const shape = []
  const legs = []
  const maneuvers = []

  for (const leg of trip.legs) {
    const legShape = decodePolyline(leg.shape, 6)
    const offset = shape.length
    // A szakaszok vége és a következő eleje ugyanaz a pont, ne duplázzuk.
    shape.push(...(offset > 0 ? legShape.slice(1) : legShape))
    legs.push({
      distanceMeters: Math.round(leg.summary.length * 1000),
      fromShapeIndex: offset === 0 ? 0 : offset - 1,
      toShapeIndex: shape.length - 1
    })
    for (const maneuver of leg.maneuvers ?? []) {
      maneuvers.push({
        type: maneuver.type,
        shapeIndex: (offset === 0 ? 0 : offset - 1) + (maneuver.begin_shape_index ?? 0),
        instruction: maneuver.instruction,
        roundabout: Boolean(maneuver.roundabout_exit_count)
      })
    }
  }

  return {
    shape,
    legs,
    maneuvers,
    distanceMeters: Math.round(trip.summary.length * 1000),
    hasToll: Boolean(trip.summary.has_toll),
    hasFerry: Boolean(trip.summary.has_ferry),
    arrivals: (trip.locations ?? []).filter((p, i) => i > 0 && p.type !== 'through').map(p => ({
      lat: p.lat, lon: p.lon, side: p.side_of_street ?? 'unknown'
    }))
  }
}

const TRACE_FILTERS = [
  'edge.way_id',
  'edge.road_class',
  'edge.surface',
  'edge.length',
  'edge.speed',
  'edge.speed_limit',
  'edge.names',
  'edge.begin_shape_index',
  'edge.end_shape_index',
  'shape'
]

/**
 * Élinformáció egy adott vonalra.
 *
 * Két üzemmód:
 *  - 'edge_walk': a vonal MÁR az úthálózaton van (tervezett útvonal), csak a
 *    tulajdonságait kérdezzük vissza;
 *  - 'map_snap': nyers GPS-nyomvonal illesztése az úthálózatra (megtett túra).
 *
 * A második azért kulcsfontosságú, mert a görbületet nem szabad nyers GPS-ből
 * számolni: a 8,3 méteres medián pontosság hamis hajtűket gyárt. Illesztés után
 * a tervezett és a megtett út ugyanabból a geometriából kapja a kanyarosztályt.
 */
export async function traceAttributes(points, mode = 'edge_walk') {
  if (points.length < 2) return { edges: [], shape: [], matched: [] }

  const chunks = []
  for (let start = 0; start < points.length; start += TRACE_CHUNK - TRACE_OVERLAP) {
    const chunk = points.slice(start, start + TRACE_CHUNK)
    if (chunk.length < 2) break
    chunks.push({ start, points: chunk })
    if (start + TRACE_CHUNK >= points.length) break
  }

  const edges = []
  const shape = []
  const matched = []

  for (const chunk of chunks) {
    const filters = mode === 'map_snap'
      ? [...TRACE_FILTERS, 'matched.point', 'matched.edge_index', 'matched.type']
      : TRACE_FILTERS

    const body = {
      shape: chunk.points.map((point) => {
        const entry = { lat: point.lat, lon: point.lon, type: 'via' }
        if (mode === 'map_snap' && Number.isFinite(point.time)) {
          entry.time = Math.round(point.time / 1000)
        }
        return entry
      }),
      costing: VALHALLA_COSTING,
      shape_match: mode,
      filters: { action: 'include', attributes: filters }
    }

    const payload = await callValhalla('trace_attributes', body, TRACE_TIMEOUT_MS)
    const chunkShape = typeof payload.shape === 'string' ? decodePolyline(payload.shape, 6) : []
    const shapeOffset = shape.length
    // A darabon belüli él- és pontindexeket a globális tömbök elejéhez toljuk.
    const edgeOffset = edges.length

    shape.push(...chunkShape)
    for (const edge of payload.edges ?? []) {
      edges.push({
        wayId: edge.way_id ?? null,
        roadClass: edge.road_class ?? null,
        surface: edge.surface ?? null,
        lengthM: Math.round((edge.length ?? 0) * 1000),
        speedKmh: edge.speed ?? null,
        speedLimitKmh: edge.speed_limit ?? null,
        name: edge.names?.[0] ?? null,
        beginShapeIndex: shapeOffset + (edge.begin_shape_index ?? 0),
        endShapeIndex: shapeOffset + (edge.end_shape_index ?? 0)
      })
    }
    for (let i = 0; i < (payload.matched_points?.length ?? 0); i++) {
      // A darabok átfednek, hogy a határon ne szakadjon meg az illesztés;
      // az átfedő pontokat viszont csak egyszer vesszük fel.
      if (chunk.start + i < matched.length) continue
      const point = payload.matched_points[i]
      matched.push({
        lat: point.lat ?? null,
        lon: point.lon ?? null,
        edgeIndex: Number.isInteger(point.edge_index) ? edgeOffset + point.edge_index : null,
        type: point.type ?? 'unmatched'
      })
    }
  }

  return { edges, shape, matched }
}

/** A domborzati lekérdezés egy hívásban ennyi pontot bír el. */
const HEIGHT_CHUNK = 1500

/**
 * Magasság a vonal mentén. Egy 47 lóerős motoron az emelkedő nem elméleti
 * kérdés: 130 km/h-nál a rendelkezésre álló gyorsulás 1,64 m/s², egy 5%-os
 * emelkedő ebből 0,49-et elvesz.
 *
 * Szándékosan ritkított pontokra kérjük (100 méterenként): az SRTM felbontása
 * 30-90 m, 20 méterenként csak zajt kapnánk vissza.
 */
export async function valhallaHeight(points) {
  const heights = []
  for (let start = 0; start < points.length; start += HEIGHT_CHUNK) {
    const chunk = points.slice(start, start + HEIGHT_CHUNK)
    const payload = await callValhalla(
      'height',
      { shape: chunk.map((p) => ({ lat: p.lat, lon: p.lon })), range: false },
      ROUTE_TIMEOUT_MS
    )
    heights.push(...(payload.height ?? []))
  }
  return heights
}

/** Elérhető-e a tervező, és tud-e sebességhatárt adni. */
export async function valhallaStatus() {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 5000)
  try {
    const response = await fetch(`${VALHALLA_URL}/status`, { signal: controller.signal })
    if (!response.ok) return { ok: false, detail: `HTTP ${response.status}` }
    const payload = await response.json()
    return {
      ok: true,
      version: payload.version,
      tilesetLastModified: payload.tileset_last_modified,
      actions: payload.available_actions ?? []
    }
  } catch (error) {
    return { ok: false, detail: error.message }
  } finally {
    clearTimeout(timer)
  }
}
