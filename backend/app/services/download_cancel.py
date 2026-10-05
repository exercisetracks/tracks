# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Cooperative cancellation for in-progress region downloads.

A region download runs as a background thread inside ONE uvicorn worker, but the
cancel request (DELETE on an in-progress region) can be handled by ANY of the
``--workers`` processes. So the cross-worker signal is a marker file on the
shared ``/map-data`` volume (``regions/<id>/.cancel``); the worker that owns the
download runs a watcher thread that polls for it and trips a local ``CancelToken``.

The token does two things the marker alone can't:
  * kills the download's live subprocesses (pmtiles/osmium/tippecanoe) and shuts
    down its process pools (contours) so work stops immediately, and
  * sets an in-process event the pipeline checks (``raise_if_cancelled``) at each
    stage boundary and inside long loops, so it unwinds to the cleanup handler.

Everything is best-effort and exception-safe: a failed kill or a missing marker
never breaks the pipeline.
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path

logger = logging.getLogger(__name__)


class DownloadCancelled(Exception):
    """Raised inside the region-download pipeline when the user aborts it."""


# ── Cross-worker marker file ────────────────────────────────────────────────

def _marker_path(region_id: int) -> Path:
    # Imported lazily to avoid a module import cycle (region_registry → nothing
    # here, but keep this leaf module dependency-free at import time).
    from app.services import region_registry
    return region_registry.source_dir(region_id) / ".cancel"


def request_cancel(region_id: int) -> None:
    """Write the cancel marker for a region (idempotent, any worker may call)."""
    p = _marker_path(region_id)
    try:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.touch()
    except OSError:
        logger.warning("Could not write cancel marker %s", p)


def is_cancel_requested(region_id: int) -> bool:
    try:
        return _marker_path(region_id).exists()
    except Exception:
        return False


def clear(region_id: int) -> None:
    """Remove a region's cancel marker if present (no-op if the dir is gone)."""
    try:
        _marker_path(region_id).unlink(missing_ok=True)
    except Exception:
        pass


# ── Per-download token (owned by the worker running the pipeline) ────────────

def _kill_proc(proc) -> None:
    try:
        proc.kill()
    except Exception:
        pass


def _shutdown_pool(pool) -> None:
    try:
        pool.shutdown(wait=False, cancel_futures=True)
    except Exception:
        pass


class CancelToken:
    """Tracks the live subprocesses / pools of one download so they can be
    killed, and carries the cancellation flag the pipeline checks."""

    def __init__(self, region_id: int):
        self.region_id = region_id
        self._event = threading.Event()
        self._procs: set = set()
        self._pools: set = set()
        self._lock = threading.Lock()
        self._watch_stop = threading.Event()
        self._watcher: threading.Thread | None = None

    # checks ------------------------------------------------------------------
    def cancelled(self) -> bool:
        return self._event.is_set()

    def raise_if_cancelled(self) -> None:
        if self._event.is_set():
            raise DownloadCancelled()

    # registration of killable work ------------------------------------------
    def add_proc(self, proc) -> None:
        with self._lock:
            self._procs.add(proc)
        if self._event.is_set():           # cancelled between check and register
            _kill_proc(proc)

    def discard_proc(self, proc) -> None:
        with self._lock:
            self._procs.discard(proc)

    def add_pool(self, pool) -> None:
        with self._lock:
            self._pools.add(pool)
        if self._event.is_set():
            _shutdown_pool(pool)

    def discard_pool(self, pool) -> None:
        with self._lock:
            self._pools.discard(pool)

    # trip --------------------------------------------------------------------
    def cancel(self) -> None:
        self._event.set()
        with self._lock:
            procs, pools = list(self._procs), list(self._pools)
        for p in procs:
            _kill_proc(p)
        for pool in pools:
            _shutdown_pool(pool)

    # watcher lifecycle -------------------------------------------------------
    def start_watching(self, interval: float = 1.0) -> None:
        """Poll the shared marker file; trip the token the moment it appears."""
        def _loop():
            while not self._watch_stop.wait(interval):
                if is_cancel_requested(self.region_id):
                    logger.info("Cancel marker seen for region %s — aborting download",
                                self.region_id)
                    self.cancel()
                    return
        self._watcher = threading.Thread(target=_loop, daemon=True,
                                         name=f"cancel-watch-{self.region_id}")
        self._watcher.start()

    def stop_watching(self) -> None:
        self._watch_stop.set()
