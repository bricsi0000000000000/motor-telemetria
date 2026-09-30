import { familiarRoutes } from './familiar.js'
import { chooseRatRun, exclusionsAwayFromWaypoints } from './routepreferences.js'
import { NX500 } from './bike.js'
import {
  STEP_M, resample, smoothPath, curvature, cornerClass, cornerClassIndex,
  bearing, groupSections, mergeShortRuns
} from './geometry.js'
import { haversine } from './overpass.js'
import { speedProfile } from './profile.js'
import { LEFT_TYPES } from './stops.js'
import { loadModel, targetSpeed } from './ridermodel.js'
import { estimateStops, signalsOnPath, countLeftTurns } from './stops.js'
import { roadQualityForSteps } from './roadquality.js'
import {
  valhallaRoute, traceAttributes, valhallaHeight, encodePolyline, RoutingError
} from './routing.js'

/**
 * Az útvonaltervezés karmestere: a Valhalla jelöltjeitől a három menetidőig.
 */

/** A kanyargós jelölt nem lehet ennyiszer lassabb a leggyorsabbnál. */
const CURVY_TIME_BUDGET = 1.4
const COMFORT_TIME_BUDGET = 1.35

/** A magasságot 100 méterenként kérdezzük; a lejtő ennél finomabban zaj. */
const HEIGHT_EVERY = 5

/** A "kanyargós" a szűk és közepes kanyarok aránya az útvonal hosszában. */
const TWISTY_CLASSES = new Set(['HAIRPIN', 'TIGHT', 'MEDIUM'])

/**
 * A jelöltek. A Valhalla nem tud "kanyargós" költséget, és lemértük, hogy a
 * use_highways önmagában nem változtat az útvonalon - ezért több, eltérően
 * hangolt kérést adunk fel, és a saját görbületelemzésünk dönt.
 *
 * Az alacsony top_speed a legerősebb fogás: ettől a nagy, gyors utak elvesztik
 * az előnyüket, és a tervező a kisebb - jellemzően kanyargósabb - utakra tér.
 */
/** Az egérút ennyiszer lehet lassabb a leggyorsabbnál - cserébe nyugodtabb. */
const RATRUN_TIME_BUDGET = 1.5

/**
 * Az egérút pontszáma: elsősorban a lámpák, másodsorban a balra kanyarodások.
 * A lámpa drágább, mert ott tényleg megállsz; a balra kanyarodás "csak"
 * várakozás a szembejövőre - de motoron az is fárasztó, ezért benne van.
 */
const ratrunScore = (plan) => plan.signalsOnRoute.length + 0.5 * plan.leftTurns

const CANDIDATES = {
  FAST: [{ label: 'gyors', options: {} }],
  CURVY: [
    { label: 'gyors', options: {} },
    { label: 'alternatívák', options: { alternates: 2 } },
    { label: 'lassú utak', options: { topSpeed: 70, useHighways: 0 } },
    { label: 'közepes utak', options: { topSpeed: 90, useHighways: 0 } },
    { label: 'legrövidebb', options: { shortest: true } }
  ],
  COMFORT: [
    { label: 'gyors', options: {} },
    { label: 'alternatívák', options: { alternates: 2 } },
    { label: 'lassú utak', options: { topSpeed: 70, useHighways: 0 } },
    { label: 'legrövidebb', options: { shortest: true } }
  ],
  // Az egérút jelöltjei menet közben állnak elő (a kizárandó pontokhoz előbb
  // ismerni kell az alapútvonalat), ezért itt csak a kiindulás szerepel.
  RATRUN: [{ label: 'alap', options: {} }]
}

