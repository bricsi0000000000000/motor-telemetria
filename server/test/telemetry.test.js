import { test } from 'node:test'
import assert from 'node:assert/strict'
process.env.MOTOR_DB = ':memory:'

const { analyzeTelemetry } = await import('../src/analysis.js')
const { db, one, run } = await import('../src/db.js')
const { directionBucket, normalizedRoughness, stableRoadQuality } = await import('../src/roadquality.js')

const points = [
  { time: 1000, lat: 47, lon: 17 },
  { time: 2000, lat: 47.0001, lon: 17.0001 },
  { time: 3000, lat: 47.0002, lon: 17.0002 }
]

test('telemetry analysis excludes weak mount data from events', () => {
  const samples = [
    { time: 1000, lat: 47, lon: 17, speedMps: 10, mountQuality: 0.9,
      forwardMinMps2: -4.2, forwardMaxMps2: 1, fusedAltitudeMeters: 100,
      verticalPeakMps2: 2, yawPeakRadS: 0.1, leanDegrees: 20, flags: 4 },
    { time: 2000, lat: 47.0001, lon: 17.0001, speedMps: 11, mountQuality: 0.9,
      forwardMinMps2: -1, forwardMaxMps2: 3.8, fusedAltitudeMeters: 102,
      verticalPeakMps2: 8, yawPeakRadS: 0.2, leanDegrees: -28, flags: 8 | 16 },
    { time: 3000, lat: 47.0002, lon: 17.0002, speedMps: 0, mountQuality: 0.3,
      forwardMinMps2: -20, forwardMaxMps2: 20, fusedAltitudeMeters: 120,
      verticalPeakMps2: 30, yawPeakRadS: 4, leanDegrees: 80, flags: 32 }
  ]
  const result = analyzeTelemetry(points, samples)
  assert.equal(result.summary.validSampleCount, 2)
  assert.equal(result.braking.length, 1)
  assert.equal(result.acceleration.length, 1)
  assert.equal(result.roughness.length, 1)
  assert.equal(result.incidents.length, 0, 'gyenge tartóminőség nem ad eseményt')
  assert.equal(result.summary.maxLeanDegrees, 28)
})

test('shadow incident needs impact and rotation, or speed loss followed by stillness', () => {
  const timeline = Array.from({ length: 8 }, (_, index) => ({
    time: 1000 + index * 1000, lat: 47, lon: 17, speedMps: index < 2 ? 15 : 0,
    mountQuality: 0.9, forwardMinMps2: -1, forwardMaxMps2: 1,
    fusedAltitudeMeters: 100, verticalRmsMps2: index < 2 ? 2 : 0.1,
    verticalPeakMps2: index === 1 ? 25 : 0.2, yawPeakRadS: 0.1,
    rollPeakRadS: 0.1, leanDegrees: 0, flags: index === 1 ? 32 : 0
  }))
  const result = analyzeTelemetry(points, timeline)
  assert.equal(result.incidents.length, 1)

  timeline[1] = { ...timeline[1], verticalPeakMps2: 1, yawPeakRadS: 4 }
  const rotationWithStop = analyzeTelemetry(points, timeline)
  assert.equal(rotationWithStop.incidents.length, 1)

  const isolated = analyzeTelemetry(points, [timeline[1]])
  assert.equal(isolated.incidents.length, 0)
})

test('telemetry table is idempotent by track and sequence', () => {
  const now = Date.now()
  run(`INSERT INTO tracks
       (device_uid, client_id, start_time, created_at, updated_at)
       VALUES ('test', 1, ?, ?, ?)`, now, now, now)
  const track = one("SELECT id FROM tracks WHERE device_uid = 'test'")
  const insert = db.prepare(`INSERT OR IGNORE INTO telemetry_samples
    (track_id, seq, time, speed_mps, forward_mean_mps2, forward_min_mps2,
     forward_max_mps2, lateral_mean_mps2, lateral_rms_mps2, lateral_peak_mps2,
     vertical_rms_mps2, vertical_peak_mps2, yaw_peak_rads, roll_peak_rads,
     mount_quality, sample_count, flags)
    VALUES (?, 0, ?, 10, 0, -1, 1, 0, 0, 0, 0, 0, 0, 0, .9, 50, 0)`)
  insert.run(track.id, now)
  insert.run(track.id, now)
  assert.equal(one('SELECT COUNT(*) AS count FROM telemetry_samples WHERE track_id = ?', track.id).count, 1)
})

test('road quality is direction-aware, speed-normalized and needs three rides', () => {
  const now = Date.now()
  const insertTrack = db.prepare(`INSERT INTO tracks
    (device_uid, client_id, start_time, created_at, updated_at)
    VALUES ('road-test', ?, ?, ?, ?)`)
  const insertSample = db.prepare(`INSERT INTO telemetry_samples
    (track_id, seq, time, lat, lon, matched_lat, matched_lon,
     speed_mps, bearing_deg, road_bearing_deg,
     vertical_rms_mps2, vertical_peak_mps2, mount_quality)
    VALUES (?, 0, ?, 47.1, 17.2, 47.1, 17.2, ?, ?, ?, ?, ?, .9)`)
  for (let ride = 1; ride <= 3; ride++) {
    const trackId = Number(insertTrack.run(100 + ride, now, now, now).lastInsertRowid)
    const speed = ride === 1 ? 10 : 20
    const raw = ride === 1 ? 2 : 2 * Math.sqrt(2)
    insertSample.run(trackId, now + ride, speed, 91, 91, raw, raw * 2)
  }
  // Ellentétes irányból egyetlen minta nem stabilizálja a másik sávot.
  const oppositeTrack = Number(insertTrack.run(200, now, now, now).lastInsertRowid)
  insertSample.run(oppositeTrack, now, 15, 270, 270, 20, 30)

  const cells = stableRoadQuality({ south: 47, west: 17, north: 48, east: 18 })
  assert.equal(cells.length, 1)
  assert.equal(cells[0].direction, 90)
  assert.ok(cells[0].roughness > 2 && cells[0].roughness < 3)
  assert.equal(directionBucket(359), 0)
  assert.ok(normalizedRoughness(2, 10) > normalizedRoughness(2, 20))
})
