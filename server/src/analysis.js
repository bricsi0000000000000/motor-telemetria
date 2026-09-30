import { fetchSpeedLimits, haversine } from './speedlimits.js'

/**
 * Egy túra részletes elemzése: hol mentél gyorsan, hol araszoltál, hol volt
 * komoly emelkedő vagy lejtő, és mindez hogyan viszonyul az adott út
 * sebességhatárához.
 *
 * A számolás szándékosan a szerveren van (nem a telefonon): itt van net a
 * sebességhatárok lekérdezéséhez, és az eredmény úgyis a szerveren tárolódik.
 */

const KMH = 3.6

/** Ennyivel a limit fölött már "gyorshajtás" (GPS-hiba és kerekítés miatt kell tűrés). */
const SPEED_TOLERANCE_KMH = 5

/** A magasság zajos, ezért csak ekkora fordulat után mondjuk, hogy irányt váltott. */
const ELEVATION_HYSTERESIS_M = 6

/** Ekkora szintkülönbség alatt nem érdemes emelkedőnek nevezni. */
const MIN_LEG_ELEVATION_M = 15

const MIN_FAST_DISTANCE_M = 300
const MIN_SLOW_DURATION_MS = 45_000
const MIN_SPEEDING_DISTANCE_M = 100

const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

/** Mozgóátlag: a GPS sebesség és magasság önmagában túl ugrálós a szakaszkereséshez. */
function smooth(values, window) {
  const half = Math.floor(window / 2)
  return values.map((_, index) => {
    const from = Math.max(0, index - half)
    const to = Math.min(values.length - 1, index + half)
    let sum = 0
    for (let i = from; i <= to; i++) sum += values[i]
    return sum / (to - from + 1)
  })
}

/**
 * Összefüggő szakaszok keresése kétküszöbös feltétellel: a szakasz akkor
 * kezdődik, ha `enter` igaz, és addig tart, amíg `exit` igazzá nem válik.
 * Így egy pillanatnyi ingadozás nem szabdalja darabokra a szakaszt.
 */
function findRuns(length, enter, exit) {
  const runs = []
  let start = -1
  for (let i = 0; i < length; i++) {
    if (start < 0) {
      if (enter(i)) start = i
    } else if (exit(i)) {
      runs.push({ from: start, to: i - 1 })
      start = -1
    }
  }
  if (start >= 0) runs.push({ from: start, to: length - 1 })
  return runs.filter((run) => run.to > run.from)
}

/** A szakaszban leggyakoribb érték (limit, útnév). */
function mode(values) {
  const counts = new Map()
  for (const value of values) {
    if (value === null || value === undefined) continue
    counts.set(value, (counts.get(value) ?? 0) + 1)
  }
  let best = null
  let bestCount = 0
  for (const [value, count] of counts) {
    if (count > bestCount) {
      best = value
      bestCount = count
    }
  }
  return best
}

