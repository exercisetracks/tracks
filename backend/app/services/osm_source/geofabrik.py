# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Geofabrik extract catalogue: find the smallest covering extract for a bbox.

Geofabrik publishes a hierarchy of pre-cut OSM extracts (continent → country →
state) with a GeoJSON index. ``find_extract`` picks the smallest-bbox extract
that fully covers a requested area — i.e. the most specific one — so first-use
downloads stay as small as possible. The index is cached on disk and refreshed
weekly.
"""

from __future__ import annotations

import json
import threading
import time

import httpx

from app.services.osm_source.config import (
    GEOFABRIK_INDEX_URL, INDEX_MAX_AGE_S, USER_AGENT, _osm_dir, logger,
)

_index_lock = threading.Lock()


def _load_index() -> list[dict] | None:
    """Return the Geofabrik extract list (cached on disk, refreshed weekly)."""
    cache = _osm_dir() / "geofabrik-index.json"
    with _index_lock:
        fresh = cache.exists() and (time.time() - cache.stat().st_mtime) < INDEX_MAX_AGE_S
        if not fresh:
            try:
                r = httpx.get(GEOFABRIK_INDEX_URL, headers={"User-Agent": USER_AGENT},
                              timeout=60, follow_redirects=True)
                r.raise_for_status()
                cache.write_bytes(r.content)
            except Exception as exc:
                logger.warning("Geofabrik index fetch failed: %s", exc)
                if not cache.exists():
                    return None
        try:
            return json.loads(cache.read_text()).get("features", [])
        except Exception as exc:
            logger.warning("Geofabrik index parse failed: %s", exc)
            return None


def _geom_bbox(geom: dict) -> tuple[float, float, float, float] | None:
    """(w,s,e,n) bounding box of a GeoJSON Polygon/MultiPolygon."""
    coords = geom.get("coordinates")
    if not coords:
        return None
    xs: list[float] = []
    ys: list[float] = []

    def walk(c):
        if isinstance(c, (list, tuple)):
            if c and isinstance(c[0], (int, float)):
                xs.append(c[0])
                ys.append(c[1])
            else:
                for sub in c:
                    walk(sub)

    walk(coords)
    if not xs:
        return None
    return min(xs), min(ys), max(xs), max(ys)


def find_extract(bbox: tuple[float, float, float, float]) -> tuple[str, str] | None:
    """Smallest Geofabrik extract (by bbox area) that fully covers ``bbox``.

    Returns (extract_id, pbf_url) or None. Geofabrik's hierarchy means the
    smallest covering box is the most specific extract (a US state, a country…).
    """
    w, s, e, n = bbox
    features = _load_index()
    if not features:
        return None
    best = None
    best_area = float("inf")
    for f in features:
        props = f.get("properties") or {}
        url = (props.get("urls") or {}).get("pbf")
        geom = f.get("geometry")
        if not url or not geom:
            continue
        bb = _geom_bbox(geom)
        if not bb:
            continue
        bw, bs, be, bn = bb
        # small epsilon so a region touching the extract edge still counts
        if bw - 1e-6 <= w and bs - 1e-6 <= s and be + 1e-6 >= e and bn + 1e-6 >= n:
            area = (be - bw) * (bn - bs)
            if area < best_area:
                best_area = area
                best = (props.get("id") or url, url)
    if best:
        logger.info("Geofabrik extract for bbox %s → %s", bbox, best[0])
    else:
        logger.warning("No Geofabrik extract covers bbox %s", bbox)
    return best
