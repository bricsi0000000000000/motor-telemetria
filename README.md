# Motor Telemetria

Android alkalmazás motoros túrák GPS-alapú rögzítéséhez. A nyomvonalat élőben
rajzolja a térképre, közben sebességet, távot és időt számol – és mindent
folyamatosan feltölt a saját szerverre, ami a túrákat részletesen ki is elemzi.

Két rész:

| Rész | Hol fut | Mit csinál |
|---|---|---|
| `app/` | telefon (Android) | mérés, térkép, túranapló, statisztika, elemzés, szinkron |
| `server/` | a szerver gépe | Node/Express API + SQLite, túraelemzés, szolgáltatásként indul |
| `server/valhalla/` | a szerver gépe | helyi útvonaltervező motor (Docker), az útvonaltervezéshez |

## Mit tud

- **Fülek**: alul három fül – **Térkép** (a mérés főképernyője), **Túrák**
  (napló) és **Statisztika** (összesítők, elemzések). A beállítások (helyek,
  szerver, téma) a fülek jobb felső fogaskerekével nyílnak.
- **Automatikus indítás**: ha elhagyod valamelyik megadott helyet (otthon,
  munkahely, célpont), magától elindul a mérés, megérkezve pedig lezárul. A
  helyek a térképen is látszanak.
- **Térkép**: OpenStreetMap (osmdroid) – ingyenes, nem kell hozzá API kulcs
  és regisztráció sem.
- **Szinkron**: minden pont és összesítő felmegy a szerverre; net hiányában
  kivár, és a következő kapcsolatnál magától pótol.
- **Élő helyzet**: amint van GPS jel, a térképen látszik a pozíció és a
  pontossági kör – mérés nélkül, már az app megnyitásakor is.
- **Widgetek**: két kezdőképernyős widget (műszerfal gombokkal, illetve kompakt
  gyorsvezérlő), hogy a telefon előkapkodása nélkül is indítható legyen a mérés.
- **Rögzítés**: előtérben futó szolgáltatás, ami zárt képernyővel és háttérbe
  tett alkalmazással is dolgozik. Az értesítésből is szüneteltethető/leállítható.
- **Mintavétel**: másodpercenként kér GPS fixet, és 3 méterenként (illetve
  legalább 5 másodpercenként) rögzít pontot – így álló helyzetben sem hízik
  feleslegesre a napló.
- **Gombok**: Indítás / Szünet / Folytatás / Leállítás.
- **Automatikus szünet**: ha öt percig egy helyben állsz, a mérés magától
  szünetel; ha a megállás helyétől 100 métert távolodsz, magától folytatódik.
- **Élő adatok**: pillanatnyi sebesség, megtett táv, eltelt idő, mozgásban
  töltött idő, átlagsebesség, maximális sebesség, tengerszint feletti magasság,
  összesített szintemelkedés, GPS pontosság és a rögzített pontok száma.
- **Túranapló**: minden lezárt túra elmentve, **napokra bontva**, az oda-vissza
  utak (pl. munkába menet és haza) párként jelölve. A Túrák fülről megnyitható. A túra
  teljes képernyős térképen nyílik, rajta egy húzható lappal (adatok, gombok,
  elemzés) – a lap félig felhúzva indul, lehúzva a térkép marad. A nyomvonal
  **GPX-be exportálható** (Strava, Komoot, OsmAnd stb. beolvassa), és törölhető.
- **Statisztika**: össztáv, összidő, átlag- és csúcssebesség, egy túrán mért
  legnagyobb emelkedő, rekordok és havi bontás – helyi adatból, tehát net nélkül
  is látszik.
- **Túraelemzés**: gyors és lassú szakaszok, meredek emelkedők és lejtők, és az
  egész összevetve azzal, hogy az adott úton mennyivel *lehetne* menni.

## Widgetek

Hosszan nyomás a kezdőképernyőn → Widgetek → Motor Telemetria.

| Widget | Méret | Tartalom |
|---|---|---|
| Motor Telemetria | 4×2 | sebesség, táv, idő, átlag + Indítás / Szünet / Stop |
| Motor – gyorsvezérlő | 3×1 | állapot, táv és egy indít-szünet váltógomb + Stop |
| Motor – statisztika | 4×2 | össztáv, túrák száma, összidő, ez a hónap, max sebesség, max emelkedő |

Az **Indítás** gomb szándékosan megnyitja az alkalmazást, nem közvetlenül a
szolgáltatásnak szól: Androidon 12-től háttérből nem indítható előtérszolgáltatás,
és így a hiányzó engedélyeket is meg tudjuk kérni. A Szünet / Folytatás / Leállítás
viszont egyenesen a szolgáltatásnak megy, mert az ilyenkor már fut – ezekhez nem
kell megnyitni az appot.

A mérős widgetek mérés közben kétmásodpercenként frissülnek. A statisztika
widget ritkábban: túra lezárásakor, túra törlésekor, a Statisztika fül
megnyitásakor és félóránként magától; koppintásra egyből a Statisztika fül
nyílik meg. Mindhárom widget a telefon nappali/éjszakai beállítását követi (az appban választott mód a widgetre nem
vonatkozik – azt a launcher rajzolja).

## Megjelenés

Kék alaphangú világos és sötét téma, a **Beállítások** képernyő *Megjelenés*
kártyáján kapcsolható: **Rendszer** (alapértelmezett), **Világos**, **Sötét**.
A választás azonnal érvényes (az AppCompat újraépíti a képernyőt), és
újraindítás után is megmarad.

Sötét módban az OSM csempéket invertáljuk, különben a fehér térkép éjszaka
vakít. A színek egyetlen névsorból jönnek (`values/colors.xml` és
`values-night/colors.xml`), a téma maga egyetlen `DayNight` stílus.

## Élő helyzet

