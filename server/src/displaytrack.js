import { traceAttributes, encodePolyline, RoutingError } from './routing.js'
import { haversine } from './overpass.js'

const dist = (a, b) => haversine(a.lat, a.lon, b.lat, b.lon)
const gap = (a, b) => a.segment !== b.segment || b.time <= a.time || b.time - a.time > 30000

/** Megjelenítési másolat: az eredeti méréshez és statisztikához soha nem nyúlunk. */
export function ridingMask(points) {
  return points.map((p, i) => {
    if (p.speedMps < 3 || p.accuracy > 50) return false
    let fast = 0
    for (let j = i; j >= 0 && p.time - points[j].time <= 15000; j--) {
      if (j < i && gap(points[j], points[j + 1])) break
      if (points[j].speedMps >= 5 && points[j].accuracy <= 50) fast++
    }
    for (let j = i + 1; j < points.length && points[j].time - p.time <= 15000; j++) {
      if (gap(points[j - 1], points[j])) break
      if (points[j].speedMps >= 5 && points[j].accuracy <= 50) fast++
    }
    return fast >= 2
  })
}

// Az illesztett pont helye az él polivonalán, hogy az összekötés a kanyarokat is kövesse.
function shapePosition(point, trace) {
  const edge = trace.edges[point.edgeIndex]
  if (!edge) return null
  let best = null
  let bestDistance = Infinity
  const cos = Math.cos(point.lat * Math.PI / 180)
  for (let i = edge.beginShapeIndex; i < edge.endShapeIndex; i++) {
    const a = trace.shape[i], b = trace.shape[i + 1]
    if (!a || !b) continue
    const dx = (b.lon - a.lon) * cos, dy = b.lat - a.lat
    const length2 = dx * dx + dy * dy
    const t = length2 ? Math.max(0, Math.min(1, (((point.lon - a.lon) * cos) * dx + (point.lat - a.lat) * dy) / length2)) : 0
    const delta = ((point.lon - a.lon) * cos - t * dx) ** 2 + (point.lat - a.lat - t * dy) ** 2
    if (delta < bestDistance) { bestDistance = delta; best = i + t }
  }
  return best
}

export async function displayTrack(points, matcher = p => traceAttributes(p, 'map_snap')) {
  if (!Array.isArray(points) || points.length > 600 || points.some(p => !p ||
      !Number.isFinite(p.lat) || !Number.isFinite(p.lon) || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180 ||
      !Number.isFinite(p.time) || !Number.isFinite(p.speedMps) || p.speedMps < 0 ||
      !Number.isFinite(p.accuracy) || p.accuracy < 0)) {
    throw new RoutingError('Legfeljebb 600 érvényes GPS-pont illeszthető egy kérésben.', { status: 400 })
  }
  const links = points.slice(1).map((p, i) => ({ from: i, to: i + 1, matched: false,
    shape: gap(points[i], p) ? null : encodePolyline([points[i], p], 6) }))
  const mask = ridingMask(points)
  let failures = 0
  for (let start = 0; start < points.length;) {
    if (!mask[start]) { start++; continue }
    let end = start + 1
    while (end < points.length && mask[end] && !gap(points[end - 1], points[end])) end++
    if (end - start >= 3) {
      try {
        const trace = await matcher(points.slice(start, end))
        const accepted = trace.matched.map((p, i) => p && p.type !== 'unmatched' &&
          Number.isFinite(p.lat) && Number.isFinite(p.lon) &&
          dist(p, points[start + i]) <= Math.min(45, Math.max(15, points[start + i].accuracy * 2)))
        for (let i = 0; i < end - start - 1; i++) {
          if (!accepted[i] || !accepted[i + 1]) continue
          const a = trace.matched[i], b = trace.matched[i + 1]
          const from = shapePosition(a, trace), to = shapePosition(b, trace)
          if (from === null || to === null || to < from) continue
          const shape = [a, ...trace.shape.slice(Math.floor(from) + 1, Math.ceil(to)), b]
          const length = shape.slice(1).reduce((sum, p, j) => sum + dist(shape[j], p), 0)
          const rawLength = dist(points[start + i], points[start + i + 1])
          const elapsed = (points[start + i + 1].time - points[start + i].time) / 1000
          if (length > Math.max(60, rawLength * 2) || length / elapsed > 55) continue
          links[start + i] = { from: start + i, to: start + i + 1, matched: true, shape: encodePolyline(shape, 6) }
        }
      } catch { failures++ /* Offline vagy bizonytalan illesztés: marad a nyers vonal. */ }
    }
    start = end
  }
  return { links, matchedLinks: links.filter(p => p.matched).length, failures, version: 1 }
}
