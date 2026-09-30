import { DatabaseSync } from 'node:sqlite'
import { mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))

/** Az adatbázis helye felülírható, hogy a teszt ne a valódi adatokra fusson. */
const dbPath = process.env.MOTOR_DB || join(here, '..', 'data', 'motor.db')

mkdirSync(dirname(dbPath), { recursive: true })

export const db = new DatabaseSync(dbPath)

// A WAL napló miatt az olvasás (webes felület) nem akad össze az írással (telefon szinkron).
db.exec('PRAGMA journal_mode = WAL')
db.exec('PRAGMA foreign_keys = ON')

db.exec(`
  CREATE TABLE IF NOT EXISTS tracks (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    device_uid         TEXT    NOT NULL,
    -- A telefonon lévő Room-azonosító. Ezzel ismerjük fel az újraküldött túrát.
    client_id          INTEGER NOT NULL,
    start_time         INTEGER NOT NULL,
    end_time           INTEGER,
    distance_m         REAL    NOT NULL DEFAULT 0,
    duration_ms        INTEGER NOT NULL DEFAULT 0,
    moving_ms          INTEGER NOT NULL DEFAULT 0,
    max_speed_mps      REAL    NOT NULL DEFAULT 0,
    elevation_gain_m   REAL    NOT NULL DEFAULT 0,
    point_count        INTEGER NOT NULL DEFAULT 0,
    note               TEXT,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    UNIQUE (device_uid, client_id)
  )
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS track_points (
    track_id   INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    -- A pont sorszáma a túrán belül. A telefon ugyanazt a sorszámot küldi
    -- újraküldéskor is, így az INSERT OR IGNORE elnyeli a duplikátumot.
    seq        INTEGER NOT NULL,
    lat        REAL    NOT NULL,
    lon        REAL    NOT NULL,
    altitude   REAL    NOT NULL DEFAULT 0,
    speed_mps  REAL    NOT NULL DEFAULT 0,
    accuracy   REAL    NOT NULL DEFAULT 0,
    bearing    REAL    NOT NULL DEFAULT 0,
    time       INTEGER NOT NULL,
    segment    INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (track_id, seq)
  ) WITHOUT ROWID
`)

for (const column of [
  'telemetry_version INTEGER NOT NULL DEFAULT 0',
  'telemetry_sample_count INTEGER NOT NULL DEFAULT 0'
]) {
  try { db.exec(`ALTER TABLE tracks ADD COLUMN ${column}`) } catch { /* már megvan */ }
}

db.exec(`
  CREATE TABLE IF NOT EXISTS telemetry_samples (
    track_id            INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    seq                 INTEGER NOT NULL,
    time                INTEGER NOT NULL,
    lat                 REAL,
    lon                 REAL,
    speed_mps           REAL NOT NULL DEFAULT 0,
    bearing_deg         REAL NOT NULL DEFAULT 0,
    matched_lat         REAL,
    matched_lon         REAL,
    road_bearing_deg    REAL,
    pressure_hpa        REAL,
    fused_altitude_m    REAL,
    forward_mean_mps2   REAL NOT NULL DEFAULT 0,
    forward_min_mps2    REAL NOT NULL DEFAULT 0,
    forward_max_mps2    REAL NOT NULL DEFAULT 0,
    lateral_mean_mps2   REAL NOT NULL DEFAULT 0,
    lateral_rms_mps2    REAL NOT NULL DEFAULT 0,
    lateral_peak_mps2   REAL NOT NULL DEFAULT 0,
    vertical_rms_mps2   REAL NOT NULL DEFAULT 0,
    vertical_peak_mps2  REAL NOT NULL DEFAULT 0,
    yaw_peak_rads       REAL NOT NULL DEFAULT 0,
    roll_peak_rads      REAL NOT NULL DEFAULT 0,
    lean_degrees        REAL,
    mount_quality       REAL NOT NULL DEFAULT 0,
    sample_count        INTEGER NOT NULL DEFAULT 0,
    flags               INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (track_id, seq)
  ) WITHOUT ROWID
`)