export async function planRoute({ waypoints, style = 'FAST', options = {} }) {
  if (!Array.isArray(waypoints) || waypoints.length < 2) {
    throw new RoutingError('Legalább két pont kell az útvonalhoz.', { status: 400 })
  }
  if (waypoints.length > 12) {
    throw new RoutingError('Legfeljebb 12 pont adható meg.', { status: 400 })
  }

  const points = waypoints.map((point) => ({
    lat: point?.lat,
    lon: point?.lon,
    name: point?.name ?? null,
    via: point?.via === true
  }))
  for (const point of points) {
    if (!Number.isFinite(point.lat) || !Number.isFinite(point.lon) || Math.abs(point.lat) > 90 || Math.abs(point.lon) > 180) {
      throw new RoutingError('Hibás koordináta a pontok között.', { status: 400 })
    }
  }
  points[0].via = false
  points[points.length - 1].via = false
  if (options.roundTrip) points.push({ ...points[0] })

  const model = loadModel()
  const plans = []
  const seen = new Set()

  if (style === 'RATRUN') {
    const plans = await planRatRun(points, options, model)
    const chosen = chooseRatRun(plans, RATRUN_TIME_BUDGET)
    return buildResponse(chosen, plans, model, style, points, options)
  }

  for (const candidate of CANDIDATES[style] ?? CANDIDATES.FAST) {
    let trips
    try {
      trips = await valhallaRoute(points, { ...options, ...candidate.options })
    } catch (error) {
      // Egy hangolás kieshet (pl. a top_speed mellett nincs út); a többi maradjon.
      if (error.status === 400 && plans.length > 0) continue
      throw error
    }
    for (const trip of trips) {
      // Ugyanaz az útvonal többféle hangolásból is kijöhet.
      const key = encodePolyline(trip.shape, 5)
      if (seen.has(key)) continue
      seen.add(key)
      plans.push(await analyseTrip(trip, model, candidate.label, points))
    }
  }

  if (plans.length === 0) throw new RoutingError('Nem sikerült útvonalat tervezni.', { status: 400 })

  const chosen = chooseplan(plans, style)
  return buildResponse(chosen, plans, model, style, points, options)
}

/**
 * Egérút: lámpák és balra kanyarodások nélküli útvonal.
 *
 * A Valhalla nem ismer ilyen költséget, viszont tud KONKRÉT PONTOKAT kizárni
 * (`exclude_locations`). Ezért két körben dolgozunk: megtervezzük az utat,
 * megnézzük, milyen lámpák és balra kanyarodások esnek rá, majd azokat
 * kizárva újratervezünk. Élő próbán ez 5 lámpáról 0-ra vitte le egy győri
 * útvonalat, 1,7 km többletért.
 *
 * Minden kört megtartunk jelöltként, mert a kizárás túl is lőhet a célon
 * (hosszú kerülő egyetlen lámpa miatt) - a végén a pontszám és az időbüdzsé dönt.
 */
