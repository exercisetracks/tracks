#!/bin/sh
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
# Supervise `go-pmtiles serve` and restart it whenever region_merger.py writes
# /map-data/.reload-trigger after a merge — restarting clears the in-process
# tile cache and re-opens the atomically-replaced .pmtiles files (new inode).
#
# Replaces the old tile-reloader sidecar, which needed /var/run/docker.sock
# (host-root equivalent) just to bounce this container. The trigger is handled
# in-process now, so nothing in the stack can reach the Docker daemon.
set -u

TRIGGER=/map-data/.reload-trigger

pid=0
term() {
    [ "$pid" != 0 ] && kill "$pid" 2>/dev/null
    exit 0
}
trap term TERM INT

while true; do
    /usr/local/bin/go-pmtiles "$@" &
    pid=$!
    while kill -0 "$pid" 2>/dev/null; do
        if [ -f "$TRIGGER" ]; then
            # Remove before restarting: a trigger written mid-restart is a new
            # event and gets its own restart on the next pass.
            rm -f "$TRIGGER"
            echo "[reload-wrapper] reload trigger — restarting go-pmtiles"
            kill "$pid" 2>/dev/null
            break
        fi
        sleep 2
    done
    wait "$pid" 2>/dev/null
    sleep 1
done
