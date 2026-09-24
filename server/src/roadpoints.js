import { all, run, tx } from './db.js'
import { AREA_BBOX, bboxString, haversine, limitFor, overpass, tiles } from './overpass.js'
import { extractPoints, findExtract, osmiumAvailable } from './localosm.js'

/**
 * Útmenti adatok a térképhez: fix sebességmérők, lámpás kereszteződések,
 * sebességhatárok (mind ingyenes OSM-adat), valamint a Waze-től jövő
 * rendőr/baleset bejelentések (ez fogyasztja a havi keretet).
 */

/**
 * Meddig maradjon a térképen egy bejelentés. A rendőr hamar továbbáll, egy
 * lezárt út viszont napokig az marad – ezért típusonként más az élettartam.
 */
const ALERT_TTL_MS = {
  POLICE: 2 * 60 * 60 * 1000,
  ACCIDENT: 4 * 60 * 60 * 1000,
  SPEED_CAMERA: 24 * 60 * 60 * 1000,
  OTHER: 12 * 60 * 60 * 1000
}

/** Két kézi frissítés között ennyi időnek el kell telnie (dupla koppintás védelme). */
const MIN_REFRESH_INTERVAL_MS = 60_000

/** A Waze ingyenes csomagjának havi kerete. */
const MONTHLY_LIMIT = Number(process.env.WAZE_MONTHLY_LIMIT || 100)

/** A geometriát ennél közelebbi pontokra nem bontjuk – kisebb válasz, ugyanaz a vonal. */
const SIMPLIFY_M = 10

/** Ekkora (fokban mért) csempékre bontjuk a területet a lekérdezéshez. */
const SPEED_TILE_DEGREES = 0.15

/** A pontok (mérők, lámpák) könnyebb lekérdezés, ott nagyobb csempe is elég. */
const POINT_TILE_DEGREES = 0.3

let lastFullRefreshAt = 0

/** Fut-e épp az úthálózat (lassú) frissítése. */
let speedRefreshRunning = false

/** Ennél régebbi úthálózatot frissítünk – a sebességhatárok ritkán változnak. */
const SPEED_LAYER_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000

const monthKey = () => new Date().toISOString().slice(0, 7)

/**
 * Ha a lefedett terület megváltozik (más bbox), a korábbi adat félrevezető
 * lenne: a régi területről ottmaradt mérők és utak nem tartoznak ide.
 * Ezért induláskor összevetjük, és eltérésnél tiszta lappal indulunk.
 */
function resetOnAreaChange() {
  const current = bboxString(AREA_BBOX)
  const stored = all("SELECT value FROM settings WHERE key = 'area'")[0]?.value
  if (stored === current) return

  console.log(`Lefedett terület változott (${stored ?? 'nincs'} → ${current}), rétegek ürítése`)
  tx(() => {
    run('DELETE FROM speed_segments')
    run('DELETE FROM road_points')
    run('DELETE FROM layer_state')
    run(
      `INSERT INTO settings (key, value) VALUES ('area', ?)
       ON CONFLICT (key) DO UPDATE SET value = excluded.value`,
      current
    )
  })
}

resetOnAreaChange()

// --- rétegek állapota (mikor frissült, sikerült-e) ----------------------------

/** A rétegek, amiket külön-külön követünk és külön-külön is frissülhetnek. */
export const LAYERS = ['cameras', 'signals', 'speedlimits', 'waze']

function markLayer(layer, { status, count, error = null, touch = false }) {
  const previous = all('SELECT updated_at AS updatedAt, count FROM layer_state WHERE layer = ?', layer)[0]
  run(
    `INSERT INTO layer_state (layer, updated_at, count, status, error) VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (layer) DO UPDATE SET updated_at = excluded.updated_at,
                                       count = excluded.count,
                                       status = excluded.status,
                                       error = excluded.error`,
    layer,
    // Csak sikeres frissítésnél lép előre az időbélyeg: így látszik, ha egy
    // réteg napok óta nem tudott megújulni.
    touch ? Date.now() : previous?.updatedAt ?? 0,
    count ?? previous?.count ?? 0,
    status,
    error
  )
}

