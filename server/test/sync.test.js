import { test, after } from 'node:test'
import assert from 'node:assert/strict'
import express from 'express'

process.env.MOTOR_DB = ':memory:'
const { syncRouter } = await import('../src/routes/sync.js')
const { one } = await import('../src/db.js')

const app = express()
app.use(express.json())
app.use('/api', syncRouter)
const server = await new Promise((resolve) => {
  const listener = app.listen(0, '127.0.0.1', () => resolve(listener))
})
after(() => server.close())
const url = `http://127.0.0.1:${server.address().port}/api/sync`

async function sync(body) {
  const response = await fetch(url, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body)
  })
  assert.equal(response.status, 200)
  return response.json()
}

test('telemetry sync is partial and idempotent', async () => {
  const base = {
    deviceUid: 'sync-test',
    tracks: [{
      clientId: 77, startTime: 1000, telemetryVersion: 1, telemetrySampleCount: 2,
      points: [], telemetrySamples: [{
        seq: 0, time: 1000, lat: 47, lon: 17, speedMps: 12, bearingDegrees: 90,
        pressureHpa: 1000, fusedAltitudeMeters: 120,
        forwardMeanMps2: 0.2, forwardMinMps2: -1, forwardMaxMps2: 1,
        lateralMeanMps2: 0, lateralRmsMps2: 0.3, lateralPeakMps2: 0.5,
        verticalRmsMps2: 0.4, verticalPeakMps2: 1,
        yawPeakRadS: 0.1, rollPeakRadS: 0.1, leanDegrees: 12,
        mountQuality: 0.9, sampleCount: 50, flags: 0
      }]
    }]
  }
  const first = await sync(base)
  assert.equal(first.results[0].storedTelemetrySamples, 1)
  const repeated = await sync(base)
  assert.equal(repeated.results[0].storedTelemetrySamples, 1)

  base.tracks[0].telemetrySamples = [{ ...base.tracks[0].telemetrySamples[0], seq: 1, time: 2000 }]
  const second = await sync(base)
  assert.equal(second.results[0].storedTelemetrySamples, 2)
  assert.equal(one('SELECT bearing_deg AS bearing FROM telemetry_samples WHERE seq = 0').bearing, 90)
})
