# NX500 CAN/OBD2 motortelemetria – ESP32 (XIAO C3) → Android BLE

## Context

A meglévő "Motor Telemetria" Android app GPS-alapú túranaplót vezet és szinkronizál egy
saját szerverre. A cél: a 2026-os Honda NX500 motor CAN-buszáról (a gyári 6-tűs
diagnosztikai csatlakozón, egy passzív Honda-6pin→OBD2-16pin kábelen és egy SN65HVD230
CAN-transceiveren keresztül) egy **Seeed XIAO ESP32-C3**-mal élő motoradatokat
(fordulatszám, hőfok, stb.) kiolvasni, és Bluetooth LE-n az appnak elküldeni, úgy hogy az
ESP32 **állandóan rá van kötve** a motorra.

Két dolgot kellett előbb tisztázni, mielőtt kód készül:

1. **Megvalósítható-e** technikailag? — Igen: a NX500 az Euro5+ szabvány (2024-től minden
   újonnan homologált motornál kötelező) miatt **szabványos OBD2 protokollt** beszél a
   CAN-buszon (SAE J1979 Mode 01 PID-ek, ISO 15765-4), nem kell Honda-specifikus
   proprietary CAN-üzeneteket visszafejteni. A megvásárolt kábel egy passzív
   lábkiosztás-adapter, az SN65HVD230 pedig pont illik az ESP32 beépített TWAI
   (CAN) vezérlőjéhez, SPI nélkül.
2. **Lemeríti-e az akkumulátort**, ha mindig rá van kötve? — **Igen, lemerítené**, ha nem
   csinálunk semmit ellene. Az OBD2 diagnosztikai csatlakozók tápláb-a szabvány szerint
   (SAE J1962) szinte mindig **közvetlenül az akkuról jön, gyújtástól függetlenül** — ez a
   NX500-nál is valószínű (multiméterrel ellenőrizendő az első bekötéskor). A NX500
   akkumulátora kicsi (12V 7,4Ah), a motor saját nyugalmi fogyasztása (ECU, riasztó, óra)
   már így is ~10-50mA — egy energiakezelés nélküli ESP32+BLE+CAN kombó (jellemzően
   30-100+ mA) ezt a kis akkut **1-3 nap alatt lemerítené**. Megoldás: **kötelező** deep
   sleep + "ébredés CAN-aktivitásra" firmware-logika. A kiválasztott hardver (XIAO
   ESP32-C3: ~44µA deep sleep-ben; SN65HVD230: ~370µA standby módban) így parkolva
   **< 1mA** összfogyasztást tesz lehetővé — ezt a bekötés után ténylegesen le kell mérni,
   mielőtt felügyelet nélkül hagynánk a motoron.

**A munka két szakaszra bomlik, és ez a terv csak az 1. szakaszt fedi le kóddal:**

- **1. szakasz (ez a terv)**: egy "felfedező" eszköz — a firmware minden szabványos OBD2
  PID-et felderít és lekérdez, amit a NX500 ECU-ja támogat, és az összeset elküldi BLE-n
  egy egyszerű Android debug-képernyőnek, hogy **lásd, milyen adatok érhetők el
  egyáltalán**, mielőtt eldöntenéd, mi kell pontosan.
- **2. szakasz (később, külön)**: a kiválasztott mezők beépítése a túranapló
  adatmodelljébe (`TrackPoint`/`Track`, Room migráció, `SyncManager`, szerver, Settings UI)
  — ennek a részletes architektúráját már felderítettük (lásd lent, "2. szakasz vázlat"),
  de kód csak azután készül, hogy eldőlt, mely PID-ek maradnak.

---

## Hardver és bekötés (1. szakasz)

Meglévő alkatrészek: Honda 6pin→OBD2 16pin adapterkábel (passzív), SN65HVD230 CAN
transceiver modul, Seeed XIAO ESP32-C3.

**Még beszerzendő:**
- egy kis step-down (buck) DC-DC modul, széles bemenettel (a motoros 12V rendszer
  töltés/load-dump alatt jóval 12V fölé mehet — olyat válassz, ami ≥6–30V bemenetet bír),
  szabályozott **5V** kimenettel, ≥300mA
