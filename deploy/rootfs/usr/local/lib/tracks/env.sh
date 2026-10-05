# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Sourced by every tracks-* script: the settings each service starts with.
#
# Read at each service's start rather than inherited from the supervisor, so
# tracks-restore can replace /data/secrets.env and restart the services onto
# it without restarting the container.

SECRETS=/data/secrets.env
PGBIN=/usr/lib/postgresql/16/bin
PGDATA=/data/postgres
RUN_UID="${HOST_UID:-1000}"
RUN_GID="${HOST_GID:-1000}"

# A value given to the container (-e / environment:) always wins over the
# generated one, so an operator who manages their own secrets can.
if [ -f "$SECRETS" ]; then
    while IFS='=' read -r key value; do
        case "$key" in ''|\#*) continue ;; esac
        if [ -z "${!key+x}" ]; then export "$key=$value"; fi
    done < "$SECRETS"
fi

: "${DATABASE_URL:=postgresql://tracks:${POSTGRES_PASSWORD:-}@127.0.0.1:5432/tracks}"
export DATABASE_URL

# The switches an operator is expected to touch are plain true/false, because
# "set this URL to an empty string" and "count your proxies" were what people
# got wrong. A value that is neither stops the container: a typo in
# MAP_DOWNLOADS silently read as true is a 16 GB download nobody asked for.
# Normalises the variable in place rather than echoing it, because an exit
# inside $(…) would only leave the subshell and the typo would read as false.
flag() {
    local value="${!1:-$2}"
    case "${value,,}" in
        true|yes|on|1) printf -v "$1" true ;;
        false|no|off|0) printf -v "$1" false ;;
        *) echo "[tracks] $1=$value: use true or false" >&2; exit 1 ;;
    esac
}

# The basemap and terrain sources, so maps work without configuration. The
# dated Protomaps build expires within a week, which is fine: the backend
# rolls a stale date forward to the newest build (pmtiles_extract).
# MAP_DOWNLOADS / TERRAIN_DOWNLOADS=false keep the multi-GB downloads off by
# emptying the URL, which is what the backend reads as "not configured"; the
# URLs themselves stay settable for anyone pointing at a mirror.
: "${PMTILES_SOURCE_URL=https://build.protomaps.com/20261001.pmtiles}"
: "${DEM_SOURCE_URL=https://download.mapterhorn.com/planet.pmtiles}"
flag MAP_DOWNLOADS true
flag TERRAIN_DOWNLOADS true
flag BEHIND_PROXY false
[ "$MAP_DOWNLOADS" = true ] || PMTILES_SOURCE_URL=
[ "$TERRAIN_DOWNLOADS" = true ] || DEM_SOURCE_URL=
export PMTILES_SOURCE_URL DEM_SOURCE_URL

# How many proxies' X-Forwarded-For entries login rate limiting may trust:
# Tracks' own web server, plus the operator's when BEHIND_PROXY says there is
# one. Trusting one too many would let a client pick its own rate-limit
# address, so this is never raised without being asked. An explicit
# TRUSTED_PROXY_HOPS still wins, for a chain of more than one proxy.
if [ -z "${TRUSTED_PROXY_HOPS:-}" ]; then
    if [ "$BEHIND_PROXY" = true ]; then TRUSTED_PROXY_HOPS=2; else TRUSTED_PROXY_HOPS=1; fi
fi
export TRUSTED_PROXY_HOPS