async function planRatRun(points, options, model) {
  const plans = []
  const seen = new Set()

  const consider = async (label, extra, candidatePoints = points) => {
    let trips
    try {
      trips = await valhallaRoute(candidatePoints, { ...options, ...extra,
        excludeLocations: exclusionsAwayFromWaypoints(extra.excludeLocations ?? [], candidatePoints, haversine)
      })
    } catch (error) {
      // Ha a kizárás miatt nincs út, az nem hiba: marad a korábbi jelölt.
      if (error.status === 400 && plans.length > 0) return null
      throw error
    }
    let first = null
    for (const trip of trips) {
      const key = encodePolyline(trip.shape, 5)
      if (seen.has(key)) continue
      seen.add(key)
      const plan = await analyseTrip(trip, model, label, points)
      plan.routingPoints = candidatePoints
      plans.push(plan)
      first ??= plan
    }
    return first
  }

  // Külön irány és megállónkénti előzmény: bolttal megszakított utak is használhatók.
  // A felhasználó által kijelölt átmenő pontok elsőbbséget élveznek.
  if (options.useHistory !== false && !points.some(p => p.via) && points.length <= 6) {
    const seeded = [points[0]]
    let historyCount = 0
    for (let i = 1; i < points.length; i++) {
      const familiar = familiarRoutes([points[i - 1], points[i]])[0]
      if (familiar) { seeded.push(familiar.anchor); historyCount += familiar.count }
      seeded.push(points[i])
    }
    if (seeded.length > points.length) {
      try {
        await consider(`megszokott utak alapján (${historyCount} út)`, {}, seeded)
      } catch (error) {
        if (error.status !== 400) throw error
      }
    }
  }
  const direct = await consider('alap', {})
  const base = plans[0] ?? direct
  if (!base) throw new RoutingError('Nem sikerült útvonalat tervezni.', { status: 400 })

  // 1. kör: a lámpák kizárása.
  const signals = base.signalsOnRoute
  if (signals.length > 0) {
    await consider('lámpák nélkül', { excludeLocations: signals }, base.routingPoints)
  }

  // 2. kör: a lámpák ÉS a balra kanyarodások kizárása. A kanyarodás helyét a
  // manőver vonalindexéből vesszük - pont azt a kereszteződést kerüli ki.
  const lefts = leftTurnPoints(base)
  if (lefts.length > 0) {
    await consider('lámpák és balra kanyarok nélkül', { excludeLocations: [...signals, ...lefts] }, base.routingPoints)
    if (signals.length > 0) await consider('balra kanyarok nélkül', { excludeLocations: lefts }, base.routingPoints)
  }

  // Tartalék: ha a kizárás mindent elrontott, legyen sima alternatíva is.
  await consider('alternatíva', { alternates: 2 })

  // A kerülő új lámpákhoz is vezethet: még egy körben azokkal is számolunk.
  const best = plans.reduce((a, b) => ratrunScore(a) <= ratrunScore(b) ? a : b)
  if (best !== base && ratrunScore(best) > 0) {
    const excluded = [...signals, ...lefts, ...best.signalsOnRoute, ...leftTurnPoints(best)]
      .filter(p => points.every(w => haversine(p.lat, p.lon, w.lat, w.lon) > 75))
    await consider('finomított egérút', { excludeLocations: excluded }, best.routingPoints)
  }

  return plans
}

/** A balra kanyarodások helye az útvonalon. */
function leftTurnPoints(plan) {
  const out = []
  for (const maneuver of plan.trip.maneuvers) {
    if (!LEFT_TYPES.has(maneuver.type)) continue
    const point = plan.trip.shape[Math.min(maneuver.shapeIndex, plan.trip.shape.length - 1)]
    if (point) out.push({ lat: point.lat, lon: point.lon })
  }
  return out
}