try { db.exec('ALTER TABLE telemetry_samples ADD COLUMN bearing_deg REAL NOT NULL DEFAULT 0') } catch {
  // Már megvan.
}
for (const column of ['matched_lat REAL', 'matched_lon REAL', 'road_bearing_deg REAL']) {
  try { db.exec(`ALTER TABLE telemetry_samples ADD COLUMN ${column}`) } catch { /* már megvan */ }
}

db.exec('CREATE INDEX IF NOT EXISTS idx_points_time ON track_points(track_id, time)')
db.exec('CREATE INDEX IF NOT EXISTS idx_telemetry_time ON telemetry_samples(track_id, time)')
db.exec('CREATE INDEX IF NOT EXISTS idx_tracks_start ON tracks(start_time DESC)')

db.exec(`
  CREATE TABLE IF NOT EXISTS track_analysis (
    track_id       INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
    payload        TEXT    NOT NULL,
    -- Hány pontból készült: ha a túra közben nőtt, újra kell számolni.
    point_count    INTEGER NOT NULL,
    limit_coverage REAL    NOT NULL DEFAULT 0,
    created_at     INTEGER NOT NULL
  )
`)

try { db.exec('ALTER TABLE track_analysis ADD COLUMN telemetry_count INTEGER NOT NULL DEFAULT 0') } catch {
  // Már megvan.
}

db.exec(`
  CREATE TABLE IF NOT EXISTS ride_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    track_id    INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
    event_time  INTEGER NOT NULL,
    kind        TEXT    NOT NULL,
    score       REAL    NOT NULL DEFAULT 0,
    label       TEXT,
    created_at  INTEGER NOT NULL,
    updated_at  INTEGER NOT NULL,
    UNIQUE(track_id, event_time, kind)
  )
`)
db.exec('CREATE INDEX IF NOT EXISTS idx_ride_events_track ON ride_events(track_id, event_time)')

db.exec(`
  CREATE TABLE IF NOT EXISTS road_points (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    source      TEXT    NOT NULL,          -- 'osm' | 'waze'
    external_id TEXT    NOT NULL UNIQUE,
    kind        TEXT    NOT NULL,          -- SPEED_CAMERA | POLICE | ACCIDENT | TRAFFIC_SIGNALS | OTHER
    lat         REAL    NOT NULL,
    lon         REAL    NOT NULL,
    road        TEXT,
    description TEXT,
    speed_limit INTEGER,
    reported_at INTEGER,
    fetched_at  INTEGER NOT NULL,
    -- Az állandó elemeknél (mérő, lámpa) NULL: sosem jár le.
    expires_at  INTEGER
  )
`)

db.exec('CREATE INDEX IF NOT EXISTS idx_road_points_kind ON road_points(kind)')

db.exec(`
  CREATE TABLE IF NOT EXISTS speed_segments (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    limit_kmh  INTEGER NOT NULL,
    -- 1, ha nem kiírt érték, hanem az úttípus szerinti alapérték.
    guessed    INTEGER NOT NULL DEFAULT 0,
    road       TEXT,
    geometry   TEXT    NOT NULL,
    -- A befoglaló téglalap: ebből tudjuk gyorsan kiválasztani a látható részt.
    min_lat    REAL    NOT NULL DEFAULT 0,
    max_lat    REAL    NOT NULL DEFAULT 0,
    min_lon    REAL    NOT NULL DEFAULT 0,
    max_lon    REAL    NOT NULL DEFAULT 0,
    -- Melyik csempéből származik (megyényi területet csempénként töltünk).
    tile       TEXT,
    fetched_at INTEGER NOT NULL
  )
`)

// A régebbi adatbázisokban ezek az oszlopok még nincsenek meg; az index csak
// utánuk jöhet létre.
for (const column of ['min_lat REAL NOT NULL DEFAULT 0', 'max_lat REAL NOT NULL DEFAULT 0',
  'min_lon REAL NOT NULL DEFAULT 0', 'max_lon REAL NOT NULL DEFAULT 0', 'tile TEXT']) {
  try {
    db.exec(`ALTER TABLE speed_segments ADD COLUMN ${column}`)
  } catch {
    // Már megvan.
  }
}