Amíg a térkép látszik, az alkalmazás GPS-t (beltéren hálózati helyzetet) figyel,
és mutatja a pozíciót a pontossági körrel – akkor is, ha épp nem fut mérés.
Ez a figyelő csak a látható képernyőhöz kötődik, háttérben nem fogyaszt. Ha 15
másodpercen belül volt GPS fix, a pontatlanabb hálózati helyzetet figyelmen kívül
hagyjuk, hogy ne rángatózzon a jelölő.

A jobb oldali célkereszt gombbal kapcsolható a követés (a térkép megfogása
automatikusan kikapcsolja).

## Pontosság

Néhány dolog, ami nélkül a GPS-adat használhatatlan lenne:

- 35 méternél pontatlanabb fixeket eldobunk.
- Álló helyzetben a GPS "sodródik"; a távba csak a 2 méternél nagyobb és
  3 km/h feletti elmozdulást számítjuk bele, így parkolva nem kúszik a kilométer.
- A két fix közti 200 méternél nagyobb ugrás biztosan hiba, azt kihagyjuk.
- A magasság zajos, ezért a szintemelkedés 4 méteres hiszterézissel gyűlik.
- Szüneteltetéskor új szakasz kezdődik, így a térképen nem húzunk egyenest a
  szünet két vége közé (a GPX-ben ez külön `<trkseg>`) – az automatikus szünet
  is így viselkedik.
- Az automatikus szünet nem a nulla sebességre figyel, hanem arra, hogy 5 percig
  a megállás pontja körüli 25 méteres körön belül maradunk (a fix hiánya –
  garázs, alagút – is állásnak számít). A folytatáshoz kellő 100 métert szintén
  légvonalban, a szünet kezdőpontjától mérjük, így a GPS sodródása se nem
  szünetelteti, se nem indítja újra a mérést. Szünet alatt is megy a GPS, csak
  ritkábban (5 másodpercenként) – enélkül nem látszana az elindulás.
- Az automatikus folytatás a kézzel indított szünetre is érvényes: 100 méter
  után abból is magától továbbmegy a mérés.
- A túra összesítője 20 másodpercenként kimentődik, és ha a rendszer elölné a
  folyamatot, az alkalmazás a mentett pontokból folytatja a mérést.

## Túranapló

A lista naponként csoportosít, és minden nap kap egy **összefoglaló fejlécet**
(Ma / Tegnap / dátum): a nap össztávja, hány túra volt, mennyi ment el
mozgásban, a napi átlag- és csúcssebesség, valamint az összes emelkedő. Alatta
időrendben visszafelé az aznapi túrák.

Egy soron az indulás órája, a **honnan → hova**, a táv, az idő és a szinkron
állapota látszik. A "honnan hova" elsősorban a **Beállításokban megadott
helyekből** jön: ha a túra első pontja az egyik hely körében van, akkor onnan
indult.

A naplóbeli felismerés szándékosan **300 méterrel nagyobb kört** használ, mint
az automatikus indítás: ha a mérés magától indul, az első rögzített pont már a
kör szélén (vagy az első GPS fixnél kicsit azon túl) van, szigorú sugárral tehát
a saját munkahelyünk sem passzolna rá. Az automatikus indítás/leállítás köre
változatlanul pontosan akkora, amekkorára beállítottad.

Ahol nincs megadott hely, ott **betájoljuk a koordinátát**, a legpontosabb
ismert szinten:

| Amit tudunk | Amit kiír |
|---|---|
| pontos cím | `Hunyadi János utca 56/A, Leányfalu` |
| utca | `Dagálysétány utca, Budapest` |
| városrész | `Budapest, Vizafogó` |
| csak település | `Leányfalu` |

A betájolás sorrendje: helyi gyorsítótár → a telefon beépített geokódolója →
a szerver (`GET /api/geocode`, OSM Nominatim). Az eredmény mindkét oldalon
gyorsítótárba kerül (a telefonon ~11 méteres rácson), tehát ugyanaz a parkoló
csak egyszer kérdeződik le. A lista azonnal megjelenik a már ismert címekkel, a
hiányzókat háttérben tölti be, körönként legfeljebb 40-et.

Ha ugyanazon a napon van egy A→B és utána egy B→A túra, a kettőt párnak
ismerjük fel, és **oda** / **vissza** jelölést kapnak – így a napi munkába járás
két sora összetartozik, nem kell fejben párosítani. Ha egy túra ugyanonnan
indul, ahova visszaér, az **körút**. A jelölés színe a hely típusát követi
(munkahely indigó, otthon zöld, célpont rózsaszín).

## Útmenti adatok a térképen

A lefedett terület **Győr-Moson-Sopron vármegye** (kb. 105 × 80 km); az
`AREA_BBOX` környezeti változóval átállítható (`dél,nyugat,észak,kelet`).

A Térkép fülön két új gomb:

- **Útmenti adatok** (térképtű ikon): megnyitja a rétegek állapotát – melyikben
  hány elem van és **mennyi ideje frissült** –, innen indítható a frissítés.
- **Sebességhatárok** (tábla ikon): a színezett útréteg ki/be kapcsolása.

| Réteg | Forrás | Megjelenés a térképen |
|---|---|---|
| Fix sebességmérők | OSM (`highway=speed_camera`, `enforcement=maxspeed`) | piros kamera, a limit a koppintásnál |
| Lámpás kereszteződések | OSM (`highway=traffic_signals`, átkelők nélkül) | kis lámpa, csak 15-ös nagyítás fölött |
| Rendőrök, balesetek | wazeapi.com (havi 100 hívás) | jelvény / háromszög, 3 óráig, halványodva |
| Sebességhatárok | OSM (`maxspeed` vagy úttípus szerinti alapérték) | színezett utak, 14-es nagyítás fölött |

