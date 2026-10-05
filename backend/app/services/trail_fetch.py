# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fetch trail ways + hiking/foot route relations from OSM.

Per-region data comes from the local OSM extract (osm_source) when available;
the one-time global-routes overview and any uncovered area fall back to Overpass.
Geometry classification lives in trail_classify; this module only fetches.
"""

from __future__ import annotations

import logging
import time

from app.services import osm_source
from app.services.overpass_client import POLITE_DELAY_S, _overpass, _split_bbox
from app.services.trail_classify import TRAIL_HIGHWAYS

logger = logging.getLogger(__name__)


def fetch_trail_ways(bbox: tuple[float, float, float, float], timeout: int = 180) -> list[dict]:
    """Fetch trail ways (geometry + tags) in bbox=(west,south,east,north).

    Excludes sidewalks/crossings — in cities those flood the map with a dense
    grid of pedestrian ways that read as trails.
    """
    w, s, e, n = bbox
    hw = "|".join(TRAIL_HIGHWAYS)
    q = (f'[out:json][timeout:120];'
         f'(way["highway"~"^({hw})$"]["footway"!~"^(sidewalk|crossing|access_aisle|traffic_island)$"]'
         f'({s},{w},{n},{e}););out geom tags;')
    return _overpass(q, timeout)


def fetch_routes(bbox: tuple[float, float, float, float], timeout: int = 180,
                 networks: str | None = None) -> list[dict]:
    """Fetch hiking/foot route relations (with member geometry) in bbox.

    `networks` is an optional Overpass regex (e.g. "iwn|nwn|rwn") restricting to
    importance classes — used by the global build to skip the huge `lwn` set.
    None keeps the per-region behaviour (local extract, any network).
    """
    if networks is None:
        reg = osm_source.region_elements(bbox)
        if reg is not None:
            return [r for r in reg["relations"]
                    if (r.get("tags") or {}).get("route") in ("hiking", "foot")]
    w, s, e, n = bbox
    net = f'["network"~"^({networks})$"]' if networks else '["network"]'
    # `out geom;` (NOT `out geom tags;` — that suppresses member geometry).
    q = (f'[out:json][timeout:160];'
         f'(relation["route"~"^(hiking|foot)$"]{net}({s},{w},{n},{e}););out geom;')
    return _overpass(q, timeout)


def fetch_trail_ways_chunked(bbox) -> list[dict]:
    """Trail ways over a bbox — local OSM extract if available, else Overpass cells."""
    reg = osm_source.region_elements(bbox)
    if reg is not None:
        return osm_source.select(reg["ways"], "highway", set(TRAIL_HIGHWAYS))
    cells = _split_bbox(bbox)
    seen, out = set(), []
    for i, cell in enumerate(cells):
        if i:
            time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_trail_ways(cell)
        except Exception as exc:
            logger.warning("Trail cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            wid = el.get("id")
            if wid in seen:
                continue
            seen.add(wid)
            out.append(el)
        if len(cells) > 1:
            logger.info("Trail cell %d/%d: +%d ways (%d total)", i + 1, len(cells), len(els), len(out))
    return out
