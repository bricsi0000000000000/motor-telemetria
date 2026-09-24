/** A végpont mindig megálló; az átmenő ponton nem fordulhatunk vissza. */
export function routingLocations(waypoints, options = {}) {
  return waypoints.map((point, index) => {
    const stop = index === 0 || index === waypoints.length - 1 || !point.via
    return {
      lat: point.lat, lon: point.lon,
      type: stop ? 'break' : 'through',
      ...(index > 0 && stop && options.arriveSameSide !== false
        ? { preferred_side: 'same' } : {})
    }
  })
}

export function chooseRatRun(plans, budgetRatio = 1.5) {
  const duration = p => p.modelMovingMs + p.stops.times.average
  const budget = Math.min(...plans.map(duration)) * budgetRatio
  const pool = plans.filter(p => duration(p) <= budget)
  const score = p => p.signalsOnRoute.length + 0.5 * p.leftTurns
  return pool.reduce((best, p) => score(p) < score(best) ||
    (score(p) === score(best) && duration(p) < duration(best)) ? p : best)
}

/** Ne zárjunk ki kötelező megállót / átmenő pontot, és ne pazaroljunk limitet duplikátumra. */
export function exclusionsAwayFromWaypoints(locations, waypoints, distance) {
  const seen = new Set()
  return locations.filter(p => {
    const key = `${p.lat.toFixed(5)}|${p.lon.toFixed(5)}`
    if (seen.has(key) || waypoints.some(w => distance(p.lat, p.lon, w.lat, w.lon) < 75)) return false
    seen.add(key)
    return true
  }).slice(0, 45)
}
