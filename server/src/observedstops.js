import { all, one, run } from './db.js'
import { overpass, haversine } from './overpass.js'
import { RoutingError } from './routing.js'
import { areaAt, findVisits, inVisitArea, visitAreasWithin } from './areas.js'

const distance = (a, b) => haversine(a.lat, a.lon, b.lat, b.lon)
const median = values => values.sort((a, b) => a - b)[Math.floor(values.length / 2)]

/** Ennyi állás már megállás. Egy piros lámpa előtti koccanás is ide tartozik. */
export const MIN_STOP_MS = 1000

/** Ennél hosszabb álldogálás után van értelme boltot keresni a közelben. */
const ERRAND_MS = 60000

/**
 * Csak ezeket a boltokat jelöljük bevásárlásként: a kért élelmiszerláncokat,
 * az OBI-t, az Árkádot és az ETO Parkot – a bevásárlóközpontokat a `shop`
 * értéke alapján is. A név elé és mögé kért határ óvja a hamis találatokat
 * (a "Mobil Pont" nem OBI, a "Takarékspar" nem SPAR).
 */
const SHOP_CHAIN = /(^|[^A-Z])(LIDL|ALDI|PENNY|INTERSPAR|SPAR|OBI|ÁRKÁD|ARKAD|ETO[ -]?PARK)([^A-Z]|$)/
const MALL_SHOPS = new Set(['mall', 'department_store'])

/** A jelölő ikonjához: melyik lánc ez. Ismeretlen boltnál null. */
export function shopBrand(tags) {
  if (!tags?.shop) return null
  const text = [tags.name, tags.brand, tags.operator].map(v => String(v ?? '').toUpperCase()).join(' ')
  const match = SHOP_CHAIN.exec(text)
  if (!match) return MALL_SHOPS.has(tags.shop) ? 'mall' : null
  const word = match[2].replace(/[ -]/g, '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
  return word === 'interspar' ? 'spar' : word
}

const isShop = tags => shopBrand(tags) !== null

/**
 * A lassú, kis területen maradó szakaszba a boltig sétálás is belefér, ezért a
 * megállás hosszát csak az álló (0,8 m/s alatti) pontokra mérjük: az araszolás
 * nem megállás, a lámpánál töltött egyetlen másodperc viszont az.
 */
export function detectObservedStops(points) {
  const stops = []
  for (let start = 0; start < points.length;) {
    if (points[start].speedMps > 2.5) { start++; continue }
    let end = start + 1
    while (end < points.length && points[end].segment === points[start].segment &&
      points[end].time > points[end - 1].time && points[end].time - points[end - 1].time <= 120000 &&
      points[end].speedMps <= 2.5 && distance(points[start], points[end]) < 150) end++
    const still = points.slice(start, end).filter(p => p.speedMps <= 0.8)
    const durationMs = still.length >= 2 ? still[still.length - 1].time - still[0].time : 0
    if (durationMs >= MIN_STOP_MS) {
      stops.push({ id: String(still[0].time), from: start, to: end - 1,
        lat: median(still.map(p => p.lat)), lon: median(still.map(p => p.lon)),
        accuracy: median(still.map(p => p.accuracy)), durationMs,
        startedAt: still[0].time, endedAt: still[still.length - 1].time,
        type: 'OTHER', name: null, brand: null, reason: 'Megállás; a cél nem állapítható meg biztosan.' })
    }
    start = end
  }
  return stops
}

function lineDistance(point, geometry = []) {
  let best = Infinity
  const cos = Math.cos(point.lat * Math.PI / 180)
  for (let i = 1; i < geometry.length; i++) {
    const a = geometry[i - 1], b = geometry[i]
    const dx = (b.lon - a.lon) * cos, dy = b.lat - a.lat
    const n = dx * dx + dy * dy
    const t = n ? Math.max(0, Math.min(1, ((point.lon - a.lon) * cos * dx + (point.lat - a.lat) * dy) / n)) : 0
    best = Math.min(best, distance(point, { lat: a.lat + (b.lat - a.lat) * t, lon: a.lon + (b.lon - a.lon) * t }))
  }
  return best
}

function inside(point, polygon) {
  if (polygon.length < 4 || distance(polygon[0], polygon.at(-1)) > 1) return false
  let result = false
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i], b = polygon[j]
    if ((a.lat > point.lat) !== (b.lat > point.lat) &&
      point.lon < (b.lon - a.lon) * (point.lat - a.lat) / (b.lat - a.lat) + a.lon) result = !result
  }
  return result
}