db.exec('CREATE INDEX IF NOT EXISTS idx_segments_bbox ON speed_segments(min_lat, max_lat, min_lon, max_lon)')

db.exec(`
  CREATE TABLE IF NOT EXISTS settings (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
  )
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS layer_state (
    layer      TEXT    PRIMARY KEY,        -- cameras | signals | speedlimits | waze
    updated_at INTEGER NOT NULL DEFAULT 0, -- utolsó SIKERES frissítés
    count      INTEGER NOT NULL DEFAULT 0,
    status     TEXT    NOT NULL DEFAULT 'idle', -- idle | running | error
    error      TEXT
  )
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS api_usage (
    month TEXT    PRIMARY KEY,             -- 'YYYY-MM'
    calls INTEGER NOT NULL DEFAULT 0
  )
`)

// A szolgáltató maga is visszaadja a maradék keretet a válasz fejlécében;
// ez pontosabb, mint a saját számlálónk, ezért eltesszük.
for (const column of ['provider_remaining INTEGER', 'provider_limit INTEGER']) {
  try {
    db.exec(`ALTER TABLE api_usage ADD COLUMN ${column}`)
  } catch {
    // Már megvan – a SQLite nem ismeri az IF NOT EXISTS-t oszlopra.
  }
}

db.exec(`
  CREATE TABLE IF NOT EXISTS geocode_cache (
    key        TEXT    PRIMARY KEY,
    payload    TEXT    NOT NULL,
    created_at INTEGER NOT NULL
  )
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS live_state (
    device_uid TEXT PRIMARY KEY,
    payload    TEXT    NOT NULL,
    updated_at INTEGER NOT NULL
  )
`)

// --- Útvonaltervezés: a személyes sebességmodell táblái -----------------------

db.exec(`
  CREATE TABLE IF NOT EXISTS track_match (
    track_id    INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
    -- A 20 méterenként újramintázott, úthálózatra illesztett nyomvonal, plusz a
    -- túra hozzájárulása az összesítőkhöz. Azért tesszük el a hozzájárulást is,
    -- hogy újraszinkronnál pontosan ki lehessen vonni, és ne duplázódjon.
    payload     TEXT    NOT NULL,
    -- A gyorsítótár kulcsa: ha a túra pontszáma nőtt, újra kell illeszteni.
    point_count INTEGER NOT NULL,
    telemetry_count INTEGER NOT NULL DEFAULT 0,
    matched_m   REAL    NOT NULL DEFAULT 0,
    unmatched   INTEGER NOT NULL DEFAULT 0,
    status      TEXT    NOT NULL DEFAULT 'ok',
    error       TEXT,
    created_at  INTEGER NOT NULL
  )
`)

try { db.exec('ALTER TABLE track_match ADD COLUMN telemetry_count INTEGER NOT NULL DEFAULT 0') } catch {
  // Már megvan.
}

