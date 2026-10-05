# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build public-land / ownership polygons from OpenStreetMap (download-time).

The Protomaps basemap carries land *cover* (wood/forest/park) but no ownership or
managing agency, so it can't distinguish National Forest from BLM from a National
Park from private land. OSM does: `boundary=protected_area` / `national_park` /
`aboriginal_lands`, `leisure=nature_reserve`, `landuse=military`, with `name`,
`operator`, `ownership`, `protect_class`. This pulls those polygons (one bulk
Overpass fetch as part of a region download — never at runtime), classifies each
to a land type + ownership category, and writes them to a `landuse` MVT layer
(master_landuse) that the style renders as a tinted overlay and the click-info
panel queries for the land type at a point.

Mirrors area_builder; reuses its polygon assembly + the shared polygon tile writer.
"""

from __future__ import annotations

import logging
import math
import re
import time

from shapely.geometry import MultiPolygon, Point, Polygon
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

from app.services import osm_source
from app.services.area_builder import _relation_geometry, _way_polygon
from app.services.trail_builder import (
    POLITE_DELAY_S, _overpass, _split_bbox,
)

logger = logging.getLogger(__name__)

# Overpass tag selectors (top-level key → value regex).
_SELECTORS = [
    ("boundary", "protected_area|national_park|aboriginal_lands"),
    ("leisure", "nature_reserve"),
    ("landuse", "military"),
]

# Area-defining tags that mark an element as an actual public-land AREA (mirrors
# the Overpass `_SELECTORS` query). REQUIRED before classify_landuse runs on the
# local-extract path: that path sees every way/relation in the PBF, including the
# forest/wilderness ROADS & TRAILS that share the extract (a Forest-Service road
# carries operator="…Forest Service", a "Hidden Wilderness Road" has "wilderness"
# in its name). Without this gate those linear features pass classify_landuse and
# pollute the public-land layer with dozens of road-name labels.
_BOUNDARY_RE = re.compile(r"^(protected_area|national_park|aboriginal_lands)$")


def _is_landuse_area(tags: dict) -> bool:
    """True only for elements carrying a public-land AREA tag (not roads/trails)."""
    if _BOUNDARY_RE.match(tags.get("boundary") or ""):
        return True
    if (tags.get("leisure") or "") == "nature_reserve":
        return True
    if (tags.get("landuse") or "") == "military":
        return True
    return False


def classify_landuse(tags: dict) -> tuple[str, str, str, int] | None:
    """(cat, land_type, ownership, min_zoom_floor) for an area's tags, or None.

    `cat` drives the overlay tint; `land_type` + `ownership` are shown in the
    click panel and labels. `ownership` ∈ public | private | restricted | tribal.
    """
    name = tags.get("name") or ""
    nl = name.lower()
    b = tags.get("boundary") or ""
    lei = tags.get("leisure") or ""
    lu = tags.get("landuse") or ""
    op = (tags.get("operator") or "").lower()
    own = (tags.get("ownership") or "").lower()
    title = (tags.get("protection_title") or "").lower()

    # Monument name checked BEFORE the generic boundary=national_park match below —
    # OSM tags nearly all NPS units (parks AND monuments alike) with
    # boundary=national_park, so a tag-only check would swallow monuments into
    # "National Park" and its z6 floor; the name is the true signal here.
    if "national monument" in nl and "national monument of" not in nl:
        return ("park", "National Monument", "public", 7)
    if b == "national_park" or "national park" in nl:
        return ("park", "National Park", "public", 6)
    # Name/title checked BEFORE the generic "forest service" operator match below —
    # wildernesses, BLM parcels, etc. are routinely *operated* by the Forest
    # Service too, so an operator-substring check alone would swallow them into
    # "National Forest" (e.g. Glacier Peak Wilderness: operator "United States
    # Forest Service", protection_title "Wilderness Area" — name/title is the
    # true signal here, operator is not).
    if "wilderness" in nl or "wilderness" in title:
        return ("wilderness", "Wilderness Area", "public", 7)
    if ("bureau of land management" in op or "blm" in op
            or "national conservation area" in nl or "wilderness study area" in nl):
        return ("blm", "BLM public land", "public", 7)
    if "national forest" in nl or "national grassland" in nl or "forest service" in op:
        return ("forest", "National Forest", "public", 7)
    if b == "aboriginal_lands" or "indian reservation" in nl:
        return ("tribal", "Tribal / reservation land", "tribal", 7)
    if lu == "military" or b == "military":
        return ("military", "Military land", "restricted", 7)
    if "state park" in nl or "state forest" in nl or "state recreation" in nl \
            or "state wildlife" in nl or own == "state":
        return ("state", "State public land", "public", 8)
    if lei == "nature_reserve":
        if own in ("private", "ngo"):
            return ("reserve", "Private nature reserve", "private", 9)
        return ("reserve", "Nature Reserve", "public", 9)
    if b == "protected_area":
        if own == "national":
            return ("federal", "Federal public land", "public", 7)
        if own in ("private", "ngo"):
            return ("reserve", "Private protected area", "private", 9)
        return ("federal", "Protected area", "public", 8)
    return None


def fetch_landuse(bbox: tuple[float, float, float, float], timeout: int = 180) -> list[dict]:
    w, s, e, n = bbox
    sel = "".join(
        f'way["{k}"~"^({v})$"]({s},{w},{n},{e});'
        f'relation["{k}"~"^({v})$"]({s},{w},{n},{e});'
        for k, v in _SELECTORS
    )
    q = f"[out:json][timeout:160];({sel});out geom;"
    return _overpass(q, timeout)


def _largest_part(geom):
    """The biggest single Polygon of a (Multi)Polygon — used to anchor one label."""
    if isinstance(geom, MultiPolygon):
        return max(geom.geoms, key=lambda p: p.area, default=None)
    if isinstance(geom, Polygon):
        return geom
    return None


def _oriented(geom):
    """Normalize ring winding (CCW exteriors) so the "left of travel direction"
    convention used by the boundary-label point sampler below consistently
    means "inside the area"."""
    if isinstance(geom, Polygon):
        return orient(geom, sign=1.0)
    if isinstance(geom, MultiPolygon):
        return MultiPolygon([orient(p, sign=1.0) for p in geom.geoms])
    return geom


# Raw OSM boundary vertices are noisy at the scale a rendered line reads at —
# this both looks "jagged" on screen and makes the label-tangent sampling below
# jitter locally. A small topology-preserving simplify smooths both without
# visibly changing the area's shape at normal viewing zooms (~20m tolerance).
_SIMPLIFY_TOLERANCE_DEG = 0.0002

# ── Boundary-label point sampler ────────────────────────────────────────────
# symbol-placement:"line" looks natural but MapLibre recomputes label spacing
# every frame as you zoom/pan, so the text visibly "swims" along the line.
# Instead we precompute a fixed set of (point, bearing) samples along each
# area's ring HERE, at build time. The frontend places plain point symbols at
# these exact lon/lats — a label that IS drawn always sits at the exact same
# spot on the ground — and relies on MapLibre's collision detection (dense
# candidates + text-padding) to thin them naturally: at low zoom the ring
# covers few screen pixels so most candidates collide and are dropped; at high
# zoom the ring spans more pixels so more of them fit. Sampled dense enough
# that collision, not the sampler, is what decides how many show at a given
# zoom (a sparse sampler would just leave gaps no matter how far you zoom in).
_LABEL_SPACING_M = 3600.0
_LABEL_MAX_PER_RING = 300


def _ring_tangent_samples(ring, spacing_m=_LABEL_SPACING_M, max_labels=_LABEL_MAX_PER_RING):
    """Evenly-spaced samples along `ring`: list of (lon, lat, unit_east, unit_north).
    Distances are converted via a flat equirectangular approximation centered on
    the ring's latitude — plenty accurate for spacing/bearing at label scale."""
    length_deg = ring.length
    if length_deg <= 0:
        return []
    lat0 = ring.centroid.y
    m_per_deg_lat = 111320.0
    m_per_deg_lon = 111320.0 * max(math.cos(math.radians(lat0)), 0.15)
    length_m = length_deg * ((m_per_deg_lat + m_per_deg_lon) / 2.0)
    n = max(1, min(max_labels, round(length_m / spacing_m)))
    eps = length_deg / max(n * 50, 200)
    out = []
    for i in range(n):
        d = (i + 0.5) * length_deg / n
        p0 = ring.interpolate(d % length_deg)
        p1 = ring.interpolate((d + eps) % length_deg)
        dx_m = (p1.x - p0.x) * m_per_deg_lon
        dy_m = (p1.y - p0.y) * m_per_deg_lat
        norm = math.hypot(dx_m, dy_m)
        if norm < 1e-9:
            continue
        out.append((p0.x, p0.y, dx_m / norm, dy_m / norm))
    return out


