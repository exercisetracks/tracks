#!/bin/bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
set -e

# Create map-data subdirectories (idempotent)
mkdir -p /map-data/regions /map-data/brouter/segments4 /map-data/fonts /map-data/sprite /map-data/trail_logos

# Seed sprite files from the bundled defaults if the runtime dir is empty.
# /app/sprite_defaults/ is baked into the Docker image at build time so that
# wiping /map-data (host bind-mount) is fully recoverable on restart.
if [ -z "$(ls -A /map-data/sprite 2>/dev/null)" ]; then
    echo "[entrypoint] Seeding /map-data/sprite/ from bundled defaults…"
    cp /app/sprite_defaults/* /map-data/sprite/
fi

# Seed long-trail emblem PNGs from bundled defaults so the real logos ship with
# the image (no dependency on Wikimedia at first launch). The launch-time
# source_logos() still runs to refresh / fill any new entries.
if [ -d /app/trail_logo_defaults ] && [ -z "$(ls -A /map-data/trail_logos 2>/dev/null)" ]; then
    echo "[entrypoint] Seeding /map-data/trail_logos/ from bundled defaults…"
    cp /app/trail_logo_defaults/* /map-data/trail_logos/ 2>/dev/null || true
fi

chown -R "${HOST_UID:-1000}:${HOST_GID:-1000}" /map-data 2>/dev/null || true

# /fit-files (HOST_FIT_DIR) is the other host bind mount this container
# writes to (object_storage.py's blob store). Same fresh-install gap as
# /map-data: Docker creates a missing bind-mount source as root:root on
# first `up`, which the container (dropping to HOST_UID below) couldn't
# write to without this.
# FIT_FILES_DIR is the app's own setting (app.config); the all-in-one image
# keeps these under /data with everything else.
fit_dir="${FIT_FILES_DIR:-/fit-files}"
mkdir -p "$fit_dir"
chown -R "${HOST_UID:-1000}:${HOST_GID:-1000}" "$fit_dir" 2>/dev/null || true

# Drop root: the setup above (mkdir/seed/chown) needs it, the app doesn't.
# Running as HOST_UID keeps /map-data and /fit-files writable from the host
# and means a compromised app process holds no root inside the container.
exec gosu "${HOST_UID:-1000}:${HOST_GID:-1000}" "$@"
