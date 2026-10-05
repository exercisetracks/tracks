# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build infrastructure line vector tiles from OpenStreetMap for a region.

USGS quads show a lot of linear culture the basemap lacks the detail for:
railroads (with crossties + sidings/yards), power transmission lines, pipelines,
dams/weirs, and levees. This pulls them from Overpass (same pure-Python pattern
as trail_builder / water_builder) into an `infra` MVT layer with a `kind` prop,
rendered by style/layers/infrastructure.js.
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

_SELECTORS = [
    ("railway", "rail|light_rail|narrow_gauge|tram|subway|preserved|funicular|monorail"),
    ("power", "line|minor_line"),
    ("man_made", "pipeline|dyke|embankment"),
    ("waterway", "dam|weir"),
]


def classify_infra(tags: dict, length_m: float) -> tuple[dict, int] | None:
    """(mvt_properties, min_zoom) for an infrastructure way, or None to skip."""
    rw, pw = tags.get("railway"), tags.get("power")
    mm, ww = tags.get("man_made"), tags.get("waterway")
    service = tags.get("service", "")

    if rw:
        if rw in ("tram", "subway", "light_rail", "monorail", "funicular"):
            kind, mz = "rail_minor", 11
        elif service in ("siding", "spur", "yard", "crossover"):
            kind, mz = "rail_service", 12
        else:
            kind = "rail"
            mz = 9 if length_m >= 4000 else (10 if length_m >= 1200 else 11)
    elif pw == "line":
        kind, mz = "power_line", 9
    elif pw == "minor_line":
        kind, mz = "power_minor", 12
    elif mm == "pipeline":
        kind, mz = "pipeline", 11
    elif ww == "dam":
        kind, mz = "dam", 12
    elif ww == "weir":
        kind, mz = "weir", 13
    elif mm in ("dyke", "embankment"):
        kind, mz = "levee", 12
    else:
        return None

    return {
        "kind": kind,
        "name": tags.get("name", ""),
        "bridge": 1 if tags.get("bridge") in ("yes", "viaduct") else 0,
        "min_zoom": mz,
    }, mz


def fetch_infra(bbox: tuple[float, float, float, float], timeout: int = 180) -> list[dict]:
    w, s, e, n = bbox
    sel = "".join(f'way["{k}"~"^({v})$"]({s},{w},{n},{e});' for k, v in _SELECTORS)
    q = f"[out:json][timeout:160];({sel});out geom tags;"
    return _overpass(q, timeout)


def fetch_infra_chunked(bbox) -> list[dict]:
    reg = osm_source.region_elements(bbox)
    if reg is not None:
        return [w for w in reg["ways"]
                if classify_infra(w.get("tags") or {}, 0.0) is not None]
    cells = _split_bbox(bbox)
    seen, out = set(), []
    for i, cell in enumerate(cells):
        if i:
            time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_infra(cell)
        except Exception as exc:
            logger.warning("Infra cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            wid = el.get("id")
            if wid in seen:
                continue
            seen.add(wid)
            out.append(el)
    return out


def classify_feature(tags: dict, geom) -> tuple[dict, int] | None:
    """Streaming-path classify for ONE LineString infrastructure way."""
    return classify_infra(tags, _approx_length_m(geom))


def build_region_infra_features(
    bbox: tuple[float, float, float, float],
) -> list[tuple[LineString, dict, int]]:
    """Region infrastructure features (the `infra` layer) as `(geom, props, min_zoom)`."""
    feats: list[tuple[LineString, dict, int]] = []
    for el in fetch_infra_chunked(bbox):
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
        cls = classify_infra(el.get("tags") or {}, _approx_length_m(ls))
        if not cls:
            continue
        props, mz = cls
        feats.append((ls, props, mz))

    if not feats:
        logger.warning("No infrastructure features for bbox %s", bbox)
    return feats
