import { all } from './db.js'

const CELL_SCALE = 2500 // Magyarországon nagyjából 30–45 méteres cella.
const REFERENCE_SPEED_MPS = 50 / 3.6

export const directionBucket = (bearing = 0) =>
  Math.round((((Number(bearing) % 360) + 360) % 360) / 45) % 8

/**
 * Azonos kátyú gyorsabban áthaladva nagyobb gyorsulást ad. A négyzetgyökös
 * korrekció 50 km/h-ra vetít, de nem erősíti túl a lassú minták zaját.
 */
export function normalizedRoughness(verticalRms, speedMps) {
  const speed = Math.min(35, Math.max(5, Number(speedMps) || 5))
  return Math.max(0, Number(verticalRms) || 0) * Math.sqrt(REFERENCE_SPEED_MPS / speed)
}

const cellKey = (lat, lon, direction) =>
  `${Math.round(lat * CELL_SCALE)}|${Math.round(lon * CELL_SCALE)}|${direction}`

/**
 * Irány- és sebességnormalizált útminőség. Egy cella csak legalább három
 * külön túra után stabil; így egy meglazult tartó nem fest át egy utcát.
 */
export function stableRoadQuality(bbox = null) {
  const where = [
    'matched_lat IS NOT NULL', 'matched_lon IS NOT NULL',
    'mount_quality >= 0.65', 'speed_mps > 3'
  ]
  const params = []
  if (bbox) {
    where.push('matched_lat BETWEEN ? AND ?', 'matched_lon BETWEEN ? AND ?')
    params.push(bbox.south, bbox.north, bbox.west, bbox.east)
  }
  const rows = all(
    `SELECT track_id AS trackId, matched_lat AS lat, matched_lon AS lon,
            speed_mps AS speedMps, road_bearing_deg AS bearingDegrees,
            vertical_rms_mps2 AS verticalRms,
            vertical_peak_mps2 AS verticalPeak
     FROM telemetry_samples WHERE ${where.join(' AND ')}`,
    ...params
  )
  const grouped = new Map()
  for (const row of rows) {
    const direction = directionBucket(row.bearingDegrees)
    const latCell = Math.round(row.lat * CELL_SCALE)
    const lonCell = Math.round(row.lon * CELL_SCALE)
    const key = `${latCell}|${lonCell}|${direction}`
    let group = grouped.get(key)
    if (!group) {
      group = { latCell, lonCell, direction, tracks: new Set(), samples: 0,
        roughnessSum: 0, peak: 0 }
      grouped.set(key, group)
    }
    group.tracks.add(row.trackId)
    group.samples++
    group.roughnessSum += normalizedRoughness(row.verticalRms, row.speedMps)
    group.peak = Math.max(group.peak, normalizedRoughness(row.verticalPeak, row.speedMps))
  }
  return [...grouped.values()]
    .filter((group) => group.tracks.size >= 3)
    .map((group) => ({
      key: `${group.latCell}|${group.lonCell}|${group.direction}`,
      lat: group.latCell / CELL_SCALE,
      lon: group.lonCell / CELL_SCALE,
      direction: group.direction * 45,
      tracks: group.tracks.size,
      samples: group.samples,
      roughness: group.roughnessSum / group.samples,
      peak: group.peak
    }))
}

export function roadQualityForSteps(steps) {
  const cells = new Map(stableRoadQuality().map((cell) => [cell.key, cell]))
  let sum = 0
  let matched = 0
  for (let index = 0; index < steps.length; index++) {
    const step = steps[index]
    const next = steps[Math.min(index + 1, steps.length - 1)]
    const previous = steps[Math.max(0, index - 1)]
    const dy = (next.lat - previous.lat) * Math.PI / 180
    const dx = (next.lon - previous.lon) * Math.PI / 180 *
      Math.cos(((next.lat + previous.lat) / 2) * Math.PI / 180)
    const bearing = (Math.atan2(dx, dy) * 180 / Math.PI + 360) % 360
    const value = cells.get(cellKey(step.lat, step.lon, directionBucket(bearing)))
    if (!value) continue
    sum += value.roughness
    matched++
  }
  return {
    roughnessScore: matched > 0 ? sum / matched : 0,
    roughnessCoverage: steps.length > 0 ? matched / steps.length : 0
  }
}
