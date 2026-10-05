# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Legacy pyosmium parse → Overpass-shaped elements, cached per bbox.

The builders that haven't moved to the streaming export path call
``region_elements`` to get a region's whole feature set as Overpass-JSON-shaped
dicts (so they consume them exactly as they did the real Overpass response).
``_parse`` does a memory-bounded two-pass pyosmium read; ``region_elements``
wraps it with a per-bbox cache + compute lock so six concurrent builders trigger
a single download/extract/parse. ``plan_build_cells`` decides whether a region is
built in one pass or chunked into a grid.
"""

from __future__ import annotations

import math
import threading
import time
from pathlib import Path

from app.services.osm_source.config import (
    BUILD_CELL_BUFFER_DEG, BUILD_CELL_DEG, BUILD_ONE_SHOT_SQDEG, logger,
)
from app.services.osm_source.osmium_runner import _extract_region, ensure_source


def _parse(pbf: Path) -> dict | None:
    """Read a region PBF into element dicts matching Overpass output:

      way      → {type:'way', id, geometry:[{lon,lat}…], tags, center:{lat,lon}}
      relation → {type:'relation', id, tags, center,
                  members:[{type:'way', role, geometry:[{lon,lat}…]}]}
      node     → {type:'node', id, lat, lon, tags}

    Relations get member-way geometry inlined (PBF is node<way<relation ordered,
    so all way geometry is known by the time a relation is seen) — reproducing
    Overpass ``out geom`` for both multipolygons and route relations.

    Two-pass strategy to bound peak memory:
      Pass 1 (fast, no location store): scan relation members to collect the way
        IDs whose geometry is actually needed — typically <1% of all ways.
      Pass 2 (full, with location store): resolve way node coordinates; only
        store geometry in way_geom for the collected IDs (as compact tuples).
    This avoids building a Python dict for every node coordinate of every way —
    for a state-sized region that can easily be 5–10 GB of Python objects.
    """
    try:
        import osmium
    except Exception as exc:
        logger.warning("pyosmium import failed: %s", exc)
        return None

    # ── Pass 1: collect relation-member way IDs (no location store needed) ────
    rel_way_ids: set[int] | None = set()

    class _RelScan(osmium.SimpleHandler):
        def relation(self, r):
            if r.tags:
                for m in r.members:
                    if m.type == "w":
                        rel_way_ids.add(m.ref)

    try:
        _RelScan().apply_file(str(pbf))
    except Exception as exc:
        logger.warning("pyosmium relation pre-scan failed: %s", exc)
        rel_way_ids = None  # fallback: store geometry for all ways

    # ── Pass 2: full parse with node location resolution ─────────────────────
    # way_geom uses compact (lon, lat) tuples — ~4× smaller than {lon,lat} dicts
    # — and is populated only for relation-member ways, not the full way set.
    way_geom: dict[int, list[tuple[float, float]]] = {}
    ways: list[dict] = []
    nodes: list[dict] = []
    relations: list[dict] = []

    class _H(osmium.SimpleHandler):
        def node(self, n):
            if len(n.tags) == 0:
                return
            try:
                nodes.append({"type": "node", "id": n.id, "lat": n.location.lat,
                              "lon": n.location.lon, "tags": {t.k: t.v for t in n.tags}})
            except Exception:
                pass

        def way(self, w):
            # Build compact geometry as (lon, lat) tuples
            pts: list[tuple[float, float]] = []
            for nd in w.nodes:
                try:
                    if nd.location.valid():
                        pts.append((nd.location.lon, nd.location.lat))
                except Exception:
                    continue

            # Cache geometry only for ways used by relations
            if len(pts) >= 2 and (rel_way_ids is None or w.id in rel_way_ids):
                way_geom[w.id] = pts

            if not w.tags:
                return

            # Builders expect {lon,lat} dicts — convert for tagged ways only
            geom = [{"lon": p[0], "lat": p[1]} for p in pts]
            if not geom:
                return
            xs = [p[0] for p in pts]
            ys = [p[1] for p in pts]
            center = {"lat": (min(ys) + max(ys)) / 2, "lon": (min(xs) + max(xs)) / 2}
            ways.append({"type": "way", "id": w.id, "geometry": geom,
                         "tags": {t.k: t.v for t in w.tags}, "center": center})

        def relation(self, r):
            if len(r.tags) == 0:
                return
            members = []
            allpts: list[tuple[float, float]] = []
            for m in r.members:
                if m.type == "w":
                    g = way_geom.get(m.ref)
                    if g:
                        members.append({"type": "way", "role": m.role,
                                        "geometry": [{"lon": p[0], "lat": p[1]} for p in g]})
                        allpts.extend(g)
            center = None
            if allpts:
                lons = [p[0] for p in allpts]
                lats = [p[1] for p in allpts]
                center = {"lat": (min(lats) + max(lats)) / 2,
                          "lon": (min(lons) + max(lons)) / 2}
            relations.append({"type": "relation", "id": r.id,
                              "tags": {t.k: t.v for t in r.tags},
                              "members": members, "center": center})

    try:
        _H().apply_file(str(pbf), locations=True)
    except Exception as exc:
        logger.warning("pyosmium parse failed: %s", exc)
        return None
    return {"ways": ways, "relations": relations, "nodes": nodes}


# ── Public API: cached per-bbox region elements ───────────────────────────────

_cache: dict[tuple, dict | None] = {}
_cache_lock = threading.Lock()
_compute_lock = threading.Lock()


def _key(bbox) -> tuple:
    return tuple(round(float(c), 6) for c in bbox)


def region_elements(bbox: tuple[float, float, float, float]) -> dict | None:
    """All local OSM elements for ``bbox`` as Overpass-shaped dicts, or None to
    signal the caller to use its Overpass fallback.

    Result is cached per bbox and computed once even when the 6 builders call
    concurrently — so a region triggers a single download/extract/parse, not six.
    """
    k = _key(bbox)
    with _cache_lock:
        if k in _cache:
            return _cache[k]
    # Serialise the (heavy) compute; double-check the cache inside the lock so
    # only the first concurrent builder does the work.
    with _compute_lock:
        with _cache_lock:
            if k in _cache:
                return _cache[k]
        result = _compute(bbox)
        with _cache_lock:
            _cache[k] = result
        return result


def _compute(bbox) -> dict | None:
    t0 = time.time()
    source = ensure_source(bbox)
    if source is None:
        return None
    region = _extract_region(bbox, source)
    if region is None:
        return None
    try:
        els = _parse(region)
    finally:
        region.unlink(missing_ok=True)
    if els is None:
        return None
    logger.info("Local OSM extract for %s: %d ways / %d relations / %d nodes in %.1fs",
                bbox, len(els["ways"]), len(els["relations"]), len(els["nodes"]),
                time.time() - t0)
    return els


def clear_cache(bbox=None) -> None:
    """Drop cached parse results to free memory after a region build."""
    with _cache_lock:
        if bbox is None:
            _cache.clear()
        else:
            _cache.pop(_key(bbox), None)


def plan_build_cells(
    bbox: tuple[float, float, float, float],
) -> list[tuple[tuple[float, float, float, float], tuple[float, float, float, float] | None]]:
    """Split a region into ``(extract_bbox, core_bbox)`` build cells.

    Returns a single ``[(bbox, None)]`` for regions small enough to build in one
    pass (the common case — no chunking, ``core_bbox=None`` means "keep every
    feature"). Larger regions are split into a grid of cells ≤ ``BUILD_CELL_DEG``
    per side: each ``extract_bbox`` is slightly buffered (so a feature crossing
    the boundary keeps its full geometry) while its ``core_bbox`` is the exact,
    unbuffered grid cell — the orchestrator keeps only features whose
    representative point lies in the core, so each feature is emitted exactly once
    across the cells. One tippecanoe then tiles the accumulated NDJSON.
    """
    w, s, e, n = (float(c) for c in bbox)
    width, height = e - w, n - s
    if width * height <= BUILD_ONE_SHOT_SQDEG:
        return [((w, s, e, n), None)]

    nx = max(1, math.ceil(width / BUILD_CELL_DEG))
    ny = max(1, math.ceil(height / BUILD_CELL_DEG))
    b = BUILD_CELL_BUFFER_DEG
    cells = []
    for ix in range(nx):
        for iy in range(ny):
            cw = w + width * ix / nx
            ce = w + width * (ix + 1) / nx
            cs = s + height * iy / ny
            cn = s + height * (iy + 1) / ny
            core = (cw, cs, ce, cn)
            ext = (cw - b, cs - b, ce + b, cn + b)
            cells.append((ext, core))
    logger.info("Region %s exceeds one-shot size — building in %d cells (%d×%d)",
                bbox, len(cells), nx, ny)
    return cells
