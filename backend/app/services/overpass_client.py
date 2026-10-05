# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared Overpass fetch + geometry helpers.

Low-level building blocks used by every OSM feature builder (trails, water,
infra, landuse, areas, poi): a mirror-racing Overpass client, the bbox splitter
for large queries, and a crude metric length estimator for zoom grading. Kept in
one small module so the builders import the same primitives without pulling in
the whole trail-classification surface.
"""

from __future__ import annotations

import logging
import math
import time

import httpx
from shapely.geometry import LineString

logger = logging.getLogger(__name__)

USER_AGENT = "Tracks/1.0 (self-hosted fitness app; trail data)"

# Public Overpass mirrors — raced in parallel with a retry pass. The public
# endpoints are frequently busy (504/timeout); racing them makes the fetch
# reliable. A proper User-Agent is required (default agents get 406).
OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]

# Large regions exceed one Overpass query, so the bbox is split into cells of at
# most this many degrees per side and fetched (politely) one at a time.
MAX_CELL_DEG = 0.4
POLITE_DELAY_S = 1.0


def _overpass_one(ep: str, query: str, timeout: int):
    """Single mirror POST → (elements, None) on 200, else (None, error)."""
    try:
        r = httpx.post(ep, content=query.encode(),
                       headers={"User-Agent": USER_AGENT, "Content-Type": "text/plain"},
                       timeout=timeout)
        if r.status_code == 200:
            return r.json().get("elements", []), None
        return None, f"{ep} -> HTTP {r.status_code}"
    except Exception as exc:
        return None, f"{ep} -> {type(exc).__name__}"


def _overpass(query: str, timeout: int = 180) -> list[dict]:
    """POST a query to all Overpass mirrors in parallel; first 200 wins.

    Racing the mirrors (rather than rotating sequentially) means a busy mirror's
    504/timeout no longer stalls the whole fetch — the fastest healthy endpoint
    answers immediately. Used for the one-time global-routes overview and as a
    fallback when the local OSM extract is unavailable.
    """
    import concurrent.futures as cf

    last = "no response"
    for attempt in range(2):
        ex = cf.ThreadPoolExecutor(max_workers=len(OVERPASS_MIRRORS))
        futs = [ex.submit(_overpass_one, ep, query, timeout) for ep in OVERPASS_MIRRORS]
        try:
            for fut in cf.as_completed(futs):
                res, err = fut.result()
                if res is not None:
                    return res
                last = err
        finally:
            # Don't block on slow/hung mirrors still in flight.
            ex.shutdown(wait=False, cancel_futures=True)
        if attempt == 0:
            time.sleep(2)
    raise RuntimeError(f"Overpass fetch failed (all mirrors): {last}")


def _split_bbox(bbox, max_deg=MAX_CELL_DEG):
    """Split (w,s,e,n) into a grid of cells ≤ max_deg per side."""
    w, s, e, n = bbox
    nx = max(1, math.ceil((e - w) / max_deg))
    ny = max(1, math.ceil((n - s) / max_deg))
    cells = []
    for ix in range(nx):
        for iy in range(ny):
            cells.append((w + (e - w) * ix / nx, s + (n - s) * iy / ny,
                          w + (e - w) * (ix + 1) / nx, s + (n - s) * (iy + 1) / ny))
    return cells


def _approx_length_m(ls: LineString) -> float:
    """Crude length in metres (~96 km per degree at mid-latitude) for zoom grading."""
    return ls.length * 96000.0
