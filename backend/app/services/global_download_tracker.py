# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Cross-worker progress tracker for the global basemap/DEM downloads.

The app runs multiple uvicorn workers, but a download runs in just one worker's
background thread. An in-memory tracker would therefore be invisible to the
worker that happens to serve a /maps/global-downloads poll, so the UI would see
a flickering "idle". State is instead persisted to a small JSON file on the
shared map-data volume: the download thread writes it; any worker reads it.
"""

from __future__ import annotations

import json
import logging
import os
import threading
from pathlib import Path
from typing import Literal

from app.config import settings

logger = logging.getLogger(__name__)

# "overview"/"dem" are byte downloads; "contours"/"routes" are first-setup
# generation tasks surfaced in the same first-run progress toast.
DownloadId = Literal["overview", "dem", "contours", "routes"]
DownloadStatus = Literal["idle", "downloading", "complete", "error"]

_STATE_FILE = Path(settings.map_data_dir) / ".download_state.json"
_lock = threading.Lock()


def _read() -> dict:
    try:
        return json.loads(_STATE_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        return {}


def _write(state: dict) -> None:
    try:
        _STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        tmp = _STATE_FILE.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(state))
        os.replace(tmp, _STATE_FILE)
    except OSError as e:
        logger.warning("Could not persist download state: %s", e)


class GlobalDownloadTracker:
    """File-backed so progress is consistent across uvicorn workers."""

    def register(self, download_id: DownloadId, label: str, size_hint: str) -> None:
        with _lock:
            state = _read()
            existing = state.get(download_id, {})
            # Preserve any in-flight status/progress; only (re)seed label/size_hint.
            state[download_id] = {
                "id": download_id,
                "label": label,
                "size_hint": size_hint,
                "status": existing.get("status", "idle"),
                "error": existing.get("error"),
                "progress": existing.get("progress", 0.0),
                "detail": existing.get("detail"),
            }
            _write(state)

    def _update(self, download_id: DownloadId, **fields) -> None:
        with _lock:
            state = _read()
            d = state.get(download_id)
            if not d:
                d = {"id": download_id, "label": download_id, "size_hint": "",
                     "status": "idle", "error": None, "progress": 0.0, "detail": None}
            d.update(fields)
            state[download_id] = d
            _write(state)

    def start(self, download_id: DownloadId) -> None:
        self._update(download_id, status="downloading", error=None, progress=0.0, detail=None)

    def set_progress(self, download_id: DownloadId, percent: float,
                     detail: str | None = None) -> None:
        fields = {"progress": round(max(0.0, min(100.0, percent)), 1)}
        if detail is not None:
            fields["detail"] = detail
        self._update(download_id, **fields)

    def complete(self, download_id: DownloadId) -> None:
        self._update(download_id, status="complete", progress=100.0)

    def fail(self, download_id: DownloadId, error: str) -> None:
        self._update(download_id, status="error", error=error)

    def get_state(self, download_id: DownloadId) -> dict | None:
        return _read().get(download_id)

    def get_all(self) -> list[dict]:
        return list(_read().values())


global_download_tracker = GlobalDownloadTracker()
