import { Router } from 'express'
import { AREA_BBOX } from '../overpass.js'
import {
  currentPoints,
  ensureSpeedSegments,
  layerStates,
  speedSegments,
  startRefresh,
  wazeConfigured,
  wazeUsage
} from '../roadpoints.js'

export const roadPointsRouter = Router()

/**
 * Minden válaszban ott van, melyik réteg mikor frissült utoljára és fut-e épp
 * frissítés – ebből írja ki az app, hogy "3 perce" vagy "2 napja".
 */
const meta = () => ({
  area: AREA_BBOX,
  layers: layerStates(),
  waze: { configured: wazeConfigured(), ...wazeUsage() }
})

/** A tárolt réteg – külső API-t nem hív, tehát ingyenes és azonnali. */
roadPointsRouter.get('/roadpoints', (_req, res) => {
  res.json({ points: currentPoints(), ...meta() })
})

/**
 * Kézi frissítés: a rétegek a háttérben frissülnek, a válasz azonnal megjön.
 * Az ingyenes (OSM) rétegek mindig mennek, a Waze csak ha van kulcs és keret –
 * így egy félrekoppintás sem éget hívást.
 */
roadPointsRouter.post('/roadpoints/refresh', (req, res) => {
  const result = startRefresh({
    freeOnly: req.query.free === '1',
    roads: req.query.roads === '1'
  })
  if (result.throttled) {
    res.status(429).json({ error: `Túl sűrű frissítés, várj ${result.retryInSeconds} másodpercet.` })
    return
  }
  res.json({ ...result, points: currentPoints(), ...meta() })
})

/** A színezett térképréteghez: útszakaszok a megengedett sebességgel. */
/**
 * A színezett térképréteghez. A telefon a látható területet küldi
 * ("bbox=dél,nyugat,észak,kelet"), így megyényi adatból is csak a lényeg megy át.
 */
roadPointsRouter.get('/speedlimits', (req, res) => {
  // Ha elavult vagy még sosem töltöttük le, itt indul a háttérfrissítés.
  ensureSpeedSegments()

  const parts = String(req.query.bbox ?? '').split(',').map(Number)
  const bbox = parts.length === 4 && parts.every(Number.isFinite)
    ? { south: parts[0], west: parts[1], north: parts[2], east: parts[3] }
    : null

  res.json({ segments: speedSegments(bbox), bbox, ...meta() })
})
