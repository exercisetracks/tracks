# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download the planet global basemap overview.

Downloads basemap tiles from a third-party tile server.
Only runs when a user explicitly enables the map feature.

The overview is extracted to z0-OVERVIEW_MAXZOOM. At z12 this is a full global
street-level basemap (~8-18 GB) so the map renders everywhere offline and the
activity/route pages can drop external tile providers. The per-region pipeline
then adds only z13-15 detail on top. A legacy z0-7 `planet_z7.pmtiles` (if
present from an earlier install) is used as a fallback by region_merger until
the larger `planet_basemap.pmtiles` finishes downloading, so the live map never
goes blank mid-upgrade.

Protomaps publishes dated daily builds (build.protomaps.com/YYYYMMDD.pmtiles) and
only keeps roughly the last week, so a hard-coded date in PMTILES_SOURCE_URL goes
404 after a few days. `resolve_source_url` (in pmtiles_extract) transparently
falls forward to the newest available build when the configured one has
expired.
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path

from app.config import settings
from app.services import region_merger
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import (
    extract_global, is_complete_archive, resolve_source_url,
)

logger = logging.getLogger(__name__)

# Zoom-agnostic name (it now spans z0-12, not just z7).
OVERVIEW_NAME = "planet_basemap"
OVERVIEW_MAXZOOM = 12

global_download_tracker.register("overview", "Global basemap tiles", "~16 GB")

# Held by the thread that is downloading the overview — see start_map_download.
_running = threading.Lock()


def ensure_global_overview() -> bool:
    """Download the global basemap overview if it doesn't exist. Returns True if downloaded."""
    output = Path(settings.map_data_dir) / f"{OVERVIEW_NAME}.pmtiles"
    if is_complete_archive(output):
        return False
    if output.exists():
        logger.warning("Removing an unfinished basemap download: %s", output)
        output.unlink()

    if not settings.pmtiles_source_url:
        logger.warning("PMTILES_SOURCE_URL not set — skipping global overview")
        global_download_tracker.fail("overview", "PMTILES_SOURCE_URL not configured")
        return False

    logger.info("Downloading global basemap overview: z0-%d (~8-18 GB) from %s…",
                OVERVIEW_MAXZOOM, settings.pmtiles_source_url)

    try:
        extract_global(lambda: resolve_source_url(settings.pmtiles_source_url),
                       output, OVERVIEW_MAXZOOM, "overview", timeout=7200)
        logger.info("Global overview downloaded: %.1f GB", output.stat().st_size / 1e9)
        global_download_tracker.complete("overview")
        return True
    except Exception as e:
        msg = str(e)
        logger.error("Global overview download failed: %s", msg)
        global_download_tracker.fail("overview", msg)
        return False


def start_map_download() -> None:
    """Download basemap overview and rebuild master tiles in a background thread.

    Safe to call multiple times — download skips if the file already exists,
    and a call while one is already running (it may be waiting out a blocked
    network for hours) leaves that one to it rather than starting a second
    extract into the same file.
    """
    import threading

    def _run():
        if not _running.acquire(blocking=False):
            return
        try:
            if ensure_global_overview():
                # The z0-12 basemap is served directly (Caddy routes z0-12 →
                # planet_basemap); just nudge go-pmtiles to open the new file.
                region_merger.write_reload_trigger()
        except Exception:
            logger.exception("Map tile download failed")
        finally:
            _running.release()

    threading.Thread(target=_run, daemon=True, name="map-download").start()