A mérők, rendőrök és balesetek mellett **koppintás nélkül is látszik a lényeg**
egy kis buborékban (mérőnél a limit, rendőrnél hogy hány perce jelentették); a
teljes leírás koppintásra jön. A lámpás kereszteződéseknél nincs buborék –
azokból sok van, és a három lámpás ikon magáért beszél.

A sebességhatár-réteg színei: ≤30 türkiz, 50 zöld, 70 sárga, 90 narancs, 100+ piros.
A **kiírt** limit folytonos vonal, az **úttípusból becsült** szaggatott. Egy
szakaszra koppintva kiírja a limitet, hogy kiírt-e vagy becsült, és az útnevet.

**Miért nem vár a gomb?** A győri úthálózat lekérése az Overpasstól percekig tart,
ezért a szerver minden réteget **háttérben** frissít, és rétegenként eltárolja,
mikor sikerült utoljára. A gomb csak elindítja a munkát és azonnal visszatér; a
párbeszédablak közben követi a haladást („frissítés folyamatban…" → „az imént").
A mérők és lámpák másodpercek alatt megvannak, az úthálózat 2–4 perc, és mivel
ritkán változik, csak hetente frissül újra.

**Mérethez képest**: a megyében ~46 fix mérő (VÉDA kapuk, Traffiboxok) és ~300
lámpás kereszteződés van az OSM-ben. Az úthálózat ennél nagyságrenddel nagyobb,
ezért a szerver **8×8 csempére bontva** tölti le (csempénként mentve, hibás
csempét újrapróbálva), a telefon pedig mindig csak a **látható területet** kéri
le, és amit egyszer letöltött, azt fájlban megtartja – így net nélkül is rajzol.

Az Overpass nyilvános kiszolgálói időnként túlterheltek (504, üres válasz),
ezért négy példányt próbálunk végig, három körben, 15 másodperces szünetekkel.
Egy megyényi úthálózat így is fél–egy óra, de a háttérben fut, és a
rétegállapotban látszik a haladás (`running 12/64`).

**Waze (rendőrök, balesetek)**: alapértelmezetten a
[wazeapi.com](https://wazeapi.com) ingyenes csomagjához illeszkedik (havi 100
hívás). **Elég a kulcsot beírni** a `server/.env` fájlba:

```
WAZE_API_KEY=ide-jon-a-kulcs
```

(vagy a szolgáltatás unitjának környezeti változói közé).

A végpontot (`https://api.wazeapi.com/v1/alerts?bottom-left=…&top-right=…`), az
`X-API-Key` fejlécet és az európai adatközpontot (`X-Country: eur`) a szerver
állítja be. Másik szolgáltatóhoz a `WAZE_API_URL`, `WAZE_KEY_HEADER`,
`WAZE_API_HOST`, `WAZE_COUNTRY` változókkal írható felül minden.

A keretet a szerver figyeli: a válasz `X-Quota-Remaining` fejlécét elhisszük (ez
pontosabb a saját számlálónknál), 0 maradéknál a Waze-réteg magától kimarad, és
két frissítés között 60 másodperc a minimum – így egy félrekoppintás sem éget
hívást.

A szolgáltató **200 elemnél levágja** a választ, ezért a lekérdezés eleve szűr:
csak rendőr, baleset, útlezárás és veszély jön (dugó nem), különben a torlódások
kiszorítanák a fontosat. A bejelentések élettartama típusfüggő – rendőr 2 óra,
baleset 4, veszély/lezárás 12, Waze-mérő 24 –, és közben halványodnak a térképen.

## Automatikus indítás (helyek)

A **Beállítások** képernyőn tetszőleges számú hely vehető fel, mindegyiknek van
típusa, neve, középpontja és sugara (alapból 300 m, 100–5000 között):

| Típus | Ikon a térképen |
|---|---|
| Otthon | zöld házikó |
| Munkahely | indigó táska |
| Célpont | rózsaszín térképtű |

A hely felvétele térképen történik: a kör közepe mindig a térkép közepe
(célkereszt), így egy célpont is megadható – nem csak az, ahol épp állsz. A
sarki gomb a jelenlegi helyzetre ugrik.

Bekapcsolva a **rendszer** figyeli a köröket (`LocationManager.addProximityAlert`),
nem egy saját szolgáltatás – ezért nincs hozzá állandó értesítés, és nyugalomban
nem fogyaszt semmit:

- **Indítás**: amint bármelyik hely köréből kilépsz, a rendszer szól, és elindul
  a mérés. (Ha be van kapcsolva a Bluetooth-feltétel, csak akkor, ha a motor
  eszköze csatlakozik.)
- **Leállítás**: mérés közben a rögzítő szolgáltatás amúgy is másodpercenként
  mér; ha bármelyik hely körén belülre érsz és 90 másodpercig állsz (7 km/h
  alatt), a túra lezárul. A puszta belépés nem elég – át is haladhatsz a körön.
  Ez hamarabb üt, mint az ötperces automatikus szünet, tehát hazaérve lezárás
  lesz, nem szüneteltetés.
