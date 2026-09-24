import { all, one, run } from './db.js'
import { overpass, haversine } from './overpass.js'
import { RoutingError } from './routing.js'

const distance = (a, b) => haversine(a.lat, a.lon, b.lat, b.lon)
const median = values => values.sort((a, b) => a - b)[Math.floor(values.length / 2)]

/** A lassú, kis területen maradó szakaszba a boltig sétálás is belefér. */
export function detectObservedStops(points) {
  const stops = []
  for (let start = 0; start < points.length;) {
    if (points[start].speedMps > 2.5) { start++; continue }
    let end = start + 1
    let stationary = points[start].speedMps <= 0.8 ? 1 : 0
    while (end < points.length && points[end].segment === points[start].segment &&
      points[end].time > points[end - 1].time && points[end].time - points[end - 1].time <= 120000 &&
      points[end].speedMps <= 2.5 && distance(points[start], points[end]) < 150) {
      if (points[end].speedMps <= 0.8) stationary++
      end++
    }
    const durationMs = points[end - 1].time - points[start].time
    if (stationary >= 2 && durationMs >= 15000) {
      const still = points.slice(start, end).filter(p => p.speedMps <= 0.8)
      stops.push({ id: String(points[start].time), from: start, to: end - 1,
        lat: median(still.map(p => p.lat)), lon: median(still.map(p => p.lon)),
        accuracy: median(still.map(p => p.accuracy)), durationMs,
        startedAt: points[start].time, endedAt: points[end - 1].time,
        type: 'OTHER', name: null, reason: 'Megállás; a cél nem állapítható meg biztosan.' })
    }
    start = end
  }
  return stops
}

function lineDistance(point, geometry = []) {
  let best = Infinity
  const cos = Math.cos(point.lat * Math.PI / 180)
  for (let i = 1; i < geometry.length; i++) {
    const a = geometry[i - 1], b = geometry[i]
    const dx = (b.lon - a.lon) * cos, dy = b.lat - a.lat
    const n = dx * dx + dy * dy
    const t = n ? Math.max(0, Math.min(1, ((point.lon - a.lon) * cos * dx + (point.lat - a.lat) * dy) / n)) : 0
    best = Math.min(best, distance(point, { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t }))
  }
  return best
}

function inside(point, polygon) {
  if (polygon.length < 4 || distance(polygon[0], polygon.at(-1)) > 1) return false
  let result = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i], b = polygon[j]
    if ((a.lat > point.lat) !== (b.lat > point.lat) &&
      point.lon < (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon) result = !result
  }
  return result
}

export function classifyObservedStop(stop, elements, signals = []) {
  const roadTypes = new Set(['motorway','trunk','primary','secondary','tertiary','unclassified','residential','living_street',
    'motorway_link','trunk_link','primary_link','secondary_link','tertiary_link'])
  const roads = elements.filter(e => roadTypes.has(e.tags?.highway))
  const roadDistance = Math.min(Infinity, ...roads.map(e => lineDistance(stop, e.geometry)))
  const signalDistance = Math.min(Infinity, ...signals.map(p => distance(stop, p)))
  if (stop.accuracy <= 25 && roadDistance <= 10) {
    return { ...stop, type: signalDistance < 25 ? 'SIGNAL' : 'ROAD',
      reason: signalDistance < 25 ? 'Úton, ismert jelzőlámpa közelében történt várakozás (becslés).' : 'A megállás helye a közútra illeszkedik (becslés).' }
  }
  const pois = elements.filter(e => e.tags?.shop || e.tags?.amenity || e.tags?.tourism || e.tags?.office).map(e => {
    const location = e.center ?? (Number.isFinite(e.lat) ? e : null)
    const d = e.geometry?.length ? (inside(stop, e.geometry) ? 0 : lineDistance(stop, e.geometry))
      : location ? distance(stop, location) : Infinity
    return { e, d }
  }).filter(p => p.d <= 30).sort((a, b) => a.d - b.d)
  const first = pois[0]
  // Egy közeli üzlet önmagában nem bizonyít bevásárlást; utca melletti sorban állás marad ismeretlen.
  if (first && stop.durationMs >= 60000 && stop.accuracy <= 30 && roads.length && roadDistance > Math.max(15, stop.accuracy) &&
      (!pois[1] || pois[1].d - first.d > 10 || (first.e.tags?.name && pois[1].e.tags?.name === first.e.tags.name))) {
    const tags = first.e.tags
    const type = tags.shop ? 'SHOP' : tags.amenity === 'fuel' ? 'FUEL' : tags.amenity === 'parking' ? 'PARKING' : 'PLACE'
    return { ...stop, type, name: tags.name ?? tags.brand ?? null,
      reason: 'Út melletti, tartós megállás és közeli térképi hely alapján becsült cél. Koppintással javítható.' }
  }
  return { ...stop, type: Number.isFinite(roadDistance) && roadDistance > 15 ? 'PARKING' : 'OTHER' }
}

export async function observedStops(points) {
  if (!Array.isArray(points) || points.length > 100000 || points.some(p => !p ||
    !Number.isFinite(p.lat) || !Number.isFinite(p.lon) || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180 ||
    !Number.isFinite(p.time) || !Number.isFinite(p.speedMps) || p.speedMps < 0 || !Number.isFinite(p.accuracy) || p.accuracy < 0)) {
    throw new RoutingError('Hibás GPS-pontok a megállások kereséséhez.', { status: 400 })
  }
  const stops = detectObservedStops(points)
  const key = p => `stop-poi-v1:${p.lat.toFixed(3)},${p.lon.toFixed(3)}`
  const datasets = new Map()
  const missing = []
  for (const stop of stops) {
    if (datasets.has(key(stop))) continue
    const cached = one('SELECT payload, created_at FROM geocode_cache WHERE key = ?', key(stop))
    if (cached && Date.now() - cached.created_at < 7 * 86400000) datasets.set(key(stop), JSON.parse(cached.payload))
    else { datasets.set(key(stop), []); missing.push(stop) }
  }
  let offline = false
  if (missing.length) {
    try {
      const probes = missing.slice(0, 30)
      const queries = probes.map(p => {
        const area = `(around:350,${p.lat.toFixed(3)},${p.lon.toFixed(3)})`
        return `way${area}[highway];nwr${area}[shop];nwr${area}[amenity];nwr${area}[tourism];nwr${area}[office];`
      }).join('')
      const response = await overpass(`[out:json][timeout:12];(${queries});out center geom;`, { timeoutMs: 15000, rounds: 1 })
      const elements = response.elements ?? []
      for (const p of probes) {
        // A lekérdezés mérete kicsi; a közös adathalmazból a távolság dönt.
        datasets.set(key(p), elements)
        run('INSERT INTO geocode_cache (key,payload,created_at) VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload,created_at=excluded.created_at', key(p), JSON.stringify(elements), Date.now())
      }
    } catch { offline = true }
  }
  const signals = all("SELECT lat,lon FROM road_points WHERE kind = 'TRAFFIC_SIGNALS'")
  return { stops: stops.map(p => classifyObservedStop(p, datasets.get(key(p)) ?? [], signals)), offline }
}
