import { all } from './db.js'
import { haversine } from './overpass.js'
import { resample } from './geometry.js'
import { encodePolyline, RoutingError } from './routing.js'

const distance = (a, b) => haversine(a.lat, a.lon, b.lat, b.lon)

// Irányonként csoportosítunk: a visszaút eltérő forgalma nem torzítja az időt.
export function clusterJourneys(journeys, from, to) {
  const groups = []
  for (const journey of journeys) {
    const path = journey.points
    if (path.length < 2 || distance(path[0], from) > 600 || distance(path.at(-1), to) > 600) continue
    const sampled = resample(path, 100)
    if (sampled.length < 2) continue
    const coverage = (a, b) => a.filter(p => b.some(q => distance(p, q) < 140)).length / a.length
    const group = groups.find(g => coverage(sampled, g.sampled) > 0.8 && coverage(g.sampled, sampled) > 0.8)
    if (group) group.journeys.push(journey)
    else groups.push({ sampled, journeys: [journey] })
  }
  const median = values => values.sort((a, b) => a - b)[Math.floor(values.length / 2)]
  const mainGroups = groups.sort((a, b) => b.journeys.length - a.journeys.length).slice(0, 2)
  return mainGroups.map((g, index) => {
    const representative = g.journeys[0]
    // Egy átmenő pont tartja a választott folyosót; a kereszteződések kerülhetők.
    const middle = g.sampled.slice(Math.floor(g.sampled.length * 0.2), Math.ceil(g.sampled.length * 0.8))
    const other = mainGroups.find(candidate => candidate !== g)
    const separation = p => Math.min(...other.sampled.map(q => distance(p, q)))
    const anchor = other ? middle.reduce((best, p) => separation(p) > separation(best) ? p : best)
      : g.sampled[Math.floor(g.sampled.length / 2)]
    return {
      id: representative.id, label: `${index + 1}. megszokott útvonal`,
      count: g.journeys.length, shape: encodePolyline(g.sampled, 5),
      distanceMeters: median(g.journeys.map(j => j.distanceMeters)),
      averageMs: median(g.journeys.map(j => j.durationMs)),
      anchor: { lat: anchor.lat, lon: anchor.lon, name: 'Választott útvonal', via: true }
    }
  })
}

export function familiarRoutes(waypoints) {
  if (!Array.isArray(waypoints) || waypoints.length !== 2 || waypoints.some(p =>
    !p || !Number.isFinite(p.lat) || !Number.isFinite(p.lon) || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180)) {
    throw new RoutingError('Két érvényes végpont szükséges.', { status: 400 })
  }
  const journeys = all(`SELECT id, distance_m, duration_ms FROM tracks
    WHERE end_time IS NOT NULL AND distance_m > 500 AND duration_ms > 0 ORDER BY start_time DESC`).map(t => ({
    id: t.id, distanceMeters: t.distance_m, durationMs: t.duration_ms,
    points: all('SELECT lat, lon FROM track_points WHERE track_id = ? ORDER BY seq', t.id)
  }))
  return clusterJourneys(journeys, ...waypoints)
}
