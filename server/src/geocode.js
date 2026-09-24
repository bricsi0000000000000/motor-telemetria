import { db, one, run } from './db.js'

/**
 * Fordított geokódolás az OpenStreetMap Nominatim szolgáltatásával.
 *
 * A Nominatim használati feltétele: másodpercenként legfeljebb egy kérés, valódi
 * User-Agent, és a válaszok gyorsítótárazása. Mindhármat betartjuk – a telefon
 * pedig eleve csak azokat a pontokat kérdezi, amiket még nem ismer.
 */

const ENDPOINT = 'https://nominatim.openstreetmap.org/reverse'

/** Ekkora rácsra kerekítünk: ~11 méter, egy parkoló ugyanaz a cella marad. */
const GRID = 1e4

/** A Nominatim udvariassági korlátja. */
const MIN_INTERVAL_MS = 1100

let lastRequestAt = 0

const cacheKey = (lat, lon) =>
  `${Math.round(lat * GRID) / GRID},${Math.round(lon * GRID) / GRID}`

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

export function cachedPlace(lat, lon) {
  const row = one('SELECT payload FROM geocode_cache WHERE key = ?', cacheKey(lat, lon))
  return row ? JSON.parse(row.payload) : null
}

export async function reverseGeocode(lat, lon) {
  const key = cacheKey(lat, lon)
  const cached = cachedPlace(lat, lon)
  if (cached) return cached

  const now = Date.now()
  const wait = MIN_INTERVAL_MS - (now - lastRequestAt)
  if (wait > 0) await sleep(wait)
  lastRequestAt = Date.now()

  const url = `${ENDPOINT}?format=jsonv2&zoom=18&addressdetails=1&accept-language=hu` +
    `&lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`

  const response = await fetch(url, {
    headers: { 'User-Agent': 'motor-telemetria/1.0 (sajat hasznalatra)' },
    signal: AbortSignal.timeout(20_000)
  })
  if (!response.ok) throw new Error(`Nominatim HTTP ${response.status}`)

  const body = await response.json()
  const address = body.address ?? {}

  // A településnek több neve is lehet a válaszban, sorrendben nézzük végig.
  const place = {
    houseNumber: address.house_number ?? null,
    road: address.road ?? address.pedestrian ?? address.footway ?? null,
    suburb: address.suburb ?? address.city_district ?? address.quarter ?? null,
    city: address.city ?? address.town ?? address.village ?? address.municipality ?? null,
    county: address.county ?? null
  }

  run(
    `INSERT INTO geocode_cache (key, payload, created_at) VALUES (?, ?, ?)
     ON CONFLICT (key) DO UPDATE SET payload = excluded.payload, created_at = excluded.created_at`,
    key,
    JSON.stringify(place),
    Date.now()
  )
  return place
}

export { db }
