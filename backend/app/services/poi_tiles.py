# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Places of interest as vector tiles: master_pois.pmtiles, layer `pois`.

The map's peaks, springs, campgrounds and the rest used to arrive as GeoJSON,
fetched from `/maps/poi/features` after every pan and ranked in the browser.
That had three costs:

- **A request per pan**, on top of the tiles, which counted against a reverse
  proxy's request limit as much as the tiles did.
- **A cap per request**: at most 900 points per view, the most important
  first, so a dense view simply lost the rest -- POI names came and went
  depending on how much else was in the box.
- **Nothing on the phone**: the phone has no such fetch, so its map had no
  POIs at all, online or off.

As tiles they are fetched and cached like the rest of the map (and joined into
the web map's one tile, merged_tiles), carried into the phone's offline packs,
and uncapped.

## Ranking, baked in

Which icon and which name show at which zoom is decided here, once, by the
same rule the browser used (frontend utils/labelRank.js, from which this is
ported): features claim cells of a world-aligned grid sized to an icon (and,
separately, to a name) in importance order, and show from the first zoom where
their cell is free. The grid is anchored to the world, not a viewport, so
deciding it ahead of time gives the same answer the browser would have --
better, since the browser only ever saw one view's worth of features.

Importance is the stored `min_zoom` grade, and within a grade, for peaks, height:
the highest summit around takes the cell, which is the hiker's idea of which
peak matters. GNIS records no elevations, so a peak's height for ranking is
read from the terrain (elevation_sampler); it is never shown, because the
overview terrain is far too coarse to print as a summit's height.
"""

from __future__ import annotations

import json
import logging
import math
import tempfile
import threading
import time
from pathlib import Path

from sqlalchemy import text

from app.database import SessionLocal
from app.services import map_store

logger = logging.getLogger(__name__)

NAME = "master_pois"
LAYER = "pois"
MIN_ZOOM = 4
#: Points need nothing finer: the map overzooms z13 tiles to 14-17, and every
#: zoom kept is another copy of every point (z14 doubled the archive).
MAX_ZOOM = 13

# Grid ranking, as utils/labelRank.js had it.
WORLD_PX = 512
MAX_Z = 17
CHANNELS = (("icon_zoom", 56, 4), ("label_zoom", 140, 12))
NEVER = MAX_Z + 99

#: Kinds that are not icons: places and boundaries the basemap names itself.
EXCLUDED_KINDS = (
    "locality", "neighbourhood", "macrohood", "microhood",
    "administrative", "country", "region", "county", "borough", "pseudo",
)
#: Kinds with layers of their own that show regardless of rank (labels.js):
#: water sources and trailheads from z10, lake names (collision-culled) from z11.
ALWAYS_FROM = {"spring": 10, "well": 10, "drinking_water": 10, "trailhead": 10,
               "lake": 11, "reservoir": 11, "bay": 11, "sea": 11, "strait": 11, "gulf": 11}


def path() -> Path:
    return map_store.data_dir() / f"{NAME}.pmtiles"


def _cells(lng, lat, z: int, cell_px: int):
    """Grid cells (one int each) of points at zoom z (labelRank.cellKey), vectorised."""
    import numpy as np
    cells = (WORLD_PX * 2 ** z) / cell_px
    x = np.floor((lng + 180) / 360 * cells).astype(np.int64)
    s = np.clip(np.sin(np.radians(lat)), -0.9999999, 0.9999999)
    y = np.floor((0.5 - np.log((1 + s) / (1 - s)) / (4 * np.pi)) * cells).astype(np.int64)
    return (x << 32) | (y & 0xFFFFFFFF)


def rank(features: list[dict]) -> None:
    """Set `icon_zoom` and `label_zoom` on each feature's properties, in place.

    A feature is `{"lng", "lat", "properties": {"min_zoom", "name", ...},
    "rank_ele"}`; `rank_ele` (metres, or None) orders a grade's features,
    highest first.

    One zoom at a time: a cell is free at a zoom unless a feature already
    shown by then sits in it, and of the features waiting for a free cell the
    most important takes it. As in the browser, the grid is the world's, so a
    feature's zooms do not depend on what else was in anyone's view. (The
    browser went feature by feature instead, which let a less important point
    take a cell at a lower zoom than a more important one that had claimed it
    deeper in; one zoom at a time, nothing shown ever shares a cell.)
    """
    import numpy as np

    def key(f):
        p = f["properties"]
        ele = f.get("rank_ele")
        return (p.get("min_zoom") if p.get("min_zoom") is not None else 13,
                0 if ele is not None else 1, -(ele or 0.0),
                str(p.get("name") or ""), f["lng"], f["lat"])

    ordered = sorted(features, key=key)
    if not ordered:
        return
    lng = np.array([f["lng"] for f in ordered], dtype=np.float64)
    lat = np.array([f["lat"] for f in ordered], dtype=np.float64)
    grade = np.array([math.ceil(f["properties"]["min_zoom"]) if f["properties"].get("min_zoom") is not None
                      else 13 for f in ordered], dtype=np.int64)
    for prop, cell_px, floor in CHANNELS:
        assigned = np.full(len(ordered), NEVER, dtype=np.int64)
        start = np.maximum(grade, floor)
        for z in range(floor, MAX_Z + 1):
            cells = _cells(lng, lat, z, cell_px)
            taken = cells[assigned < z]
            waiting = np.nonzero((assigned == NEVER) & (start <= z))[0]
            if not len(waiting):
                continue
            c = cells[waiting]
            free = ~np.isin(c, taken)
            waiting, c = waiting[free], c[free]
            # In priority order, so the first of each cell is the one that wins it.
            _, first = np.unique(c, return_index=True)
            assigned[waiting[first]] = z
        for f, a in zip(ordered, assigned.tolist()):
            f["properties"][prop] = a


def _tile_min_zoom(p: dict) -> int | None:
    """The first zoom a feature is in the tiles, or None when it never shows."""
    always = ALWAYS_FROM.get(p.get("kind") or "")
    first = min(p["icon_zoom"], p["label_zoom"])
    if always is not None:
        first = min(first, always)
    if first > MAX_Z:
        return None
    return max(MIN_ZOOM, min(first, MAX_ZOOM))


def _load(db) -> list[dict]:
    rows = db.execute(text("""
        SELECT name, kind, kind_detail, lat, lng, ele_ft, source, min_zoom
        FROM poi_search
        WHERE kind IS NULL OR kind NOT IN :excluded
    """), {"excluded": EXCLUDED_KINDS}).fetchall()
    out = []
    for r in rows:
        m = r._mapping
        if m["lat"] is None or m["lng"] is None:
            continue
        out.append({
            "lng": float(m["lng"]), "lat": float(m["lat"]),
            "rank_ele": m["ele_ft"] * 0.3048 if m["ele_ft"] is not None else None,
            "properties": {
                "name": m["name"] or "", "kind": m["kind"] or "",
                "kind_detail": m["kind_detail"] or "", "ele_ft": m["ele_ft"],
                "source": m["source"] or "", "min_zoom": m["min_zoom"],
            },
        })
    return out


#: Terrain zoom heights for ranking are read at: ~150 m a pixel, ample to
#: tell a summit from its neighbours, and 16x fewer tiles to decode than z12.
RANK_DEM_ZOOM = 10


def _peak_heights(features: list[dict]) -> None:
    """Fill `rank_ele` for peaks with none, from the terrain.

    The regional terrain where an area is downloaded, the world overview
    elsewhere. Peaks are visited tile by tile, so each terrain tile is decoded
    once (the sampler's own walk, point by point, took half an hour for the
    country's peaks).
    """
    from app.services import elevation_sampler as es
    peaks = [f for f in features if f["properties"]["kind"] == "peak" and f["rank_ele"] is None]
    if not peaks:
        return
    base = map_store.data_dir()
    arcs = [es._open_archive(base / fn) for fn in es._DEM_FILES if (base / fn).exists()]
    try:
        for arc in arcs:
            z = min(arc["zmax"], RANK_DEM_ZOOM)
            if z < arc["zmin"]:
                continue
            todo = [f for f in peaks if f["rank_ele"] is None]
            placed = []
            for f in todo:
                sx, sy = es._world_px(f["lng"], f["lat"], z)
                placed.append((int(sx), int(sy), sx, sy, f))
            placed.sort(key=lambda t: (t[0], t[1]))
            for tx, ty, sx, sy, f in placed:
                arr = es._arc_tile(arc, z, tx, ty)
                if arr is None:
                    continue
                v = es._bilinear(arr, (sx - tx) * es.TILE_PX, (sy - ty) * es.TILE_PX)
                if v == v:
                    f["rank_ele"] = float(v)
    except Exception:
        logger.exception("Peak heights could not be read; peaks rank by name")
    finally:
        for arc in arcs:
            arc["f"].close()


def build(output: Path | None = None) -> int:
    """Build the archive from the POI index. Returns the features written."""
    from app.services import tippecanoe_writer
    output = output or path()
    db = SessionLocal()
    try:
        features = _load(db)
    finally:
        db.close()
    started = time.monotonic()
    _peak_heights(features)
    rank(features)
    # A build killed part-way (a restart) leaves its working copy behind:
    # some hundreds of MB, cleared by the next build.
    import shutil
    for stale in map_store.data_dir().glob(".poi-build-*"):
        shutil.rmtree(stale, ignore_errors=True)
    with tempfile.TemporaryDirectory(prefix=".poi-build-", dir=map_store.data_dir()) as tmp:
        nd = Path(tmp) / f"{LAYER}.ndjson"
        n = 0
        with open(nd, "w", encoding="utf-8") as fh:
            for f in features:
                mz = _tile_min_zoom(f["properties"])
                if mz is None:
                    continue
                p = f["properties"]
                # Only what the map and the point panel read: no `source`, and
                # no detail that only repeats the kind.
                props = {k: v for k, v in p.items()
                         if v not in (None, "") and k != "source"
                         and not (k == "kind_detail" and v == p.get("kind"))}
                fh.write(json.dumps({
                    "type": "Feature",
                    "geometry": {"type": "Point", "coordinates": [round(f["lng"], 6), round(f["lat"], 6)]},
                    "properties": props,
                    "tippecanoe": {"minzoom": mz},
                }, separators=(",", ":")) + "\n")
                n += 1
        if n == 0:
            output.unlink(missing_ok=True)
            return 0
        # -r1: keep every point at every zoom it is placed at. tippecanoe thins
        # points below its base zoom by default, which would undo the ranking
        # above at random.
        tippecanoe_writer.build_overlay({LAYER: nd}, output, min_zoom=MIN_ZOOM, max_zoom=MAX_ZOOM,
                                        extra_args=["-r1"])
    logger.info("Built %s: %d of %d places in %.0fs", output.name, n, len(features),
                time.monotonic() - started)
    return n


# ── Rebuilds ────────────────────────────────────────────────────────────────
#
# The index changes when an area is built (its OSM points), when GNIS is
# imported and on a reindex. Each asks for a rebuild; one runs at a time, and
# requests that land while it runs fold into one more run after it.

_lock = threading.Lock()
_running = False
_again = False

#: Requests within this long of each other make one build: an area build
#: flushes its points and then still has minutes of tiles to make.
DEBOUNCE_S = 20.0


def request_rebuild(delay_s: float = DEBOUNCE_S) -> None:
    """Rebuild the archive in the background, soon."""
    global _running, _again
    with _lock:
        if _running:
            _again = True
            return
        _running = True

    def run():
        global _running, _again
        time.sleep(delay_s)
        while True:
            try:
                build()
                map_store.write_reload_trigger()
            except Exception:
                logger.exception("Building the places archive failed")
            with _lock:
                if not _again:
                    _running = False
                    return
                _again = False

    threading.Thread(target=run, daemon=True, name="poi-tiles").start()


def ensure_built() -> None:
    """At startup: build the archive if there is none."""
    if not path().exists():
        request_rebuild(delay_s=0)
