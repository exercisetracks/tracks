# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""PMTiles contour builder: DEM archive → contour MVT archive.

Iterates the DEM tiles, processes each into a contour MVT tile across a process
pool, and streams the results into a new PMTiles archive. Each worker decodes its
own tile (plus neighbour padding), extracts contour lines, and encodes them as a
gzipped MVT.

The pool uses a *spawn* context, not fork: this builder runs inside a background
thread of the multi-threaded uvicorn process, where forking inherits broken
C-extension/lock state and every worker silently fails. Spawn re-imports the
module fresh, so the DEM path can't be inherited via a global and is passed
through the pool initializer instead.
"""

from __future__ import annotations

import gzip
import multiprocessing
import os
from collections import OrderedDict
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from typing import Sequence

import mapbox_vector_tile
from pmtiles.reader import MmapSource, Reader, all_tiles
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import write as pmtiles_write

from .constants import CONTOUR_PAD_PX, INTERVAL_FT, TILE_PX, logger
from .contours import _generate_mvt_features
from .dem import _decode_terrarium, _padded_elevation
from .tiling import _bbox_overlaps, _tile_bounds

# DEM archive path seeded into each spawned worker via the pool initializer (spawn
# re-imports this module, so a parent global can't be inherited).
_CONTOUR_DEM_PATH: str | None = None


def _init_contour_worker(dem_path_str: str) -> None:
    """ProcessPool initializer (spawn): seed each worker's DEM path. Spawn re-imports
    this module fresh, so the path can't be inherited via a parent global."""
    global _CONTOUR_DEM_PATH
    _CONTOUR_DEM_PATH = dem_path_str


def _contour_tile(job: tuple[int, int, int]) -> tuple[int, bytes | None]:
    """ex.map worker: process one (z, x, y) DEM tile into a contour MVT tile."""
    z, x, y = job
    return _process_one_contour_tile(_CONTOUR_DEM_PATH, z, x, y, INTERVAL_FT.get(z, 100))


def _process_one_contour_tile(
    dem_path_str: str, z: int, x: int, y: int, interval_ft: int,
) -> tuple[int, bytes | None]:
    """Process a single DEM tile and return (tile_id, gzip_mvt_bytes | None).

    Runs in a worker process — each opens its own mmap over the DEM file. Multiple
    mmaps of the same file share the kernel page cache, so there is no extra I/O.
    """
    try:
        with open(dem_path_str, "rb") as f:
            reader = Reader(MmapSource(f))
            raw = reader.get(z, x, y)
            if not raw:
                return (zxy_to_tileid(z, x, y), None)

            elev_m = _decode_terrarium(raw)
            if elev_m is None:
                return (zxy_to_tileid(z, x, y), None)
            if elev_m.shape[0] != TILE_PX or elev_m.shape[1] != TILE_PX:
                return (zxy_to_tileid(z, x, y), None)

            cache: OrderedDict = OrderedDict()
            cache[(z, x, y)] = elev_m
            padded = _padded_elevation(reader, z, x, y, CONTOUR_PAD_PX, cache, elev_m)

            features = _generate_mvt_features(padded, interval_ft, pad=CONTOUR_PAD_PX)
            if not features:
                return (zxy_to_tileid(z, x, y), None)

            mvt_bytes = mapbox_vector_tile.encode(
                [{"name": "contours", "features": features}],
                y_coord_down=True,
            )
            mvt_bytes = gzip.compress(mvt_bytes)
            return (zxy_to_tileid(z, x, y), mvt_bytes)
    except Exception:
        return (zxy_to_tileid(z, x, y), None)