- Androidon 12-től a rendszer megtagadhatja a háttérből indított mérést. Ilyenkor
  nem vész el az indulás: egy koppintható értesítés jön („Elindultál"), ami
  megnyitja az appot és elindítja a rögzítést.

### Csak a motor mellett

A **Csak a motor Bluetooth-ával** kapcsolóval megadható a motor párosított
Bluetooth-eszköze (a párosított eszközök listájából). Ezután a kilépés csak
akkor indít mérést, ha a telefon ehhez csatlakozik – így a gyalogos és autós
utak nem lesznek motortúrák.

Ha menet közben kapcsolod be a fejhallgatót (és épp nem vagy egyik helyen sem),
a csatlakozás pillanatában is elindul a mérés, hogy ne vesszen el a túra eleje.
A Bluetooth csatlakozását/bontását a rendszer üzeneteiből tudjuk, ehhez sem kell
futó szolgáltatás.

Bekapcsolt feltétel esetén a **Térkép fül** műszerfalának alján egy sor mutatja,
látja-e épp a telefon a motort (zöld pont = csatlakozva). Ez a sor csak akkor
jelenik meg, ha a Bluetooth-feltétel be van kapcsolva és van kiválasztott eszköz.

Az újraindítás utáni önindításhoz a helyzet-engedélyt „Mindig engedélyezve"
értékre kell állítani; enélkül is működik, csak reboot után egyszer meg kell
nyitni az appot. A készenlét az értesítésből egy gombbal kikapcsolható.

A helylista a telefonon tárolódik. Útvonaltervezéskor a kiválasztott pontok koordinátái a saját szerverre kerülnek.

## Beállítások

Minden beállítás egy helyen (Túrák vagy Statisztika fül → fogaskerék):

- **Helyek és automatikus indítás**: kapcsoló és a helyek listája (otthon,
  munkahely, célpontok); egy sorra koppintva szerkeszthető vagy törölhető
- **Szerver**: cím, token, szinkron állapota, „Szinkron most", kapcsolat teszt, eszközazonosító
- **Szolgáltatások**: egy gombbal végigméri, elérhető-e minden, amire az app
  támaszkodik – saját szerver, Overpass, Nominatim, OSM csempeszerver, a telefon
  geokódolója, GPS és a motor Bluetooth-a. Soronként kiírja, hogy rendben van-e,
  mennyi idő alatt válaszolt és mit mondott. A **Waze külön gombbal** fut, mert
  minden hívása fogyaszt a havi keretből.
- **Motor Bluetooth**: a kiválasztott eszköz alatt élőben látszik, látja-e épp a
  telefon (koppintásra újraellenőrzi, hosszan nyomva részletes állapot)
- **Megjelenés**: rendszer / világos / sötét téma

## Szerver és szinkron

A telefon Tailscale-en keresztül éri el a szervergépen futó backendet, a gép
tailnet IP-jén vagy MagicDNS nevén (`http://<gep>.<tailnet>.ts.net:8787`). A
tényleges cím nincs a repóban: fordításkor a `secrets.properties` adja, futás
közben pedig az app **Statisztika** fülén bármikor átírható.

Nincs felhasználókezelés: egyetlen megosztott token véd, amit a telefon az
`X-Motor-Token` fejlécben küld. A token sincs a forrásban – a szerveren a
`server/.env` (vagy a szolgáltatás unitjának környezeti változója) adja a
`MOTOR_TOKEN` változóban, a telefonon a `secrets.properties`. Alapértelmezett
értéke szándékosan nincs: hiányzó `MOTOR_TOKEN` esetén a szerver el sem indul.
Generálás:

```bash
node -e "console.log(require('node:crypto').randomBytes(24).toString('base64url'))"
```

**Mikor küld a telefon?**

| Esemény | Mi megy ki |
|---|---|
| mérés közben 3 másodpercenként | élő állapot (helyzet, sebesség, táv, idő) |
| mérés közben 15 másodpercenként | az új nyomvonalpontok és az összesítő |
| túra leállításakor | a lezárt túra azonnal |
| app megnyitásakor, hálózat visszatérésekor | ami elmaradt |
| negyedóránként a háttérben (JobScheduler) | ami elmaradt, akkor is, ha az app nem fut |

A korlátlan mobilnet miatt szándékosan sűrű a küldés; egy pont néhány tíz bájt,
és a kérés törzse gzippelve megy.

**Miért nem veszik el adat net nélkül?** Nincs külön üzenetsor: maga az
adatbázis az outbox. Minden túránál el van tárolva, hogy változott-e az
összesítője (`dirty`) és hány pontját vette át a szerver (`syncedPoints`) –
ebből bármikor kiszámolható, mi hiányzik. A szerver a `(eszköz, túraazonosító)`
párost és a pontok sorszámát nézve idempotensen fogad mindent, tehát az
újraküldés soha nem csinál duplikátumot. Ugyanígy a törlés is kivár, amíg a
szerver vissza nem igazolja.

### Backend indítása

Kézzel:

```bash
cd server && npm start
```

Tartós üzemhez a rendszer szolgáltatáskezelőjére érdemes bízni, hogy induláskor
magától feljöjjön és hiba után újrainduljon. A port, a token és a Waze-kulcs a
`server/.env` fájlból jön, de a unit környezeti változói felül is írhatják.

Az adatbázis: `server/data/motor.db` (SQLite, WAL módban).

#### systemd user service

```bash
systemctl --user status motor-telemetria      # állapot
journalctl --user -u motor-telemetria -f      # napló
```

A gép bekapcsolásakor is induljon, bejelentkezés nélkül: `loginctl enable-linger`.

#### launchd user agent

```bash
launchctl print gui/$(id -u)/com.motortelemetria.server        # állapot
launchctl kickstart -k gui/$(id -u)/com.motortelemetria.server # újraindítás
```

Az agent leírója: `~/Library/LaunchAgents/com.motortelemetria.server.plist`,
naplója: `~/Library/Logs/motor-telemetria.log`.

## Útvonaltervezés

A **Tervező** fülön köztes pontokkal lehet útvonalat összerakni (hosszú nyomás a
térképen, vagy a mentett helyek gyorsválasztói), és a becsült idő nem egy
általános átlagsebességből jön, hanem **a saját megtett túráidból**.

### Mit tud

- **Két stílus.** *Gyors*: a legrövidebb idő. *Kanyargós*: 5-6 útvonaljelöltet
  végigszámol, mindegyikre kiméri a kanyarosságot, és a legkanyargósabbat
  választja azok közül, amelyek nem lassabbak a leggyorsabb 1,4-szeresénél.
  Mindkettő **elkerüli a fizetős utakat** (matrica nélkül az M-utak nem
  használhatók).
- **Három idő**: leggyorsabb / átlagos / leglassabb, **mozgás- és állóidőre
  bontva**. A bontás nem kozmetika: ugyanazon az úton a mozgásidő ±15%-ot szór,
  a teljes idő viszont akár háromszorosát – a különbség szinte teljesen abból
  jön, mennyit álltál.
- **Kanyartípusok**: az útvonal a térképen a kanyar élessége szerint van
  színezve (hajtű piros → egyenes kék), és szakaszonként látszik a sugár, a
  sebességhatár és a becsült tempó.
- **Honnan tudja**: minden szakasznál kiírja, hogy az idő *ezen az úton* mért
  tempódból, *hasonló úton* mértből, vagy csak a geometriából becsült jön-e.
- **Átadás a Google Maps-nek**: a kész terv alatti gombbal a navigáció a Google
  Mapsben folytatható. A Maps URL API csak pontokat vesz át, nyomvonalat nem, és
  legfeljebb kilenc köztes pontot – ezért a felvett pontok mellé a szabad
  helyeket a tervezett vonalról egyenletesen vett mintapontokkal töltjük fel,
  útvonal szerinti sorrendben. Így a Maps nagyjából a mi utunkat rakja ki, nem a
  saját leggyorsabb változatát. Ha a Maps nincs telepítve, böngészőben nyílik.
- **Automatikus mentés**: minden sikeres tervezés magától bekerül a *Mentett
  útvonalak* közé (a legutóbbi 15), így a tegnap kiszámolt út holnap – net
  nélkül is – előkerül. Ugyanarra a pontsorra a meglévő tétel frissül, nem
  keletkezik új. A névvel mentett utak nem évülnek el, és kiváltják az
  ugyanarra a pontsorra automatikusan eltett tételt.

### Hogyan becsül

1. A megtett túrákat a szerver **ráilleszti az úthálózatra** (Valhalla map
   matching), és a görbületet az így kapott OSM-vonalból számolja. Nyers GPS-ből
   nem lehet: a medián pontosság 8,3 m, ami simítás nélkül hajtűkanyarokat gyárt
   ott, ahol egyenes van.
2. A mért sebességeket két szinten összesíti: **cellánként** (ez a konkrét út,
   ebben az irányban) és **típusonként** (kanyarosztály × útosztály × limit).
   Utóbbi olyan úton is működik, ahol még sosem jártál.
3. A tervezett útvonalat 20 méteres lépésekre bontja, minden lépésre célsebességet
   számol, majd **előre-hátra menetben** simítja az NX500 gyorsulásával és a te
   mért fékezési szokásoddal. Ettől lesz reális az „egyenes két kanyar között":
   150 méteren fizikailag nem érhető el az egyenes tempója.
4. A három idő nem a szakaszonkénti percentilisek összege (az tökéletes
   korrelációt feltételezne), hanem **egy modellezett idő szorozva a múltbeli
   „valóság / modell" arány percentiliseivel**.

### Amit a modell megtanult rólad

A 41 túrából (59 553 minta, 5310 cella):

| Paraméter | Érték | Mit jelent |
|---|---|---|
| Oldalgyorsulás (`a_lat`) mediánja | 0,95 m/s² | ennyivel veszed a kanyarokat; a tapadási határ ~9,8 |
| Gyorsítás / fékezés (p90) | 1,50 / 1,27 m/s² | a kényelmes tempód, nem a motor határa |
| Limitmegfelelés | 0,86 | átlagosan a kiírt sebességhatár 86%-ával mész |
| Valóság / modell arány | 0,95 / 1,03 / 1,14 | ebből lesz a leggyorsabb / átlagos / leglassabb |

### Mennyire pontos

Leave-one-out visszaméréssel (minden túrát olyan modell jósol meg, ami nem látta):

```bash
curl -H "X-Motor-Token: $MOTOR_TOKEN" http://localhost:8787/api/route/backtest
```

| Mérőszám | Eredmény | Cél |
|---|---|---|
| MAPE (mozgásidő) | **6,9%** | ≤10% jó |
| Referencia: konstans 46,5 km/h | 13% | a modell kétszer pontosabb |
| A p10–p90 sávba eső túrák | **76%** | 75–85% reális |

**Korlát, amit érdemes tudni:** a 65 ezer GPS-pontod nagyjából 379 km *egyedi*
utat fed le, és ebből csak ~79 km-en jártál többször. A „konkrét úton mért
tempód" réteg tehát főleg a rendszeres útjaidra válaszol; minden máshol az
úttípus szerinti tempód dolgozik – a felület ezt szakaszonként ki is írja.

### A tervezőmotor (Valhalla)

Saját példány fut Dockerben, a magyar OSM kivonatból építve. Azért nem a
nyilvános FOSSGIS példány: az nem adja vissza a sebességhatárokat (0/319 élen
jött vissza), a kanyargós stílus pedig tervenként 5-6 hívást igényel.

```bash
cd server/valhalla && docker compose up -d      # kézi indítás
curl http://127.0.0.1:8002/status                # él-e
```

Tartós üzemben a konténert ugyanaz a szolgáltatáskezelő hozza fel
induláskor, mint a backendet (`docker compose up -d`). A backend a
`VALHALLA_URL` környezeti változóból veszi a címet, tehát a nyilvános példány
bármikor tartalék marad. Ha a motor áll, a tervezés tiszta **503**-at ad, a
többi funkció változatlanul működik.

### A modell frissítése

Szinkron után magától lefut a háttérben. Kézzel:

```bash
curl -X POST -H "X-Motor-Token: $MOTOR_TOKEN" "http://localhost:8787/api/route/rebuild?rematch=1"
curl -H "X-Motor-Token: $MOTOR_TOKEN" http://localhost:8787/api/route/status
```

### API

Minden végpont tokent kér, kivéve a `/api/health`-et.

| Végpont | Mit ad |
|---|---|
| `POST /api/sync` | túrák és pontok fogadása a telefonról (idempotens) |
| `POST /api/live` | élő állapot mérés közben (csak az utolsó marad meg) |
| `GET /api/live` | az utolsó élő állapot |
| `GET /api/tracks` | lezárt túrák listája |
| `GET /api/tracks/:id` | egy túra a teljes nyomvonallal |
| `GET /api/tracks/:id/gpx` | GPX letöltés (`?token=…` is elég hozzá) |
| `DELETE /api/tracks/:id` | túra törlése |
| `GET /api/tracks/:id/analysis` | részletes elemzés (`?refresh=1` = újraszámolás) |
| `GET /api/roadpoints` | mérők, lámpák, bejelentések + rétegállapotok |
| `POST /api/roadpoints/refresh` | frissítés indítása háttérben (`?free=1` = Waze nélkül) |
| `GET /api/speedlimits` | sebességhatár-szakaszok a térképréteghez |
| `GET /api/stats` | összesítők, havi bontás, rekordok |

## Túraelemzés

A **Túrák** fülön megnyitott túra alján, illetve a **Statisztika** fül *Túrák
elemzése* kártyáján látszik. A számolás a szerveren fut (`server/src/analysis.js`),
mert a sebességhatárokhoz internet kell; az eredményt a telefon eltárolja, így
egyszer letöltve net nélkül is előjön.

**Mit keres?**

| Szakasz | Feltétel |
|---|---|
| Gyors | a simított sebesség legalább 60 km/h *és* a túra mozgásátlagának 1,25-szerese, legalább 300 m hosszan |
| Lassú | a mozgásátlag fele alatt (max. 25 km/h), legalább 45 másodpercig |
| Emelkedő / lejtő | 6 méteres hiszterézissel szűrt magasságból, legalább 15 m szintkülönbség; 6% felett "meredek" |
| Limit felett | a helyi sebességhatár + 5 km/h fölött, legalább 100 méteren át |

A meredek emelkedők és a limit feletti szakaszok a túra térképére is rákerülnek
külön színnel. A listában bármelyik szakaszra koppintva a lap visszaereszkedik
félig, a térkép ráközelít a szakaszra, és kiemelve tartja, amíg másikat nem
választasz.

**Honnan tudja, mennyivel lehetne menni?** A nyomvonal mentén (nem a befoglaló
téglalapra) kérdezi le az OpenStreetMap útjait az Overpass API-tól, és minden
ponthoz a legközelebbi utat párosítja – kicsit előnyben részesítve a nagyobb
rendű utat, mert egy főút melletti szervizút sokszor pár méterrel közelebb esik
a GPS-nyomhoz. Ahol nincs kiírt `maxspeed`, ott az úttípus szerinti magyar
alapérték szerepel (lakott területen kívüli főút 90, autóút 110, autópálya 130,
lakóutca 50 stb.), tehát **ez becslés, nem hatósági adat** – a lakott terület
táblát az OSM-ből nem mindig lehet kiolvasni. Az elemzés kiírja, a pontok hány
százalékához talált egyáltalán sebességhatárt.

Az első lekérdezés túránként 5–30 másodperc (Overpass), utána a szerver a
tárolt eredményt adja vissza. Az **Újraszámolás** gomb kényszeríti a friss
számolást (pl. ha időközben javult az OSM adat).

## Beállítás klónozás után

A gépfüggő adatok és a titkok (szervercím, token, Waze-kulcs) nincsenek a
repóban. Klónozás után két fájlt kell létrehozni a minták alapján – mindkettő a
`.gitignore`-ban van, tehát sosem kerül fel:

```bash
cp secrets.properties.example secrets.properties   # az Android app alapértelmezései
cp server/.env.example server/.env                 # a backend beállításai
```

| Fájl | Mi van benne | Ha hiányzik |
|---|---|---|
| `secrets.properties` | `motor.defaultBaseUrl`, `motor.defaultToken` | az app fordul, de üres szervercímmel indul – a Beállításokban megadható |
| `server/.env` | `MOTOR_TOKEN` (kötelező), `WAZE_API_KEY`, `PORT`, `HOST`, `VALHALLA_URL`, `SELFTEST_LAT`/`LON` | a szerver `MOTOR_TOKEN` nélkül nem indul el |

A `MOTOR_TOKEN` és a `motor.defaultToken` ugyanaz az érték legyen. Kiszolgálóra
telepítve a `.env` helyett a szolgáltatás unitjának környezeti változói is
adhatják ugyanezeket – a már beállított környezeti változó erősebb a fájlnál.

## Fordítás

Kell hozzá JDK 17 és az Android SDK (platform 35, build-tools 35.0.0). A
`build.sh` a szokásos helyeken keresi őket, de a `JAVA_HOME` és az
`ANDROID_HOME` környezeti változóval bárhova átirányítható:

```bash
./build.sh
```

Az APK ide kerül: `app/build/outputs/apk/debug/app-debug.apk`.

Telefonra (USB hibakeresés bekapcsolva a fejlesztői beállításokban):

```bash
./build.sh installDebug
```

Android Studio-ból is nyitható: `File → Open`, majd a projekt gyökere.

## Engedélyek

Első indításkor a Helyzet ("Pontos helyzet") és az Értesítések engedélyt kéri.
A helyzet-engedélynél az **"Alkalmazás használata közben"** is elég – az előtérben
futó szolgáltatás miatt zárt képernyővel is működik a mérés.

Ha a telefon gyártója agresszíven altatja az appokat (Xiaomi, Huawei, Samsung,
OnePlus), érdemes a rendszerbeállításokban felvenni az akkumulátor-optimalizálás
alóli kivételek közé, különben hosszú túra alatt megszakadhat a rögzítés.

## Felépítés

```
app/src/main/java/hu/motor/telemetria/
├── App.kt                      osmdroid konfiguráció + szinkron indítása
├── MainActivity.kt             a fülek váza (alsó navigáció)
├── SettingsActivity.kt         helyek + automatika, szerver, téma
├── PlaceEditActivity.kt        hely felvétele/szerkesztése térképen
├── TrackDetailActivity.kt      egy túra nyomvonala, statisztikái, GPX export
├── ui/
│   ├── MapFragment.kt          Térkép fül: élő térkép, műszerfal, gombok
│   ├── TracksFragment.kt       Túrák fül: mentett túrák listája
│   └── StatsFragment.kt        Statisztika fül: összesítők, elemzések, szerver
├── analysis/
│   ├── AnalysisModels.kt       a szervertől kapott elemzés modellje
│   └── AnalysisRepository.kt   lekérés + helyi tárolás (offline is látszik)
├── data/                       Room adatbázis (Track, TrackPoint, PendingDelete, Place, DAO)
├── net/
│   ├── ServerSettings.kt       szervercím, token, eszközazonosító
│                               (alapértelmezések: secrets.properties → BuildConfig)
│   └── ApiClient.kt            HttpURLConnection + gzip, könyvtár nélkül
├── sync/
│   ├── SyncManager.kt          mi hiányzik a szerverről, és mikor megy fel
│   └── SyncJobService.kt       negyedórás háttérpótlás (JobScheduler)
├── service/
│   ├── TrackingService.kt      előtérszolgáltatás: GPS, szűrés, számolás, mentés
│   ├── AutoStartService.kt     helyek figyelése, automatikus indítás/leállítás
│   ├── BootReceiver.kt         újraindítás után visszakapcsolja a készenlétet
│   ├── TrackingState.kt        a UI által figyelt állapot
│   └── PathBuffer.kt           a futó nyomvonal memóriában
├── widget/
│   ├── WidgetRenderer.kt       RemoteViews építés és gomb-szándékok
│   ├── TrackingWidget.kt       nagy widget
│   ├── TrackingControlWidget.kt kompakt widget
│   ├── StatsWidget.kt          statisztika widget
│   └── StatsWidgetRenderer.kt  az összesítők betöltése a widgethez
└── util/                       formázás, GPX író, otthon- és témabeállítás

server/src/
├── index.js                    express app
├── env.js                      a `server/.env` betöltése (titkok a repón kívül)
├── db.js                       SQLite séma (node:sqlite, WAL)
├── auth.js                     megosztott token ellenőrzése
├── analysis.js                 szakaszkeresés, emelkedők, limit-összevetés
├── speedlimits.js              OSM sebességhatárok (Overpass) és útpárosítás
├── gpx.js                      GPX írás
└── routes/                     sync.js, tracks.js, stats.js
```

A UI és a szolgáltatás nincs összekötve bindinggal: a szolgáltatás egy
`StateFlow`-t publikál, a fülek azt figyelik. Így a térkép akkor is helyesen
áll vissza, ha az alkalmazást közben kitették a memóriából. A fülváltás nem
építi újra a fragmenteket, csak elrejti őket – a térkép megtartja a csempéket
és a kirajzolt nyomvonalat, cserébe a `MapFragment` kézzel szünetelteti a
GPS-figyelést, amikor másik fül van elöl.

### Megszokott útvonalak és Egérút

Az Útvonal fülön válaszd ki az otthont és a munkahelyet (ebben a sorrendben,
ha odafelé tervezel). Két végpont esetén a szerver a lezárt, szinkronizált
utakból felajánlja a két leggyakoribb, geometriailag eltérő nyomvonalat.
Az ajánlat mellett az utak száma, a medián táv és a medián teljes menetidő
látszik. A hosszabb vagy lassabb jelölt halványabb, de ugyanúgy kiválasztható.
A visszaút külön számít; megfelelő előzmény nélkül nem készül kitalált ajánlat.

Az ajánlatra vagy térképi vonalára koppintás Egérutat tervez a választott
nyomvonal egy jellegzetes átmenő pontján keresztül. Az Egérút külön gombbal,
előzmény nélkül is használható. A keresés a rendelkezésre álló lámpaadatok és
balra kanyarodási manőverek alapján kerülőket vizsgál, legfeljebb 50% becsült
teljes menetidő-többlettel. Nem garantál lámpamentességet: a megmaradt ismert lámpák
és balra kanyarok száma megjelenik, hiányzó lámpaadatnál figyelmeztetéssel.
A telefon új verziója mellett a szerver frissített kódja is szükséges
(`POST /api/route/familiar`).

Ellenőrzés: `node --test server/test/*.test.js`, Android build: `./build.sh`.


### 1.2 – Napi utak és megállók

- A Tervezőben az **Otthon → Munkahely** és **Munkahely → Otthon** gombok a
  Beállításokban megadott HOME/WORK helyekből készülnek. Külön tárolják az irányok
  kötelező köztes pontjait és választható boltjait a telefonon.
- **Kötelező út / átmenő pont kijelölése** után hosszan nyomd meg a kívánt utcát.
  Több ponttal kijelölhető a kívánt folyosó, sorrendjük húzással módosítható.
  A **Jelenlegi út rögzítése ehhez az irányhoz** elmenti ezeket. A rögzített
  pontokat a következő napi út betölti, és az Egérút keresése is megtartja.
- **Bolt / választható megálló hozzáadása**: mentett hely vagy térképen megadott
  bejárat. Az **Útba ejtem** kapcsoló az adott útra iktatja be; alapból ki van
  kapcsolva. A listában a bolt sorrendje is módosítható. A bezáró ikon a mentett
  bolti opciót törli, a kikapcsolás csak az aktuális útból veszi ki.
- Az **Érkezés a cél felőli útoldalon** alapból aktív a végcélnál és a köztes
  megállóknál. Valhalla `preferred_side: same` preferenciát használ. Ha nem
  teljesíthető, vagy az útoldal nem állapítható meg, ezt a terv jelzi. A helyet
  a tényleges bejárathoz kell tenni, nem az út közepére.
- Az Egérút automatikusan keres irányhelyes előzményt a megállók közötti
  szakaszokra. A kézzel megadott kötelező pontok elsőbbséget élveznek. A
  jelöltek közül a lámpák és balra kanyarok száma alapján választ, teljes
  menetidőre számolt 50%-os kerülőkerettel. A bolti tartózkodás ideje nincs a
  menetidőben; az indulás és megállás gyorsítása/fékezése szerepel benne.

### Okos nyomvonal-megjelenítés

Az élő térkép és a túra részletei külön rajzolási geometriát kérnek a
`POST /api/track/display` végponton. Az eredeti pontok, GPX-export és mért
statisztikák változatlanok. A túra részleteinél az **Okos útra illesztés**
kapcsolóval az eredeti vonal is megtekinthető.

A rendszer a motoros mozgásra utaló pontsorokat Valhalla map matchinggel
illeszti, az út kanyarjait követő geometriával. A 3 m/s alatti haladás, séta,
parkolás, rossz pontosság, nagy illesztési eltérés és bizonytalan egyezés
nyers GPS-ként marad meg. Egyetlen sebességkiugrás nem elég a motoros
besoroláshoz. A mérési szüneteket és 30 másodpercnél nagyobb mintavételi
hézagokat nem köti össze kitalált útvonallal. A lassú motorozás egy része
szándékosan eredeti maradhat; ez óvja a bolthoz sétálás nyomvonalát.

A részletes nézet gyorsítótárazza az illesztést. Élőben legfeljebb
félpercenként az utolsó 480 pont frissül; a legújabb pontok azonnal láthatók
nyersen. Hálózat vagy tervezőmotor nélkül az eredeti vonal megmarad.
Az útvonaltervezés és illesztés a kiválasztott helyek/pontok koordinátáit a
beállított saját szervernek küldi; a helylista és az iránybeállítások a
telefonon tárolódnak.

### Megállásikonok

Az élő térkép és a túra részletei megjelölik a legalább 15 másodperces,
helyben maradással járó lassú szakaszokat. Az úton/lámpánál várakozás,
bevásárlás, tankolás, parkolás és más hely felkeresése külön ikont kap.
A típus a megállás időtartama, GPS-pontossága, közúttól mért távolsága és
az OpenStreetMap közeli helyei alapján **becslés**. Egy bolt közelsége
önmagában nem jelenti, hogy vásároltál: bizonytalan helyzetben általános
megállás marad. Egy közeli lámpa sem bizonyítja, hogy piros volt.

Az ikonra koppintva megjelenik az időtartam és a besorolás oka; a
**Típus javítása** választással a helyes kategória eltárolható a telefonon,
az automatikus besorolás pedig visszaállítható. Internet nélkül is vannak
általános megállásjelölők. A helyazonosítás a `POST /api/track/stops`
végponton fut; az OSM-helyeket hét napig gyorsítótárazzuk. Ehhez a szerver
az érintett megállók környezetét lekérdezi az Overpass szolgáltatástól.

### 1.4 – Egyszerűbb útvonalnézet

Az elméleti motorsebesség, az ehhez kapcsolódó színezés és elméleti menetidő
kikerült az appból és a tervező válaszából. A térkép a kanyartípusok szerinti
színezést, a szakaszlista a megszokott tempó becslését mutatja. A korábbi
mentett útvonalak továbbra is megnyithatók; a régi elméleti adatokat az app
figyelmen kívül hagyja. A szakaszkiemelés türkiz, a második megszokott útvonal
zöldeskék színt kapott.

### 1.5 – Útvonaltervező lapmagasság

A tervező felhúzható lapjának maximális magassága a fülsáv fölötti konténer
magassága mínusz a lap felső eltolása. Így teljesen felhúzva az utolsó sor és
az alsó térköz is elérhető görgetéssel. A méret képernyőfordításkor és
ablakméret-változáskor újraszámolódik.

### 1.6 – Google Maps átadás és automatikus tervmentés

A kész terv alatt megjelent a **Megnyitás Google Maps-ben** gomb. A Maps URL API
nyomvonalat nem vesz át, csak pontokat, és legfeljebb kilencet: ezért a felvett
pontok mellé a szabad helyeket a tervezett vonalról egyenletesen vett
mintapontokkal töltjük fel, útvonal szerinti sorrendben. Így a Maps nagyjából a
tervezett utat rakja ki, nem a saját leggyorsabb változatát. Kilencnél több
felvett köztes pontnál ritkítunk, és erről szólunk. Ha nincs Google Maps,
böngészőben nyílik meg.

Minden sikeres tervezés magától bekerül a **Mentett útvonalak** közé (a
legutóbbi 15), a végpontokból képzett névvel, „Legutóbb tervezett" jelöléssel.
Ugyanarra a pontsorra a meglévő tétel frissül, nem keletkezik új; a névvel
mentett utak nem évülnek el, és kiváltják az automatikus tételt. A teljes
szerverválasz is eltevődik, így a terv net nélkül is megnyitható. Az
automatikus tételek a szerverre nem kerülnek fel, csak a kézzel mentettek.
Adatbázis-séma: 7 → 8 (`saved_routes.autoSaved`), migrációval.
