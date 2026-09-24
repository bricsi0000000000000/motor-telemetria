import { maxAccel, maxDecel, topSpeedMps } from './bike.js'

/**
 * Sebességprofil előre-hátra menettel - ez az, ami a kérésben az "egyenesek".
 *
 * Egy 150 méteres egyenes két 50 méter sugarú kanyar között fizikailag nem éri
 * el az egyenes célsebességét: nincs elég út gyorsítani, és közben már fékezni
 * kell a következő kanyarba. Ezt csak akkor lehet megfogni, ha a lépéseket nem
 * függetlenül nézzük, hanem előre és visszafelé is végigfésüljük. Ugyanaz az
 * eljárás, amivel a köridő-szimulátorok dolgoznak.
 */

/** A nullával osztás ellen: ennél lassabban nem számolunk időt. */
const MIN_V = 0.5

/**
 * @param steps     [{ lengthM, gradePercent }] a 20 méteres lépések
 * @param targets   Float64Array a lépésenkénti célsebességgel (m/s)
 * @param bike      a jármű
 * @param rider     { accelMps2, decelMps2 } a SZEMÉLYES kényelmi korlátok
 * @param edges     { startMps, endMps } a szakasz eleje/vége (megállóknál 0)
 */
export function speedProfile(steps, targets, bike, rider, edges = {}) {
  const n = steps.length
  const v = new Float64Array(n)
  if (n === 0) return { speeds: v, timeSeconds: 0, stepSeconds: new Float64Array(0) }

  const vMax = topSpeedMps(bike)
  const startMps = edges.startMps ?? 0
  const endMps = edges.endMps ?? 0

  for (let i = 0; i < n; i++) v[i] = Math.min(targets[i], vMax)

  // Előre: ennél gyorsabban nem tudok felgyorsulni az előző lépésből.
  v[0] = Math.min(v[0], Math.max(0, startMps))
  for (let i = 1; i < n; i++) {
    const ds = steps[i].lengthM
    const grade = steps[i].gradePercent ?? 0
    // A motor fizikai határa és a saját kényelmi tempód közül a kisebbik.
    const accel = Math.min(maxAccel(bike, v[i - 1], grade), rider.accelMps2)
    if (accel > 0) {
      const reachable = Math.sqrt(v[i - 1] * v[i - 1] + 2 * accel * ds)
      if (reachable < v[i]) v[i] = reachable
    } else {
      // Meredek emelkedőn a motor lassul akkor is, ha a cél magasabb lenne.
      const reachable = Math.sqrt(Math.max(0, v[i - 1] * v[i - 1] + 2 * accel * ds))
      if (reachable < v[i]) v[i] = reachable
    }
  }

  // Hátra: ennél gyorsabban nem érkezhetek, mert le kell tudnom fékezni.
  if (endMps >= 0) v[n - 1] = Math.min(v[n - 1], Math.max(endMps, 0))
  for (let i = n - 2; i >= 0; i--) {
    const ds = steps[i + 1].lengthM
    const grade = steps[i + 1].gradePercent ?? 0
    const decel = Math.min(maxDecel(bike, v[i + 1], grade), rider.decelMps2)
    const reachable = Math.sqrt(v[i + 1] * v[i + 1] + 2 * decel * ds)
    if (reachable < v[i]) v[i] = reachable
  }

  // Trapéz szabály: a lépésen belül a sebesség lineárisan változik.
  const stepSeconds = new Float64Array(n)
  let total = 0
  for (let i = 1; i < n; i++) {
    const ds = steps[i].lengthM
    const a = Math.max(v[i - 1], MIN_V)
    const b = Math.max(v[i], MIN_V)
    const seconds = (2 * ds) / (a + b)
    stepSeconds[i] = seconds
    total += seconds
  }

  return { speeds: v, timeSeconds: total, stepSeconds }
}

/**
 * A profil ellenőrzése. A tervben ez invariánsként szerepel, és tesztből is,
 * de futás közben is olcsó - ha egyszer elcsúszik, csendben rossz időt adnánk.
 */
export function validateProfile(steps, targets, speeds, bike, rider) {
  const problems = []
  for (let i = 0; i < speeds.length; i++) {
    if (speeds[i] > targets[i] + 0.01) {
      problems.push(`${i}. lépés: ${speeds[i].toFixed(2)} > cél ${targets[i].toFixed(2)}`)
    }
  }
  for (let i = 1; i < speeds.length; i++) {
    const ds = steps[i].lengthM
    if (ds <= 0) continue
    const a = (speeds[i] * speeds[i] - speeds[i - 1] * speeds[i - 1]) / (2 * ds)
    const allowedUp = Math.min(maxAccel(bike, speeds[i - 1], steps[i].gradePercent ?? 0), rider.accelMps2)
    const allowedDown = Math.min(maxDecel(bike, speeds[i], steps[i].gradePercent ?? 0), rider.decelMps2)
    if (a > allowedUp + 0.05) problems.push(`${i}. lépés: túl nagy gyorsulás (${a.toFixed(2)})`)
    if (-a > allowedDown + 0.05) problems.push(`${i}. lépés: túl nagy lassulás (${(-a).toFixed(2)})`)
  }
  return problems
}
