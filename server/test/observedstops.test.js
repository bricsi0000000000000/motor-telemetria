import { test } from 'node:test'
import assert from 'node:assert/strict'
process.env.MOTOR_DB = ':memory:'
const { detectObservedStops, classifyObservedStop, shopBrand } = await import('../src/observedstops.js')
const stop = { id:'1', lat:47, lon:17, accuracy:5, durationMs:120000, type:'OTHER' }
const road = lat => ({ tags: { highway:'residential' }, geometry: [{lat,lon:16.99},{lat,lon:17.01}] })
const shop = (name='Lidl', lat=47, extra={}) => ({ lat, lon:17, tags:{shop:'supermarket',name,...extra} })
test('standing on the road is traffic, and a traffic light nearby explains it', () => {
  assert.equal(classifyObservedStop(stop,[road(47),shop()]).type,'ROAD')
  assert.equal(classifyObservedStop(stop,[road(47),shop()],[{lat:47,lon:17}]).type,'SIGNAL')
})
test('a traffic light from the overpass answer counts as well', () => {
  const light = { lat:47.0002, lon:17, tags:{highway:'traffic_signals'} }
  assert.equal(classifyObservedStop(stop,[road(47),light]).type,'SIGNAL')
})
test('only the requested chains, malls and OBI count as a shop', () => {
  for (const [name, brand] of [['Lidl','lidl'],['SPAR Market','spar'],['INTERSPAR','spar'],['Aldi','aldi'],
    ['PENNY','penny'],['OBI Barkácsáruház','obi'],['Árkád Győr','arkad'],['ETO Park','etopark']]) {
    assert.equal(shopBrand({shop:'supermarket',name}),brand,name)
  }
  assert.equal(shopBrand({shop:'mall',name:'Győr Plaza'}),'mall')
  // Nem támogatott lánc és a névbe ágyazott találat nem bolt.
  for (const name of ['Auchan','Tesco','Mobil Pont','Takarékspar']) {
    assert.equal(shopBrand({shop:'supermarket',name}),null,name)
  }
})
test('off-road dwell at a recognised chain gets a shopping icon', () => {
  const result = classifyObservedStop(stop,[road(47.0004),shop('Lidl')])
  assert.equal(result.type,'SHOP')
  assert.equal(result.brand,'lidl')
  assert.equal(result.name,'Lidl')
})
test('two chains side by side stay a shop, but lose the brand', () => {
  const result = classifyObservedStop(stop,[road(47.0004),shop('Lidl'),shop('Aldi',47.00001)])
  assert.equal(result.type,'SHOP')
  assert.equal(result.brand,null)
  assert.equal(result.name,null)
})
test('unsupported shops, missing roads and short stops stay unspecified', () => {
  assert.equal(classifyObservedStop(stop,[road(47.0004),shop('Auchan')]).type,'PARKING')
  assert.equal(classifyObservedStop(stop,[shop()]).type,'OTHER')
  assert.equal(classifyObservedStop({...stop,durationMs:5000},[road(47.0004),shop()]).type,'PARKING')
})
test('a one second standstill counts, crawling does not, recording pause not bridged', () => {
  const p = [9,0,0,0,0,9].map((speedMps,i) => ({lat:47,lon:17,speedMps,accuracy:5,time:1000+i*10000,segment:0}))
  const result = detectObservedStops(p)
  assert.equal(result.length,1)
  assert.equal(result[0].durationMs,30000)
  const brief = [9,0,0,9].map((speedMps,i) => ({lat:47,lon:17,speedMps,accuracy:5,time:1000+i*1000,segment:0}))
  assert.equal(detectObservedStops(brief)[0].durationMs,1000)
  const crawl = [9,2,2,2,9].map((speedMps,i) => ({lat:47,lon:17,speedMps,accuracy:5,time:1000+i*10000,segment:0}))
  assert.equal(detectObservedStops(crawl).length,0)
  p[3].segment=p[4].segment=1
  assert.deepEqual(detectObservedStops(p).map(s => s.durationMs),[10000,10000])
})
