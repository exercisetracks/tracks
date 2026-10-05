# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-region trail/road feature builder + back-compat hub.

This module builds the rich per-region `trails` overlay features (z9-15) and
re-exports the shared primitives the other OSM builders (water/area/infra/landuse/
poi/regions) import from here. The long-trail GLOBAL overview and its supporting
logic now live in dedicated modules:

  • overpass_client  — Overpass fetch + bbox split + length helper
  • trail_fetch      — trail-way / route-relation fetch
  • trail_classify   — way → MVT props + length-graded min_zoom
  • route_registry   — curated thru-hike list + colour/abbr/slot
  • route_geometry   — chain ways into ordered runs + lengths
  • route_hierarchy  — unify OSM relation trees into one trail + features
  • global_routes    — build master_routes.pmtiles (build_global_routes)
"""

from __future__ import annotations

import logging

from shapely.geometry import LineString

# ── Re-exports (other builders import these names from trail_builder) ──────────
from app.services.overpass_client import (  # noqa: F401
    MAX_CELL_DEG, OVERPASS_MIRRORS, POLITE_DELAY_S, USER_AGENT,
    _approx_length_m, _overpass, _overpass_one, _split_bbox,
)
from app.services.trail_classify import (  # noqa: F401
    ROUTE_NETWORKS, TRAIL_HIGHWAYS, _PAVED, _SERVICE_SKIP,
    classify, classify_feature, trail_use,
)
from app.services.trail_fetch import (  # noqa: F401
    fetch_routes, fetch_trail_ways, fetch_trail_ways_chunked,
)
from app.services.route_registry import _route_abbr, _route_color  # noqa: F401
from app.services.global_routes import build_global_routes, write_route_index  # noqa: F401

logger = logging.getLogger(__name__)


def _route_features(elements, min_length_m: float = 0.0):
    """Per-region route relations → per-member LineString features tagged with the
    route. `min_length_m` drops short loops from the broad sweep (curated pass 0)."""
    feats = []
    for el in elements:
        if el.get("type") != "relation":
            continue
        tags = el.get("tags") or {}
        if tags.get("route") not in ("hiking", "foot"):
            continue
        net = tags.get("network", "")
        mz = ROUTE_NETWORKS.get(net, 11)
        name = tags.get("name", "")
        props = {
            "kind": "route", "use": "foot", "route": 1, "name": name,
            "network": net, "surface": "", "paved": -1, "sac_scale": "",
            "trail_visibility": "", "tracktype": "", "bridge": 0, "oneway": 0,
            "min_zoom": mz, "color": _route_color(name), "abbr": _route_abbr(name),
        }
        members, total_m = [], 0.0
        for m in el.get("members") or []:
            if m.get("type") != "way":
                continue
            g = m.get("geometry")
            if not g or len(g) < 2:
                continue
            try:
                ls = LineString([(p["lon"], p["lat"]) for p in g])
            except Exception:
                continue
            if ls.is_empty:
                continue
            members.append(ls)
            total_m += _approx_length_m(ls)
        if min_length_m and total_m < min_length_m:
            continue
        for ls in members:
            feats.append((ls, props, mz))
    return feats


def build_region_trail_features(bbox):
    """Region trail features (the `trails` overlay layer) as `(geom, props, min_zoom)`.

    Rich trail ways are length-graded down to z9; per-region route relations are
    added too. Tiling is done downstream by tippecanoe (see tippecanoe_writer)."""
    feats: list[tuple[LineString, dict, int]] = []

    for el in fetch_trail_ways_chunked(bbox):
        if el.get("type") != "way":
            continue
        geom = el.get("geometry")
        if not geom or len(geom) < 2:
            continue
        try:
            ls = LineString([(p["lon"], p["lat"]) for p in geom])
        except Exception:
            continue
        if ls.is_empty:
            continue
        tags = el.get("tags") or {}
        if tags.get("footway") in ("sidewalk", "crossing", "access_aisle", "traffic_island"):
            continue
        if tags.get("highway") == "service" and tags.get("service") in _SERVICE_SKIP:
            continue
        props, mz = classify(tags, _approx_length_m(ls))
        feats.append((ls, props, mz))

    try:
        feats.extend(_route_features(fetch_routes(bbox)))
    except Exception as exc:
        logger.warning("Route fetch failed (non-fatal): %s", exc)

    if not feats:
        logger.warning("No trail features for bbox %s", bbox)
    return feats