- egy ~1A-es betétbiztosíték a tápcsapra
- egy kis Schottky dióda (Seeed saját ajánlása szerint az 5V lábra, hogy USB
  visszatáplálás ne történhessen, ha közben USB-n is rá van dugva programozáshoz)

**Bekötés:**
| Honnan | Hova | Megjegyzés |
|---|---|---|
| OBD2 16pin adapter, 16-os láb (akku+) | biztosíték → buck IN+ | **multiméterrel ellenőrizd bekötés előtt**, hogy tényleg akku+ (gyújtástól független) |
| OBD2 adapter, 4/5-ös láb (föld) | buck IN−, közös föld | |
| Buck OUT+ (5V) | dióda → XIAO **5V** láb | ne a 3V3 vagy BAT+ lábra — l. indoklás lent |
| Buck OUT− | XIAO **GND** | |
| SN65HVD230 VCC | XIAO **3V3** | a transceiver csak 3,3V logikát bír, 5V-ot soha |
| SN65HVD230 GND | XIAO GND | közös föld |
| SN65HVD230 CANH / CANL | OBD2 adapter 6-os / 14-es láb | |
| SN65HVD230 TXD | XIAO **D1** (GPIO3) | TWAI TX |
| SN65HVD230 RXD | XIAO **D2** (GPIO4) | TWAI RX **és** deep-sleep ébresztő láb |
| SN65HVD230 RS (standby vezérlés) | XIAO **D3** (GPIO5) | LOW=normál, HIGH=standby |

Miért pont D1/D2/D3 (GPIO3/4/5)? Az ESP32-C3-on csak a GPIO0-5 tud deep sleep-ből
ébreszteni (RTC-domain), de a GPIO2/8/9 ún. "strapping" láb (boot módot befolyásol) —
ezeket kerülni kell mint ébresztő/CAN vonal. A GPIO3/4/5 (XIAO D1/D2/D3) RTC-képes **és**
nem strapping — ez a biztonságos választás.

**Fontos figyelmeztetés a XIAO 5V lábáról**: az 5V láb az USB VBUS sín, nem szabályozott
kimenet — de külső 5V betáplálásra alkalmas (ezért kell rá a fent említett dióda), és ez
táplálja az áramkör saját 3,3V-os szabályozóját. A BAT+/BAT− lábak egy Li-ion töltő
áramkörhöz tartoznak (3,7-4,2V, saját töltésvezérléssel) — **ne ezt használd** motoros
tápra, mert a töltő IC battery nélkül/külső feszültséggel kiszámíthatatlanul viselkedhet.

A CAN modulon ellenőrizd, hogy a **120Ω záróellenállás jumper NINCS áthidalva** (a buszt
már lezárja az ECU és a műszeregység — egy harmadik lezárás megzavarná a buszimpedanciát).

---

## Firmware (1. szakasz) — új `firmware/esp32-obd/` PlatformIO projekt

PlatformIO + Arduino keret, `board = seeed_xiao_esp32c3`. BLE-hez `NimBLE-Arduino`
könyvtár (kicsi, RAM-takarékos, támogatja a C3-at).

```ini
[env:xiao_esp32c3]
platform = espressif32
board = seeed_xiao_esp32c3
framework = arduino
lib_deps =
    h2zero/NimBLE-Arduino
```

**Állapotgép** (kötelező elem, nem opcionális — ez védi az akkumulátort):

1. **BOOT** — minden ébredés egy teljes újraindítás (deep sleep után nincs élő RAM az
   NVS/RTC memórián kívül). NVS-ből betöltjük az utoljára sikeres CAN sebességet.
2. **BAUD_DISCOVERY** — ha nincs cache-elt/nem válaszol: 500 kbps, majd 250 kbps
   próbálása: egy szabványos `0x7DF` Mode 01 PID `0x00` kérés kiküldése, várakozás
   `0x7E8` válaszra (~150ms timeout). Siker esetén a sebesség NVS-be mentve.
3. **PID_DISCOVERY** — a támogatott PID-ek felderítése a szabványos bitmask-kéréssel
   (`0x00`, majd ha a válasz jelzi, `0x20`, `0x40`, `0x60`, `0x80`... egészen addig, amíg a
   válasz "van még köv. blokk" bitje él) — ez adja meg, mely PID-eket **érdemes**
   egyáltalán lekérdezni ezen a motoron.