export async function analyzeTrack(track, points, telemetry = [], telemetryProfile = {}) {
  if (points.length < 5) {
    return {
      trackId: track.id,
      computedAt: Date.now(),
      pointCount: points.length,
      summary: null,
      sections: { fast: [], slow: [], climbs: [], descents: [], speeding: [], braking: [], acceleration: [], roughness: [], incidents: [] },
      note: 'Túl kevés pont az elemzéshez.'
    }
  }

  const n = points.length
  const cumulative = new Array(n).fill(0)
  const rawSpeed = new Array(n).fill(0)
  const altitude = points.map((point) => point.altitude ?? 0)

  for (let i = 1; i < n; i++) {
    const previous = points[i - 1]
    const point = points[i]
    // Szünet után új szakasz kezdődik: a két vége közé nem húzunk távolságot.
    const step =
      point.segment === previous.segment
        ? haversine(previous.lat, previous.lon, point.lat, point.lon)
        : 0
    cumulative[i] = cumulative[i - 1] + step

    const dtSeconds = (point.time - previous.time) / 1000
    const measured = point.speedMps ?? 0
    rawSpeed[i] = measured > 0 ? measured : dtSeconds > 0 && dtSeconds < 60 ? step / dtSeconds : 0
  }
  rawSpeed[0] = points[0].speedMps ?? 0

  const speed = smooth(rawSpeed, 5)
  const elevation = smooth(altitude, 9)

  const distanceM = cumulative[n - 1]
  const durationMs = points[n - 1].time - points[0].time
  let movingMs = 0
  for (let i = 1; i < n; i++) {
    const dt = points[i].time - points[i - 1].time
    if (dt > 0 && dt < 30_000 && speed[i] > 0.8) movingMs += dt
  }
  const avgMovingKmh = movingMs > 0 ? (distanceM / (movingMs / 1000)) * KMH : 0
  const maxKmh = Math.max(...rawSpeed) * KMH
  const telemetryAnalysis = analyzeTelemetry(points, telemetry, telemetryProfile)

  // --- sebességhatárok ------------------------------------------------------
  let limitData = { limits: new Array(n).fill(null), names: new Array(n).fill(null), coverage: 0, source: null }
  let limitError = null
  try {
    limitData = await fetchSpeedLimits(points)
  } catch (error) {
    limitError = error.message ?? 'ismeretlen hiba'
  }
  const limits = limitData.limits
  const names = limitData.names ?? new Array(n).fill(null)

  // --- szakaszleíró ---------------------------------------------------------
  const describe = (from, to, type, extra = {}) => {
    const slice = { from, to }
    const sectionDistance = cumulative[to] - cumulative[from]
    const sectionDuration = points[to].time - points[from].time
    let sectionMax = 0
    for (let i = from; i <= to; i++) sectionMax = Math.max(sectionMax, rawSpeed[i])
    const elevationDelta = elevation[to] - elevation[from]

    return {
      type,
      ...slice,
      startTime: points[from].time,
      endTime: points[to].time,
      durationMillis: sectionDuration,
      distanceMeters: sectionDistance,
      avgKmh: sectionDuration > 0 ? (sectionDistance / (sectionDuration / 1000)) * KMH : 0,
      maxKmh: sectionMax * KMH,
      elevationDeltaMeters: elevationDelta,
      gradePercent: sectionDistance > 20 ? (elevationDelta / sectionDistance) * 100 : 0,
      limitKmh: mode(limits.slice(from, to + 1)),
      road: mode(names.slice(from, to + 1)),
      lat: points[from].lat,
      lon: points[from].lon,
      ...extra
    }
  }

  // --- gyors és lassú szakaszok --------------------------------------------
  const fastThresholdKmh = Math.max(60, avgMovingKmh * 1.25)
  const fast = findRuns(
    n,
    (i) => speed[i] * KMH >= fastThresholdKmh,
    (i) => speed[i] * KMH < fastThresholdKmh * 0.9
  )
    .map((run) => describe(run.from, run.to, 'FAST'))
    .filter((section) => section.distanceMeters >= MIN_FAST_DISTANCE_M)
    .sort((a, b) => b.avgKmh - a.avgKmh)

  const slowThresholdKmh = Math.min(25, Math.max(10, avgMovingKmh * 0.5))
  const slow = findRuns(
    n,
    (i) => speed[i] * KMH <= slowThresholdKmh,
    (i) => speed[i] * KMH > slowThresholdKmh * 1.3
  )
    .map((run) => describe(run.from, run.to, 'SLOW'))
    .filter((section) => section.durationMillis >= MIN_SLOW_DURATION_MS)
    .sort((a, b) => b.durationMillis - a.durationMillis)

  // --- emelkedők és lejtők --------------------------------------------------
  const legs = []
  let direction = 0
  let extremeIndex = 0
  let legStart = 0
  for (let i = 1; i < n; i++) {
    const diff = elevation[i] - elevation[extremeIndex]
    if (direction === 1) {
      if (diff > 0) {
        extremeIndex = i
      } else if (diff <= -ELEVATION_HYSTERESIS_M) {
        legs.push({ from: legStart, to: extremeIndex, direction: 1 })
        legStart = extremeIndex
        direction = -1
        extremeIndex = i
      }
    } else if (direction === -1) {
      if (diff < 0) {
        extremeIndex = i
      } else if (diff >= ELEVATION_HYSTERESIS_M) {
        legs.push({ from: legStart, to: extremeIndex, direction: -1 })
        legStart = extremeIndex
        direction = 1
        extremeIndex = i
      }
    } else if (diff >= ELEVATION_HYSTERESIS_M) {
      direction = 1
      extremeIndex = i
    } else if (diff <= -ELEVATION_HYSTERESIS_M) {
      direction = -1
      extremeIndex = i
    }
  }
  if (direction !== 0 && extremeIndex > legStart) {
    legs.push({ from: legStart, to: extremeIndex, direction })
  }

  let elevationGainM = 0
  let elevationLossM = 0
  const climbs = []
  const descents = []
  for (const leg of legs) {
    const delta = elevation[leg.to] - elevation[leg.from]
    if (delta > 0) elevationGainM += delta
    else elevationLossM += -delta
    if (Math.abs(delta) < MIN_LEG_ELEVATION_M) continue
    const section = describe(leg.from, leg.to, delta > 0 ? 'CLIMB' : 'DESCENT')
    // "Hirtelen" az, ami 6%-nál meredekebb – ezt már a motor is megérzi.
    section.steep = Math.abs(section.gradePercent) >= 6
    if (delta > 0) climbs.push(section)
    else descents.push(section)
  }
  climbs.sort((a, b) => b.elevationDeltaMeters - a.elevationDeltaMeters)
  descents.sort((a, b) => a.elevationDeltaMeters - b.elevationDeltaMeters)

  // --- sebességhatárhoz mérve ----------------------------------------------
  const over = (i) => limits[i] !== null && rawSpeed[i] * KMH > limits[i] + SPEED_TOLERANCE_KMH
  const speeding = findRuns(
    n,
    over,
    (i) => limits[i] === null || rawSpeed[i] * KMH <= limits[i]
  )
    .map((run) => {
      const section = describe(run.from, run.to, 'SPEEDING')
      let maxOver = 0
      for (let i = run.from; i <= run.to; i++) {
        if (limits[i] === null) continue
        maxOver = Math.max(maxOver, rawSpeed[i] * KMH - limits[i])
      }
      section.maxOverKmh = maxOver
      return section
    })
    .filter((section) => section.distanceMeters >= MIN_SPEEDING_DISTANCE_M)
    .sort((a, b) => b.maxOverKmh - a.maxOverKmh)

  let aboveDistanceM = 0
  let aboveDurationMs = 0
  let ratioDistance = 0
  let ratioSum = 0
  let maxOverKmh = 0
  let maxOverAtLimit = null
  let maxOverSpeedKmh = 0
  for (let i = 1; i < n; i++) {
    const step = cumulative[i] - cumulative[i - 1]
    const dt = points[i].time - points[i - 1].time
    const limit = limits[i]
    if (limit === null) continue
    const kmh = rawSpeed[i] * KMH
    if (kmh > 3) {
      ratioDistance += step
      ratioSum += (kmh / limit) * step
    }
    if (kmh > limit + SPEED_TOLERANCE_KMH) {
      aboveDistanceM += step
      if (dt > 0 && dt < 30_000) aboveDurationMs += dt
      if (kmh - limit > maxOverKmh) {
        maxOverKmh = kmh - limit
        maxOverAtLimit = limit
        maxOverSpeedKmh = kmh
      }
    }
  }

  const summary = {
    distanceMeters: distanceM,
    durationMillis: durationMs,
    movingMillis: movingMs,
    avgMovingKmh,
    maxKmh,
    elevationGainMeters: elevationGainM,
    elevationLossMeters: elevationLossM,
    biggestClimb: climbs[0] ?? null,
    biggestDescent: descents[0] ?? null,
    steepestClimbPercent: climbs.reduce((max, section) => Math.max(max, section.gradePercent), 0),
    steepestDescentPercent: descents.reduce((min, section) => Math.min(min, section.gradePercent), 0),
    fastestSection: fast[0] ?? null,
    fastSectionCount: fast.length,
    slowSectionCount: slow.length,
    telemetry: telemetryAnalysis.summary,
    limit: {
      coverage: limitData.coverage,
      source: limitData.source,
      error: limitError,
      aboveDistanceMeters: aboveDistanceM,
      aboveDurationMillis: aboveDurationMs,
      aboveShare: distanceM > 0 ? clamp(aboveDistanceM / distanceM, 0, 1) : 0,
      // 1.0 = pont annyival mentél, amennyi ki van írva.
      avgRatio: ratioDistance > 0 ? ratioSum / ratioDistance : null,
      maxOverKmh,
      maxOverAtLimitKmh: maxOverAtLimit,
      maxOverSpeedKmh,
      speedingSectionCount: speeding.length
    }
  }

  return {
    trackId: track.id,
    computedAt: Date.now(),
    pointCount: n,
    summary,
    sections: {
      fast: fast.slice(0, 5),
      slow: slow.slice(0, 5),
      climbs: climbs.slice(0, 5),
      descents: descents.slice(0, 5),
      speeding: speeding.slice(0, 8),
      braking: telemetryAnalysis.braking,
      acceleration: telemetryAnalysis.acceleration,
      roughness: telemetryAnalysis.roughness,
      incidents: telemetryAnalysis.incidents
    }
  }
}