export function layerStates() {
  const rows = all('SELECT layer, updated_at AS updatedAt, count, status, error FROM layer_state')
  const states = {}
  for (const layer of LAYERS) {
    const row = rows.find((item) => item.layer === layer)
    states[layer] = {
      updatedAt: row?.updatedAt ?? 0,
      count: row?.count ?? 0,
      status: row?.status ?? 'idle',
      error: row?.error ?? null
    }
  }
  return states
}

const running = new Set()

/** Egy réteg frissítése a háttérben – a telefon soha nem vár rá. */
function startLayer(layer, worker) {
  if (running.has(layer)) return false
  running.add(layer)
  markLayer(layer, { status: 'running' })

  worker()
    .then((count) => markLayer(layer, { status: 'idle', count, touch: true }))
    .catch((error) => {
      console.warn(`A(z) ${layer} réteg frissítése nem sikerült:`, error.message)
      markLayer(layer, { status: 'error', error: error.message })
    })
    .finally(() => running.delete(layer))

  return true
}

// --- ingyenes réteg: OSM ------------------------------------------------------

/**
 * Fix mérők és lámpás kereszteződések a győri területről.
 * A gyalogosátkelő-lámpákat kiszűrjük: minket a kereszteződések érdekelnek.
 */
export async function refreshOsmPoints() {
  const now = Date.now()

  // Elsődlegesen a HELYI OSM kivonatból dolgozunk: a Valhalla miatt amúgy is
  // ott van a gépen, és 2-3 másodperc alatt kiadja az ország összes lámpáját.
  // A nyilvános Overpass ugyanezt egy megyére órákig nem tudta teljesíteni.
  if (osmiumAvailable() && findExtract()) {
    try {
      const local = await extractPoints()
      if (local.length > 0) {
        replaceSource('osm', local, now)
        const cameras = local.filter((point) => point.kind === 'SPEED_CAMERA').length
        const signals = local.filter((point) => point.kind === 'TRAFFIC_SIGNALS').length
        markLayer('cameras', { status: 'idle', count: cameras, touch: true })
        markLayer('signals', { status: 'idle', count: signals, touch: true })
        return { cameras, signals, points: local, source: 'helyi kivonat' }
      }
    } catch (error) {
      console.warn('A helyi OSM kivonat nem használható, marad az Overpass:', error.message)
    }
  }

  const points = []
  const elements = []

  // Megyényi területen a nyilvános Overpass egyben elszáll, ezért 3×3 csempe.
  // Ha egy csempe nem jön össze, a többi eredménye akkor is megmarad.
  let failed = 0
  for (const tile of tiles(AREA_BBOX, POINT_TILE_DEGREES)) {
    const bbox = bboxString(tile)
    try {
      const response = await overpass(`[out:json][timeout:120];
(
  node["highway"="speed_camera"](${bbox});
  node["enforcement"="maxspeed"](${bbox});
  node["highway"="traffic_signals"](${bbox});
);
out body;`)
      elements.push(...(response.elements ?? []))
    } catch (error) {
      failed++
      console.warn(`Pontok, ${tile.label} csempe: ${error.message}`)
    }
  }
  if (elements.length === 0 && failed > 0) {
    throw new Error(`egyetlen csempe sem jött össze (${failed} hiba)`)
  }

  for (const element of elements) {
    if (element.type !== 'node') continue
    const tags = element.tags ?? {}

    const isCamera = tags.highway === 'speed_camera' || tags.enforcement === 'maxspeed'
    const isSignal = tags.highway === 'traffic_signals'
    // A gyalogosátkelő lámpája nem kereszteződés.
    const isCrossing = Boolean(tags.crossing) || tags.highway === 'crossing'
    if (!isCamera && (!isSignal || isCrossing)) continue

    points.push({
      source: 'osm',
      externalId: `osm-node-${element.id}`,
      kind: isCamera ? 'SPEED_CAMERA' : 'TRAFFIC_SIGNALS',
      lat: element.lat,
      lon: element.lon,
      road: tags.name ?? tags.ref ?? null,
      description: isCamera ? (tags.description ?? null) : null,
      speedLimit: limitFor(tags)?.guessed === false ? limitFor(tags).limit : null,
      reportedAt: null,
      expiresAt: null
    })
  }

  replaceSource('osm', points, now)

  const cameras = points.filter((point) => point.kind === 'SPEED_CAMERA').length
  const signals = points.filter((point) => point.kind === 'TRAFFIC_SIGNALS').length
  markLayer('cameras', { status: 'idle', count: cameras, touch: true })
  markLayer('signals', { status: 'idle', count: signals, touch: true })
  return { cameras, signals, points }
}

