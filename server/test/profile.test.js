import { test } from 'node:test'
import assert from 'node:assert/strict'
import { speedProfile } from '../src/profile.js'
import { NX500 } from '../src/bike.js'

test('travel estimate accelerates from rest and brakes at intermediate shop', () => {
  const steps = Array.from({ length: 50 }, (_, i) => ({ lengthM: i ? 20 : 0 }))
  const targets = new Float64Array(50).fill(20)
  targets[25] = 0
  const result = speedProfile(steps, targets, NX500, { accelMps2: 1.5, decelMps2: 2 })
  assert.equal(result.speeds[0], 0)
  assert.equal(result.speeds[25], 0)
  assert.equal(result.speeds.at(-1), 0)
  assert.ok(result.speeds[24] < result.speeds[15])
  assert.ok(Number.isFinite(result.timeSeconds) && result.timeSeconds > 0)
})