db.exec(`
  CREATE TABLE IF NOT EXISTS ride_profile (
    -- "A" szint: hogyan megyek ilyen TÍPUSÚ úton. Ez olyan úton is működik,
    -- amin még sosem jártam - és mivel a 65 ezer pontod alig 379 km egyedi utat
    -- fed le, a tervezett útvonalak túlnyomó részén ez dolgozik.
    corner_class TEXT    NOT NULL,
    road_class   TEXT    NOT NULL,
    limit_class  INTEGER NOT NULL,
    samples      INTEGER NOT NULL DEFAULT 0,
    -- 5 km/h-s vödrök 0..200 között: a percentilis így összeadás, nem rendezés.
    histogram    TEXT    NOT NULL,
    sum_v        REAL    NOT NULL DEFAULT 0,
    sum_v2       REAL    NOT NULL DEFAULT 0,
    updated_at   INTEGER NOT NULL,
    PRIMARY KEY (corner_class, road_class, limit_class)
  ) WITHOUT ROWID
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS ride_cells (
    -- "B" szint: hogyan megyek EZEN a konkrét úton. A cella ~100 m, az irány
    -- 8 szektor - a szembe irányban mért sebesség másra vonatkozik (más ív,
    -- más elsőbbség, más emelkedő), ezért nem közösíthető.
    cell_lat   INTEGER NOT NULL,
    cell_lon   INTEGER NOT NULL,
    octant     INTEGER NOT NULL,
    samples    INTEGER NOT NULL DEFAULT 0,
    tracks     INTEGER NOT NULL DEFAULT 0,
    histogram  TEXT    NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (cell_lat, cell_lon, octant)
  ) WITHOUT ROWID
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS model_calibration (
    -- A modell és a valóság aránya túránként. Ebből jön a leggyorsabb/átlagos/
    -- leglassabb - NEM a szakaszonkénti percentilisek összegéből, mert az
    -- tökéletes korrelációt feltételezne és irreálisan széles sávot adna.
    track_id     INTEGER PRIMARY KEY REFERENCES tracks(id) ON DELETE CASCADE,
    predicted_ms INTEGER NOT NULL,
    actual_ms    INTEGER NOT NULL,
    stopped_ms   INTEGER NOT NULL,
    distance_m   REAL    NOT NULL,
    ratio        REAL    NOT NULL,
    -- 0, ha kizártuk: rossz illesztés, elfelejtett leállítás, túl rövid túra.
    usable       INTEGER NOT NULL DEFAULT 1,
    reason       TEXT,
    created_at   INTEGER NOT NULL
  )
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS routes (
    -- Elmentett útvonalak. A tervet is eltesszük, hogy net nélkül is
    -- megnyitható legyen ugyanaz, amit a telefon utoljára látott.
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    waypoints  TEXT    NOT NULL,
    style      TEXT    NOT NULL DEFAULT 'FAST',
    plan       TEXT,
    distance_m REAL    NOT NULL DEFAULT 0,
    avg_ms     INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )
`)

db.exec(`
  CREATE TABLE IF NOT EXISTS map_areas (
    -- Kézzel megrajzolt, címkézett területek a webes felületről. Ezek
    -- erősebbek az OpenStreetMap-becslésnél: ha egy megállás ide esik, a
    -- találgatásnak vége, ez a hely.
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL,
    -- Melyik megállástípust adja: SHOP | FUEL | PLACE | PARKING | SIGNAL | ROAD
    kind       TEXT    NOT NULL DEFAULT 'SHOP',
    note       TEXT,
    -- A sokszög pontjai JSON-ben: [[lat,lon], ...]
    polygon    TEXT    NOT NULL,
    -- Befoglaló téglalap, hogy a keresés ne olvassa végig az összeset.
    min_lat    REAL    NOT NULL DEFAULT 0,
    max_lat    REAL    NOT NULL DEFAULT 0,
    min_lon    REAL    NOT NULL DEFAULT 0,
    max_lon    REAL    NOT NULL DEFAULT 0,
    -- A feltöltött logó; a telefon körbe vágva rajzolja ki.
    logo       BLOB,
    logo_mime  TEXT,
    logo_at    INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
  )
`)

db.exec('CREATE INDEX IF NOT EXISTS idx_areas_bbox ON map_areas(min_lat, max_lat, min_lon, max_lon)')

db.exec('CREATE INDEX IF NOT EXISTS idx_calibration_usable ON model_calibration(usable, ratio)')

/** Rövid segéd: egy sor. */
export const one = (sql, ...params) => db.prepare(sql).get(...params)

/** Rövid segéd: több sor. */
export const all = (sql, ...params) => db.prepare(sql).all(...params)

/** Rövid segéd: írás, a beszúrt azonosítóval. */
export const run = (sql, ...params) => db.prepare(sql).run(...params)

/** Tranzakció: a szinkron egy telefonos csomagja vagy egyben megy be, vagy sehogy. */
export function tx(fn) {
  db.exec('BEGIN')
  try {
    const result = fn()
    db.exec('COMMIT')
    return result
  } catch (error) {
    db.exec('ROLLBACK')
    throw error
  }
}

export { dbPath }
