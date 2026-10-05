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

# The basemap and terrain sources, so maps work without configuration. The
# dated Protomaps build expires within a week, which is fine: the backend
# rolls a stale date forward to the newest build (pmtiles_extract). Set either
# to an empty value (-e PMTILES_SOURCE_URL=) to keep the multi-GB downloads off.
: "${PMTILES_SOURCE_URL=https://build.protomaps.com/20261001.pmtiles}"
: "${DEM_SOURCE_URL=https://download.mapterhorn.com/planet.pmtiles}"
export PMTILES_SOURCE_URL DEM_SOURCE_URL
