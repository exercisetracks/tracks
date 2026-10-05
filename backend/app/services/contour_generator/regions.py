# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Region-level contour orchestration (public entry points).

Wraps the per-archive PMTiles builder with the region registry: build one
region's contours from its DEM, or rebuild every active region whose contours
are missing/stale, then assemble the master contours archive. Both are
serialised via ``_contour_lock`` so the lifespan startup thread and the
post-reindex call can't race on the same output files.
"""

from __future__ import annotations

from pathlib import Path

from .builder import build_contours_pmtiles
from .constants import _contour_lock, logger


def build_region_contours(region_id: int, progress_cb=None, cancel=None) -> bool:
    """Build ONE region's contours from its own ``dem.pmtiles`` → region
    ``contours.pmtiles``, then assemble ``master_contours`` from all regions.

    Bounded by region size — no full-planet DEM iteration and no whole-archive
    copy loop (the old design re-copied every existing contour tile per region).
    Serialised via ``_contour_lock``."""
    from app.services import region_registry, region_merger
    region_dir = region_registry.source_dir(region_id)
    dem = region_dir / "dem.pmtiles"
    out = region_dir / "contours.pmtiles"
    if not dem.exists():
        logger.info("Region %s has no dem.pmtiles — skipping contours", region_id)
        return False
    with _contour_lock:
        build_contours_pmtiles(dem, out, progress_cb=progress_cb, cancel=cancel)
    if not out.exists():
        return False
    region_merger.rebuild_master_contours(write_trigger=True)
    return True


def regenerate_contours(progress_cb=None) -> bool:
    """Rebuild every active region's contours from its DEM (when missing or older
    than the DEM) and assemble ``master_contours``. Used at startup and by the
    admin endpoint. ``progress_cb(done_regions, total_regions)`` for a coarse bar."""
    from app.services import region_registry, region_merger
    active = region_registry.list_active()
    jobs: list[tuple[Path, Path]] = []
    for r in active:
        d = region_registry.source_dir(r["id"])
        dem, out = d / "dem.pmtiles", d / "contours.pmtiles"
        if dem.exists() and (not out.exists() or out.stat().st_mtime < dem.stat().st_mtime):
            jobs.append((dem, out))

    total = len(jobs)
    with _contour_lock:
        for i, (dem, out) in enumerate(jobs):
            if progress_cb is not None:
                try:
                    progress_cb(i, total or 1)
                except Exception:
                    pass
            build_contours_pmtiles(dem, out)
    if progress_cb is not None:
        try:
            progress_cb(total, total or 1)
        except Exception:
            pass
    region_merger.rebuild_master_contours(write_trigger=True)
    return True
