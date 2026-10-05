# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build vegetation / wetland area vector tiles from OpenStreetMap.

USGS quads fill the land with cover symbology: green woodland, marsh/swamp
tufts, orchards and vineyards in rows, sand stipple, etc. The Protomaps basemap
carries some landcover but drops out at low zoom and lacks wetland subtypes and
orchards/vineyards. This pulls the cover polygons from Overpass (same pure-Python
pattern as trail_builder / water_builder) into a `areas` MVT layer the style
renders with `fill-pattern` + solid greens (style/layers/vegetation.js).

`kind` is chosen to match a sprite pattern name where one exists (marsh, swamp,
mangrove, orchard, vineyard, sand, gravel) or a solid fill class (wood, scrub,
grass, glacier).
"""

from __future__ import annotations

import logging
import time

from shapely.geometry import LineString, Polygon
from shapely.ops import polygonize, unary_union

from app.services import osm_source
from app.services.trail_builder import (
    POLITE_DELAY_S, _overpass, _split_bbox,
)

logger = logging.getLogger(__name__)

# Overpass tag selectors (top-level key → value regex).
_SELECTORS = [
    ("natural", "wood|scrub|heath|wetland|glacier|sand|bare_rock|scree|beach|"
                "grassland|mud|reef|shoal|tidalflat"),
    ("landuse", "forest|orchard|vineyard|quarry|meadow"),
]

# area (deg², ~8.1e-5 per km²) → min_zoom: big landscape cover reads at overview,
# small patches only when zoomed in.
def _area_min_zoom(area_deg2: float, floor: int) -> int:
    if area_deg2 >= 4e-4:      # ≳ 5 km²
        mz = 10
    elif area_deg2 >= 8e-5:    # ≳ 1 km²
        mz = 11
    elif area_deg2 >= 8e-6:    # ≳ 0.1 km²
        mz = 12
    else:
        mz = 13
    return max(mz, floor)


def classify_area(tags: dict) -> tuple[str, int] | None:
    """(kind, min_zoom_floor) for an area's OSM tags, or None to skip."""
    nat, lu = tags.get("natural"), tags.get("landuse")
    if nat in ("wood",) or lu == "forest":
        return "wood", 10
    if nat in ("scrub", "heath"):
        return "scrub", 11
    if nat == "grassland" or lu == "meadow":
        return "grass", 11
    if nat == "glacier":
        return "glacier", 9
    if nat == "sand" or nat == "beach":
        return "sand", 11
    if nat in ("bare_rock", "scree") or lu == "quarry":
        return "gravel", 11
    if lu == "orchard":
        return "orchard", 12
    if lu == "vineyard":
        return "vineyard", 12
    # ── Coastal / submerged areas (USGS blue-ground symbology) ──
    if nat == "reef":
        return "reef", 11
    if nat in ("mud", "shoal", "tidalflat"):
        return "tidalflat", 11   # foreshore flat — mud stipple on a blue tint
    if nat == "wetland":
        wt = tags.get("wetland", "")
        if wt == "mangrove":
            return "mangrove", 11
        # Tidal / submerged wetlands read with a blue ground on a USGS quad.
        if wt in ("tidalflat",) or (wt == "saltmarsh" and tags.get("tidal") == "yes"):
            return "tidalflat", 11
        if wt in ("swamp", "bog", "reedbed", "wet_meadow", "fen"):
            return "swamp", 11
        return "marsh", 11   # marsh, saltmarsh, generic
    return None


def fetch_areas(bbox: tuple[float, float, float, float], timeout: int = 180) -> list[dict]:
    w, s, e, n = bbox
    sel = "".join(
        f'way["{k}"~"^({v})$"]({s},{w},{n},{e});'
        f'relation["{k}"~"^({v})$"]({s},{w},{n},{e});'
        for k, v in _SELECTORS
    )
    q = f"[out:json][timeout:160];({sel});out geom;"
    return _overpass(q, timeout)


def _way_polygon(geom: list[dict]) -> Polygon | None:
    """Closed way → Polygon."""
    if not geom or len(geom) < 4:
        return None
    pts = [(p["lon"], p["lat"]) for p in geom]
    if pts[0] != pts[-1]:
        pts.append(pts[0])
    try:
        poly = Polygon(pts)
        return poly if (poly.is_valid and poly.area > 0) else poly.buffer(0)
    except Exception:
        return None


def _relation_geometry(el: dict):
    """Assemble a multipolygon relation → (Multi)Polygon with holes.

    Member ways are stitched into rings with shapely.ops.polygonize (handles ways
    split across several members); inner-role rings are subtracted from outer.
    """
    outer_lines, inner_lines = [], []
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
        (inner_lines if m.get("role") == "inner" else outer_lines).append(ls)
    if not outer_lines:
        return None
    try:
        outer = unary_union(list(polygonize(outer_lines)))
        if outer.is_empty:
            return None
        if inner_lines:
            inner = unary_union(list(polygonize(inner_lines)))
            outer = outer.difference(inner)
        return outer if not outer.is_empty else None
    except Exception:
        return None


def fetch_areas_chunked(bbox) -> list[dict]:
    """Area ways + multipolygon relations over a bbox — local extract if available."""
    reg = osm_source.region_elements(bbox)
    if reg is not None:
        return [el for el in (reg["ways"] + reg["relations"])
                if classify_area(el.get("tags") or {})]
    cells = _split_bbox(bbox)
    seen: set = set()
    out: list[dict] = []
    for i, cell in enumerate(cells):
        if i:
            time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_areas(cell)
        except Exception as exc:
            logger.warning("Area cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            key = (el.get("type"), el.get("id"))
            if key in seen:
                continue
            seen.add(key)
            out.append(el)
    return out


def classify_feature(tags: dict, geom) -> tuple[dict, int] | None:
    """Streaming-path classify for ONE (Multi)Polygon vegetation/cover area.

    osmium export already assembles the polygon (closed ways + multipolygon
    relations), so no _way_polygon/_relation_geometry is needed here."""
    cls = classify_area(tags)
    if not cls:
        return None
    kind, floor = cls
    try:
        area = geom.area
    except Exception:
        return None
    mz = _area_min_zoom(area, floor)
    return {"kind": kind, "name": tags.get("name", ""), "min_zoom": mz}, mz


def build_region_areas_features(bbox: tuple[float, float, float, float]) -> list[tuple]:
    """Region vegetation/wetland polygon features (the `areas` layer)."""
    seen: set = set()
    feats: list[tuple] = []
    for el in fetch_areas_chunked(bbox):
        etype = el.get("type")
        key = (etype, el.get("id"))
        if key in seen:
            continue
        seen.add(key)
        tags = el.get("tags") or {}
        cls = classify_area(tags)
        if not cls:
            continue
        kind, floor = cls
        if etype == "way":
            geom = _way_polygon(el.get("geometry"))
        elif etype == "relation":
            geom = _relation_geometry(el)
        else:
            continue
        if geom is None or geom.is_empty:
            continue
        mz = _area_min_zoom(geom.area, floor)
        props = {"kind": kind, "name": tags.get("name", ""), "min_zoom": mz}
        feats.append((geom, props, mz))

    if not feats:
        logger.warning("No area features for bbox %s", bbox)
    return feats
