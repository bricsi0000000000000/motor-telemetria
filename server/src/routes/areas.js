import { Router, raw } from 'express'
import {
  AreaError,
  AREA_KINDS,
  LOGO_TYPES,
  clearLogo,
  createArea,
  deleteArea,
  getLogo,
  listAreas,
  setLogo,
  updateArea
} from '../areas.js'

export const areasRouter = Router()

/** Az ismert hibák a saját üzenetükkel és státuszukkal mennek vissza. */
const handle = (fn) => (req, res) => {
  try {
    const result = fn(req, res)
    if (result !== undefined) res.json(result)
  } catch (error) {
    if (error instanceof AreaError) res.status(error.status).json({ error: error.message })
    else throw error
  }
}

const idOf = (req) => {
  const id = Number(req.params.id)
  if (!Number.isInteger(id) || id <= 0) throw new AreaError('Hibás azonosító.')
  return id
}

areasRouter.get('/areas', handle(() => ({ areas: listAreas(), kinds: AREA_KINDS })))
areasRouter.post('/areas', handle((req, res) => { res.status(201); return createArea(req.body ?? {}) }))
areasRouter.put('/areas/:id', handle((req) => updateArea(idOf(req), req.body ?? {})))
areasRouter.delete('/areas/:id', handle((req, res) => { deleteArea(idOf(req)); res.status(204).end() }))

/** A logó nyers bájtokként jön, a Content-Type mondja meg a formátumát. */
areasRouter.put('/areas/:id/logo', raw({ type: LOGO_TYPES, limit: '1mb' }),
  handle((req) => {
    const mime = String(req.get('content-type') ?? '').split(';')[0].trim()
    if (!Buffer.isBuffer(req.body)) throw new AreaError('A logó PNG, JPEG vagy WebP lehet.', 415)
    return setLogo(idOf(req), req.body, mime)
  }))

areasRouter.delete('/areas/:id/logo', handle((req) => clearLogo(idOf(req))))

areasRouter.get('/areas/:id/logo', handle((req, res) => {
  const logo = getLogo(idOf(req))
  // A kliens a logó idejét is a címbe teszi, így a régi kép sosem ragad be.
  res.set('Cache-Control', 'private, max-age=31536000, immutable')
  res.type(logo.mime).send(logo.bytes)
}))

/** A túl nagy feltöltés ne "Szerverhiba" legyen, hanem érthető üzenet. */
areasRouter.use((error, _req, res, next) => {
  if (error?.type === 'entity.too.large') {
    res.status(413).json({ error: 'A logó legfeljebb 512 KB lehet.' })
    return
  }
  next(error)
})
