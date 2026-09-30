// Elsőként fut: a .env-ből származó beállítások a többi modul előtt kellenek.
import './env.js'

import express from 'express'
import cors from 'cors'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { requireToken } from './auth.js'
import { dbPath } from './db.js'
import { syncRouter } from './routes/sync.js'
import { tracksRouter } from './routes/tracks.js'
import { statsRouter } from './routes/stats.js'
import { roadPointsRouter } from './routes/roadpoints.js'
import { selfTestRouter } from './routes/selftest.js'
import { routeRouter } from './routes/route.js'
import { areasRouter } from './routes/areas.js'

const PORT = Number(process.env.PORT || 8787)
// Tailscale-en keresztül érjük el, ezért minden interfészen hallgatunk;
// kifelé a gépet a tailnet ACL-je (és a token) védi.
const HOST = process.env.HOST || '0.0.0.0'

const app = express()
app.disable('x-powered-by')
app.use(cors())
// Egy hosszú túra pontjai offline után egyben érkezhetnek, ezért bőven mérünk.
app.use(express.json({ limit: '32mb' }))

/**
 * A webes felület (területek rajzolása, logók). Maga az oldal token nélkül
 * betölthető, de minden adatot a tokenes API-n kér – token nélkül üres marad.
 */
app.use(express.static(join(dirname(fileURLToPath(import.meta.url)), '..', 'web')))

/** Token nélkül is elérhető: erről tudja az app és a szolgáltatáskezelő, hogy él a szerver. */
app.get('/api/health', (_req, res) => {
  res.json({ ok: true, service: 'motor-telemetria', time: Date.now() })
})

app.use('/api', requireToken, syncRouter)
app.use('/api', requireToken, tracksRouter)
app.use('/api', requireToken, statsRouter)
app.use('/api', requireToken, roadPointsRouter)
app.use('/api', requireToken, selfTestRouter)
app.use('/api', requireToken, routeRouter)
app.use('/api', requireToken, areasRouter)

app.use((error, _req, res, _next) => {
  console.error('Hiba a kérés feldolgozásakor:', error)
  res.status(500).json({ error: 'Szerverhiba' })
})

app.listen(PORT, HOST, () => {
  console.log(`Motor Telemetria szerver: http://${HOST}:${PORT}`)
  console.log(`Adatbázis: ${dbPath}`)
})

/**
 * A webes felület saját porton is elérhető. A 8787-et ezen a gépen a
 * KorteDrive is foglalja a 127.0.0.1-en, így böngészőből a localhost:8787
 * oda jutna, nem ide. A telefon marad a PORT-on; ugyanaz az alkalmazás fut
 * mindkettőn. WEB_PORT=0 kikapcsolja.
 */
const WEB_PORT = Number(process.env.WEB_PORT ?? 8789)
if (WEB_PORT && WEB_PORT !== PORT) {
  app.listen(WEB_PORT, HOST, () => {
    console.log(`Webes felület: http://localhost:${WEB_PORT}/`)
  }).on('error', (error) => {
    // A foglalt webes port ne vigye el a telefon szerverét is.
    console.warn(`A webes felület portja (${WEB_PORT}) nem nyitható meg: ${error.message}`)
  })
}