export function analyzeTelemetry(points, samples, profile = { eligible: true }) {
  const valid = samples.filter((sample) => sample.mountQuality >= 0.65)
  const nearestPoint = (time) => {
    let best = 0
    let distance = Infinity
    for (let i = 0; i < points.length; i++) {
      const candidate = Math.abs(points[i].time - time)
      if (candidate < distance) { distance = candidate; best = i }
    }
    return best
  }
  const section = (sample, type, value) => {
    const index = nearestPoint(sample.time)
    return {
      type, from: index, to: Math.min(points.length - 1, index + 1),
      startTime: sample.time, endTime: sample.time + 1000, durationMillis: 1000,
      distanceMeters: sample.speedMps, avgKmh: sample.speedMps * 3.6,
      maxKmh: sample.speedMps * 3.6, elevationDeltaMeters: 0, gradePercent: 0,
      limitKmh: null, road: null, lat: sample.lat ?? points[index]?.lat ?? 0,
      lon: sample.lon ?? points[index]?.lon ?? 0, telemetryValue: value,
      mountQuality: sample.mountQuality, steep: false
    }
  }
  const top = (predicate, score, type, count = 8) => valid.filter(predicate)
    .sort((a, b) => score(b) - score(a)).slice(0, count)
    .map((sample) => section(sample, type, score(sample)))

  const eligible = profile.eligible !== false
  const accelerationThreshold = Math.max(3, profile.accelerationThreshold ?? 3)
  const brakingThreshold = Math.max(3, profile.brakingThreshold ?? 3)
  const incidentSamples = []
  for (let index = 0; index < valid.length; index++) {
    const sample = valid[index]
    if ((sample.flags & 32) === 0) continue
    const before = valid.slice(Math.max(0, index - 3), index + 1)
    const after = valid.slice(index + 1, index + 11)
    const priorSpeed = Math.max(sample.speedMps ?? 0, ...before.map((item) => item.speedMps ?? 0))
    const laterSpeed = after.length ? Math.min(...after.map((item) => item.speedMps ?? 0)) : priorSpeed
    const suddenLoss = priorSpeed - laterSpeed >= 5
    const stillSeconds = after.filter((item) => (item.speedMps ?? 0) < 1 &&
      (item.verticalRmsMps2 ?? 0) < 0.8 && (item.yawPeakRadS ?? 0) < 0.3).length
    const impact = (sample.verticalPeakMps2 ?? 0) > 20
    const rotation = Math.max(sample.yawPeakRadS ?? 0, sample.rollPeakRadS ?? 0) > 3.5
    if ((impact && rotation) || ((impact || rotation) && suddenLoss && stillSeconds >= 3)) {
      incidentSamples.push(sample)
    }
  }

  let elevationGain = 0
  let altitudeRef = null
  for (const sample of valid) {
    if (!Number.isFinite(sample.fusedAltitudeMeters)) continue
    if (altitudeRef === null) altitudeRef = sample.fusedAltitudeMeters
    else if (sample.fusedAltitudeMeters - altitudeRef >= 1.5) {
      elevationGain += sample.fusedAltitudeMeters - altitudeRef
      altitudeRef = sample.fusedAltitudeMeters
    } else if (sample.fusedAltitudeMeters < altitudeRef) altitudeRef = sample.fusedAltitudeMeters
  }
  return {
    summary: samples.length === 0 ? null : {
      sampleCount: samples.length,
      validSampleCount: valid.length,
      qualityCoverage: valid.length / samples.length,
      maxAccelerationMps2: valid.reduce((max, s) => Math.max(max, s.forwardMaxMps2), 0),
      maxBrakingMps2: valid.reduce((max, s) => Math.max(max, -s.forwardMinMps2), 0),
      maxLeanDegrees: valid.reduce((max, s) => Math.max(max, Math.abs(s.leanDegrees ?? 0)), 0),
      maxVerticalPeakMps2: valid.reduce((max, s) => Math.max(max, s.verticalPeakMps2), 0),
      fusedElevationGainMeters: elevationGain,
      roughEventCount: valid.filter((s) => (s.flags & 16) !== 0).length,
      incidentCandidateCount: incidentSamples.length,
      personalizationEligible: eligible,
      accelerationThresholdMps2: accelerationThreshold,
      brakingThresholdMps2: brakingThreshold
    },
    braking: eligible
      ? top((s) => s.forwardMinMps2 < -brakingThreshold, (s) => -s.forwardMinMps2, 'BRAKING', 5)
      : [],
    acceleration: eligible
      ? top((s) => s.forwardMaxMps2 > accelerationThreshold, (s) => s.forwardMaxMps2, 'ACCELERATION', 5)
      : [],
    roughness: top((s) => (s.flags & 16) !== 0, (s) => s.verticalPeakMps2, 'ROUGHNESS', 8),
    incidents: incidentSamples
      .sort((a, b) => Math.max(b.verticalPeakMps2 ?? 0, (b.yawPeakRadS ?? 0) * 5) -
        Math.max(a.verticalPeakMps2 ?? 0, (a.yawPeakRadS ?? 0) * 5))
      .slice(0, 8)
      .map((sample) => section(sample, 'INCIDENT',
        Math.max(sample.verticalPeakMps2 ?? 0, (sample.yawPeakRadS ?? 0) * 5)))
  }
}
