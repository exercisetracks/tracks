#!/bin/bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
set -e

# /data is a host bind mount (HOST_GARMIN_SYNC_DIR). Docker creates a missing
# bind-mount source directory as root:root at first `up` — a fresh install
# never has this directory pre-created with the right ownership, so the
# container (which must run as HOST_UID to write GARMIN_SYNC_DEST /
# GARMIN_AGPS_CACHE_DIR) would find it unwritable on first boot. Same
# root-then-drop-privilege pattern as backend/entrypoint.sh: this script runs
# as root (no `user:` override in docker-compose for this service), fixes
# ownership, then gosu hands off to HOST_UID for the actual sync process —
# idempotent, so it's a no-op once the directory is already owned correctly.
mkdir -p /data/.agps-cache
chown -R "${HOST_UID:-1000}:${HOST_GID:-1000}" /data

exec gosu "${HOST_UID:-1000}:${HOST_GID:-1000}" /usr/bin/tini -- "$@"