/** Egy jelölt teljes elemzése: geometria, célsebességek, profil, megállók. */
async function analyseTrip(trip, model, label, waypoints) {
  // Élek: útosztály, sebességhatár, burkolat. A köztes pontoknál (ahol
  // megfordulhatsz) a szigorú edge_walk elhasal, ezért walk_or_snap: ahol nem
  // tud pontosan végigsétálni, ott illesztésre vált.
  let edges = []
  let edgeError = null
  let shape = trip.shape
  try {
    const trace = await traceAttributes(trip.shape, 'walk_or_snap')
    edges = trace.edges
    // FONTOS: a geometriát is a trace-től vesszük. Az él-indexek az ő saját
    // vonalára mutatnak, ami a snap miatt hosszabb lehet - ha a tervezőtől
    // kapott vonalra mintáznánk, az élek pár pontot csúsznának.
    if (trace.shape.length >= 2) shape = trace.shape
  } catch (error) {
    // Él nélkül is tudunk tervezni, de ez NEM néma hiba: enélkül nincs
    // sebességhatár és útosztály, tehát a becslés érdemben rosszabb.
    edgeError = error.message
  }

  const sampled = resample(shape, STEP_M)
  const smoothed = smoothPath(sampled, 5)
  const radii = curvature(smoothed)

  const edgeForShapeIndex = new Int32Array(shape.length).fill(-1)
  for (let e = 0; e < edges.length; e++) {
    const edge = edges[e]
    for (let i = edge.beginShapeIndex; i <= Math.min(edge.endShapeIndex, shape.length - 1); i++) {
      edgeForShapeIndex[i] = e
    }
  }

  const grades = await gradeProfile(sampled)

  const steps = sampled.map((point, index) => {
    const edgeIndex = edgeForShapeIndex[Math.min(point.src, edgeForShapeIndex.length - 1)]
    const edge = edgeIndex >= 0 ? edges[edgeIndex] : null
    const prev = sampled[Math.max(index - 1, 0)]
    const next = sampled[Math.min(index + 1, sampled.length - 1)]
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
      lengthM: index === 0 ? 0 : point.d - sampled[index - 1].d,
      gradePercent: grades[index] ?? 0
    }
  })

  const targets = new Float64Array(steps.length)
  for (let i = 0; i < steps.length; i++) {
    const target = targetSpeed(model, steps[i], NX500)
    targets[i] = target.vMps
    steps[i].source = target.source
    steps[i].samples = target.samples
    steps[i].fromHistory = target.history
  }

  for (const leg of legsFromWaypoints(steps, waypoints)) {
    if (!waypoints[leg.toIndex].via) targets[leg.toStep] = 0
  }
  const rider = { accelMps2: model.dynamics.accelMps2, decelMps2: model.dynamics.decelMps2 }
  // A köztes pontok megállók: ott tényleg nulláról indulsz.
  const profile = speedProfile(steps, targets, NX500, rider, { startMps: 0, endMps: 0 })

  for (let i = 0; i < steps.length; i++) {
    steps[i].speedKmh = Math.round(profile.speeds[i] * 3.6)
    steps[i].durationMs = Math.round(profile.stepSeconds[i] * 1000)
  }

  // A rövid ívek elnyelése a profil kiszámolása UTÁN történik: a sebességet
  // a valódi sugár adja, csak a megjelenítés lesz összevonva.
  mergeShortRuns(steps)

  const stops = estimateStops(steps, trip.maneuvers)
  const legs = legsFromWaypoints(steps, waypoints)
  // Az egérútnak ezek a döntő számok; a többi stílusnál is megmutatjuk.
  const signalsOnRoute = signalsOnPath(steps)
  const leftTurns = countLeftTurns(trip.maneuvers)
  const twistyMeters = steps.reduce(
    (sum, step) => sum + (TWISTY_CLASSES.has(step.cornerClass) ? step.lengthM : 0), 0
  )
  const totalMeters = steps[steps.length - 1]?.d ?? trip.distanceMeters

  return {
    label,
    trip,
    steps,
    targets,
    legs,
    signalsOnRoute,
    leftTurns,
    edgeError,
    modelMovingMs: Math.round(profile.timeSeconds * 1000),
    stops,
    distanceMeters: Math.round(totalMeters),
    twistiness: totalMeters > 0 ? twistyMeters / totalMeters : 0
  }
}

/**
 * A szakaszhatárok a köztes pontokból. Index szerint nem lehet átvenni a
 * tervezőtől: a snap után más a vonal felbontása, ezért mindig a ponthoz
 * legközelebbi lépést keressük meg, előrehaladva.
 */
function legsFromWaypoints(steps, waypoints) {
  const legs = []
  let cursor = 0
  let previousStep = 0

  for (let w = 1; w < waypoints.length; w++) {
    const target = waypoints[w]
    let best = cursor
    let bestDistance = Infinity
    for (let i = cursor; i < steps.length; i++) {
      const distance = haversine(target.lat, target.lon, steps[i].lat, steps[i].lon)
      if (distance < bestDistance) {
        bestDistance = distance
        best = i
      }
    }
    legs.push({
      fromIndex: w - 1,
      toIndex: w,
      fromStep: previousStep,
      toStep: best,
      distanceMeters: Math.round(steps[best].d - steps[previousStep].d),
      durationMillis: steps.slice(previousStep, best + 1).reduce((sum, step) => sum + (step.durationMs ?? 0), 0)
    })
    previousStep = best
    cursor = best
  }
  return legs
}

