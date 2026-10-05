# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Assemble downloaded region PMTiles into the live served archives.

Each downloadable layer is kept as a small per-region archive and the served
"master" is assembled by ``custom_merge`` (a byte-copy + last-wins dedup) of the
active regions' parts — NOT by rewriting a multi-GB overview:

  * ``master_basemap_detail`` = regions' z13-15 basemap detail (``source.pmtiles``).
    The static planet z0-12 overview is served directly and never rewritten; Caddy
    serves z0-12 from ``planet_basemap`` and z13-15 from this detail archive behind
    one ``/api/tiles/basemap`` URL, so the client sees one continuous source.
  * ``master_dem``     = regions' z8-12 terrain (``dem.pmtiles``). The static z0-7
    ``planet_dem_z7`` overview is a separate raster-dem source on the frontend.
  * ``master_overlay`` = regions' z6-15 OSM overlay (``overlay.pmtiles``). Unlike
    the other three, this one is NOT a custom_merge at all — see
    rebuild_master_overlay for why it needs a real tile-join at every zoom.
  * ``master_contours``= regions' z9-12 contours (``contours.pmtiles``).

Because the giant overviews are static, a region download only writes these small
archives (seconds), never a 17 GB rewrite. A ``.reload-trigger`` file signals go-pmtiles'
reload-wrapper to restart the serve process (which holds an FD to the old inode).
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path

from pmtiles.reader import MmapSource, Reader, all_tiles

from app.config import settings
from app.services import custom_merge, region_registry

logger = logging.getLogger(__name__)

# Served archive names (without .pmtiles) ⇄ the per-region part each assembles from.
MASTER_BASEMAP_DETAIL_NAME = "master_basemap_detail"
MASTER_DEM_NAME = "master_dem"
MASTER_OVERLAY_NAME = "master_overlay"
MASTER_CONTOURS_NAME = "master_contours"

# Above this size, skip the post-merge `pmtiles cluster` (a full-file rewrite). It
# pays off for the small vector layers (frequent reads) but is a wasteful second
# multi-GB pass for the DEM raster, whose hillshade reads tolerate scattered data.
CLUSTER_MAX_BYTES = 1_500_000_000

_merge_lock = threading.Lock()


# ── Public assembly entry points ──────────────────────────────────────────────

def rebuild_master(index_source: str | None = None, write_trigger: bool = True,
                   mark_installed: bool = True) -> None:
    """Assemble ``master_basemap_detail`` from active regions' z13-15 detail.

    ``mark_installed=False`` keeps regions in their in-progress status so the
    download pipeline can run later phases (trails, contours) with their own
    progress before flipping to "installed". If ``index_source`` (a region's
    source.pmtiles) is given, its z13+ POIs are indexed asynchronously.
    """
    with _merge_lock:
        active = region_registry.list_active()
        _assemble(MASTER_BASEMAP_DETAIL_NAME, "source.pmtiles", active)

        if mark_installed:
            for r in active:
                if r["status"] not in ("error", "deleting"):
                    region_registry.update_status(r["id"], "installed")

    if index_source and Path(index_source).exists():
        threading.Thread(target=_safe_index_pois, args=(index_source,),
                          daemon=True, name="poi-reindex").start()

    if write_trigger:
        write_reload_trigger()


def rebuild_master_dem(write_trigger: bool = True) -> None:
    """Assemble ``master_dem`` from active regions' z8-12 terrain tiles.

    Contours are NOT triggered here — they are generated per-region as their own
    tracked pipeline phase (see contour_generator.build_region_contours)."""
    with _merge_lock:
        _assemble(MASTER_DEM_NAME, "dem.pmtiles", region_registry.list_active())
    if write_trigger:
        write_reload_trigger()


def rebuild_master_overlay(write_trigger: bool = True) -> None:
    """Assemble ``master_overlay`` from active regions' z6-15 OSM overlays.

    A real tile-join (feature-level union), at every zoom — never the tile-id
    custom_merge the other three archives use.

    The distinction is not about resolution, which is what an earlier version of
    this assumed: it kept a cheap last-wins merge from z9 up on the grounds that
    regions are "spatially disjoint" there, and tile-joined only the coarse z6-8
    band where one tile plainly spans several regions. But two regions are never
    disjoint *in tiles*. A tile boundary does not follow a user-drawn box, so any
    two regions that touch or overlap share the whole band of tiles along their
    common edge, at every zoom — a thin band at z15, a thick one at z9, but never
    none. Last-wins keeps one archive's version of each shared tile and discards
    the other's entirely: every layer in it, not merely the colliding feature.

    That is exactly what a seam is, and it was measurable: two areas meeting at
    39.84°N left 3,513 tiles differing between the served archive and the true
    union, ~2.5 MB of features missing, and one z11 tile inside the older area cut
    from 86 kB to 99 bytes — an empty tile where a town's worth of trails had been.

    tile-join concatenates rather than de-duplicating, so it depends on the
    regions' archives not holding the same feature twice; see ``build_overlay``'s
    ``clip_bbox`` for how that is guaranteed."""
    with _merge_lock:
        _assemble_overlay(region_registry.list_active())
    if write_trigger:
        write_reload_trigger()


def rebuild_master_contours(write_trigger: bool = True) -> None:
    """Assemble ``master_contours`` from active regions' per-region contours."""
    with _merge_lock:
        _assemble(MASTER_CONTOURS_NAME, "contours.pmtiles", region_registry.list_active())
    if write_trigger:
        write_reload_trigger()


# ── Core assembly ─────────────────────────────────────────────────────────────