4. **ACTIVE** — a felderített PID-ek round-robin lekérdezése és dekódolása (szabványos
   SAE J1979 képletekkel, pl. RPM=`((A*256)+B)/4`, hőfok=`A-40`, sebesség=`A` stb.),
   BLE hirdetés + kapcsolat, minden ~1 másodpercben egy JSON csomag az összes aktuális
   PID-értékkel. Minden vett CAN keret (nem csak a sajátunk) frissíti az
   "utolsó buszaktivitás" időbélyeget.
5. **INACTIVITY_TIMEOUT** — ha ~15-20 másodpercig semmi keret nem jön a buszon
   (= gyújtás lekapcsolva), BLE leáll, TWAI leáll, RS láb HIGH-ra (transceiver standby),
   és `esp_deep_sleep_start()`. A RS láb állapotát **hold**-olni kell
   (`gpio_hold_en`/`rtc_gpio_hold_en`) elalvás előtt, különben a láb elveszti az
   állapotát alvás közben és a transceiver visszaugorhat normál (nagyobb fogyasztású)
   módba.
6. Ébresztő forrás: `esp_sleep_enable_ext1_wakeup` a D2 (GPIO4) lábon, "ANY_LOW" módban
   (a CAN busz nyugalmi állapotban recesszív=magas, egy keret domináns bitje lehúzza
   a transceiver RXD kimenetét — ez ébreszt). Az SN65HVD230 standby módban is aktívan
   tartja a vevőt (ez a megkülönböztető tulajdonsága a HVD231-hez képest), tehát az RXD
   valóban tükrözi a buszforgalmat alvás közben is.

**BLE (1. szakasz — felfedező mód, nem véglegesített protokoll)**: egyetlen custom
service, egy "telemetry" notify characteristic, ami ~1 másodpercenként egy JSON stringet
küld az összes aktuálisan ismert PID-del (`{"rpm":3200,"coolant_c":78,"speed_kmh":42,...}`)
— szándékosan JSON és nem tömör bináris struct, mert ez a szakasz a felfedezésről szól,
nem az energia-optimalizált végleges formátumról. Ha a JSON hossz meghaladja az MTU-t,
kérj nagyobb MTU-t (`NimBLEDevice::setMTU(247)` és Android oldali `requestMtu`).

---

## Android (1. szakasz) — új, önálló "OBD debug" képernyő

Ez **nem** épül be a túranaplóba/Room adatbázisba — külön, egyszerű eszköz, hogy lásd az
élő adatokat.

- Új package: `hu.motor.telemetria.ble`
  - `ObdBleManager.kt` — a meglévő `BikeBluetooth.kt` mintáját követő singleton
    (`SharedPreferences`-es eszköz-mentés, `hasPermission()` minta), de BLE
    scan/connect/GATT logikával (a `BikeBluetooth` klasszikus BT/bonded-eszköz mintája
    itt nem elég, mert az ESP32 nem párosított eszköz — élő BLE scan kell,
    `ScanFilter.setServiceUuid(...)`).
  - Egy `StateFlow<Map<String, Double>>` a legutóbb vett PID-értékekkel.
- Új képernyő (pl. `ObdDebugActivity.kt` + egyszerű `RecyclerView` vagy lista-lapozó
  `TextView`), ami a `SettingsActivity`-ból nyitható (egy "Motor diagnosztika (kísérleti)"
  gombbal, a meglévő Bluetooth-szekció mintáját követve, lásd `SettingsActivity.kt:52-72`
  és `134-220`), és élőben listázza az összes vett PID-et névvel/értékkel.
- Manifest: `BLUETOOTH_SCAN` (`neverForLocation` flag-gel, mivel csak service UUID
  szerint szűrt scan-elést csinálunk, nem helymeghatározásra) — a meglévő
  `BLUETOOTH_CONNECT` (`AndroidManifest.xml:17`) újrahasználható.

---

## 2. szakasz vázlat (később, csak folytonosság kedvéért — nincs benne kód ebben a körben)

Ha eldőlt, mely PID-ek maradnak, ez épülhet be a túranaplóba:

- Új `EnginePoint` Room entitás (nem a meglévő `TrackPoint` bővítése — más a
  mintavételi ütem, és így egy régi szerver/build érintetlen marad), `MIGRATION_8_9`
  `AppDatabase.kt`-ban a meglévő `MIGRATION_7_8` mintájára.
- `TrackingService.kt` `onLocationChanged()` (486-496) és `startTicker()` (540-600)
  köti be az `ObdBleManager.latest` értékét minden GPS-fixhez / minden másodperces
  tickhez.
- `SyncManager.kt` `trackJson()` (166-195) és `pushLive()` (201-231) viszi tovább a
  szerverre; `server/src/db.js` új `engine_points` tábla, `server/src/routes/sync.js`
  új insert-ág — a `/live` végpont már JSON-t ment nyersen, ott nem kell módosítás.
- `SettingsActivity.kt`-ban a végleges "OBD eszköz" párosító UI a meglévő Bluetooth-
  szekció mintájára (ekkor válthatjuk le rá az 1. szakasz ideiglenes debug-képernyőjét).

---

## Ellenőrzés / tesztsorrend

**A) Csak asztalon, motor nélkül:**
1. ESP32 + SN65HVD230 egy második CAN-node-dal (pl. egy másik ESP32 egyszerű
   periodikus-üzenetküldő szkiccsel, vagy egy USB-CAN adapter), 120Ω lezárással mindkét
   végen — ellenőrzi a TWAI init/TX/RX-et és a H/L bekötést, mielőtt bármi a motorhoz
   kerülne.
2. Deep sleep/ébresztés állapotgép tesztelése ezen a pad-en: keretek befecskendezése
   alvó ESP32-nek, ébredés+visszaalvás ellenőrzése. **Itt mérd le ténylegesen** a deep
   sleep áramfelvételt (multiméter/USB teljesítménymérő) — csak akkor higgy a
   tervezett <1mA célnak, ha mérted.
3. BLE JSON-stream ellenőrzése egy generikus eszközzel (pl. nRF Connect), mielőtt az
   Android app elkészül.
4. Android `ObdBleManager` + debug-képernyő tesztelése ez ellen a pad ellen, valós motor
   nélkül.

**B) Valós OBD2 csatlakozó, álló motor:**
1. Bekötés előtt **multiméterrel ellenőrizd** az olcsó adapterkábel tényleges
   bekötését a 4/5 (föld), 6 (CAN-H), 14 (CAN-L), 16 (akku+) lábakon.
2. Gyújtás ON, motor áll: csatlakoztasd a pad-en már bevált ESP32-t, ellenőrizd hogy a
   baud-felismerés és a PID-felderítés lát választ.
3. Gyújtás OFF: ellenőrizd, hogy a busz valóban elnémul és az ESP32 a beállított időn
   belül elalszik. Először **felügyelt**, rövid ideig hagyd rákötve (ne egész éjszakára),
   és mérd le a tényleges áramfelvételt a biztosítéknál/akkumulátornál, mielőtt egy
   hosszabb, felügyelet nélküli (pl. egész éjszakás) tesztre mennél.
4. Csak azután tekintsd biztonságosnak az állandó bekötést, hogy egy több órás/éjszakai
   teszt is <1mA körüli parkolt fogyasztást igazolt.

**C) Vezetés közben:**
1. Rövid teszttúra a teljes riggel, app-recording mellett: BLE újracsatlakozás
   ellenőrzése az ESP32 saját alvás/ébredés ciklusai közben.
2. A debug-képernyőn megnézett PID-lista alapján eldönteni, mely mezők kellenek a
   2. szakaszhoz.

## Érintett/létrehozandó fájlok

- `firmware/esp32-obd/platformio.ini`, `firmware/esp32-obd/src/main.cpp` (új)
- `app/src/main/java/hu/motor/telemetria/ble/ObdBleManager.kt` (új)
- `app/src/main/java/hu/motor/telemetria/ble/ObdDebugActivity.kt` (új, + egyszerű layout)
- `app/src/main/java/hu/motor/telemetria/SettingsActivity.kt` (egy gomb hozzáadása a
  debug-képernyő megnyitásához)
- `app/src/main/AndroidManifest.xml` (`BLUETOOTH_SCAN` permission,
  `android.hardware.bluetooth_le` feature, új Activity bejegyzés)
