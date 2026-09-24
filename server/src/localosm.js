import { execFile } from 'node:child_process'
import { existsSync, readdirSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const run = promisify(execFile)
const here = dirname(fileURLToPath(import.meta.url))

/**
 * Lámpák és fix mérők kinyerése a HELYI OSM kivonatból.
 *
 * Miért nem az Overpassból, ahogy a roadpoints.js csinálja: a nyilvános
 * Overpass kiszolgálók egy megyényi lekérdezést órákig nem tudtak teljesíteni
 * (a réteg "running" állapotban ragadt), és minden szerverújraindítás elölről
 * kezdette. A Valhalla miatt viszont amúgy is ott van a gépen a teljes magyar
 * kivonat - abból ugyanez az adat 2-3 másodperc alatt kijön, pontosabban
 * (országos, nem csak a megye) és külső szolgáltatás nélkül.
 *
 * Az `osmium` egy önálló eszköz (brew install osmium-tool). Ha nincs meg, a
 * hívó visszaeshet az Overpassra - ezért dob beszédes hibát.
 */

const OSMIUM = process.env.OSMIUM_BIN || '/opt/homebrew/bin/osmium'

/** A Valhalla ide tölti a kivonatot; a konténer frissítésekor magától újul. */
const DEFAULT_PBF_DIR = join(here, '..', 'valhalla', 'custom_files')

export function findExtract() {
  if (process.env.OSM_PBF) return existsSync(process.env.OSM_PBF) ? process.env.OSM_PBF : null
  if (!existsSync(DEFAULT_PBF_DIR)) return null
  const pbf = readdirSync(DEFAULT_PBF_DIR).find((name) => name.endsWith('.osm.pbf'))
  return pbf ? join(DEFAULT_PBF_DIR, pbf) : null
}

export function osmiumAvailable() {
  return existsSync(OSMIUM)
}

/** Az OSM tagekből a road_points tábla `kind` értéke. */
function kindOf(tags) {
  if (tags.highway === 'speed_camera' || tags.enforcement === 'maxspeed') return 'SPEED_CAMERA'
  if (tags.highway === 'traffic_signals') {
    // A gyalogátkelő lámpája nem kereszteződés: az útvonaltervezésnél nem
    // ugyanaz a súlya, és a roadpoints.js is kiszűri.
    if (tags.crossing || tags['traffic_signals'] === 'pedestrian_crossing') return null
    return 'TRAFFIC_SIGNALS'
  }
  return null
}

/**
 * A kivonatból kinyert pontok. A visszaadott alak megegyezik azzal, amit a
 * roadpoints.js `replaceSource()`-a vár.
 */
export async function extractPoints() {
  const pbf = findExtract()
  if (!pbf) throw new Error('Nincs helyi OSM kivonat (server/valhalla/custom_files/*.osm.pbf).')
  if (!osmiumAvailable()) throw new Error(`Az osmium nem található (${OSMIUM}). Telepítés: brew install osmium-tool`)

  const work = await mkdtemp(join(tmpdir(), 'motor-osm-'))
  try {
    const filtered = join(work, 'points.osm.pbf')
    await run(OSMIUM, [
      'tags-filter', pbf,
      'n/highway=traffic_signals',
      'n/highway=speed_camera',
      'n/enforcement=maxspeed',
      '-o', filtered, '--overwrite'
    ], { maxBuffer: 1 << 26 })

    const geojson = join(work, 'points.geojsonl')
    await run(OSMIUM, [
      'export', filtered,
      '-f', 'geojsonseq',
      '--geometry-types=point',
      '--add-unique-id=type_id',
      '-o', geojson, '--overwrite'
    ], { maxBuffer: 1 << 26 })

    const now = Date.now()
    const points = []
    for (const line of (await readFile(geojson, 'utf8')).split('\n')) {
      const trimmed = line.trim().replace(/^\x1e/, '')
      if (!trimmed) continue
      let feature
      try {
        feature = JSON.parse(trimmed)
      } catch {
        continue
      }
      const tags = feature.properties ?? {}
      const kind = kindOf(tags)
      if (!kind) continue
      const [lon, lat] = feature.geometry?.coordinates ?? []
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue

      points.push({
        source: 'osm',
        externalId: `osm-${feature.id ?? `${lat},${lon}`}`,
        kind,
        lat,
        lon,
        road: tags.name ?? tags.ref ?? null,
        description: kind === 'SPEED_CAMERA' ? 'Fix mérő (OSM)' : 'Jelzőlámpa (OSM)',
        speedLimit: Number.parseInt(tags.maxspeed, 10) || null,
        reportedAt: null,
        fetchedAt: now,
        // Az úthálózat része, nem múló bejelentés: nem jár le.
        expiresAt: null
      })
    }
    return points
  } finally {
    await rm(work, { recursive: true, force: true })
  }
}