/**
 * A győri úthálózat sebességhatárokkal – ebből lesz a színezett térképréteg.
 *
 * A várost négy negyedre bontva kérdezzük le: egyben a nyilvános Overpass
 * kiszolgálók időtúllépéssel elszállnak. A `service` utakat (parkolósorok,
 * behajtók) kihagyjuk – abból van a legtöbb, és motorral úgysem érdekesek.
 */
export async function refreshSpeedSegments(onProgress = null) {
  const now = Date.now()
  let total = 0

  // Csempénként kérjük le ÉS mentjük: egy megyényi terület így is fél óra, de
  // közben már használható, ami eddig megvan, és egy hiba sem visz el mindent.
  const grid = tiles(AREA_BBOX, SPEED_TILE_DEGREES)

  // Ami nem a mostani rácshoz tartozik (régi csempeméret vagy terület), az
  // félrevezető lenne – kitakarítjuk, mielőtt újratöltünk.
  const labels = grid.map((tile) => tile.label)
  run(
    `DELETE FROM speed_segments WHERE tile IS NULL OR tile NOT IN (${labels.map(() => '?').join(',')})`,
    ...labels
  )
  const failedTiles = []

  for (const [index, tile] of grid.entries()) {
    const bbox = bboxString(tile)
    let response
    try {
      response = await overpass(`[out:json][timeout:120];
way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$"](${bbox});
out tags geom;`)
    } catch (error) {
      // Egy elszállt csempe nem viheti el az egészet: megjegyezzük, és a végén
      // újrapróbáljuk. Ami már megvan, az addig is használható.
      failedTiles.push(tile)
      console.warn(`Úthálózat, ${tile.label} csempe: ${error.message}`)
      onProgress?.(index + 1, grid.length, total)
      continue
    }

    const segments = []
    for (const way of response.elements ?? []) {
      const limit = limitFor(way.tags ?? {})
      if (!limit) continue
      const geometry = simplify(way.geometry ?? [])
      if (geometry.length < 2) continue

      segments.push({
        limitKmh: limit.limit,
        guessed: limit.guessed ? 1 : 0,
        road: way.tags?.name ?? way.tags?.ref ?? null,
        geometry,
        minLat: Math.min(...geometry.map((point) => point[0])),
        maxLat: Math.max(...geometry.map((point) => point[0])),
        minLon: Math.min(...geometry.map((point) => point[1])),
        maxLon: Math.max(...geometry.map((point) => point[1]))
      })
    }

    tx(() => {
      run('DELETE FROM speed_segments WHERE tile = ?', tile.label)
      for (const segment of segments) {
        run(
          `INSERT INTO speed_segments
             (limit_kmh, guessed, road, geometry, min_lat, max_lat, min_lon, max_lon, tile, fetched_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          segment.limitKmh,
          segment.guessed,
          segment.road,
          JSON.stringify(segment.geometry),
          segment.minLat,
          segment.maxLat,
          segment.minLon,
          segment.maxLon,
          tile.label,
          now
        )
      }
    })

    total += segments.length
    onProgress?.(index + 1, grid.length, total)
  }

  if (failedTiles.length > 0) {
    console.log(`Újrapróbálom a kimaradt ${failedTiles.length} csempét…`)
    for (const tile of failedTiles) {
      try {
        total += await refreshTile(tile, now)
      } catch (error) {
        console.warn(`Úthálózat, ${tile.label} csempe másodszor sem jött össze: ${error.message}`)
      }
    }
  }
  return total
}

/** Egyetlen csempe lekérése és mentése – az újrapróbáláshoz. */
async function refreshTile(tile, now) {
  const response = await overpass(`[out:json][timeout:120];
way["highway"~"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$"](${bboxString(tile)});
out tags geom;`)

  const segments = []
  for (const way of response.elements ?? []) {
    const limit = limitFor(way.tags ?? {})
    if (!limit) continue
    const geometry = simplify(way.geometry ?? [])
    if (geometry.length < 2) continue
    segments.push({ limit, geometry, road: way.tags?.name ?? way.tags?.ref ?? null })
  }

  tx(() => {
    run('DELETE FROM speed_segments WHERE tile = ?', tile.label)
    for (const segment of segments) {
      const lats = segment.geometry.map((point) => point[0])
      const lons = segment.geometry.map((point) => point[1])
      run(
        `INSERT INTO speed_segments
           (limit_kmh, guessed, road, geometry, min_lat, max_lat, min_lon, max_lon, tile, fetched_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        segment.limit.limit,
        segment.limit.guessed ? 1 : 0,
        segment.road,
        JSON.stringify(segment.geometry),
        Math.min(...lats),
        Math.max(...lats),
        Math.min(...lons),
        Math.max(...lons),
        tile.label,
        now
      )
    }
  })
  return segments.length
}

/**
 * Az úthálózat lekérése percekig tart (negyedenként ~1 perc), ezért soha nem
 * várakoztatjuk vele a telefont: háttérben fut, és a következő megnyitásnál
 * már kész. Az időbélyegből látszik, mennyire friss.
 */
export function ensureSpeedSegments({ force = false } = {}) {
  const state = layerStates().speedlimits
  const stale = state.count === 0 || Date.now() - state.updatedAt > SPEED_LAYER_MAX_AGE_MS
  if (!force && !stale) return false

  return startLayer('speedlimits', async () => {
    const count = await refreshSpeedSegments((done, all, sofar) => {
      // Menet közben is látszódjon a haladás a telefonon.
      markLayer('speedlimits', { status: `running ${done}/${all}`, count: sofar })
    })
    enrichCameraLimits()
    return count
  })
}

/** Ritkítás: 10 méternél közelebbi pontok elhagyása, 5 tizedesre kerekítve. */
function simplify(geometry) {
  const result = []
  let lastLat = null
  let lastLon = null
  for (const point of geometry) {
    if (
      lastLat === null ||
      haversine(lastLat, lastLon, point.lat, point.lon) >= SIMPLIFY_M
    ) {
      result.push([Number(point.lat.toFixed(5)), Number(point.lon.toFixed(5))])
      lastLat = point.lat
      lastLon = point.lon
    }
  }
  const last = geometry[geometry.length - 1]
  if (last && (result.length === 0 || result[result.length - 1][0] !== Number(last.lat.toFixed(5)))) {
    result.push([Number(last.lat.toFixed(5)), Number(last.lon.toFixed(5))])
  }
  return result
}

/**
 * Ahol a mérőnél nincs kiírt sebességhatár, ott a legközelebbi útszakaszéval
 * egészítjük ki – ugyanaz az adat, amit a túraelemzés is használ.
 */
export function enrichCameraLimits() {
  const cameras = all(
    "SELECT id, lat, lon FROM road_points WHERE kind = 'SPEED_CAMERA' AND speed_limit IS NULL"
  )
  if (cameras.length === 0) return

  const segments = all('SELECT limit_kmh AS limitKmh, geometry FROM speed_segments')
      .map((row) => ({ limitKmh: row.limitKmh, points: JSON.parse(row.geometry) }))

  for (const camera of cameras) {
    let best = null
    let bestDistance = 40
    for (const segment of segments) {
      for (const [lat, lon] of segment.points) {
        const distance = haversine(camera.lat, camera.lon, lat, lon)
        if (distance < bestDistance) {
          bestDistance = distance
          best = segment.limitKmh
        }
      }
    }
    if (best) run('UPDATE road_points SET speed_limit = ? WHERE id = ?', best, camera.id)
  }
}

// --- fizetős réteg: Waze ------------------------------------------------------

/**
 * Alapértelmezett szolgáltató: wazeapi.com (ingyenes csomag havi 100 hívással).
 * Csak a kulcs kell hozzá; a végpont és a fejlécek innen jönnek. Más
 * szolgáltatóra a WAZE_API_URL / WAZE_KEY_HEADER változókkal lehet váltani.
 */
const WAZE_DEFAULT_URL =
  'https://api.wazeapi.com/v1/alerts?bottom-left={south},{west}&top-right={north},{east}' +
  // A szolgáltató 200 elemnél levágja a választ, a dugók (JAM) pedig könnyen
  // kiszorítanák a fontosat – ezért csak a minket érdeklő típusokat kérjük.
  `&filter=${encodeURIComponent('["POLICE","ACCIDENT","ROAD_CLOSED","HAZARD"]')}`

export const wazeConfigured = () => Boolean(process.env.WAZE_API_KEY || process.env.WAZE_API_URL)

export function wazeUsage() {
  const row = all(
    'SELECT calls, provider_remaining AS providerRemaining, provider_limit AS providerLimit FROM api_usage WHERE month = ?',
    monthKey()
  )[0]
  const calls = row?.calls ?? 0
  const limit = row?.providerLimit ?? MONTHLY_LIMIT
  // Ha a szolgáltató megmondta a maradékot, azt hisszük el.
  const remaining = row?.providerRemaining ?? Math.max(0, limit - calls)
  return { month: monthKey(), calls, limit, remaining }
}

function countWazeCall() {
  run(
    `INSERT INTO api_usage (month, calls) VALUES (?, 1)
     ON CONFLICT (month) DO UPDATE SET calls = calls + 1`,
    monthKey()
  )
}

/**
 * A Waze (vagy Waze-kompatibilis) végpont hívása. A konkrét URL és a
 * kulcs-fejléc környezeti változóból jön, hogy a szolgáltató cseréje ne
 * igényeljen kódmódosítást.
 */
export async function refreshWazeAlerts() {
  if (!wazeConfigured()) return { skipped: 'no-config', points: [] }
  const usage = wazeUsage()
  if (usage.remaining <= 0) return { skipped: 'quota', points: [] }

  const url = (process.env.WAZE_API_URL || WAZE_DEFAULT_URL)
    .replace('{south}', String(AREA_BBOX.south))
    .replace('{west}', String(AREA_BBOX.west))
    .replace('{north}', String(AREA_BBOX.north))
    .replace('{east}', String(AREA_BBOX.east))

  const headers = {
    'User-Agent': 'motor-telemetria/1.0 (sajat hasznalatra)',
    // A wazeapi.com X-API-Key-t vár; RapidAPI-nál x-rapidapi-key + host.
    [process.env.WAZE_KEY_HEADER || 'X-API-Key']: process.env.WAZE_API_KEY ?? '',
    // Európai adatközpont – enélkül amerikai adatot adna.
    'X-Country': process.env.WAZE_COUNTRY || 'eur'
  }
  if (process.env.WAZE_API_HOST) headers['x-rapidapi-host'] = process.env.WAZE_API_HOST

  countWazeCall()
  const response = await fetch(url, { headers, signal: AbortSignal.timeout(30_000) })
  if (!response.ok) throw new Error(`Waze HTTP ${response.status}`)

  rememberProviderQuota(response.headers)
  const body = await response.json()
  const now = Date.now()
  const points = normalizeWaze(body, now)

  replaceSource('waze', points, now)
  dropWazeDuplicates()
  markLayer('waze', { status: 'idle', count: points.length, touch: true })
  return { skipped: null, points }
}

/** A szolgáltató kiírja a maradék keretet – ez pontosabb a saját számlálónknál. */
function rememberProviderQuota(headers) {
  const remaining = Number(headers.get('x-quota-remaining'))
  const limit = Number(headers.get('x-quota-limit'))
  if (!Number.isFinite(remaining) && !Number.isFinite(limit)) return

  run(
    `UPDATE api_usage SET provider_remaining = ?, provider_limit = ? WHERE month = ?`,
    Number.isFinite(remaining) ? remaining : null,
    Number.isFinite(limit) ? limit : null,
    monthKey()
  )
}

/**
 * A wazeapi.com egy sima tömböt ad vissza, benne `locationY`/`locationX`
 * koordinátákkal, `subType` mezővel és ezredmásodperces `timestamp`-pel.
 * Más szolgáltatók burkolt alakot használnak, ezért többfélét is elfogadunk.
 */
function normalizeWaze(body, now) {
  const alerts = Array.isArray(body)
    ? body
    : body?.alerts ?? body?.data?.alerts ?? body?.results ?? []

  return (Array.isArray(alerts) ? alerts : []).map((alert) => {
    const lat = alert.locationY ?? alert.location?.lat ?? alert.location?.y ?? alert.latitude
    const lon = alert.locationX ?? alert.location?.lng ?? alert.location?.x ?? alert.longitude
    const type = String(alert.type ?? alert.alertType ?? '').toUpperCase()
    const subtype = String(alert.subType ?? alert.subtype ?? '').toUpperCase()

    const reportedAt = parseReportedAt(alert, now)
    const place = [alert.street, alert.city].filter(Boolean).join(', ') || null

    return {
      source: 'waze',
      externalId: `waze-${alert.id ?? alert.uuid ?? `${lat},${lon},${reportedAt}`}`,
      kind: when(type, subtype),
      lat: Number(lat),
      lon: Number(lon),
      road: place,
      description: alert.reportDescription ?? describeSubtype(subtype),
      speedLimit: null,
      reportedAt,
      expiresAt: reportedAt + (ALERT_TTL_MS[when(type, subtype)] ?? ALERT_TTL_MS.OTHER)
    }
  }).filter((point) => Number.isFinite(point.lat) && Number.isFinite(point.lon))
}

/** A gépi altípusból ("POLICE_VISIBLE") olvasható szöveg. */
function describeSubtype(subtype) {
  if (!subtype) return null
  return subtype.toLowerCase().replace(/_/g, ' ')
}

/** ISO időbélyeg vagy ezredmásodperc – mindkettőt látni a szolgáltatóknál. */
function parseReportedAt(alert, now) {
  const value = alert.timestamp ?? alert.reported_at ?? alert.reportedAt ?? alert.pubMillis
  if (typeof value === 'number') return value
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    if (!Number.isNaN(parsed)) return parsed
  }
  return now
}

function when(type, subtype) {
  if (subtype.includes('CAMERA') || type.includes('CAMERA')) return 'SPEED_CAMERA'
  if (type === 'POLICE') return 'POLICE'
  if (type === 'ACCIDENT') return 'ACCIDENT'
  return 'OTHER'
}

/** A Waze-mérőt eldobjuk, ha 50 méteren belül már van OSM-mérőnk. */
function dropWazeDuplicates() {
  const osm = all("SELECT lat, lon FROM road_points WHERE source = 'osm' AND kind = 'SPEED_CAMERA'")
  const waze = all("SELECT id, lat, lon FROM road_points WHERE source = 'waze' AND kind = 'SPEED_CAMERA'")
  for (const camera of waze) {
    const duplicate = osm.some((point) => haversine(point.lat, point.lon, camera.lat, camera.lon) < 50)
    if (duplicate) run('DELETE FROM road_points WHERE id = ?', camera.id)
  }
}

// --- tárolás és lekérdezés ----------------------------------------------------

function replaceSource(source, points, now) {
  tx(() => {
    run('DELETE FROM road_points WHERE source = ?', source)
    for (const point of points) {
      run(
        `INSERT OR REPLACE INTO road_points
           (source, external_id, kind, lat, lon, road, description, speed_limit,
            reported_at, fetched_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        point.source,
        point.externalId,
        point.kind,
        point.lat,
        point.lon,
        point.road,
        point.description,
        point.speedLimit,
        point.reportedAt,
        now,
        point.expiresAt
      )
    }
  })
}

export function currentPoints() {
  return all(
    `SELECT id, source, kind, lat, lon, road, description,
            speed_limit AS speedLimit, reported_at AS reportedAt, expires_at AS expiresAt
     FROM road_points
     WHERE expires_at IS NULL OR expires_at > ?
     ORDER BY kind ASC`,
    Date.now()
  )
}

/**
 * A látható területre eső szakaszok. Megyényi adatból a telefon mindig csak
 * annyit kér, amennyit épp néz – így nem kell több megabájtot letöltenie.
 */
export function speedSegments(bbox = null, limit = 4000) {
  const rows = bbox
    ? all(
        `SELECT id, limit_kmh AS limitKmh, guessed, road, geometry
         FROM speed_segments
         WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?
         ORDER BY limit_kmh DESC LIMIT ?`,
        bbox.south,
        bbox.north,
        bbox.west,
        bbox.east,
        limit
      )
    : all(
        `SELECT id, limit_kmh AS limitKmh, guessed, road, geometry
         FROM speed_segments ORDER BY limit_kmh DESC LIMIT ?`,
        limit
      )

  return rows.map((row) => ({ ...row, guessed: row.guessed === 1, geometry: JSON.parse(row.geometry) }))
}

export function lastFetchedAt() {
  const row = all('SELECT MAX(fetched_at) AS at FROM road_points')[0]
  return row?.at ?? 0
}

/**
 * Kézi frissítés: minden réteget a háttérben indít el, és azonnal visszatér.
 * Az ingyenes rétegek mindig mennek, a Waze csak ha van kulcs és van keret.
 */
export function startRefresh({ freeOnly = false, roads = false } = {}) {
  const now = Date.now()
  if (now - lastFullRefreshAt < MIN_REFRESH_INTERVAL_MS) {
    return {
      throttled: true,
      retryInSeconds: Math.ceil((MIN_REFRESH_INTERVAL_MS - (now - lastFullRefreshAt)) / 1000)
    }
  }
  lastFullRefreshAt = now

  const started = []
  if (startLayer('cameras', async () => {
    const result = await refreshOsmPoints()
    return result.cameras
  })) {
    started.push('cameras', 'signals')
  }

  if (ensureSpeedSegments({ force: roads })) started.push('speedlimits')

  let wazeSkipped = null
  if (freeOnly) {
    wazeSkipped = 'free-only'
  } else if (!wazeConfigured()) {
    wazeSkipped = 'no-config'
  } else if (wazeUsage().remaining <= 0) {
    wazeSkipped = 'quota'
  } else if (startLayer('waze', async () => {
    const result = await refreshWazeAlerts()
    return result.points.length
  })) {
    started.push('waze')
  }

  return { throttled: false, started, wazeSkipped, usage: wazeUsage() }
}