def _bearing_for_text(ux, uy):
    """Tangent unit vector → (MapLibre `text-rotate` degrees clockwise from
    horizontal, flipped 180° whenever that would render the text upside down;
    `flipped` bool). The point itself is baked exactly ON the boundary line —
    perpendicular "off the line" placement is done at render time via a
    constant `text-offset` (ems, so it stays a fixed screen distance from the
    line at every zoom, unlike a baked ground-meters nudge). That offset is
    applied in the label's own local frame BEFORE `text-rotate` turns it to
    match the line, so local -y always lands on the LEFT of the (unflipped)
    travel direction — except flipping the text 180° for readability also
    flips which side "-y" lands on. Callers must correct for that via
    `flipped` (see `_label_side`) or upside-down segments offset backwards."""
    deg = (math.degrees(math.atan2(ux, uy)) - 90.0) % 360.0
    flipped = 90.0 < deg < 270.0
    if flipped:
        deg = (deg + 180.0) % 360.0
    return deg, flipped


def _label_side(flipped: bool, host: bool) -> int:
    """Which of the frontend's two static-offset label layers (`side == 1`:
    text-offset local -y; `side == -1`: local +y) this point belongs on. A
    self label wants the INWARD side, a mirrored `boundary_host` label wants
    OUTWARD — swapped when `flipped` since that already mirrored which local
    direction is inward (see `_bearing_for_text`)."""
    inward = -1 if flipped else 1
    return -inward if host else inward


