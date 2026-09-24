import { test } from 'node:test'
import assert from 'node:assert/strict'
process.env.MOTOR_DB = ':memory:'
const { detectObservedStops, classifyObservedStop } = await import('../src/observedstops.js')
const stop = { id:'1', lat:47, lon:17, accuracy:5, durationMs:120000, type:'OTHER' }
const road = lat => ({ tags: { highway:'residential' }, geometry: [{lat,lon:16.99},{lat,lon:17.01}] })
const shop = (lat=47, name='Tesztbolt') => ({ lat, lon:17, tags:{shop:'supermarket',name} })
test('standing in traffic beside a shop does not imply shopping', () => {
  assert.equal(classifyObservedStop(stop,[road(47),shop()]).type,'ROAD')
  assert.equal(classifyObservedStop(stop,[road(47),shop()],[{lat:47,lon:17}]).type,'SIGNAL')
})
test('off-road dwell at a clearly identifiable shop gets a shopping icon', () => {
  const result = classifyObservedStop(stop,[road(47.0004),shop()])
  assert.equal(result.type,'SHOP')
  assert.equal(result.name,'Tesztbolt')
})
test('ambiguous nearby shops and unavailable roads remain unspecified', () => {
  assert.equal(classifyObservedStop(stop,[shop()]).type,'OTHER')
  assert.equal(classifyObservedStop(stop,[road(47.0004),shop(),shop(47.00001,'Másik bolt')]).type,'PARKING')
})
test('short slowdowns are ignored, actual stops found, recording pause not bridged', () => {
  const p = [9,0,0,0,0,9].map((speedMps,i) => ({lat:47,lon:17,speedMps,accuracy:5,time:1000+i*10000,segment:0}))
  const result = detectObservedStops(p)
  assert.equal(result.length,1)
  assert.equal(result[0].durationMs,30000)
  assert.equal(detectObservedStops(p.slice(0,3)).length,0)
  p[3].segment=p[4].segment=1
  assert.equal(detectObservedStops(p).length,0)
})
