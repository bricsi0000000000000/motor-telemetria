import { test } from 'node:test'
import assert from 'node:assert/strict'
process.env.MOTOR_DB = ':memory:'
const areas = await import('../src/areas.js')
const { observedStops } = await import('../src/observedstops.js')

// Egy nagyjából 100×100 méteres négyzet és egy kisebb, beágyazott L alak.
const square = [[47.0, 17.0], [47.0, 17.0013], [47.0009, 17.0013], [47.0009, 17.0]]
const lShape = [[47.0002, 17.0002], [47.0002, 17.0006], [47.0004, 17.0006], [47.0004, 17.0004], [47.0006, 17.0004], [47.0006, 17.0002]]

test('an area is saved as a polygon with a centre and without logo bytes', () => {
  const area = areas.createArea({ name: '  Pláza  ', kind: 'SHOP', polygon: [...square, square[0]] })
  assert.equal(area.name, 'Pláza')
  assert.equal(area.polygon.length, 4, 'a lezáró pont elmarad')
  assert.equal(area.hasLogo, false)
  assert.ok(Math.abs(area.center[0] - 47.00045) < 1e-5 && Math.abs(area.center[1] - 17.00065) < 1e-5)
  areas.deleteArea(area.id)
})

test('invalid input is rejected with a readable message', () => {
  assert.throws(() => areas.createArea({ name: '', polygon: square }), /nevet/)
  assert.throws(() => areas.createArea({ name: 'x', polygon: square.slice(0, 2) }), /három pont/)
  assert.throws(() => areas.createArea({ name: 'x', kind: 'CAFE', polygon: square }), /Ismeretlen típus/)
  assert.throws(() => areas.createArea({ name: 'x', polygon: [[91, 17], [47, 17], [47, 18]] }), /Hibás koordináta/)
})

test('a concave polygon is respected, and the smallest containing area wins', () => {
  const outer = areas.createArea({ name: 'Külső', kind: 'PARKING', polygon: square })
  const inner = areas.createArea({ name: 'Belső', kind: 'SHOP', polygon: lShape })
  assert.equal(areas.areaAt(47.0003, 17.0003)?.name, 'Belső')
  // Az L alak "kivágott" sarka a külső területhez tartozik, nem a belsőhöz.
  assert.equal(areas.areaAt(47.0005, 17.0005)?.name, 'Külső')
  assert.equal(areas.areaAt(47.01, 17.01), null)
  areas.deleteArea(outer.id)
  areas.deleteArea(inner.id)
})

test('a nested area wins even when it spans the whole outer box', () => {
  // A belső sáv átlósan a külső négyzet sarkától sarkáig ér: a befoglaló
  // téglalapjuk ugyanaz, a valódi területük viszont nagyon különböző.
  const outer = areas.createArea({ name: 'Pláza', kind: 'SHOP', polygon: square })
  const band = areas.createArea({ name: 'Sáv', kind: 'PLACE', polygon: [
    [47.0, 17.0], [47.0, 17.0002], [47.0009, 17.0013], [47.0009, 17.0011]] })
  assert.equal(areas.areaAt(47.00045, 17.00065)?.name, 'Sáv')
  assert.equal(areas.areaAt(47.0008, 17.0002)?.name, 'Pláza')
  areas.deleteArea(outer.id)
  areas.deleteArea(band.id)
})

test('logos: only supported images, size limit, clearing', () => {
  const area = areas.createArea({ name: 'Logós', polygon: square })
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 1, 2, 3])
  assert.equal(areas.setLogo(area.id, png, 'image/png').hasLogo, true)
  assert.deepEqual(areas.getLogo(area.id).bytes, png)
  assert.throws(() => areas.setLogo(area.id, png, 'image/svg+xml'), /PNG, JPEG vagy WebP/)
  assert.throws(() => areas.setLogo(area.id, Buffer.alloc(areas.LOGO_MAX_BYTES + 1), 'image/png'), /512 KB/)
  assert.equal(areas.clearLogo(area.id).hasLogo, false)
  assert.throws(() => areas.getLogo(area.id), /nincs logója/)
  areas.deleteArea(area.id)
})

