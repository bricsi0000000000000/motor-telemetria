import { Router } from 'express'
import { overpass } from '../overpass.js'
import { cachedPlace, reverseGeocode } from '../geocode.js'
import { refreshWazeAlerts, wazeConfigured, wazeUsage } from '../roadpoints.js'
import { valhallaStatus, VALHALLA_URL } from '../routing.js'
import { modelSummary } from '../ridermodel.js'

export const selfTestRouter = Router()

/**
 * Az önteszt mintaterülete. Egy konkrét koordináta elárulja, hol szoktál járni,
 * ezért a valódi értéket a `server/.env` adja; a beégetett tartalék Budapest
 * belvárosa, ami mindenkinek semleges.
 */
const SELFTEST_LAT = Number(process.env.SELFTEST_LAT || 47.4979)
const SELFTEST_LON = Number(process.env.SELFTEST_LON || 19.0402)
// Néhány száz méteres doboz a pont körül: ennyi elég egy „válaszol-e" kérdéshez.
const BOX = 0.0025

/** Egy ellenőrzés lefuttatása időméréssel, hibaüzenettel együtt. */
async function check(name, worker) {
  const started = Date.now()
  try {
    const detail = await worker()
    return { name, ok: true, ms: Date.now() - started, detail: detail ?? null }
  } catch (error) {
    return { name, ok: false, ms: Date.now() - started, detail: error.message }
  }
}

/**
 * Elérhető-e minden külső szolgáltatás, amit használunk.
 *
 * A Waze szándékosan kimarad: minden hívása fogyasztja a havi keretet, ezért
 * csak külön kérésre (`?waze=1`) próbáljuk meg élesben.
 */
selfTestRouter.get('/selftest', async (req, res, next) => {
  try {
    const checks = await Promise.all([
      check('overpass', async () => {
        // Szándékosan pici lekérdezés: csak azt nézzük, válaszol-e.
        // Rövid határidő: az önteszt ne álljon percekig egy túlterhelt kiszolgálón.
        const response = await overpass(
          '[out:json][timeout:15];node["highway"="traffic_signals"]' +
            `(${SELFTEST_LAT - BOX},${SELFTEST_LON - BOX},${SELFTEST_LAT + BOX},${SELFTEST_LON + BOX});out body;`,
          { timeoutMs: 20_000 }
        )
        return `válaszolt, ${response.elements?.length ?? 0} találat a mintaterületen`
      }),

      check('valhalla', async () => {
        const status = await valhallaStatus()
        if (!status.ok) throw new Error(`${VALHALLA_URL} nem válaszol (${status.detail})`)
        if (!status.actions.includes('trace_attributes')) {
          throw new Error('a példány nem tud térképre illeszteni (trace_attributes hiányzik)')
        }
        const model = modelSummary()
        return `${status.version}, ${model.matchedTracks} illesztett túra, ${model.bucketSamples} minta`
      }),

      check('nominatim', async () => {
        const cached = cachedPlace(SELFTEST_LAT, SELFTEST_LON)
        if (cached) return `gyorsítótárból: ${cached.city ?? cached.road ?? 'ismeretlen'}`
        const place = await reverseGeocode(SELFTEST_LAT, SELFTEST_LON)
        return `élő válasz: ${place.city ?? place.road ?? 'ismeretlen'}`
      })
    ])

    if (req.query.waze === '1') {
      checks.push(
        await check('waze', async () => {
          if (!wazeConfigured()) throw new Error('nincs beállítva kulcs')
          const result = await refreshWazeAlerts()
          if (result.skipped) throw new Error(result.skipped)
          return `${result.points.length} bejelentés, keret: ${wazeUsage().remaining}`
        })
      )
    } else {
      checks.push({
        name: 'waze',
        ok: wazeConfigured(),
        ms: 0,
        detail: wazeConfigured()
          ? `kulcs beállítva, keret: ${wazeUsage().remaining} / ${wazeUsage().limit} (élő teszt külön kérhető)`
          : 'nincs beállítva kulcs'
      })
    }

    res.json({ checks })
  } catch (error) {
    next(error)
  }
})
