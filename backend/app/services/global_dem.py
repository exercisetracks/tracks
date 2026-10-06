# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from __future__ import annotations

import logging
import threading
from pathlib import Path

from app.config import settings
from app.services import region_merger
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import extract_global, is_complete_archive

logger = logging.getLogger(__name__)

PLANET_DEM_Z7 = "planet_dem_z7"

global_download_tracker.register("dem", "Global terrain tiles", "~3.1 GB")

# Held by the thread that is downloading the DEM, so a second call while it
# waits out a blocked network does not start another extract into the same file.
_running = threading.Lock()


def ensure_global_dem() -> bool:
    output = Path(settings.map_data_dir) / f"{PLANET_DEM_Z7}.pmtiles"
    if is_complete_archive(output):
        return False
    if output.exists():
        logger.warning("Removing an unfinished terrain download: %s", output)
        output.unlink()

    if not settings.dem_source_url:
        logger.warning("DEM_SOURCE_URL not set — skipping global DEM download")
        global_download_tracker.fail("dem", "DEM_SOURCE_URL not configured")
        return False

    logger.info("Downloading global DEM overview: z0-7 (~3.1 GB)…")
    try:
        extract_global(lambda: settings.dem_source_url, output, 7, "dem", timeout=7200)
        logger.info("Global DEM downloaded: %.1f MB", output.stat().st_size / 1e6)
        global_download_tracker.complete("dem")
        return True
    except Exception as e:
        msg = str(e)
        logger.error("Global DEM download failed: %s", msg)
        global_download_tracker.fail("dem", msg)
        return False


def start_dem_download() -> None:
    def _run():
        if not _running.acquire(blocking=False):
            return
        try:
            if ensure_global_dem():
                # planet_dem_z7 is served directly as the dem_overview source;
                # just nudge go-pmtiles to open the new file.
                region_merger.write_reload_trigger()
        except Exception:
            logger.exception("Map tile download failed")
        finally:
            _running.release()

    threading.Thread(target=_run, daemon=True, name="dem-download").start()
