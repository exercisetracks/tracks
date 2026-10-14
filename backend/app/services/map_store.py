# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The served map archives, and the only code that writes them.

## Masters only

The server holds each map once. Every downloaded area's tiles live in four
master archives that go-pmtiles serves, one file per layer:

| Master | Holds | Per-area file kept? |
|---|---|---|
| `master_basemap_detail` | z13-15 basemap | no |
| `master_dem` | z8-12 terrain | no |
| `master_contours` | z9-12 contour lines | no |
| `master_overlay` | z6-15 trails, water, land, infra | yes: `regions/<id>/overlay*.pmtiles` |

The first three layers are *area-independent*: a basemap or terrain tile is a
copy of the planet's tile at that address, identical whichever area fetched
it, and a contour tile is traced from the master terrain. So an area is added
by merging its freshly built tiles into the master (`publish`), and removed by
dropping the tiles no remaining area touches (`drop_area`). Nothing per area
needs to be kept; that used to be a full second copy of every map (1.6 GB for
a 1.6 GB region).

The overlay is the exception, and the reason is in what its tiles hold: OSM
features cut to an area's own ground. A tile on the edge between two areas
holds features from both, so removing one area from it means knowing which
features were its. Each area therefore keeps its overlay part — about 15% of a
map's size — cut to the ground it `owned` when it was built: its box minus
everything other areas already covered. Owned ground never overlaps, so the
master is exactly the union of the parts (tippecanoe_writer.merge_overlays
tile-joins only the tiles parts share), no feature is ever drawn twice, and
overlapping areas need no merging. When an area is deleted, the ground it
owned that another area still covers is rebuilt into that area's parts
(services.map_builds).

## Writes

Only the build queue (services.map_builds) calls the writers here, and it
holds a cross-process lock while it does, so two uvicorn workers can never
rewrite a master at once. Every write goes to a temp file beside the master
and is renamed over it: go-pmtiles and a phone pack being cut never see a
half-written archive, and a failed write leaves the previous one serving.
"""

from __future__ import annotations

import logging
import math
from pathlib import Path

from app.config import settings
from app.services import tile_archives

logger = logging.getLogger(__name__)

MASTER_BASEMAP = "master_basemap_detail"
MASTER_DEM = "master_dem"
MASTER_CONTOURS = "master_contours"
MASTER_OVERLAY = "master_overlay"
AREA_INDEPENDENT = (MASTER_BASEMAP, MASTER_DEM, MASTER_CONTOURS)


def data_dir() -> Path:
    return Path(settings.map_data_dir)


def master_path(name: str) -> Path:
    return data_dir() / f"{name}.pmtiles"


def work_dir(region_id: int) -> Path:
    """Scratch for one build: phase outputs live here until published."""
    return data_dir() / "work" / str(region_id)


def area_dir(region_id: int) -> Path:
    return data_dir() / "regions" / str(region_id)


def overlay_parts(region_id: int | None = None) -> list[Path]:
    """One area's overlay parts, or every area's, oldest area first."""
    root = data_dir() / "regions"
    if region_id is not None:
        return sorted((root / str(region_id)).glob("overlay*.pmtiles"))
    if not root.exists():
        return []
    dirs = sorted((d for d in root.iterdir() if d.is_dir() and d.name.isdigit()),
                  key=lambda d: int(d.name))
    return [p for d in dirs for p in sorted(d.glob("overlay*.pmtiles"))]


def write_reload_trigger() -> None:
    """Tell go-pmtiles' reload-wrapper to reopen the archives. Idempotent."""
    trigger = data_dir() / ".reload-trigger"
    try:
        trigger.touch()
    except OSError:
        logger.warning("Could not write reload trigger at %s", trigger)


# ── Writers ──────────────────────────────────────────────────────────────────

def publish(name: str, part: str | Path, progress=None) -> int:
    """Merge an area's freshly built tiles into a master; the new tiles win.
    `progress(fraction)` follows the merge."""
    master = master_path(name)
    before = master.stat().st_size if master.exists() else 0
    n = tile_archives.merge([master, part], master, prototype=part,
                            progress=(lambda d, t: progress(d / max(t, 1))) if progress else None)
    logger.info("Published %s into %s: %d → %d bytes, %d tiles", Path(part).name, name,
                before, master.stat().st_size if master.exists() else 0, n)
    return n


def drop_area(name: str, area, remaining) -> int:
    """Remove from a master the tiles `area` touches and `remaining` does not.

    `area` and `remaining` are shapely geometries in lon/lat; `remaining` may be
    None (nothing else is installed). A tile on the border of both stays as it
    is: for these layers its content does not depend on which area fetched it.

    The tiles to drop are worked out as tile-id ranges, per zoom, by the same
    quadtree cover the size estimate uses (remote_estimate.rect_ranges), and
    each tile is then a binary search away from its answer. Testing every
    tile's bounds against the shapes in Python took 17 s for a state's 737,000
    basemap tiles, on one core.
    """
    import bisect

    from shapely.geometry import box
    from shapely.prepared import prep

    from app.services.remote_estimate import rect_ranges

    master = master_path(name)
    if not master.exists():
        return 0
    h = tile_archives.header(master)
    held = prep(remaining) if remaining is not None and not remaining.is_empty else None
    # Area envelopes are safe here: any tile inside an envelope but outside
    # the area's own shape was put there by another area, which `remaining`
    # protects.
    skip = (lambda w, s, e, n: held.contains(box(w, s, e, n))) if held else None
    skip_tile = (lambda w, s, e, n: held.intersects(box(w, s, e, n))) if held else None
    ranges = sorted(r for z in range(int(h["min_zoom"]), int(h["max_zoom"]) + 1)
                    for r in rect_ranges(area.bounds, z, skip, skip_tile))
    if not ranges:
        # Nothing only this area touched (it lay inside others): no rewrite.
        logger.info("Nothing to drop from %s", name)
        return tile_archives.tile_count(master)
    starts = [lo for lo, _ in ranges]
    dropped = 0

    def keep(tid: int) -> bool:
        nonlocal dropped
        i = bisect.bisect_right(starts, tid) - 1
        if i >= 0 and tid < ranges[i][1]:
            dropped += 1
            return False
        return True

    n = tile_archives.merge([master], master, keep=keep)
    logger.info("Dropped %d tiles from %s (%d remain)", dropped, name, n)
    return n


def rebuild_overlay(progress_cb=None, cancel=None) -> int:
    """master_overlay = the union of every area's overlay parts."""
    from app.services import tippecanoe_writer
    parts = [str(p) for p in overlay_parts()]
    master = master_path(MASTER_OVERLAY)
    if not parts:
        master.unlink(missing_ok=True)
        return 0
    tippecanoe_writer.merge_overlays(parts, master, progress_cb=progress_cb, cancel=cancel)
    if progress_cb is not None:
        progress_cb(100.0, "Trails added")
    return tile_archives.tile_count(master)


# ── Geometry ─────────────────────────────────────────────────────────────────

def tile_bounds(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    """(west, south, east, north) of an XYZ tile."""
    n = 2 ** z
    west = x / n * 360.0 - 180.0
    east = (x + 1) / n * 360.0 - 180.0
    north = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / n))))
    south = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n))))
    return west, south, east, north


def geojson(geom) -> dict | None:
    from shapely.geometry import mapping
    if geom is None or geom.is_empty:
        return None
    return mapping(geom)


def shape(doc):
    from shapely.geometry import shape as _shape
    return _shape(doc) if doc else None
