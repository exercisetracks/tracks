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
                   max_zoom: int | None = None, prototype: str | None = None) -> int:
    """Merge archives (newest source wins on overlap) into ``output``.

    When ``min_zoom`` is given, tiles below that zoom are dropped. This is used
    to pre-combine region archives into a slice that is *disjoint* from a static
    low-zoom overview (e.g. keep only z13+ region detail so it can be handed to
    go-pmtiles' native ``merge`` alongside a z0-12 basemap). ``max_zoom`` drops
    tiles above that zoom — used the opposite way, to slice a single archive
    down to just its coarse band (e.g. region_merger pulling each region's z6-8
    tiles out on their own before a real tile-join, since last-wins here would be
    unsafe for tiles two regions both cover). Returns the number of unique tiles
    written (0 if nothing matched — no file is produced).

    The header and metadata come from ``prototype`` (default: the last source),
    except the bounds and zoom range, which are the union of every source's: a
    last source that covers only part of the ground — the shared tiles
    tippecanoe_writer.merge_overlays puts last — must not narrow what the whole
    archive claims to hold.
    """
    cleaned = [s for s in sources if Path(s).exists() and Path(s).stat().st_size > 0]

    if not cleaned:
        logger.info("No non-empty sources — skipping merge")
        return 0

    proto_path = prototype if prototype and Path(prototype).exists() else cleaned[-1]
    with open(proto_path, "rb") as f:
        proto = Reader(MmapSource(f))
        header = proto.header()
        metadata = proto.metadata()
    _union_extent(header, cleaned)

    new_output = Path(str(output) + ".new")

    seen: set[int] = set()
    with pmtiles_write(str(new_output)) as writer:
        for src in reversed(cleaned):
            with open(src, "rb") as f:
                reader = Reader(MmapSource(f))
                for (z, x, y), data in all_tiles(reader.get_bytes):
                    if min_zoom is not None and z < min_zoom:
                        continue
                    if max_zoom is not None and z > max_zoom:
                        continue
                    tile_id = zxy_to_tileid(z, x, y)
                    if tile_id not in seen:
                        writer.write_tile(tile_id, data)
                        seen.add(tile_id)

        # finalize() indexes tile_entries[0]; calling it with no tiles raises.
        if seen:
            header["tile_type"] = _pick_tile_type(cleaned) or header["tile_type"]
            writer.finalize(header, metadata)

    if not seen:
        new_output.unlink(missing_ok=True)
        logger.info("Merge produced no tiles (min_zoom=%s, max_zoom=%s) — skipping write",
                   min_zoom, max_zoom)
        return 0

    old_size = Path(output).stat().st_size if Path(output).exists() else 0
    new_size = new_output.stat().st_size

    os.replace(str(new_output), str(output))

    logger.info(
        "Custom merge done: %d → %d bytes (%d sources, %d unique tiles)",
        old_size, new_size, len(cleaned), len(seen),
    )

    return len(seen)


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
