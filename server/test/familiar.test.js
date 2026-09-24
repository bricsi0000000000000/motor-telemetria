import { test } from 'node:test'
import assert from 'node:assert/strict'
process.env.MOTOR_DB = ':memory:'
const { clusterJourneys, familiarRoutes } = await import('../src/familiar.js')
const from = { lat: 47, lon: 17 }
const to = { lat: 47, lon: 17.05 }
const path = lat => [from, { lat, lon: 17.01 }, { lat, lon: 17.04 }, to]
const journey = (id, lat, durationMs = 600000) => ({ id, points: path(lat), distanceMeters: 5000, durationMs })

test('two corridors ranked by usage, noisy repeats merged, reverse excluded', () => {
  const reverse = { ...journey(4, 47.01), points: path(47.01).reverse() }
  const routes = clusterJourneys([journey(1, 47.01), journey(2, 47.01005, 700000), journey(3, 46.99), reverse], from, to)
  assert.equal(routes.length, 2)
  assert.deepEqual(routes.map(r => r.count), [2, 1])
  assert.equal(routes[0].averageMs, 700000)
  assert.ok(routes[0].anchor.lat > 47.009)
  assert.ok(routes[1].anchor.lat < 46.991)
  assert.equal(routes[0].anchor.via, true)
})
test('missing history produces no invented routes', () => {
  assert.deepEqual(clusterJourneys([], from, to), [])
  assert.deepEqual(familiarRoutes([from, to]), [])
})
test('invalid coordinates are rejected', () => {
  assert.throws(() => familiarRoutes([from, { lat: 200, lon: 17 }]), /érvényes/)
  assert.throws(() => familiarRoutes([from]), /érvényes/)
})
