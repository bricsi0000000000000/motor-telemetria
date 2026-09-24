/**
 * A jármű fizikai paraméterei: 2026 Honda NX500.
 *
 * FONTOS, mert megfordítja, amit az ember először gondolna: a MÉRT adataidon a
 * p99 oldalirányú gyorsulás 2,54 m/s², vagyis 0,26 g. A gumi tapadási határa
 * ~1,0 g körül van, tehát a tapadás gyakorlatilag SOHA nem korlátoz. Ugyanez
 * a hosszanti irányban: a mért p99 gyorsításod 2,20 m/s², a motor ~3,5-öt tudna.
 *
 * Ezért a kanyarsebességet nem a fizikából számoljuk, hanem a te szokásodból
 * (lásd ridermodel.js). Az itteni értékek VÉSZFÉKEK: olyan felső korlátok,
 * amik csak a tartomány tetején lépnek működésbe - és ott tényleg kellenek,
 * mert ~110 km/h felett a 35 kW valóban elfogy.
 */

export const NX500 = {
  name: 'Honda NX500 2026',

  // Menetkész tömeg + vezető felszereléssel + csomag.
  massBikeKg: 196,
  massRiderKg: 85,
  massLuggageKg: 8,

  // 47,5 LE @ 8600 f/p. A hajtáslánc vesztesége után a kerékre ~31,5 kW jut.
  powerKw: 35.0,
  driveEfficiency: 0.9,

  // Felegyenesedett ülés, kicsi plexi.
  cdA: 0.45,
  rho: 1.225,
  crr: 0.018,

  topSpeedKmh: 170,

  // Vészfékek, nem a modell.
  muCorner: 0.85,
  maxDecelMps2: 7.5,
  maxAccelMps2: 3.5
}

const G = 9.81

export const totalMass = (bike = NX500) =>
  bike.massBikeKg + bike.massRiderKg + bike.massLuggageKg

/**
 * Vonóerő adott sebességnél. Alacsony sebességen nem a teljesítmény korlátoz,
 * hanem a tapadás és a borulás (ezért a kinematikai plafon), nagy sebességen
 * viszont a P/v hiperbola.
 */
export function tractiveForce(bike, vMps) {
  const powerWatt = bike.powerKw * 1000 * bike.driveEfficiency
  const mass = totalMass(bike)
  return Math.min(powerWatt / Math.max(vMps, 3), mass * bike.maxAccelMps2)
}

export const dragForce = (bike, vMps) => 0.5 * bike.rho * bike.cdA * vMps * vMps

/**
 * Elérhető gyorsulás. A gradePercent a magasságprofilból jön: emelkedőn a
 * motor érezhetően lassabban gyorsul, és egy 47 lóerős motoron ez nem elméleti.
 *
 * Ellenőrzés: 130 km/h-nál ez 1,64 m/s²-t ad, a mért p90-ed ugyanabban a
 * sávban 1,19 - tehát itt már tényleg a motor a szűk keresztmetszet.
 */
export function maxAccel(bike, vMps, gradePercent = 0) {
  const mass = totalMass(bike)
  const theta = Math.atan(gradePercent / 100)
  const net =
    tractiveForce(bike, vMps) -
    dragForce(bike, vMps) -
    bike.crr * mass * G * Math.cos(theta) -
    mass * G * Math.sin(theta)
  return net / mass
}

/** Fékezés: lejtőn kevesebb, emelkedőn több. A légellenállás is segít. */
export function maxDecel(bike, vMps, gradePercent = 0) {
  const theta = Math.atan(gradePercent / 100)
  const mass = totalMass(bike)
  return Math.max(
    1.0,
    bike.maxDecelMps2 + (dragForce(bike, vMps) / mass) - G * Math.sin(theta)
  )
}

/** Tapadási kanyarsebesség. Gyakorlatilag sosem aktív, de a plafon legyen zárt. */
export const gripCornerSpeed = (bike, radiusM) =>
  Math.sqrt(bike.muCorner * G * Math.min(radiusM, 1e6))

/** Végsebesség m/s-ban. */
export const topSpeedMps = (bike = NX500) => bike.topSpeedKmh / 3.6
