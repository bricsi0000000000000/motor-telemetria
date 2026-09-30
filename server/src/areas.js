/**
 * Kézzel megrajzolt, címkézett területek.
 *
 * A webes felületen rajzolod meg őket, és te döntöd el, mi a hely neve, milyen
 * megállásnak számít, és milyen logó jelölje. Ha egy megállás ilyen területre
 * esik, az erősebb minden OpenStreetMap-alapú becslésnél.
 */
import { all, one, run } from './db.js'

/** A megállástípusok, amiket egy terület adhat – ugyanazok, mint a telefonon. */
export const AREA_KINDS = ['SHOP', 'FUEL', 'PLACE', 'PARKING', 'SIGNAL', 'ROAD']

/** Ezeket a képformátumokat tudja a telefon gond nélkül kirajzolni. */
export const LOGO_TYPES = ['image/png', 'image/jpeg', 'image/webp']

/** Egy logó ekkora lehet: a telefonon úgyis egy kis körben jelenik meg. */
export const LOGO_MAX_BYTES = 512 * 1024

const MAX_POINTS = 500

export class AreaError extends Error {
  constructor(message, status = 400) {
    super(message)
    this.status = status
  }
}

function parsePolygon(value) {
  if (!Array.isArray(value) || value.length < 3) {
    throw new AreaError('A területhez legalább három pont kell.')
  }
  if (value.length > MAX_POINTS) throw new AreaError(`Legfeljebb ${MAX_POINTS} pontból állhat egy terület.`)
  const points = value.map((point) => {
    const [lat, lon] = Array.isArray(point) ? point : [point?.lat, point?.lon]
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      throw new AreaError('Hibás koordináta a területben.')
    }
    return [Number(lat.toFixed(6)), Number(lon.toFixed(6))]
  })
  // A lezáró pont (első = utolsó) nem kell, a körvonal magától zárul.
  const [first, last] = [points[0], points.at(-1)]
  if (first[0] === last[0] && first[1] === last[1]) points.pop()
  if (points.length < 3) throw new AreaError('A területhez legalább három különböző pont kell.')
  return points
}

function parseFields(body, { partial = false } = {}) {
  const fields = {}
  if (!partial || body.name !== undefined) {
    const name = typeof body.name === 'string' ? body.name.trim() : ''
    if (!name) throw new AreaError('Adj nevet a területnek.')
    if (name.length > 80) throw new AreaError('A név legfeljebb 80 karakter lehet.')
    fields.name = name
  }
  if (!partial || body.kind !== undefined) {
    const kind = body.kind ?? 'SHOP'
    if (!AREA_KINDS.includes(kind)) throw new AreaError(`Ismeretlen típus: ${kind}`)
    fields.kind = kind
  }
  if (body.note !== undefined) {
    const note = typeof body.note === 'string' ? body.note.trim() : ''
    if (note.length > 500) throw new AreaError('A megjegyzés legfeljebb 500 karakter lehet.')
    fields.note = note || null
  }
  if (!partial || body.polygon !== undefined) fields.polygon = parsePolygon(body.polygon)
  return fields
}

function bounds(polygon) {
  const lats = polygon.map((point) => point[0])
  const lons = polygon.map((point) => point[1])
  return { minLat: Math.min(...lats), maxLat: Math.max(...lats), minLon: Math.min(...lons), maxLon: Math.max(...lons) }
}

/**
 * A sokszög súlypontja – ide kerül a logó. A webes felület és a telefon is
 * ezt kapja, hogy a logó mindkettőn ugyanott legyen.
 */
