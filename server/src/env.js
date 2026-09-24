import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/**
 * Helyi beállítások betöltése a `server/.env` fájlból.
 *
 * A titkok (token, Waze-kulcs) nincsenek a repóban: vagy ez a fájl adja őket,
 * vagy a systemd/launchd unit `Environment=` sorai. A már meglévő környezeti
 * változókat a Node nem írja felül, tehát a unit erősebb a fájlnál.
 *
 * Minden modul, ami induláskor olvas környezeti változót, ezt importálja
 * elsőként. A modulgyorsítótár miatt a betöltés akkor is egyszer fut le, ha
 * többen kérik.
 */
const envPath = fileURLToPath(new URL('../.env', import.meta.url))

if (existsSync(envPath)) {
  process.loadEnvFile(envPath)
}

export { envPath }