/** Két körvonal legkisebb távolsága – ebből tudjuk, hogy egymás mellett vannak-e. */
function ringDistance(a, b) {
  let best = Infinity
  for (const point of a) best = Math.min(best, lineDistance(point, b))
  for (const point of b) best = Math.min(best, lineDistance(point, a))
  return best
}

/** A bolthoz simuló parkoló még a boltnál van, nem az utcán. */
const ANNEX_M = 40

/**
 * Egy bolt területe: a saját körvonala és a hozzá tartozó parkolók.
 *
 * Egy plázába a parkolóján át megy be az ember, és a parkoló nagyobb is lehet,
 * mint maga az épület (az ETO Parknál 280 méter átmérőjű). Külön kezelve a
 * parkolóbeli megállás "út melletti megállás" lett, holott az ETO Parkban volt.
 */
function shopArea(shop, parkings) {
  const own = shop.geometry ?? []
  if (own.length < 4) return []
  const parts = [own]
  for (const lot of parkings) if (ringDistance(own, lot.geometry) <= ANNEX_M) parts.push(lot.geometry)
  return parts
}

const isMall = tags => MALL_SHOPS.has(tags?.shop)

export function classifyObservedStop(stop, elements, signals = []) {
  const roadTypes = new Set(['motorway','trunk','primary','secondary','tertiary','unclassified','residential','living_street',
    'motorway_link','trunk_link','primary_link','secondary_link','tertiary_link'])
  const roads = elements.filter(e => roadTypes.has(e.tags?.highway))
  const roadDistance = Math.min(Infinity, ...roads.map(e => lineDistance(stop, e.geometry)))
  // A lámpák a letöltött rétegből és az aktuális Overpass válaszból is jöhetnek.
  // A megyényi listából csak a megállás pár száz méteres környéke érdekes.
  const around = p => p && Math.abs(p.lat - stop.lat) < 0.002 && Math.abs(p.lon - stop.lon) < 0.003
  const lights = signals.filter(around).concat(elements
    .filter(e => e.tags?.highway === 'traffic_signals')
    .map(e => e.center ?? (Number.isFinite(e.lat) ? e : null)).filter(around))
  const signalDistance = Math.min(Infinity, ...lights.map(p => distance(stop, p)))
  if (stop.accuracy <= 25 && roadDistance <= 10) {
    // Úton állva csak két eset van: vagy a lámpa tart, vagy az előtted lévők.
    const atSignal = signalDistance <= Math.max(30, stop.accuracy)
    return { ...stop, type: atSignal ? 'SIGNAL' : 'ROAD',
      signalDistanceM: atSignal ? Math.round(signalDistance) : null,
      reason: atSignal ? 'Úton, ismert jelzőlámpa közelében történt várakozás (becslés).'
        : 'A megállás helye a közútra illeszkedik, lámpa nincs a közelben – forgalomban állás (becslés).' }
  }
  const poiRadius = Math.max(30, stop.accuracy)
  const parkings = elements.filter(e => e.tags?.amenity === 'parking' && (e.geometry?.length ?? 0) >= 4)
  const pois = elements.filter(e => isShop(e.tags) || e.tags?.amenity === 'fuel').map(e => {
    const location = e.center ?? (Number.isFinite(e.lat) ? e : null)
    let d = Infinity
    // "Telken belül" csak a valódi területbe esés számít; egy pontszerű bolt
    // véletlen nulla távolsága nem jelenti, hogy ott is jártál.
    let within = false
    const parts = shopArea(e, parkings)
    for (const ring of parts) {
      if (inside(stop, ring)) { d = 0; within = true; break }
      d = Math.min(d, lineDistance(stop, ring))
    }
    if (!parts.length) d = location ? distance(stop, location) : Infinity
    return { e, d, within }
  }).filter(p => p.d <= poiRadius).sort((a, b) => a.d - b.d)
  // A telken belül a plázát nevezzük meg, nem a benne lévő üzletet: aki megáll
  // az ETO Parkban, az az ETO Parkban állt meg, nem a benne lévő Aldiban.
  const onPlot = pois.filter(p => p.within)
  const first = onPlot.find(p => isMall(p.e.tags)) ?? onPlot[0] ??
    pois.find(p => isShop(p.e.tags)) ?? pois[0]
  // A telken belül állva nincs mit összekeverni a forgalommal, ezért ott a
  // rövid megállás is a hely felkeresése. Kívül marad az óvatos szabály: egy
  // közeli üzlet önmagában nem bizonyít bevásárlást.
  const onSite = Boolean(first?.within)
  const plausible = onSite ||
    (stop.durationMs >= ERRAND_MS && roads.length > 0 && roadDistance > Math.max(15, stop.accuracy))
  if (first && plausible && stop.accuracy <= 30) {
    // Több támogatott üzlet közelében az nem dönthető el, melyikben jártál –
    // az viszont igen, hogy boltban. Ilyenkor a típus marad bolt, a név elmarad.
    // A plázát csak másik pláza teheti bizonytalanná, a benne lévő üzlet nem.
    const rival = pois.find(p => p !== first && p.d - first.d <= 10 &&
      (p.e.tags?.name ?? null) !== (first.e.tags?.name ?? null) &&
      isMall(p.e.tags) === isMall(first.e.tags))
    const tags = first.e.tags
    const brand = shopBrand(tags)
    return { ...stop, type: brand ? 'SHOP' : 'FUEL', brand: rival ? null : brand,
      name: rival ? null : (tags.name ?? tags.brand ?? null),
      reason: rival ? 'Út melletti, tartós megállás több egymás melletti üzlet mellett; a lánc nem dönthető el. Koppintással javítható.'
        : 'Út melletti, tartós megállás és közeli térképi hely alapján becsült cél. Koppintással javítható.' }
  }
  return { ...stop, type: Number.isFinite(roadDistance) && roadDistance > 15 ? 'PARKING' : 'OTHER' }
}

