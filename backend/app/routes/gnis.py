# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""API endpoints for USGS GNIS data management."""

import logging
import threading

from fastapi import APIRouter

from app.services.gnis_downloader import download_gnis_zip, gnis_import_status, import_gnis_to_db

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/maps/gnis", tags=["gnis"])

_download_lock = threading.Lock()
_download_in_progress = False


@router.get("/status")
def status():
    """Check if GNIS data has been imported."""
    return gnis_import_status()


@router.post("/download")
def trigger_download():
    """Download and import USGS GNIS data (background thread)."""
    global _download_in_progress

    with _download_lock:
        if _download_in_progress:
            return {"status": "in_progress"}
        _download_in_progress = True

    def _run():
        global _download_in_progress
        try:
            data = download_gnis_zip()
            if data:
                count = import_gnis_to_db(data)
                logger.info("GNIS import complete: %d features", count)
            else:
                logger.error("GNIS download failed")
        finally:
            _download_in_progress = False

    threading.Thread(target=_run, daemon=True, name="gnis-download").start()
    return {"status": "started"}