// Pontok ötmásodpercenként egy helyen, adott sebességgel (a felvétel ennyivel lép, ha lassú).
const stay = (from, to, [lat, lon], speedMps = 0) => {
  const out = []
  for (let t = from; t <= to; t += 5000) out.push({ lat, lon, speedMps, accuracy: 6, time: t, segment: 0 })
  return out
}
const OUT = [47.003, 17.0], IN_OUTER = [47.0002, 17.0002], IN_INNER = [47.0007, 17.001]
const innerBox = [[47.0006, 17.0009], [47.0006, 17.0012], [47.0008, 17.0012], [47.0008, 17.0009]]
const plaza = { id: 1, name: 'Pláza', kind: 'SHOP', polygon: square }
const shop = { id: 2, name: 'Bolt', kind: 'SHOP', polygon: innerBox }

test('stepping into an inner area leaves the outer one, and each area gets its summed time', () => {
  const points = [
    ...stay(0, 5000, OUT, 8),
    ...stay(10000, 60000, IN_OUTER, 0),          // leparkol a plázában
    ...stay(65000, 300000, IN_OUTER, 1.3),       // sétál
    // 300 és 420 mp között nincs GPS (épület): a pláza számol tovább.
    ...stay(420000, 540000, IN_INNER, 0),        // a boltban áll
    ...stay(545000, 655000, IN_INNER, 1.0),
    ...stay(660000, 840000, IN_OUTER, 1.3),      // vissza a plázába
    ...stay(845000, 860000, OUT, 8)
  ]
  const visits = areas.findVisits(points, [plaza, shop])
  assert.deepEqual(visits.map((v) => [v.name, v.durationMs / 1000]), [['Pláza', 410 + 185], ['Bolt', 240]])
})

test('leaving and coming back is summed into one entry', () => {
  const points = [...stay(0, 120000, IN_OUTER, 0), ...stay(125000, 400000, OUT, 9), ...stay(405000, 525000, IN_OUTER, 1)]
  const visits = areas.findVisits(points, [plaza])
  assert.equal(visits.length, 1)
  assert.equal(visits[0].durationMs, 125000 + 120000)
})

test('riding through without stopping, or a moment inside, is not a visit', () => {
  assert.equal(areas.findVisits([...stay(0, 90000, IN_OUTER, 6), ...stay(95000, 100000, OUT, 8)], [plaza]).length, 0)
  assert.equal(areas.findVisits([...stay(0, 20000, IN_OUTER, 0), ...stay(25000, 60000, OUT, 8)], [plaza]).length, 0)
})

test('inaccurate indoor fixes do not move you out of the area', () => {
  const lost = stay(100000, 200000, OUT, 0).map((p) => ({ ...p, accuracy: 80 }))
  const visits = areas.findVisits([...stay(0, 95000, IN_OUTER, 0), ...lost, ...stay(205000, 300000, IN_OUTER, 1), ...stay(305000, 310000, OUT, 8)], [plaza])
  assert.equal(visits[0].durationMs, 305000)
})

test('a shop visit replaces the separate stops, a traffic area only renames them', async () => {
  const drawnShop = areas.createArea({ name: 'Saját bolt', kind: 'SHOP', polygon: square })
  const walkAround = [
    ...stay(0, 5000, OUT, 8), ...stay(10000, 40000, IN_OUTER, 0), ...stay(45000, 90000, IN_OUTER, 1.4),
    ...stay(95000, 130000, IN_OUTER, 0), ...stay(135000, 150000, OUT, 8)
  ]
  const { stops } = await observedStops(walkAround)
  assert.deepEqual(stops.map((s) => [s.name, s.durationMs / 1000]), [['Saját bolt', 125]])
  areas.deleteArea(drawnShop.id)

  const junction = areas.createArea({ name: 'Kereszteződés', kind: 'SIGNAL', polygon: square })
  const { stops: atLight } = await observedStops([...stay(0, 5000, OUT, 8), ...stay(10000, 20000, IN_OUTER, 0), ...stay(25000, 30000, OUT, 8)])
  assert.deepEqual(atLight.map((s) => [s.name, s.type]), [['Kereszteződés', 'SIGNAL']])
  areas.deleteArea(junction.id)
})