/**
 * Eddig a lámpától (a nyomvonal mentén mérve) a sor elején álltál. A lámpa
 * pontja a kereszteződés közepén van, a stopvonal 20–30 méterrel előtte, és
 * még egy-két jármű fér elé. A 2026. 09. 30-i úton a lámpánál állások 29–42
 * méterre voltak, a mögötte sorban állások 73–84 méterre.
 */
export const SIGNAL_FRONT_M = 45

/** Eddig a nyomvonal menti távolságig számít egy lámpa a sor elejének. */
export const QUEUE_M = 350

/** A sor legfeljebb ennyi idő alatt ér el a lámpáig (több zöldön át is). */
const QUEUE_MS = 5 * 60000

/** Ennyire kell a nyomvonalnak a lámpa mellett elhaladnia. */
const SIGNAL_PASS_M = 25

/**
 * Forgalomban állásnál: a lámpa tartotta-e fel a sort előtted. Az álló pont
 * utáni nyomvonalat követjük legfeljebb QUEUE_M méterig; ha közben elhaladsz
 * egy jelzőlámpa mellett, a sor miatta állt, csak még nem értél oda.
 *
 * @returns a lámpa távolsága a megállástól a nyomvonal mentén, vagy null
 */
export function signalAhead(stop, points, lights) {
  if (!Number.isInteger(stop.to) || !lights.length) return null
  const near = lights.filter(p => Math.abs(p.lat - stop.lat) < 0.005 && Math.abs(p.lon - stop.lon) < 0.007)
  if (!near.length) return null
  const from = points[stop.to]
  let walked = 0
  // Az első lámpa, ami mellett elhaladsz; annál a legközelebbi pontig mérünk,
  // mert az a lámpa helye. Egy kereszteződés túloldali lámpája már nem számít.
  let best = null
  for (let i = stop.to + 1; i < points.length; i++) {
    const prev = points[i - 1], p = points[i]
    if (p.segment !== from.segment || p.time - from.time > QUEUE_MS) break
    walked += distance(prev, p)
    if (best) {
      const gap = distance(p, best.light)
      if (gap > best.gap) break
      best.gap = gap
      best.walked = walked
      continue
    }
    if (walked > QUEUE_M) break
    const light = near.find(l => distance(p, l) <= SIGNAL_PASS_M)
    if (light) best = { light, gap: distance(p, light), walked }
  }
  return best ? Math.round(best.walked) : null
}

