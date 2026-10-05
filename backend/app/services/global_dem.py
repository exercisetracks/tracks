# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from __future__ import annotations

import logging
import threading
from pathlib import Path

from app.config import settings
from app.services import region_merger
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import extract_with_progress

logger = logging.getLogger(__name__)

PLANET_DEM_Z7 = "planet_dem_z7"

global_download_tracker.register("dem", "Global terrain tiles", "~3.1 GB")


def ensure_global_dem() -> bool:
    output = Path(settings.map_data_dir) / f"{PLANET_DEM_Z7}.pmtiles"
    if output.exists():
        return False

    if not settings.dem_source_url:
        logger.warning("DEM_SOURCE_URL not set — skipping global DEM download")
        global_download_tracker.fail("dem", "DEM_SOURCE_URL not configured")
        return False

    logger.info("Downloading global DEM overview: z0-7 (~3.1 GB)…")
    global_download_tracker.start("dem")

    try:
        extract_with_progress(settings.dem_source_url, output, 7, "dem", timeout=7200)
        logger.info("Global DEM downloaded: %.1f MB", output.stat().st_size / 1e6)
        global_download_tracker.complete("dem")
        return True
    except Exception as e:
        msg = str(e)
        logger.error("Global DEM download failed: %s", msg)
        global_download_tracker.fail("dem", msg)
        Path(str(output)).unlink(missing_ok=True)
        return False


def start_dem_download() -> None:
    def _run():
        try:
            if ensure_global_dem():
                # planet_dem_z7 is served directly as the dem_overview source;
                # just nudge go-pmtiles to open the new file.
                region_merger.write_reload_trigger()
        except Exception:
            logger.exception("Map tile download failed")

    threading.Thread(target=_run, daemon=True, name="dem-download").start()
