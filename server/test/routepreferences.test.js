import { test } from 'node:test'
import assert from 'node:assert/strict'
import { routingLocations, chooseRatRun, exclusionsAwayFromWaypoints } from '../src/routepreferences.js'
import { haversine } from '../src/overpass.js'

const points = [{ lat: 47, lon: 17, via: true }, { lat: 47.01, lon: 17, via: true },
  { lat: 47.02, lon: 17, name: 'Bolt' }, { lat: 47.03, lon: 17, via: true }]
test('same-side preference at shop and destination, through on compulsory road', () => {
  const locations = routingLocations(points)
  assert.deepEqual(locations.map(p => p.type), ['break', 'through', 'break', 'break'])
  assert.deepEqual(locations.map(p => p.preferred_side), [undefined, undefined, 'same', 'same'])
  assert.ok(routingLocations(points, { arriveSameSide: false }).every(p => !p.preferred_side))
})
test('exclusions preserve mandatory stops and remove duplicates', () => {
  const far = { lat: 47.5, lon: 17 }
  assert.deepEqual(exclusionsAwayFromWaypoints([...points, far, far], points, haversine), [far])
})
test('Egérút prioritizes no signals/lefts within total-time budget', () => {
  const plan = (time, stop, signals, leftTurns) => ({ modelMovingMs: time, stops: { times: { average: stop } }, signalsOnRoute: Array(signals).fill({}), leftTurns })
  const fast = plan(100, 0, 2, 1)
  const calm = plan(110, 10, 0, 0)
  const excessive = plan(160, 0, 0, 0)
  assert.equal(chooseRatRun([fast, calm, excessive]), calm)
  assert.equal(chooseRatRun([fast, excessive]), fast)
})
