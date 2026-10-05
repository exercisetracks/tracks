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
from pathlib import Path

from app.config import settings
from app.services import region_merger
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import extract_with_progress, resolve_source_url

logger = logging.getLogger(__name__)

# Zoom-agnostic name (it now spans z0-12, not just z7).
OVERVIEW_NAME = "planet_basemap"
OVERVIEW_MAXZOOM = 12

global_download_tracker.register("overview", "Global basemap tiles", "~16 GB")


def ensure_global_overview() -> bool:
    """Download the global basemap overview if it doesn't exist. Returns True if downloaded."""
    output = Path(settings.map_data_dir) / f"{OVERVIEW_NAME}.pmtiles"
    if output.exists():
        return False

    if not settings.pmtiles_source_url:
        logger.warning("PMTILES_SOURCE_URL not set — skipping global overview")
        global_download_tracker.fail("overview", "PMTILES_SOURCE_URL not configured")
        return False

    source = resolve_source_url(settings.pmtiles_source_url)

    logger.info("Downloading global basemap overview: z0-%d (~8-18 GB) from %s…",
                OVERVIEW_MAXZOOM, source)
    global_download_tracker.start("overview")

    try:
        extract_with_progress(source, output, OVERVIEW_MAXZOOM, "overview", timeout=7200)
        logger.info("Global overview downloaded: %.1f GB", output.stat().st_size / 1e9)
        global_download_tracker.complete("overview")
        return True
    except Exception as e:
        msg = str(e)
        logger.error("Global overview download failed: %s", msg)
        global_download_tracker.fail("overview", msg)
        # Don't leave a half-written archive behind.
        Path(str(output)).unlink(missing_ok=True)
        return False


def start_map_download() -> None:
    """Download basemap overview and rebuild master tiles in a background thread.

    Safe to call multiple times — download skips if the file already exists.
    """
    import threading

    def _run():
        try:
            if ensure_global_overview():
                # The z0-12 basemap is served directly (Caddy routes z0-12 →
                # planet_basemap); just nudge go-pmtiles to open the new file.
                region_merger.write_reload_trigger()
        except Exception:
            logger.exception("Map tile download failed")

    threading.Thread(target=_run, daemon=True, name="map-download").start()