def _assemble(name: str, part_filename: str, active_regions: list[dict]) -> int:
    """Assemble ``{name}.pmtiles`` = custom_merge of each region's ``part_filename``.

    Returns the number of unique tiles written. With no contributing regions the
    served archive is removed (so stale tiles stop being served). The output is
    clustered for serve efficiency when custom_merge left it unclustered (small
    archives now — never the old 17 GB master)."""
    data_dir = Path(settings.map_data_dir)
    regions_dir = data_dir / "regions"
    output = data_dir / f"{name}.pmtiles"

    sources = _source_paths(active_regions, regions_dir, part_filename)
    if not sources:
        _clear_master(name, data_dir)
        return 0

    old_size = output.stat().st_size if output.exists() else 0
    n_tiles = custom_merge.merge_archives(sources, str(output))
    return _finalize_master(name, output, data_dir, old_size, len(sources), n_tiles)


def _assemble_overlay(active_regions: list[dict]) -> int:
    """Assemble ``master_overlay.pmtiles`` by tile-joining every active region's
    overlay, for the reasons in ``rebuild_master_overlay``."""
    from app.services import tippecanoe_writer

    data_dir = Path(settings.map_data_dir)
    output = data_dir / f"{MASTER_OVERLAY_NAME}.pmtiles"

    sources = _source_paths(active_regions, data_dir / "regions", "overlay.pmtiles")
    if not sources:
        _clear_master(MASTER_OVERLAY_NAME, data_dir)
        return 0

    old_size = output.stat().st_size if output.exists() else 0
    tippecanoe_writer.merge_overlays(sources, str(output))
    if not output.exists() or output.stat().st_size == 0:
        _clear_master(MASTER_OVERLAY_NAME, data_dir)
        return 0

    n_tiles = _count_tiles(output)
    return _finalize_master(MASTER_OVERLAY_NAME, output, data_dir, old_size,
                            len(sources), n_tiles)


def _finalize_master(name: str, output: Path, data_dir: Path, old_size: int,
                     n_sources: int, n_tiles: int) -> int:
    """Shared post-merge step for ``_assemble``/``_assemble_overlay``: clear the
    served archive if the merge produced nothing, else cluster it (when small
    enough to be worth the rewrite) and log the result."""
    if n_tiles == 0:
        _clear_master(name, data_dir)
        return 0

    # custom_merge writes a valid (sorted) directory but, when interleaving disjoint
    # sources, leaves the tile DATA unclustered. Clustering rewrites the whole file
    # for contiguous reads — worth it for the small vector layers, but a wasteful
    # second multi-GB pass for the DEM (raster, large; hillshade tolerates scattered
    # reads fine). So only cluster archives below CLUSTER_MAX_BYTES; go-pmtiles
    # serves the larger ones unclustered (correctly — the directory is sorted).
    try:
        size = output.stat().st_size if output.exists() else 0
        if size and size <= CLUSTER_MAX_BYTES and not _is_clustered(output):
            _run_pmtiles(["cluster", str(output)], f"{name} cluster")
    except Exception:
        logger.warning("Cluster of %s failed (serving unclustered)", name, exc_info=True)

    new_size = output.stat().st_size if output.exists() else 0
    logger.info("Assembled %s: %d → %d bytes (%d region sources, %d tiles)",
                name, old_size, new_size, n_sources, n_tiles)
    return n_tiles


def _safe_index_pois(index_source: str) -> None:
    from app.services.poi_indexer import index_pmtiles, MIN_TILE_ZOOM
    try:
        index_pmtiles(index_source, label="region POIs (post-merge)",
                      skip_below_zoom=MIN_TILE_ZOOM)
    except Exception:
        logger.exception("POI reindex after merge failed")


# ── Helpers ───────────────────────────────────────────────────────────────────

def _run_pmtiles(args: list[str], what: str, timeout: int = 3600) -> None:
    import subprocess
    result = subprocess.run(["pmtiles", *args], capture_output=True,
                            text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(
            f"pmtiles {what} failed (exit {result.returncode}): "
            f"{result.stderr.strip() or result.stdout.strip()}"
        )


def _count_tiles(path: Path) -> int:
    """Tiles in an archive. ``custom_merge`` returns this from its own bookkeeping;
    tile-join does not, and ``_finalize_master`` needs it to tell "assembled" from
    "produced nothing".

    A read failure returns -1, not 0: ``_finalize_master`` *deletes* the archive on
    0, so a counting problem must never be mistaken for an empty merge."""
    try:
        with open(path, "rb") as f:
            return sum(1 for _ in all_tiles(Reader(MmapSource(f)).get_bytes))
    except Exception:
        logger.warning("Could not count tiles in %s", path.name, exc_info=True)
        return -1


def _is_clustered(path) -> bool:
    with open(path, "rb") as f:
        return bool(Reader(MmapSource(f)).header().get("clustered"))


def _clear_master(master_name, data_dir) -> None:
    """Remove a served archive when no regions contribute to it (e.g. the last
    region was deleted), so its stale tiles stop being served."""
    p = Path(data_dir) / f"{master_name}.pmtiles"
    if p.exists():
        p.unlink()
        write_reload_trigger()
        logger.info("Removed %s — no regions remain", p.name)


def write_reload_trigger() -> None:
    """Signal go-pmtiles' reload-wrapper to restart the serve process. Idempotent."""
    trigger = Path(settings.map_data_dir) / ".reload-trigger"
    try:
        trigger.touch()
    except OSError:
        logger.warning("Could not write reload trigger at %s", trigger)


def _source_paths(active_regions, regions_dir, filename) -> list[str]:
    paths = []
    for r in active_regions:
        p = regions_dir / str(r["id"]) / filename
        if p.exists():
            paths.append(str(p))
    return paths