def _exterior_rings(geom):
    if isinstance(geom, Polygon):
        return [geom.exterior]
    if isinstance(geom, MultiPolygon):
        return [p.exterior for p in geom.geoms]
    return []


def _merged_bounds(parts):
    """(minx, miny, maxx, maxy) covering every part — cheap bbox test used to
    reject non-nested candidate pairs before an expensive Shapely intersection."""
    minx = min(p.bounds[0] for p in parts)
    miny = min(p.bounds[1] for p in parts)
    maxx = max(p.bounds[2] for p in parts)
    maxy = max(p.bounds[3] for p in parts)
    return (minx, miny, maxx, maxy)


def _area_min_zoom(area_deg2: float, floor: int) -> int:
    """Big public lands (a National Forest) read at overview; small reserves later."""
    if area_deg2 >= 4e-3:      # ≳ 50 km²
        mz = 6
    elif area_deg2 >= 4e-4:    # ≳ 5 km²
        mz = 7
    elif area_deg2 >= 8e-5:    # ≳ 1 km²
        mz = 9
    else:
        mz = 11
    return max(mz, floor)


def fetch_landuse_chunked(bbox) -> list[dict]:
    """Public-land ways + relations over a bbox — local extract if available."""
    reg = osm_source.region_elements(bbox)
    if reg is not None:
        # Gate on the area selector FIRST (the Overpass path filters in the query;
        # the local extract does not), so only real public-land polygons reach
        # classify_landuse — not the forest/wilderness roads sharing the extract.
        return [el for el in (reg["ways"] + reg["relations"])
                if _is_landuse_area(el.get("tags") or {})
                and classify_landuse(el.get("tags") or {})]
    cells = _split_bbox(bbox)
    seen: set = set()
    out: list[dict] = []
    for i, cell in enumerate(cells):
        if i:
            time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_landuse(cell)
        except Exception as exc:
            logger.warning("Landuse cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            key = (el.get("type"), el.get("id"))
            if key in seen:
                continue
            seen.add(key)
            out.append(el)
    return out


def collect_feature(tags: dict, geom, groups: dict) -> None:
    """Streaming-path: gate + classify ONE public-land (Multi)Polygon into ``groups``
    (mutated in place: gkey → [floor, [geoms]]). osmium export pre-assembles the
    polygon, so no _way_polygon/_relation_geometry is needed. Dissolve happens once
    at the end via dissolve_groups()."""
    if geom is None or getattr(geom, "is_empty", True):
        return
    if not _is_landuse_area(tags):
        return
    cls = classify_landuse(tags)
    if not cls:
        return
    cat, land_type, ownership, floor = cls
    name = tags.get("name", "")
    gkey = (cat, land_type, ownership, name)
    groups.setdefault(gkey, [floor, []])[1].append(geom)


def dissolve_groups(groups: dict) -> list[tuple]:
    """Dissolve grouped public-land geometries → `(geom, props, min_zoom)` features
    (+ one center-label point per named area, + precomputed boundary-label points
    along each ring — see below). See build_region_landuse_features."""
    entries: list[dict] = []
    for (cat, land_type, ownership, name), (floor, geoms) in groups.items():
        try:
            merged = geoms[0] if len(geoms) == 1 else unary_union(geoms)
        except Exception:
            logger.warning("Landuse union failed for %r (%d parts) — keeping unmerged",
                           name, len(geoms))
            merged = None
        raw_parts = [g for g in ([merged] if merged is not None else geoms)
                     if g is not None and not g.is_empty]
        # Smooth raw OSM vertex noise (both for a cleaner drawn line and for
        # stable label tangents — see _SIMPLIFY_TOLERANCE_DEG above).
        parts = []
        for g in raw_parts:
            try:
                sg = g.simplify(_SIMPLIFY_TOLERANCE_DEG, preserve_topology=True)
            except Exception:
                sg = g
            if sg is not None and not sg.is_empty:
                parts.append(_oriented(sg))
        if not parts:
            continue
        # min_zoom from the TOTAL managed area, so a big (even scattered) area
        # reads at overview while a small reserve comes in late.
        total_area = sum(g.area for g in parts)
        mz = _area_min_zoom(total_area, floor)
        entries.append({"cat": cat, "land_type": land_type, "ownership": ownership,
                         "name": name, "mz": mz, "parts": parts, "area": total_area})

    feats: list[tuple] = []
    for e in entries:
        props = {"cat": e["cat"], "land_type": e["land_type"], "ownership": e["ownership"],
                  "name": e["name"], "min_zoom": e["mz"]}
        # Polygon fill/outline: the dissolved (Multi)Polygon — one clean wash + edge.
        for geom in e["parts"]:
            feats.append((geom, dict(props), e["mz"]))
        # ONE label point per NAMED area, anchored in its largest part — so a
        # multi-part area (scattered BLM / national-forest parcels) gets a single
        # label instead of one per part. Unnamed areas get the wash but no label
        # (the colour carries the meaning; the click panel still reports the type).
        if e["name"]:
            anchor = _largest_part(max(e["parts"], key=lambda g: g.area))
            if anchor is not None and not anchor.is_empty:
                lprops = dict(props)
                lprops["label"] = 1
                feats.append((anchor.representative_point(), lprops, e["mz"]))

    # Boundary-edge labels: precomputed (point, bearing, side) samples along
    # each area's own ring, baked exactly ON the line (see
    # _ring_tangent_samples/_bearing_for_text — perpendicular placement is a
    # constant-ems `text-offset` at render time, not baked into the point).
    # Cache the raw tangent samples per entry so the nested-area pass below
    # can reuse the identical points for the mirrored host label.
    #
    # NOT restricted to named entries: a huge share of borders sit between a
    # named area and an unnamed one of a different category (unnamed BLM/state
    # parcels are common), and only labelling the named side left most shared
    # borders one-sided. Unnamed entries still carry a meaningful `land_type`
    # (e.g. "BLM public land") from classify_landuse, and the frontend's
    # text-field already falls back to it when `name` is absent — so every
    # entry gets edge samples, not just named ones.
    named = entries
    tangents_by_entry: dict[int, list] = {}
    for e in named:
        samples = []
        for geom in e["parts"]:
            for ring in _exterior_rings(geom):
                samples.extend(_ring_tangent_samples(ring))
        tangents_by_entry[id(e)] = samples
        props = {"cat": e["cat"], "land_type": e["land_type"], "ownership": e["ownership"],
                  "min_zoom": e["mz"], "edge_label": 1}
        if e["name"]:
            props["name"] = e["name"]
        for (x, y, ux, uy) in samples:
            deg, flipped = _bearing_for_text(ux, uy)
            eprops = dict(props)
            eprops["bearing"] = deg
            eprops["side"] = _label_side(flipped, host=False)
            feats.append((Point(x, y), eprops, e["mz"]))

    # Nested areas (a Wilderness fully inside its National Forest) don't share
    # a boundary with any OTHER feature by default — the wilderness's ring
    # sits entirely inside the forest's interior, not on the forest's own
    # outer ring, so only the wilderness's name would ever print there. Fix:
    # reuse the CHILD's own tangent samples verbatim (same points, same side
    # of the line) and tag them with the HOST's identity (`boundary_host`) —
    # the frontend's OUTWARD offset layer mirrors them to the other side.
    #
    # Matching is done PER SAMPLE POINT, not as a single whole-area containment
    # test — a child can straddle two hosts (e.g. Glacier Peak Wilderness is
    # split between two National Forests, so no single host's
    # polygon covers even 90% of it) and an all-or-nothing test would silently
    # drop the host label for the whole area. A bbox-overlap pre-check (near-
    # free tuple math) shrinks the candidate list before the per-point Shapely
    # containment test, which is what actually decides each sample's host —
    # different stretches of the same child ring can legitimately pick
    # different hosts. Host polygons are buffered by a few simplify-tolerances
    # of slack so boundary-coincident points (the child and host were
    # simplified INDEPENDENTLY, so a shared edge can miss exact containment by
    # a hair) still register as "inside".
    _BBOX_SLACK = _SIMPLIFY_TOLERANCE_DEG * 5
    for e in named:
        e["bounds"] = _merged_bounds(e["parts"])
    for child in named:
        if child["area"] <= 0:
            continue
        cminx, cminy, cmaxx, cmaxy = child["bounds"]
        candidates = []
        for candidate in named:
            if candidate is child or candidate["area"] <= child["area"]:
                continue
            hminx, hminy, hmaxx, hmaxy = candidate["bounds"]
            if (hmaxx < cminx - _BBOX_SLACK or hminx > cmaxx + _BBOX_SLACK
                    or hmaxy < cminy - _BBOX_SLACK or hminy > cmaxy + _BBOX_SLACK):
                continue  # bboxes don't even overlap
            try:
                buffered = [hg.buffer(_BBOX_SLACK) for hg in candidate["parts"]]
            except Exception:
                continue
            candidates.append((candidate, buffered))
        if not candidates:
            continue
        by_host: dict[int, tuple] = {}
        for (x, y, ux, uy) in tangents_by_entry[id(child)]:
            pt = Point(x, y)
            best, best_area = None, None
            for candidate, buffered in candidates:
                try:
                    inside = any(bg.contains(pt) for bg in buffered)
                except Exception:
                    continue
                if inside and (best_area is None or candidate["area"] < best_area):
                    best, best_area = candidate, candidate["area"]
            if best is None:
                continue
            entry = by_host.setdefault(id(best), (best, []))
            entry[1].append((x, y, ux, uy))
        for host, samples in by_host.values():
            hprops = {"cat": host["cat"], "land_type": host["land_type"],
                      "ownership": host["ownership"],
                      "min_zoom": child["mz"], "edge_label": 1, "boundary_host": 1}
            if host["name"]:
                hprops["name"] = host["name"]
            for (x, y, ux, uy) in samples:
                deg, flipped = _bearing_for_text(ux, uy)
                hp = dict(hprops)
                hp["bearing"] = deg
                hp["side"] = _label_side(flipped, host=True)
                feats.append((Point(x, y), hp, child["mz"]))

    return feats


def build_region_landuse_features(bbox: tuple[float, float, float, float]) -> list[tuple]:
    """Region public-land polygon features (the `landuse` layer) — LEGACY Overpass /
    region_elements path (the single-pass osmium-export builder uses collect_feature +
    dissolve_groups directly).

    Same-area parcels are DISSOLVED: OSM splits a managed area (a National Forest, a
    BLM field office's holdings) into many polygons that would each get their own
    outline + label; grouping by (cat, land_type, ownership, name) and unioning
    yields one feature — one outline, one label — per area.
    """
    seen: set = set()
    groups: dict[tuple, list] = {}
    for el in fetch_landuse_chunked(bbox):
        dkey = (el.get("type"), el.get("id"))
        if dkey in seen:
            continue
        seen.add(dkey)
        tags = el.get("tags") or {}
        etype = el.get("type")
        if etype == "way":
            geom = _way_polygon(el.get("geometry"))
        elif etype == "relation":
            geom = _relation_geometry(el)
        else:
            continue
        collect_feature(tags, geom, groups)

    feats = dissolve_groups(groups)
    if not feats:
        logger.warning("No public-land features for bbox %s", bbox)
    return feats
