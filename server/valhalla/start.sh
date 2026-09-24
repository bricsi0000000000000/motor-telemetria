#!/usr/bin/env bash
# A Valhalla konténer felhozása bejelentkezéskor.
#
# A konténer "restart: unless-stopped" beállítással fut, tehát a Docker
# elindulásakor magától visszajön - ez a szkript arra kell, hogy a Dockert
# is elindítsa, ha még nem fut, és kivárja, amíg használható lesz.
set -u
cd "$(dirname "$0")"

log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*"; }

if ! docker info >/dev/null 2>&1; then
    log "A Docker nem fut, indítom."
    open -ga Docker || log "A Docker Desktopot nem sikerült elindítani."
fi

# Legfeljebb öt percet várunk a démonra; a Docker Desktop indulása lassú tud lenni.
for _ in $(seq 1 60); do
    docker info >/dev/null 2>&1 && break
    sleep 5
done

if ! docker info >/dev/null 2>&1; then
    log "A Docker öt perc után sem érhető el, feladom."
    exit 1
fi

log "Konténer indítása."
docker compose up -d

# A csempék betöltése után válaszol csak a /status.
for _ in $(seq 1 60); do
    if curl -sf --max-time 3 http://127.0.0.1:8002/status >/dev/null 2>&1; then
        log "A Valhalla kiszolgál a 8002-es porton."
        exit 0
    fi
    sleep 5
done

log "A Valhalla elindult, de nem válaszol a /status-ra."
exit 1