export function centroid(polygon) {
  let area = 0, lat = 0, lon = 0
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const cross = polygon[j][1] * polygon[i][0] - polygon[i][1] * polygon[j][0]
    area += cross
    lat += (polygon[j][0] + polygon[i][0]) * cross
    lon += (polygon[j][1] + polygon[i][1]) * cross
  }
  if (Math.abs(area) < 1e-12) {
    // Elfajult (vonalszerű) alakzat: a pontok átlaga is megteszi.
    return [polygon.reduce((sum, p) => sum + p[0], 0) / polygon.length,
      polygon.reduce((sum, p) => sum + p[1], 0) / polygon.length]
  }
  return [Number((lat / (3 * area)).toFixed(6)), Number((lon / (3 * area)).toFixed(6))]
}

/** A sor a kliensnek: a logó bájtjai nélkül, csak hogy van-e és mikor változott. */
function present(row) {
  const polygon = JSON.parse(row.polygon)
  return {
    id: row.id,
    name: row.name,
    kind: row.kind,
    note: row.note,
    polygon,
    center: centroid(polygon),
    hasLogo: row.logo_at != null,
    logoAt: row.logo_at,
    updatedAt: row.updated_at
  }
}

const COLUMNS = 'id, name, kind, note, polygon, logo_at, updated_at'

export function listAreas() {
  return all(`SELECT ${COLUMNS} FROM map_areas ORDER BY name COLLATE NOCASE`).map(present)
}

export function getArea(id) {
  const row = one(`SELECT ${COLUMNS} FROM map_areas WHERE id = ?`, id)
  if (!row) throw new AreaError('Nincs ilyen terület.', 404)
  return present(row)
}

