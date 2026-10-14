# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Merge PMTiles archives handling overlapping tile keys.

Unlike ``pmtiles merge`` (which errors on overlap), this processes
sources in order and the last source's data wins for any tile key
that appears in multiple inputs.  This allows overlapping region
downloads to coexist — the most-recently-downloaded region takes
precedence on overlapping tiles.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

from pmtiles.reader import MmapSource, Reader, all_tiles
from pmtiles.tile import TileType, zxy_to_tileid
from pmtiles.writer import write as pmtiles_write

logger = logging.getLogger(__name__)


def merge_archives(sources: list[str], output: str, min_zoom: int | None = None,
                   max_zoom: int | None = None, prototype: str | None = None,
                   progress=None) -> int:
    """Merge archives (newest source wins on overlap) into ``output``.

    ``min_zoom``/``max_zoom`` slice by zoom. The header and metadata come from
    ``prototype`` (default: the last source), with the bounds and zoom range
    widened to every source's, so a last source covering only part of the
    ground cannot narrow what the whole archive claims. Returns the number of
    unique tiles written (0 if nothing matched — no file is produced).

    A k-way merge in tile-id order (tile_archives.merge): the output is
    clustered as written, where the old source-by-source copy needed a full
    ``pmtiles cluster`` rewrite afterwards.
    """
    from app.services import tile_archives

    cleaned = [s for s in sources if Path(s).exists() and Path(s).stat().st_size > 0]
    if not cleaned:
        logger.info("No non-empty sources — skipping merge")
        return 0
    old_size = Path(output).stat().st_size if Path(output).exists() else 0
    n = tile_archives.merge(cleaned, output, min_zoom=min_zoom, max_zoom=max_zoom,
                            prototype=prototype, progress=progress)
    if n:
        logger.info("Custom merge done: %d → %d bytes (%d sources, %d unique tiles)",
                    old_size, Path(output).stat().st_size, len(cleaned), n)
    else:
        logger.info("Merge produced no tiles (min_zoom=%s, max_zoom=%s) — skipping write",
                    min_zoom, max_zoom)
    return n


def _union_extent(header: dict, sources: list[str]) -> None:
    """Widen ``header``'s bounds and zoom range to cover every source's."""
    for src in sources:
        try:
            with open(src, "rb") as f:
                h = Reader(MmapSource(f)).header()
        except Exception:
            continue
        for key, pick in (("min_zoom", min), ("min_lon_e7", min), ("min_lat_e7", min),
                          ("max_zoom", max), ("max_lon_e7", max), ("max_lat_e7", max)):
            if key in h and key in header:
                header[key] = pick(header[key], h[key])


def _pick_tile_type(sources: list[str]) -> TileType | None:
    for src in reversed(sources):
        try:
            with open(src, "rb") as f:
                h = Reader(MmapSource(f)).header()
            return h["tile_type"]
        except Exception:
            continue
    return None