/** Lejtőprofil: ritkított magasságkérés, aztán lineáris kitöltés. */
async function gradeProfile(sampled) {
  const grades = new Float64Array(sampled.length)
  const probes = []
  const probeIndex = []
  for (let i = 0; i < sampled.length; i += HEIGHT_EVERY) {
    probes.push(sampled[i])
    probeIndex.push(i)
  }
  if (probes.length < 2) return grades

  let heights
  try {
    heights = await valhallaHeight(probes)
  } catch {
    return grades
  }
  if (heights.length !== probes.length) return grades

  for (let p = 1; p < probes.length; p++) {
    const rise = heights[p] - heights[p - 1]
    const run = sampled[probeIndex[p]].d - sampled[probeIndex[p - 1]].d
    if (run <= 0) continue
    // A meredekséget a teljes szakaszra terítjük, ezért simább, mint a pontonkénti.
    const grade = Math.max(-15, Math.min(15, (rise / run) * 100))
    for (let i = probeIndex[p - 1]; i <= probeIndex[p] && i < grades.length; i++) {
      grades[i] = grade
    }
  }
  return grades
}

/**
 * A jelöltek rangsora. Gyors stílusnál a menetidő dönt; kanyargósnál a
 * kanyarosság, de csak az időbüdzsén belüli jelöltek közül - egy háromszor
 * hosszabb út nem "élvezetes", hanem rossz.
 */
function chooseplan(plans, style) {
  if (style === 'COMFORT') {
    const fastest = Math.min(...plans.map((plan) => plan.modelMovingMs))
    const affordable = plans.filter((plan) => plan.modelMovingMs <= fastest * COMFORT_TIME_BUDGET)
    const pool = affordable.length > 0 ? affordable : plans
    for (const plan of pool) Object.assign(plan, roadQualityForSteps(plan.steps))
    const measured = pool.filter((plan) => plan.roughnessCoverage > 0)
    if (measured.length === 0) {
      return pool.reduce((best, plan) => (plan.modelMovingMs < best.modelMovingMs ? plan : best))
    }
    const maxRoughness = Math.max(...measured.map((plan) => plan.roughnessScore), 0.01)
    for (const plan of measured) {
      const timeRatio = plan.modelMovingMs / fastest
      const surfacePenalty = plan.roughnessScore / maxRoughness
      const missingPenalty = (1 - plan.roughnessCoverage) * 0.35
      // Az idő, az útminőség és a már meglévő kanyargóssági modell együtt dönt.
      plan.comfortScore = timeRatio + surfacePenalty * 0.7 + missingPenalty - plan.twistiness * 0.15
    }
    return measured.reduce((best, plan) =>
      plan.comfortScore < best.comfortScore ? plan : best)
  }
  if (style !== 'CURVY') {
    return plans.reduce((best, plan) => (plan.modelMovingMs < best.modelMovingMs ? plan : best))
  }
  const fastest = Math.min(...plans.map((plan) => plan.modelMovingMs))
  const affordable = plans.filter((plan) => plan.modelMovingMs <= fastest * CURVY_TIME_BUDGET)
  const pool = affordable.length > 0 ? affordable : plans
  return pool.reduce((best, plan) => (plan.twistiness > best.twistiness ? plan : best))
}