export function createArea(body) {
  const fields = parseFields(body)
  const box = bounds(fields.polygon)
  const now = Date.now()
  const result = run(
    `INSERT INTO map_areas (name, kind, note, polygon, min_lat, max_lat, min_lon, max_lon, created_at, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    fields.name, fields.kind, fields.note ?? null, JSON.stringify(fields.polygon),
    box.minLat, box.maxLat, box.minLon, box.maxLon, now, now
  )
  return getArea(Number(result.lastInsertRowid))
}

export function updateArea(id, body) {
  const current = getArea(id)
  const fields = { ...current, ...parseFields(body, { partial: true }) }
  const box = bounds(fields.polygon)
  run(
    `UPDATE map_areas SET name = ?, kind = ?, note = ?, polygon = ?,
       min_lat = ?, max_lat = ?, min_lon = ?, max_lon = ?, updated_at = ? WHERE id = ?`,
    fields.name, fields.kind, fields.note ?? null, JSON.stringify(fields.polygon),
    box.minLat, box.maxLat, box.minLon, box.maxLon, Date.now(), id
  )
  return getArea(id)
}

export function deleteArea(id) {
  const result = run('DELETE FROM map_areas WHERE id = ?', id)
  if (result.changes === 0) throw new AreaError('Nincs ilyen terület.', 404)
}

export function setLogo(id, bytes, mime) {
  getArea(id)
  if (!LOGO_TYPES.includes(mime)) throw new AreaError('A logó PNG, JPEG vagy WebP lehet.', 415)
  if (!bytes?.length) throw new AreaError('Üres a feltöltött fájl.')
  if (bytes.length > LOGO_MAX_BYTES) throw new AreaError('A logó legfeljebb 512 KB lehet.', 413)
  const now = Date.now()
  run('UPDATE map_areas SET logo = ?, logo_mime = ?, logo_at = ?, updated_at = ? WHERE id = ?',
    new Uint8Array(bytes), mime, now, now, id)
  return getArea(id)
}

export function clearLogo(id) {
  getArea(id)
  run('UPDATE map_areas SET logo = NULL, logo_mime = NULL, logo_at = NULL, updated_at = ? WHERE id = ?', Date.now(), id)
  return getArea(id)
}

export function getLogo(id) {
  const row = one('SELECT logo, logo_mime AS mime, logo_at AS logoAt FROM map_areas WHERE id = ?', id)
  if (!row?.logo) throw new AreaError('Ennek a területnek nincs logója.', 404)
  return { bytes: Buffer.from(row.logo), mime: row.mime, logoAt: row.logoAt }
}

/** Pont a sokszögben (a körvonal magától zárul). */
export function insidePolygon(lat, lon, polygon) {
  let result = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const [aLat, aLon] = polygon[i]
    const [bLat, bLon] = polygon[j]
    if ((aLat > lat) !== (bLat > lat) && lon < (bLon - aLon) * (lat - aLat) / (bLat - aLat) + aLon) result = !result
  }
  return result
}

/**
 * A sokszög valódi területe, összevetéshez (a hosszúságot a szélesség
 * koszinuszával arányosítjuk, hogy a két irány egy mértékben legyen).
 */
export function polygonArea(polygon) {
  const cos = Math.cos(polygon[0][0] * Math.PI / 180)
  let sum = 0
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    sum += polygon[j][1] * cos * polygon[i][0] - polygon[i][1] * cos * polygon[j][0]
  }
  return Math.abs(sum) / 2
}

/**
 * A pontot tartalmazó terület. Ha több is tartalmazza (egy bolt a plázán
 * belül), a kisebb nyer: az a konkrétabb. A valódi területet vetjük össze, nem
 * a befoglaló téglalapot – egy átlós belső sávnak ugyanakkora a téglalapja,
 * mint a körülötte lévő plázának.
 */
export function areaAt(lat, lon) {
  const candidates = all(
    `SELECT id, name, kind, polygon, logo_at FROM map_areas
     WHERE min_lat <= ? AND max_lat >= ? AND min_lon <= ? AND max_lon >= ?`,
    lat, lat, lon, lon
  )
  let best = null
  for (const row of candidates) {
    const polygon = JSON.parse(row.polygon)
    if (!insidePolygon(lat, lon, polygon)) continue
    const size = polygonArea(polygon)
    if (!best || size < best.size) best = { row, size }
  }
  if (!best) return null
  const { row } = best
  return { id: row.id, name: row.name, kind: row.kind, hasLogo: row.logo_at != null, logoAt: row.logo_at }
}

// --- látogatások -------------------------------------------------------------------

/**
 * Ezekben a területekben gyalog is jársz: a bent töltött idő egyetlen
 * látogatás, sebességtől függetlenül. A lámpa és a forgalom kimarad – ott a
 * sebesség maga a lényeg, az áthaladás nem megállás.
 */
export const VISIT_KINDS = new Set(['SHOP', 'PLACE', 'FUEL', 'PARKING'])

/**
 * Ennél rövidebb összesített bent-tartózkodás csak elhaladás vagy GPS-tévedés,
 * nem látogatás (egy egyszer kitévedt pont a belső területre ne legyen "Aldi").
 */
export const MIN_VISIT_MS = 30_000

/** Ennél pontatlanabb pont nem dönt arról, hol vagy: épületben a GPS sokat téved. */
const MAX_ACCURACY_M = 40

const medianOf = (values) => [...values].sort((a, b) => a - b)[Math.floor(values.length / 2)]

/**
 * Mennyi időt töltöttél az egyes területeken egy úton.
 *
 * Minden pontnál eldől, melyik területen vagy: egymásba rajzoltaknál a
 * legbelsőben. Amikor ez megváltozik, az a kilépés a régiből és a belépés az
 * újba – egy belső területre (Aldi az ETO Parkon belül) átmenni tehát kilépés
 * a külsőből. Egy terület bent töltött szakaszait összeadjuk, és ez az
 * egyetlen adat, ami a területhez megjelenik.
 *
 * Ha közben elmegy a GPS (épületben), vagy szünetel a felvétel, a legutóbb
 * látott területen számolunk tovább, amíg egy új pont mást nem mutat. A
 * sebesség nem számít: bent gyalog jársz.
 *
 * Egy terület akkor jelenik meg, ha összesen legalább fél percet voltál bent,
 * és közben legalább egyszer álltál is – az áthajtás nem látogatás.
 *
 * @param points a túra pontjai időrendben: { lat, lon, speedMps, accuracy, time }
 * @param areas területek: { id, name, kind, polygon, logoAt }
 */
export function findVisits(points, areas) {
  const candidates = areas.filter((area) => VISIT_KINDS.has(area.kind)).map((area) => {
    const lats = area.polygon.map((p) => p[0]), lons = area.polygon.map((p) => p[1])
    return { ...area, size: polygonArea(area.polygon),
      minLat: Math.min(...lats), maxLat: Math.max(...lats), minLon: Math.min(...lons), maxLon: Math.max(...lons) }
  })
  if (!candidates.length) return []

  /** A pontot tartalmazó legbelső terület, vagy null. */
  const areaOf = (p) => {
    let best = null
    for (const area of candidates) {
      if (p.lat < area.minLat || p.lat > area.maxLat || p.lon < area.minLon || p.lon > area.maxLon) continue
      if (!insidePolygon(p.lat, p.lon, area.polygon)) continue
      if (!best || area.size < best.size) best = area
    }
    return best
  }

  const totals = new Map()
  const totalOf = (area) => {
    if (!totals.has(area.id)) totals.set(area.id, { area, ms: 0, still: 0, inside: [], first: null, last: null })
    return totals.get(area.id)
  }

  let current = null, since = null, lastTime = null
  const leave = (time) => {
    if (!current) return
    const total = totalOf(current)
    total.ms += time - since
    total.last = time
  }

  for (const p of points) {
    if (!(p.accuracy <= MAX_ACCURACY_M)) continue
    const here = areaOf(p)
    if (here !== current) {
      // Kilépés a régiből és belépés az újba ugyanabban a pillanatban.
      leave(p.time)
      current = here
      since = p.time
    }
    if (here) {
      const total = totalOf(here)
      total.inside.push(p)
      if (p.speedMps <= 0.8) total.still++
      if (total.first === null) total.first = p.time
    }
    lastTime = p.time
  }
  // A túra bent ért véget: az utolsó pontig számít.
  if (current) leave(lastTime)

  return [...totals.values()]
    .filter((total) => total.ms >= MIN_VISIT_MS && total.still >= 2)
    .map(({ area, ms, inside, first, last }) => {
      // Egy látogatás egyetlen pont: a terület pontja (ahol a logója is áll),
      // nem a bent bolyongó GPS-pontok közepe.
      const [lat, lon] = centroid(area.polygon)
      return {
        id: String(first),
        lat,
        lon,
        accuracy: medianOf(inside.map((p) => p.accuracy)),
        startedAt: first,
        endedAt: last,
        // Az összeadott bent töltött idő – nem a kettő különbsége, mert közben
        // kiléphettél (akár egy belső területre is).
        durationMs: ms,
        type: area.kind,
        name: area.name,
        brand: null,
        areaId: area.id,
        logoAt: area.logoAt ?? null,
        reason: 'A területen töltött idő összesen, a be- és kilépések alapján.'
      }
    })
    .sort((a, b) => a.startedAt - b.startedAt)
}

/**
 * Bent van-e a pont valamelyik látogatás-típusú területen. Ami ilyen helyen
 * történt, azt a terület összesített ideje már tartalmazza.
 */
export function inVisitArea(lat, lon, areas) {
  return areas.some((area) => VISIT_KINDS.has(area.kind) && insidePolygon(lat, lon, area.polygon))
}

/** A látogatás-típusú területek, amelyek a megadott befoglaló téglalapba belelógnak. */
export function visitAreasWithin(minLat, maxLat, minLon, maxLon) {
  return all(
    `SELECT id, name, kind, polygon, logo_at FROM map_areas
     WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?`,
    minLat, maxLat, minLon, maxLon
  ).filter((row) => VISIT_KINDS.has(row.kind))
    .map((row) => ({ id: row.id, name: row.name, kind: row.kind, polygon: JSON.parse(row.polygon), logoAt: row.logo_at }))
}
