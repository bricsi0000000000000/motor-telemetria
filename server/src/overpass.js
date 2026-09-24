/**
 * Közös OpenStreetMap (Overpass) segédek.
 *
 * Két helyen kell ugyanez: a túraelemzés a nyomvonal menti sebességhatárokat
 * kérdezi le, a térképréteg pedig a győri terület mérőit, lámpáit és útjait.
 * Ugyanaz a lekérdező és ugyanazok a sebességhatár-szabályok, hogy a két nézet
 * soha ne mondjon mást ugyanarra az útra.
 */

// A nyilvános kiszolgálók terhelés alatt 504-gyel vagy üres törzzsel dobálnak
// vissza, ezért többet is végigpróbálunk. A sorrend a mérésünk szerinti
// megbízhatóság: a kumi példány válaszolt a legtöbbször.
// FONTOS: csak világméretű adatbázist tartalmazó példányok! A regionális
// tükrök (pl. overpass.osm.ch = csak Svájc) hiba nélkül, üres eredménnyel
// válaszolnak a magyar területre – az sokkal rosszabb, mint egy hibaüzenet.
// A sorrend számít: az overpass-api.de adatbázisa naprakész (percekkel korábbi
// OSM állapot), a kumi példányé viszont a mérésünkkor 2,5 hónapos volt – mérők
// és sebességhatárok esetén ez lényeges. A kumi így csak tartalék, amikor a
// naprakész példány túlterhelt.
const ENDPOINTS = [
  'https://overpass-api.de/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter'
]

/** Ennyiszer kerüljük körbe az összes kiszolgálót, mielőtt feladjuk. */
const ROUNDS = 3

/** Két kör között ennyit várunk, hogy a túlterhelt kiszolgáló levegőhöz jusson. */
const RETRY_DELAY_MS = 15_000

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * A lefedett terület: Győr-Moson-Sopron vármegye befoglaló téglalapja
 * (kb. 105 × 80 km). Az AREA_BBOX környezeti változóval felülírható
 * "dél,nyugat,észak,kelet" alakban.
 */
export const AREA_BBOX = parseArea(process.env.AREA_BBOX) ?? {
  south: 47.28,
  west: 16.40,
  north: 48.03,
  east: 18.00
}

/** Visszafelé kompatibilis név – a kód régebbi részei ezt használják. */
export const GYOR_BBOX = AREA_BBOX

function parseArea(value) {
  if (!value) return null
  const parts = value.split(',').map(Number)
  if (parts.length !== 4 || parts.some((number) => !Number.isFinite(number))) return null
  return { south: parts[0], west: parts[1], north: parts[2], east: parts[3] }
}

export const bboxString = (bbox = AREA_BBOX) =>
  `${bbox.south},${bbox.west},${bbox.north},${bbox.east}`

/**
 * A területet nagyjából [degrees] fokos csempékre bontja, hogy egy-egy Overpass
 * kérés kicsi maradjon. Területarányos: egy városra 2-4 csempe, egy megyére
 * több tucat – nem 64 apró kérés mindkét esetben.
 */
export function tiles(bbox, degrees = 0.15) {
  const latDivision = Math.max(1, Math.ceil((bbox.north - bbox.south) / degrees))
  const lonDivision = Math.max(1, Math.ceil((bbox.east - bbox.west) / degrees))
  const latStep = (bbox.north - bbox.south) / latDivision
  const lonStep = (bbox.east - bbox.west) / lonDivision
  const result = []
  for (let i = 0; i < latDivision; i++) {
    for (let j = 0; j < lonDivision; j++) {
      const south = bbox.south + i * latStep
      const west = bbox.west + j * lonStep
      result.push({
        south,
        west,
        north: south + latStep,
        east: west + lonStep,
        label: `${i + 1}/${j + 1}`
      })
    }
  }
  return result
}

/** Magyar alapértékek, ha az OSM-ben nincs kiírt sebességhatár. */
export const DEFAULT_LIMITS = {
  motorway: 130,
  motorway_link: 80,
  trunk: 110,
  trunk_link: 70,
  primary: 90,
  primary_link: 70,
  secondary: 90,
  secondary_link: 70,
  tertiary: 90,
  tertiary_link: 70,
  unclassified: 90,
  residential: 50,
  living_street: 20,
  service: 20
}

const EARTH_R = 6371000

export function haversine(aLat, aLon, bLat, bLon) {
  const toRad = Math.PI / 180
  const dLat = (bLat - aLat) * toRad
  const dLon = (bLon - aLon) * toRad
  const lat1 = aLat * toRad
  const lat2 = bLat * toRad
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2
  return 2 * EARTH_R * Math.asin(Math.sqrt(h))
}

/** "80", "80 km/h", "50 mph", "none", "walk" – ennyiféle alakot látni az OSM-ben. */
export function parseMaxspeed(value) {
  if (!value) return null
  const text = String(value).trim().toLowerCase()
  if (text === 'none') return 130
  if (text === 'walk') return 10
  const mph = text.match(/^(\d+)\s*mph$/)
  if (mph) return Math.round(Number(mph[1]) * 1.609)
  const kmh = text.match(/^(\d+)/)
  return kmh ? Number(kmh[1]) : null
}

/** Kiírt sebességhatár, vagy ha nincs, az úttípus szerinti becslés. */
export function limitFor(tags = {}) {
  const tagged = parseMaxspeed(tags.maxspeed)
  if (tagged !== null) return { limit: tagged, guessed: false }
  const fallback = DEFAULT_LIMITS[tags.highway]
  return fallback ? { limit: fallback, guessed: true } : null
}

export async function overpass(query, { timeoutMs = 90_000, rounds = ROUNDS } = {}) {
  let lastError = null
  for (let round = 0; round < rounds; round++) {
    if (round > 0) await sleep(RETRY_DELAY_MS)
    for (const endpoint of ENDPOINTS) {
      try {
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'User-Agent': 'motor-telemetria/1.0 (sajat hasznalatra)'
          },
          body: new URLSearchParams({ data: query }),
          signal: AbortSignal.timeout(timeoutMs)
        })
        if (!response.ok) {
          lastError = new Error(`Overpass HTTP ${response.status}`)
          continue
        }
        // Terhelés alatt üres törzzsel utasítanak vissza: ez is hiba, nem üres eredmény.
        const text = await response.text()
        if (!text.trim()) {
          lastError = new Error('Overpass üres választ adott')
          continue
        }
        return JSON.parse(text)
      } catch (error) {
        lastError = error
      }
    }
  }
  throw lastError ?? new Error('Overpass nem elérhető')
}