def build_contours_pmtiles(
    dem_path: str | Path,
    output_path: str | Path,
    min_zoom: int = 9,
    max_zoom: int = 14,
    bbox: Sequence[float] | None = None,
    progress_cb=None,
    cancel=None,
) -> None:
    """Build a contour MVT PMTiles archive from a Terrarium DEM PMTiles archive.

    Tiles are generated in parallel across all cores via ProcessPoolExecutor and
    streamed straight into the output archive as they complete (driven by ex.map,
    not an all-futures dict), so peak memory is bounded by the per-tile work rather
    than the tile count — a continent-scale DEM won't exhaust RAM.

    When *bbox* (w, s, e, n) is given, only tiles overlapping that bbox
    are regenerated — all other tiles are copied from an existing
    contours archive if present.
    """
    dem_path = Path(dem_path)
    output_path = Path(output_path)

    if not dem_path.exists():
        logger.error("DEM archive not found: %s", dem_path)
        return

    tmp_output = output_path.with_name(output_path.name + ".tmp")
    if tmp_output.exists():
        tmp_output.unlink()
    if output_path.exists():
        output_path.unlink()

    logger.info(
        "Building contours %s → %s (z%d–z%d)",
        dem_path.name, output_path.name, min_zoom, max_zoom,
    )

    # ── Phase 1: collect tile coordinates to process ───────────────────────
    tile_jobs: list[tuple[int, int, int]] = []
    dem_max_zoom = 12
    metadata: dict = {}

    with open(dem_path, "rb") as f:
        reader = Reader(MmapSource(f))
        try:
            metadata = reader.metadata() or {}
            dem_max_zoom = reader.header().get("max_zoom", 12)
        except Exception:
            pass

        effective_max = min(max_zoom, dem_max_zoom)
        logger.info("DEM max zoom: %d — generating contours z%d–z%d",
                    dem_max_zoom, min_zoom, effective_max)

        for (z, x, y), raw in all_tiles(reader.get_bytes):
            if z < min_zoom or z > effective_max:
                continue
            if not raw:
                continue
            if bbox is not None:
                tw, ts, te, tn = _tile_bounds(z, x, y)
                if not _bbox_overlaps((tw, ts, te, tn), bbox):
                    continue
            tile_jobs.append((z, x, y))

    n_jobs = len(tile_jobs)
    if n_jobs == 0:
        logger.warning("No contour tiles to generate — DEM may be empty or all-ocean")
        tmp_output.unlink(missing_ok=True)
        return

    # ── Phase 2+3: generate tiles in parallel, streaming them into the archive ─
    # Tiles are written straight into the archive as the pool yields them (rather
    # than collected in a list) and the pool is driven with ex.map (no all-futures
    # dict), so peak memory stays bounded by the DEM tile size — not by the tile
    # count — even for a continent-scale DEM. tile_jobs is in clustered DEM order
    # and ex.map preserves input order, so the new tiles are written clustered.
    n_workers = max(1, min(os.cpu_count() or 4, n_jobs, 32))
    logger.info("Generating contours for %d tiles with %d workers…",
                n_jobs, n_workers)

    global _CONTOUR_DEM_PATH
    _CONTOUR_DEM_PATH = str(dem_path)
    chunksize = max(1, n_jobs // (n_workers * 8))
    processed = skipped = copied = 0

    header = {
        "tile_type":        TileType.MVT,
        "tile_compression": Compression.GZIP,
        "min_zoom":         min_zoom,
        "max_zoom":         effective_max,
        "min_lon":          -180.0,
        "max_lon":          180.0,
        "min_lat":          -85.0,
        "max_lat":          85.0,
        "center_lon":       0.0,
        "center_lat":       0.0,
        "center_zoom":      min_zoom,
    }

    wrote_any = False
    with pmtiles_write(str(tmp_output)) as writer:
        # bbox-scoped regen keeps every tile outside the bbox by copying it across.
        if bbox is not None and output_path.exists():
            try:
                with open(output_path, "rb") as ef:
                    existing = Reader(MmapSource(ef))
                    for (ez, ex_, ey), eraw in all_tiles(existing.get_bytes):
                        if eraw:
                            writer.write_tile(zxy_to_tileid(ez, ex_, ey), eraw)
                            copied += 1
            except Exception:
                copied = 0

        from app.services.download_cancel import DownloadCancelled
        ctx = multiprocessing.get_context("spawn")
        with ProcessPoolExecutor(max_workers=n_workers, mp_context=ctx,
                                 initializer=_init_contour_worker,
                                 initargs=(str(dem_path),)) as ex:
            if cancel is not None:
                cancel.add_pool(ex)   # watcher shuts the pool down on cancel
            try:
                for tile_id, mvt_bytes in ex.map(_contour_tile, tile_jobs, chunksize=chunksize):
                    if cancel is not None and cancel.cancelled():
                        raise DownloadCancelled()
                    if mvt_bytes is not None:
                        writer.write_tile(tile_id, mvt_bytes)
                        processed += 1
                    else:
                        skipped += 1
                    total = processed + skipped
                    if total % 5000 == 0 or total == n_jobs:
                        logger.info("  %d tiles written, %d skipped, %d/%d done",
                                    processed, skipped, total, n_jobs)
                    # ~100 evenly-spaced ticks regardless of job size, plus the
                    # final one, so small regions get a smooth bar and huge jobs
                    # don't spam.
                    if progress_cb is not None and (total % max(1, n_jobs // 100) == 0 or total == n_jobs):
                        try:
                            progress_cb(total, n_jobs)
                        except Exception:
                            pass
            except DownloadCancelled:
                raise
            except Exception:
                # A watcher pool shutdown surfaces as a broken-pool error mid-map;
                # if we're cancelling, report it as such rather than a build error.
                if cancel is not None and cancel.cancelled():
                    raise DownloadCancelled()
                raise
            finally:
                if cancel is not None:
                    cancel.discard_pool(ex)

        # finalize() indexes tile_entries[0]; skip it (and the file) if nothing
        # was written, otherwise it raises on an empty archive.
        if processed > 0 or copied > 0:
            writer.finalize(header, metadata)
            wrote_any = True

    if not wrote_any:
        logger.warning("No contour tiles generated — DEM may be empty or all-ocean")
        tmp_output.unlink(missing_ok=True)
        return

    os.replace(str(tmp_output), str(output_path))
    mb = output_path.stat().st_size / 1_000_000
    logger.info(
        "Contours written: %s — %d generated + %d copied, %d skipped, %.1f MB",
        output_path.name, processed, copied, skipped, mb,
    )
