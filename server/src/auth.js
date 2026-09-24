import './env.js'

import { timingSafeEqual } from 'node:crypto'

/**
 * Egyfelhasználós szerver: nincs regisztráció és nincs munkamenet, csak egy
 * megosztott token. A telefon és a webes felület ugyanezt küldi minden kérésnél.
 *
 * A token nincs a forrásban: a `server/.env` fájlból vagy a szolgáltatás
 * unitjának környezeti változójából jön. Alapértelmezett érték szándékosan nincs – egy
 * beégetett default a publikus repóval együtt mindenki számára ismertté válna.
 */
export const TOKEN = process.env.MOTOR_TOKEN?.trim() || ''

if (!TOKEN) {
  throw new Error(
    'Hiányzik a MOTOR_TOKEN. Hozd létre a server/.env fájlt a .env.example alapján ' +
      '(cp .env.example .env), vagy add meg a szolgáltatás unitjában.'
  )
}

function sameToken(candidate) {
  if (typeof candidate !== 'string') return false
  const a = Buffer.from(candidate)
  const b = Buffer.from(TOKEN)
  // A timingSafeEqual azonos hosszt vár, ezért a hosszt előbb nézzük meg.
  return a.length === b.length && timingSafeEqual(a, b)
}

/** A tokent fejlécben, Bearerként vagy lekérdezésben is elfogadjuk (GPX letöltés). */
function tokenFromRequest(req) {
  const header = req.get('authorization')
  if (header?.startsWith('Bearer ')) return header.slice(7).trim()
  return req.get('x-motor-token') || req.query.token
}

export function requireToken(req, res, next) {
  if (!sameToken(tokenFromRequest(req))) {
    res.status(401).json({ error: 'Érvénytelen token' })
    return
  }
  next()
}