/**
 * A már letöltött úthálózat-réteg a megállás körül. Ezzel a lámpa/forgalom
 * döntés Overpass-kérés nélkül is megvan, így nem a rövid megállások élik fel
 * a lekérdezési keretet.
 */
function nearbyRoadLayer(stop, radiusM = 200) {
  const dLat = radiusM / 111320
  const dLon = dLat / Math.max(0.2, Math.cos(stop.lat * Math.PI / 180))
  return all(`SELECT geometry FROM speed_segments
     WHERE max_lat >= ? AND min_lat <= ? AND max_lon >= ? AND min_lon <= ?`,
    stop.lat - dLat, stop.lat + dLat, stop.lon - dLon, stop.lon + dLon)
    .map(row => ({ tags: { highway: 'unclassified' },
      geometry: JSON.parse(row.geometry).map(([lat, lon]) => ({ lat, lon })) }))
}

export async function observedStops(points) {
  if (!Array.isArray(points) || points.length > 100000 || points.some(p => !p ||
    !Number.isFinite(p.lat) || !Number.isFinite(p.lon) || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180 ||
    !Number.isFinite(p.time) || !Number.isFinite(p.speedMps) || p.speedMps < 0 || !Number.isFinite(p.accuracy) || p.accuracy < 0)) {
    throw new RoutingError('Hibás GPS-pontok a megállások kereséséhez.', { status: 400 })
  }
  // A boltok és más gyalog bejárt helyek területén a be- és kilépésekből
  // összeadott idő az egyetlen adat; ami ott történt (séta közbeni ácsorgás,
  // GPS-kiesés utáni újrakezdés), az már benne van. A többi megállás a szokott
  // úton megy.
  let minLat = Infinity, maxLat = -Infinity, minLon = Infinity, maxLon = -Infinity
  for (const p of points) {
    minLat = Math.min(minLat, p.lat); maxLat = Math.max(maxLat, p.lat)
    minLon = Math.min(minLon, p.lon); maxLon = Math.max(maxLon, p.lon)
  }
  const visitAreas = points.length ? visitAreasWithin(minLat, maxLat, minLon, maxLon) : []
  const visits = findVisits(points, visitAreas)
  const stops = detectObservedStops(points).filter(stop => !inVisitArea(stop.lat, stop.lon, visitAreas))
  // A webes felületen megrajzolt terület felülír minden becslést: ott te
  // döntötted el, mi a hely. Ezekhez OSM-lekérdezés sem kell.
  const areas = new Map(stops.map(stop => [stop, areaAt(stop.lat, stop.lon)]))
  const key = p => `stop-poi-v4:${p.lat.toFixed(3)},${p.lon.toFixed(3)}`
  const datasets = new Map()
  const missing = []
  for (const stop of stops) {
    if (areas.get(stop) || datasets.has(key(stop))) continue
    const cached = one('SELECT payload, created_at FROM geocode_cache WHERE key = ?', key(stop))
    if (cached && Date.now() - cached.created_at < 7 * 86400000) datasets.set(key(stop), JSON.parse(cached.payload))
    // Rövid megálláshoz nem keresünk boltot, így a keret a valódi jelölteké marad.
    else { datasets.set(key(stop), []); if (stop.durationMs >= ERRAND_MS) missing.push(stop) }
  }
  let offline = false
  if (missing.length) {
    try {
      const probes = missing.slice(0, 30)
      // A 3 tizedesre kerekített cella sarka 67 méter, plusz a 30 méteres
      // keresési sugár: 150 méter bőven elég, és sokkal kisebb választ ad.
      const queries = probes.map(p => {
        const area = `(around:150,${p.lat.toFixed(3)},${p.lon.toFixed(3)})`
        return `node${area}[highway=traffic_signals];nwr${area}[shop];nwr${area}[amenity=fuel];nwr${area}[amenity=parking];`
      }).join('')
      // Kulcsra szűrünk, nem értékre: a mérés szerint a `[shop][name~...]` alakú
      // lekérdezés 40 másodperc alatt sem jött vissza, ez viszont tíz alatt. Az
      // utak geometriája ugyanígy kimarad – azt a helyi rétegből vesszük.
      const response = await overpass(`[out:json][timeout:25];(${queries});out center geom;`, { timeoutMs: 25000, rounds: 1 })
      const elements = response.elements ?? []
      for (const p of probes) {
        // A lekérdezés mérete kicsi; a közös adathalmazból a távolság dönt.
        datasets.set(key(p), elements)
        run('INSERT INTO geocode_cache (key,payload,created_at) VALUES (?,?,?) ON CONFLICT(key) DO UPDATE SET payload=excluded.payload,created_at=excluded.created_at', key(p), JSON.stringify(elements), Date.now())
      }
    } catch { offline = true }
  }
  const signals = all("SELECT lat,lon FROM road_points WHERE kind = 'TRAFFIC_SIGNALS'")
  const classified = stops.map(p => {
    const area = areas.get(p)
    if (area) {
      return { ...p, type: area.kind, name: area.name, brand: null,
        areaId: area.id, logoAt: area.logoAt ?? null,
        reason: 'A webes felületen megrajzolt területen belül.' }
    }
    const elements = [...(datasets.get(key(p)) ?? []), ...nearbyRoadLayer(p)]
    const result = classifyObservedStop(p, elements, signals)
    if (result.type !== 'ROAD') return result
    // Úton állva a lámpa légvonalban gyakran messzebb van, mint gondolnánk: a
    // nyomvonal mentén előtted lévő lámpa dönt. Közel hozzá a sor elején
    // álltál, messzebb a mögötte feltorlódott sorban.
    const lights = signals.concat(elements.filter(e => e.tags?.highway === 'traffic_signals')
      .map(e => e.center ?? (Number.isFinite(e.lat) ? e : null)).filter(Boolean))
    const ahead = signalAhead(p, points, lights)
    if (ahead === null) return result
    if (ahead <= SIGNAL_FRONT_M) {
      return { ...result, type: 'SIGNAL', signalDistanceM: ahead,
        reason: `Úton, közvetlenül egy jelzőlámpa előtt (kb. ${ahead} méterre) történt várakozás (becslés).` }
    }
    return { ...result, type: 'SIGNAL_QUEUE', signalDistanceM: ahead,
      reason: `Lámpa miatti sorban állás: a jelzőlámpa még kb. ${ahead} méterrel előtted volt, nem tudtál odáig előremenni (becslés).` }
  })
  return { stops: [...visits, ...classified].sort((a, b) => a.startedAt - b.startedAt), offline }
}
