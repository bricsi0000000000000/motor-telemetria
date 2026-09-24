import { test } from 'node:test'
import assert from 'node:assert/strict'
import { displayTrack, ridingMask } from '../src/displaytrack.js'
import { decodePolyline } from '../src/routing.js'
const points = (speeds) => speeds.map((speedMps, i) => ({
  lat: 47, lon: 17 + i * 0.0002, speedMps, accuracy: 10, time: 100000 + i * 3000, segment: 0
}))
const matcher = async p => ({
  shape: p.map(a => ({ lat: a.lat + 0.00005, lon: a.lon })),
  edges: [{ beginShapeIndex: 0, endShapeIndex: p.length - 1 }],
  matched: p.map(a => ({ lat: a.lat + 0.00005, lon: a.lon, edgeIndex: 0, type: 'matched' }))
})
test('ride-shop-walk-ride: walking and stop links remain raw', async () => {
  const p = points([8,8,8,8,1,1,0,0,1,1,8,8,8,8])
  const result = await displayTrack(p, matcher)
  assert.equal(result.matchedLinks, 6)
  for (let i = 3; i <= 9; i++) {
    assert.equal(result.links[i].matched, false)
    assert.deepEqual(decodePolyline(result.links[i].shape, 6), [p[i], p[i + 1]].map(({ lat, lon }) => ({ lat, lon })))
  }
})
test('never plans across gaps or recording pauses', async () => {
  const p = points([8,8,8,8,8,8])
  p[3].segment = p[4].segment = p[5].segment = 1
  assert.equal((await displayTrack(p, matcher)).links[2].shape, null)
  const q = points([8,8,8,8,8,8])
  for (let i = 3; i < q.length; i++) q[i].time += 120000
  assert.equal((await displayTrack(q, matcher)).links[2].shape, null)
})
test('large snap error and network failure retain measured path', async () => {
  const p = points([8,8,8,8])
  const distant = async p => { const t = await matcher(p); t.matched.forEach(m => m.lat += 0.01); return t }
  assert.equal((await displayTrack(p, distant)).matchedLinks, 0)
  const failed = await displayTrack(p, async () => { throw new Error('offline') })
  assert.equal(failed.matchedLinks, 0)
  assert.equal(failed.failures, 1)
})
test('walking only and one fast GPS spike do not imply motorcycling', () => {
  assert.ok(ridingMask(points([1,1,7,1,1])).every(v => !v))
})
test('invalid and oversized input is rejected', async () => {
  await assert.rejects(displayTrack([{ lat: 91 }]), /érvényes/)
  await assert.rejects(displayTrack(points(Array(601).fill(8))), /600/)
})
test('matched link follows the road bend rather than a straight GPS chord', async () => {
  const p = points([8,8,8])
  const curved = async p => {
    const mid = { lat: 47.00004, lon: (p[0].lon + p[1].lon) / 2 }
    return { shape: [p[0], mid, p[1], p[2]],
      edges: [{ beginShapeIndex: 0, endShapeIndex: 3 }],
      matched: p.map(a => ({ ...a, type: 'matched', edgeIndex: 0 })) }
  }
  const result = await displayTrack(p, curved)
  assert.equal(result.links[0].matched, true)
  const shape = decodePolyline(result.links[0].shape, 6)
  assert.equal(shape.length, 3)
  assert.equal(shape[1].lat, 47.00004)
})
