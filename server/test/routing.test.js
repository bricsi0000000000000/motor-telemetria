import { test } from 'node:test'
import assert from 'node:assert/strict'
import { valhallaRoute, encodePolyline } from '../src/routing.js'

test('same-side fallback keeps the shop and compulsory road, and reports fallback', async () => {
  const originalFetch = globalThis.fetch
  const requests = []
  const points = [{ lat: 47, lon: 17 }, { lat: 47.01, lon: 17, via: true },
    { lat: 47.02, lon: 17, name: 'Bolt' }, { lat: 47.03, lon: 17 }]
  globalThis.fetch = async (_url, init) => {
    const body = JSON.parse(init.body)
    requests.push(body)
    if (requests.length === 1) return new Response(JSON.stringify({ error: 'No route', error_code: 442 }), { status: 400 })
    return new Response(JSON.stringify({ trip: {
      summary: { length: 3 }, locations: body.locations,
      legs: [{ summary: { length: 3 }, shape: encodePolyline(points, 6), maneuvers: [] }]
    } }))
  }
  try {
    const [trip] = await valhallaRoute(points)
    assert.equal(trip.arrivalFallback, true)
    assert.equal(requests.length, 2)
    assert.equal(requests[0].locations[2].preferred_side, 'same')
    assert.ok(requests[1].locations.every(p => p.preferred_side === undefined))
    assert.deepEqual(requests[1].locations.map(p => p.type), ['break', 'through', 'break', 'break'])
    assert.deepEqual(requests[1].locations.map(p => p.lat), points.map(p => p.lat))
  } finally { globalThis.fetch = originalFetch }
})
