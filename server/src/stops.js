import { all } from './db.js'
import { haversine } from './overpass.js'

/**
 * Állóidő: lámpák és manőverek.
 *
 * Miért külön a mozgásidőtől: a mért adataidon ugyanazon a 16 km-es úton a
 * mozgás-átlagsebesség 39,9 és 56,1 km/h között szór (±15%), a TELJES idő
 * viszont 19,9 és 70,2 perc között (3,5-szeres). A kettőt egy számba gyúrva
 * a becslés félrevezető lenne - a szórás nem a vezetésedből jön, hanem abból,
 * hogy mennyit álltál.
 */

/** Ilyen közel kell elhaladni a lámpa mellett, hogy az útvonalra essen. */
const SIGNAL_MATCH_M = 25

/** Egy lámpánál ekkora eséllyel kell megállni, és ennyit várni. */
const SIGNAL_STOP_PROBABILITY = 0.55
const SIGNAL_WAIT_MS = 18_000

/** Valhalla manővertípusok, amik időbe kerülnek. */
const MANEUVER_PENALTY_MS = {
  // kisebb körforgalom: lassítás, besorolás, kihajtás
  roundabout: 6000,
  // balra kanyarodás szembejövő forgalomban
  left: 5000,
  sharpLeft: 6000,
  uturn: 12000,
  sharpRight: 4000
}

/**
 * A Valhalla manővertípusai (DirectionsLeg_Maneuver_Type). Élő válaszon
 * ellenőrizve: 10 = "Forduljon jobbra", 15 = "Forduljon balra",
 * 26/27 = körforgalomba be/ki.
 */
export const LEFT_TYPES = new Set([13, 14, 15, 16])
const SHARP_LEFT_TYPES = new Set([14])
const UTURN_TYPES = new Set([12, 13])
const SHARP_RIGHT_TYPES = new Set([11])

/** Hány balra kanyarodás van az útvonalon - az egérút ezt akarja elkerülni. */
export const countLeftTurns = (maneuvers = []) =>
  maneuvers.filter((maneuver) => LEFT_TYPES.has(maneuver.type)).length

/**
 * Az útvonalra eső lámpák. Az egérút ezeket adja át a tervezőnek
 * `exclude_locations`-ként, hogy tényleg kikerülje őket.
 */
export function signalsOnPath(points, radiusM = SIGNAL_MATCH_M) {
  const signals = all("SELECT lat, lon, road FROM road_points WHERE kind = 'TRAFFIC_SIGNALS'")
  if (signals.length === 0) return []

  const grid = new Map()
  const cell = (lat, lon) => `${Math.round(lat / 0.005)}|${Math.round(lon / 0.005)}`
  for (const signal of signals) {
    const key = cell(signal.lat, signal.lon)
    if (!grid.has(key)) grid.set(key, [])
    grid.get(key).push(signal)
  }

  const seen = new Set()
  const hits = []
  for (const point of points) {
    const baseLat = Math.round(point.lat / 0.005)
    const baseLon = Math.round(point.lon / 0.005)
    for (let dLat = -1; dLat <= 1; dLat++) {
      for (let dLon = -1; dLon <= 1; dLon++) {
        for (const signal of grid.get(`${baseLat + dLat}|${baseLon + dLon}`) ?? []) {
          const key = `${signal.lat},${signal.lon}`
          if (seen.has(key)) continue
          if (haversine(signal.lat, signal.lon, point.lat, point.lon) > radiusM) continue
          seen.add(key)
          hits.push(signal)
        }
      }
    }
  }
  return hits
}

/**
 * Az útvonalra eső megállók és a várható állóidő.
 *
 * A lámpák a road_points táblából jönnek, amit a helyi OSM kivonatból töltünk
 * (localosm.js) - országos lefedettséggel, másodpercek alatt.
 */
export function estimateStops(steps, maneuvers = []) {
  const signals = all("SELECT lat, lon, road FROM road_points WHERE kind = 'TRAFFIC_SIGNALS'")
  const stops = []

  if (signals.length > 0) {
    // Cellarács a lámpákra: útvonalanként több ezer lépés × több száz lámpa
    // négyzetes lenne, így viszont csak a szomszédos cellákat nézzük.
    const grid = new Map()
    const cell = (lat, lon) => `${Math.round(lat / 0.005)}|${Math.round(lon / 0.005)}`
    for (const signal of signals) {
      const key = cell(signal.lat, signal.lon)
      if (!grid.has(key)) grid.set(key, [])
      grid.get(key).push(signal)
    }

    const seen = new Set()
    for (const step of steps) {
      const baseLat = Math.round(step.lat / 0.005)
      const baseLon = Math.round(step.lon / 0.005)
      for (let dLat = -1; dLat <= 1; dLat++) {
        for (let dLon = -1; dLon <= 1; dLon++) {
          for (const signal of grid.get(`${baseLat + dLat}|${baseLon + dLon}`) ?? []) {
            const key = `${signal.lat},${signal.lon}`
            if (seen.has(key)) continue
            if (haversine(signal.lat, signal.lon, step.lat, step.lon) > SIGNAL_MATCH_M) continue
            seen.add(key)
            stops.push({
              lat: signal.lat,
              lon: signal.lon,
              kind: 'TRAFFIC_SIGNALS',
              road: signal.road ?? null,
              penaltyMillis: Math.round(SIGNAL_STOP_PROBABILITY * SIGNAL_WAIT_MS)
            })
          }
        }
      }
    }
  }

  for (const maneuver of maneuvers) {
    let penalty = 0
    let kind = null
    if (maneuver.roundabout) {
      penalty = MANEUVER_PENALTY_MS.roundabout
      kind = 'ROUNDABOUT'
    } else if (UTURN_TYPES.has(maneuver.type)) {
      penalty = MANEUVER_PENALTY_MS.uturn
      kind = 'UTURN'
    } else if (SHARP_LEFT_TYPES.has(maneuver.type)) {
      penalty = MANEUVER_PENALTY_MS.sharpLeft
      kind = 'SHARP_LEFT'
    } else if (LEFT_TYPES.has(maneuver.type)) {
      penalty = MANEUVER_PENALTY_MS.left
      kind = 'LEFT_TURN'
    } else if (SHARP_RIGHT_TYPES.has(maneuver.type)) {
      penalty = MANEUVER_PENALTY_MS.sharpRight
      kind = 'SHARP_RIGHT'
    }
    if (!kind) continue
    const step = steps[Math.min(maneuver.shapeIndex, steps.length - 1)]
    stops.push({
      lat: step?.lat ?? null,
      lon: step?.lon ?? null,
      kind,
      road: null,
      penaltyMillis: penalty
    })
  }

  const average = stops.reduce((sum, stop) => sum + stop.penaltyMillis, 0)
  const signalCount = stops.filter((stop) => stop.kind === 'TRAFFIC_SIGNALS').length

  // A sáv binomiálisan szóródik a lámpák számával, nem egyetlen szorzóval:
  // tíz lámpánál a "minden zöld" és a "minden piros" is elképzelhető, száznál
  // már nem. A szórás sqrt(n·p·(1-p)) · várakozás.
  const spread = Math.sqrt(signalCount * SIGNAL_STOP_PROBABILITY * (1 - SIGNAL_STOP_PROBABILITY)) * SIGNAL_WAIT_MS

  return {
    stops,
    signalCount,
    times: {
      fastest: Math.max(0, Math.round(average - 1.28 * spread)),
      average: Math.round(average),
      slowest: Math.round(average + 1.28 * spread)
    },
    hasSignalData: signals.length > 0
  }
}