function buildResponse(chosen, plans, model, style, waypoints, options) {
  const { steps, stops, modelMovingMs } = chosen
  const ratios = model.ratios

  const fromHistory = steps.filter((step) => step.fromHistory)
  const historyShare = steps.length ? fromHistory.length / steps.length : 0
  const knownBucket = steps.filter((step) => step.source === 'BUCKET' || step.source === 'CELL')
  const bucketShare = steps.length ? knownBucket.length / steps.length : 0

  // Ismeretlen úton a sáv legyen őszintén szélesebb.
  const widen = 1 + 0.3 * (1 - bucketShare)
  const movingAverage = Math.round(modelMovingMs * ratios.p50)
  const moving = {
    fastest: Math.round(movingAverage - (movingAverage - modelMovingMs * ratios.p10) * widen),
    average: movingAverage,
    slowest: Math.round(movingAverage + (modelMovingMs * ratios.p90 - movingAverage) * widen)
  }

  const sections = groupSections(steps).map((section, index) => {
    const mid = steps[Math.floor((section.fromStep + section.toStep) / 2)]
    return {
      index,
      type: section.type,
      fromStep: section.fromStep,
      toStep: section.toStep,
      lat: mid.lat,
      lon: mid.lon,
      distanceMeters: Math.round(section.distanceMeters),
      durationMillis: Math.round(section.durationMillis),
      minRadiusMeters: Math.round(Math.min(section.minRadiusMeters, 99999)),
      predictedKmh: section.speedCount ? Math.round(section.speedSum / section.speedCount) : 0,
      limitKmh: mostCommon(section.limits),
      road: mostCommon(section.roads),
      roadClass: steps[section.fromStep].roadClass,
      source: mostCommon(section.sources) ?? 'CURVE',
      samples: section.samples
    }
  })

  const cornerSummary = summariseCorners(steps)
  const confidence = describeConfidence({
    calibrationTracks: model.calibrationTracks,
    historyShare,
    bucketShare,
    cold: model.dynamics.cold
  })

  const warnings = []
  if (waypoints.slice(1, -1).some(p => !p.via)) warnings.push('A boltban és más köztes megállóknál eltöltött idő nincs a menetidőben.')
  if (options.arriveSameSide !== false) {
    if (chosen.trip.arrivalFallback) {
      warnings.push('A cél felőli útoldalra nem sikerült útvonalat találni; az érkezés oldalát ellenőrizd.')
    } else if (!chosen.trip.arrivals?.length || chosen.trip.arrivals.some(p => p.side === 'unknown' || p.side === 'none' || p.side === 'left')) {
      warnings.push('A cél felőli útoldalt előnyben részesítettem, de nem minden megállónál igazolható. A jelölőt tedd a bejárathoz, az út megfelelő oldalára.')
    }
  }
  if (!stops.hasSignalData) {
    warnings.push('A lámpák adatbázisa üres, az állóidő csak a manőverekből számolt. Frissítsd a közúti adatokat a térkép fülön.')
  }
  if (chosen.distanceMeters > 80_000) {
    warnings.push('Hosszú úton a pihenők és a tankolás nincsenek beleszámolva.')
  }
  const guessedLimits = steps.filter((step) => !step.limitKmh).length
  if (guessedLimits / Math.max(1, steps.length) > 0.15) {
    warnings.push(`A sebességhatárok ${Math.round((guessedLimits / steps.length) * 100)}%-a becsült (az OSM-ben nincs kiírva).`)
  }
  if (chosen.trip.hasToll) {
    warnings.push('Az útvonal fizetős szakaszt tartalmaz.')
  }
  if (style === 'RATRUN' && (chosen.signalsOnRoute.length > 0 || chosen.leftTurns > 0)) {
    warnings.push(
      `A vizsgált útvonalak között nem találtam teljesen lámpa- és balkanyar-mentes utat: maradt ${chosen.signalsOnRoute.length} lámpa és ${chosen.leftTurns} balra kanyarodás.`
    )
  }
  if (chosen.edgeError) {
    warnings.push('Az útadatok (sebességhatár, útosztály) nem jöttek meg, ezért a becslés durvább.')
  }

  return {
    style,
    options: { arriveSameSide: options.arriveSameSide !== false, useHistory: options.useHistory !== false },
    arrival: { preferredSameSide: options.arriveSameSide !== false,
      fallback: chosen.trip.arrivalFallback, stops: chosen.trip.arrivals ?? [] },
    label: chosen.label,
    distanceMeters: chosen.distanceMeters,
    shape: encodePolyline(steps, 5),
    stepMeters: STEP_M,
    waypoints,
    legs: chosen.legs,
    times: {
      moving,
      stops: stops.times,
      total: {
        fastest: moving.fastest + stops.times.fastest,
        average: moving.average + stops.times.average,
        slowest: moving.slowest + stops.times.slowest
      },
      modelMovingMs,
      ratios
    },
    confidence: { ...confidence, historyShare: round2(historyShare), bucketShare: round2(bucketShare), calibrationTracks: model.calibrationTracks },
    profile: {
      stepMeters: STEP_M,
      radius: steps.map((step) => Math.min(99999, Math.round(step.radius))),
      speedKmh: steps.map((step) => step.speedKmh),
      cornerClass: steps.map((step) => cornerClassIndex(step.cornerClass)),
      limitKmh: steps.map((step) => step.limitKmh ?? 0),
      gradePercent: steps.map((step) => Math.round(step.gradePercent * 10) / 10),
      fromHistory: steps.map((step) => (step.fromHistory ? 1 : 0))
    },
    sections,
    cornerSummary,
    // Az egérút döntő számai - a felület ezeket mutatja, és a stílus ezek
    // alapján választott a jelöltek közül.
    avoidance: {
      trafficLights: chosen.signalsOnRoute.length,
      leftTurns: chosen.leftTurns
    },
    comfort: style === 'COMFORT' ? {
      score: round2(chosen.comfortScore ?? 0),
      roughness: round2(chosen.roughnessScore ?? 0),
      coverage: round2(chosen.roughnessCoverage ?? 0)
    } : null,
    stops: stops.stops,
    alternatives: plans
      .filter((plan) => plan !== chosen)
      .map((plan) => ({
        label: plan.label,
        distanceMeters: plan.distanceMeters,
        averageMs: Math.round(plan.modelMovingMs * ratios.p50),
        twistiness: round2(plan.twistiness),
        trafficLights: plan.signalsOnRoute.length,
        leftTurns: plan.leftTurns
      })),
    bike: {
      model: NX500.name,
      massKg: NX500.massBikeKg + NX500.massRiderKg + NX500.massLuggageKg,
      topSpeedKmh: NX500.topSpeedKmh
    },
    warnings,
    computedAt: Date.now()
  }
}

