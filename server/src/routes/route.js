import { Router } from 'express'

import { all, one, run } from '../db.js'
import { familiarRoutes } from '../familiar.js'
import { planRoute } from '../routeplan.js'
import { RoutingError, valhallaStatus } from '../routing.js'
import { modelSummary } from '../ridermodel.js'
import { rebuildAggregates, aggregateState, matchTrack, pendingTracks } from '../aggregate.js'
import { rebuildCalibration, backtest } from '../calibration.js'

export const routeRouter = Router()

/** A hosszú újraépítés nem foglalhatja a kérést; egyszerre egy futhat. */
let rebuildJob = null

const fail = (res, error) => {
  if (error instanceof RoutingError) {
    res.status(error.status).json({ error: error.message })
    return true
  }
  return false
}

/**
 * Útvonalterv köztes pontokkal, a saját tempód alapján számolt idővel.
 */
routeRouter.post('/route/plan', async (req, res, next) => {
  try {
    const plan = await planRoute({
      waypoints: req.body?.waypoints,
      style: ['CURVY', 'COMFORT', 'RATRUN'].includes(req.body?.style) ? req.body.style : 'FAST',
      options: req.body?.options ?? {}
    })
    res.json(plan)
  } catch (error) {
    if (!fail(res, error)) next(error)
  }
})

routeRouter.post('/route/familiar', (req, res, next) => {
  try { res.json({ routes: familiarRoutes(req.body?.waypoints) }) }
  catch (error) { if (!fail(res, error)) next(error) }
})

/** A modell állapota: mennyit tanult, és él-e a tervezőmotor. */
routeRouter.get('/route/status', async (_req, res, next) => {
  try {
    const valhalla = await valhallaStatus()
    res.json({
      valhalla,
      model: modelSummary(),
      aggregate: aggregateState(),
      rebuilding: Boolean(rebuildJob)
    })
  } catch (error) {
    next(error)
  }
})

/**
 * A modell újraépítése. Hálózat nélkül csak az összesítőket rakja össze a már
 * eltett illesztésekből; ?rematch=1 esetén a túrákat is újra illeszti.
 */
routeRouter.post('/route/rebuild', (req, res) => {
  if (rebuildJob) {
    res.status(409).json({ error: 'Már fut egy újraépítés.' })
    return
  }
  const rematch = req.query.rematch === '1'

  rebuildJob = (async () => {
    if (rematch) {
      for (const id of pendingTracks()) await matchTrack(id, { force: true })
    }
    rebuildAggregates()
    rebuildCalibration()
  })()
    .catch((error) => {
      console.error('Az útvonalmodell újraépítése elszállt:', error)
    })
    .finally(() => {
      rebuildJob = null
    })

  res.json({ started: true, rematch })
})

/**
 * Visszamérés: minden túrát olyan modellel jósolunk meg, ami nem látta.
 * Ez mondja meg, ér-e valamit a becslés - érdemes új adat után újrafuttatni.
 */
routeRouter.get('/route/backtest', (_req, res, next) => {
  try {
    res.json(backtest())
  } catch (error) {
    next(error)
  }
})

/** Mentett útvonalak. A terv is elmegy, hogy net nélkül is megnyitható legyen. */
routeRouter.get('/routes', (_req, res) => {
  res.json({
    routes: all(`SELECT id, name, waypoints, style, distance_m AS distanceMeters,
                        avg_ms AS averageMillis, created_at AS createdAt, updated_at AS updatedAt
                 FROM routes ORDER BY updated_at DESC`)
      .map((row) => ({ ...row, waypoints: JSON.parse(row.waypoints) }))
  })
})

routeRouter.post('/routes', (req, res) => {
  const name = String(req.body?.name ?? '').trim()
  const waypoints = req.body?.waypoints
  if (!name || !Array.isArray(waypoints) || waypoints.length < 2) {
    res.status(400).json({ error: 'Név és legalább két pont kell.' })
    return
  }

  const now = Date.now()
  const plan = req.body?.plan ?? null
  const result = run(
    `INSERT INTO routes (name, waypoints, style, plan, distance_m, avg_ms, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    name, JSON.stringify(waypoints), req.body?.style ?? 'FAST',
    plan ? JSON.stringify(plan) : null,
    Number(plan?.distanceMeters ?? 0), Number(plan?.times?.total?.average ?? 0), now, now
  )
  res.json({ id: Number(result.lastInsertRowid), name })
})

routeRouter.get('/routes/:id', (req, res) => {
  const row = one('SELECT * FROM routes WHERE id = ?', Number(req.params.id))
  if (!row) {
    res.status(404).json({ error: 'Nincs ilyen útvonal' })
    return
  }
  res.json({
    id: row.id,
    name: row.name,
    style: row.style,
    waypoints: JSON.parse(row.waypoints),
    plan: row.plan ? JSON.parse(row.plan) : null,
    distanceMeters: row.distance_m,
    averageMillis: row.avg_ms,
    createdAt: row.created_at,
    updatedAt: row.updated_at
  })
})

routeRouter.delete('/routes/:id', (req, res) => {
  const result = run('DELETE FROM routes WHERE id = ?', Number(req.params.id))
  res.json({ deleted: result.changes })
})
