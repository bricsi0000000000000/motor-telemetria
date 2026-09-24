const escapeXml = (value) =>
  String(value).replace(/[<>&'"]/g, (char) => `&${{ '<': 'lt', '>': 'gt', '&': 'amp', "'": 'apos', '"': 'quot' }[char]};`)

const iso = (millis) => new Date(millis).toISOString()

/** Ugyanaz a formátum, amit a telefon exportál – Strava, Komoot, OsmAnd beolvassa. */
export function toGpx(track, points) {
  const lines = []
  lines.push('<?xml version="1.0" encoding="UTF-8"?>')
  lines.push(
    '<gpx version="1.1" creator="Motor Telemetria" xmlns="http://www.topografix.com/GPX/1/1">'
  )
  lines.push('  <metadata>')
  lines.push(`    <name>${escapeXml(`Motortúra ${iso(track.startTime)}`)}</name>`)
  lines.push(`    <time>${iso(track.startTime)}</time>`)
  lines.push('  </metadata>')
  lines.push('  <trk>')
  lines.push(`    <name>${escapeXml(`Motortúra ${iso(track.startTime)}`)}</name>`)

  let segment = null
  for (const point of points) {
    if (point.segment !== segment) {
      if (segment !== null) lines.push('    </trkseg>')
      segment = point.segment
      lines.push('    <trkseg>')
    }
    lines.push(`      <trkpt lat="${point.lat}" lon="${point.lon}">`)
    lines.push(`        <ele>${point.altitude ?? 0}</ele>`)
    lines.push(`        <time>${iso(point.time)}</time>`)
    lines.push('      </trkpt>')
  }
  if (segment !== null) lines.push('    </trkseg>')

  lines.push('  </trk>')
  lines.push('</gpx>')
  return lines.join('\n')
}