function summariseCorners(steps) {
  const summary = {}
  for (const cls of ['HAIRPIN', 'TIGHT', 'MEDIUM', 'GENTLE', 'STRAIGHT']) {
    summary[cls.toLowerCase()] = { count: 0, meters: 0 }
  }
  let previous = null
  let twisty = 0
  let total = 0
  for (const step of steps) {
    const key = step.cornerClass.toLowerCase()
    summary[key].meters += step.lengthM
    if (step.cornerClass !== previous) summary[key].count++
    previous = step.cornerClass
    total += step.lengthM
    if (TWISTY_CLASSES.has(step.cornerClass)) twisty += step.lengthM
  }
  for (const key of Object.keys(summary)) summary[key].meters = Math.round(summary[key].meters)
  summary.twistiness = total > 0 ? round2(twisty / total) : 0
  return summary
}

/**
 * A bizalom őszinte kimondása. A 65 ezer pontod ~379 km egyedi utat fed le, és
 * ebből csak ~79 km-en jártál többször - ezt nem elfedni kell, hanem megírni.
 */
function describeConfidence({ calibrationTracks, historyShare, bucketShare, cold }) {
  if (cold || calibrationTracks < 3 || bucketShare < 0.3) {
    return {
      level: 'COLD',
      reason: 'Még nincs elég saját adat: az idő általános becslés, nem a te tempód.'
    }
  }
  if (calibrationTracks >= 20 && bucketShare >= 0.8 && historyShare >= 0.5) {
    return {
      level: 'HIGH',
      reason: 'Az útvonal nagy részén jártál már, és elég túrád van a hitelesítéshez.'
    }
  }
  if (calibrationTracks >= 10 && bucketShare >= 0.6) {
    const percent = Math.round(historyShare * 100)
    return {
      level: 'MEDIUM',
      reason: percent >= 10
        ? `Az útvonal ${percent}%-án jártál már; a többit a hasonló utakon mért tempódból becsültem.`
        : 'Ezen az útvonalon még nem jártál; a becslés a hasonló típusú utakon mért tempódból készült.'
    }
  }
  return {
    level: 'LOW',
    reason: 'Kevés túra van még a hitelesítéshez, ezért a becslés bizonytalanabb.'
  }
}

function mostCommon(values) {
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

const round2 = (value) => Math.round(value * 100) / 100
