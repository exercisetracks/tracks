# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build stream/waterway vector tiles from OpenStreetMap for a region.

The Protomaps basemap only carries coarse water (lakes, big rivers, canals) — no
small streams and no perennial/intermittent distinction. This adds the USGS-style
waterway network (rivers, streams, canals, ditches; perennial vs intermittent)
from Overpass, in the same pure-Python way as the trail builder, into a `water`
MVT layer that the map styles like a quad sheet.
"""

from __future__ import annotations

import logging
import time

from shapely.geometry import LineString

from app.services import osm_source
from app.services.trail_builder import (
    POLITE_DELAY_S, _approx_length_m, _overpass, _split_bbox,
)

logger = logging.getLogger(__name__)

WATERWAY_KINDS = ("river", "stream", "tidal_channel", "canal", "drain", "ditch")
_SEASONAL_YES = {"yes", "spring", "summer", "wet_season", "dry_season", "rainy_season"}


def fetch_waterways(bbox: tuple[float, float, float, float], timeout: int = 180) -> list[dict]:
    w, s, e, n = bbox
    ww = "|".join(WATERWAY_KINDS)
    q = (f'[out:json][timeout:160];'
         f'(way["waterway"~"^({ww})$"]({s},{w},{n},{e}););out geom tags;')
    return _overpass(q, timeout)


def fetch_waterways_chunked(bbox) -> list[dict]:
    reg = osm_source.region_elements(bbox)
    if reg is not None:
        return osm_source.select(reg["ways"], "waterway", set(WATERWAY_KINDS))
    cells = _split_bbox(bbox)
    seen, out = set(), []
    for i, cell in enumerate(cells):
        if i:
            time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_waterways(cell)
        except Exception as exc:
            logger.warning("Water cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            wid = el.get("id")
            if wid in seen:
                continue
            seen.add(wid)
            out.append(el)
        if len(cells) > 1:
            logger.info("Water cell %d/%d: +%d ways (%d total)", i + 1, len(cells), len(els), len(out))
    return out


def classify_water(tags: dict, length_m: float) -> tuple[dict, int]:
    """(mvt_properties, min_zoom) for a waterway, length-graded like trails."""
    ww = tags.get("waterway")
    intermittent = 1 if (tags.get("intermittent") == "yes"
                         or tags.get("seasonal") in _SEASONAL_YES) else 0
    if ww == "river":
        mz = 9 if length_m >= 3000 else 10
    elif ww == "canal":
        mz = 10 if length_m >= 1500 else 11
    elif ww == "stream":
        mz = 11 if length_m >= 700 else 12
    elif ww == "tidal_channel":
        mz = 11
    else:  # ditch, drain
        mz = 13
    return {
        "kind": ww,
        "intermittent": intermittent,
        "name": tags.get("name", ""),
        "min_zoom": mz,
    }, mz


def classify_feature(tags: dict, geom) -> tuple[dict, int] | None:
    """Streaming-path classify for ONE LineString waterway → (props, min_zoom)."""
    if tags.get("waterway") not in WATERWAY_KINDS:
        return None
    return classify_water(tags, _approx_length_m(geom))


def build_region_water_features(
    bbox: tuple[float, float, float, float],
) -> list[tuple[LineString, dict, int]]:
    """Region waterway features (the `water` layer) as `(geom, props, min_zoom)`.

    Tiling is done once, downstream, by tippecanoe (see tippecanoe_writer).
    """
    feats: list[tuple[LineString, dict, int]] = []
    for el in fetch_waterways_chunked(bbox):
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
        props, mz = classify_water(el.get("tags") or {}, _approx_length_m(ls))
        feats.append((ls, props, mz))

    if not feats:
        logger.warning("No waterway features for bbox %s", bbox)
    return feats
