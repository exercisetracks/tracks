# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Pull USGS-style point features from OpenStreetMap into the POI index.

The basemap `pois` layer and GNIS cover named natural features and common
amenities, but a USGS quad also marks a lot of small culture/utility points the
index misses: wells, tanks, gaging stations, mine shafts, cave entrances, boat
ramps, substations, ranger stations, churches/schools, benchmarks, etc.

This fetches those categories from Overpass (same pure-Python pattern as
trail_builder / water_builder), classifies each to a `kind` that maps to a USGS
sprite, and upserts them into `poi_search` (source='osm_poi'). They are then
served by routes/poi.py `/maps/poi/features` and drawn as icons by
style/layers/labels.js.

Run per downloaded region (wired into regions._download_and_merge), non-fatal.
"""

from __future__ import annotations

import logging
import time

from sqlalchemy import text

from app.database import SessionLocal
from app.models.poi_search import PoiSearch
from app.services import osm_source
from app.services.trail_builder import POLITE_DELAY_S, _overpass, _split_bbox

logger = logging.getLogger(__name__)

SOURCE = "osm_poi"
BATCH_SIZE = 1000

# Overpass tag selectors (regex per top-level key). nwr = node/way/relation;
# `out center` gives ways/relations a representative point.
_SELECTORS = [
    ('man_made', "water_well|water_tower|storage_tank|reservoir_covered|"
                 "monitoring_station|survey_point|pumping_station|mast|tower|"
                 "water_works|windmill|watermill|adit|mineshaft|water_tap"),
    # Water sources are a hiker priority — pull every potable/natural source
    # richly so springs/seeps/taps are easy to find in the field. Peaks/volcanoes
    # carry the summit names + elevations a topo sheet is built around.
    ('natural', "spring|hot_spring|cave_entrance|sinkhole|geyser|peak|volcano"),
    ('historic', "mine|mine_shaft|adit"),
    ('waterway', "waterfall"),
    ('leisure', "slipway|firepit|bird_hide"),
    ('tourism', "camp_site|picnic_site|viewpoint|wilderness_hut|alpine_hut|information"),
    # NB: parking deliberately excluded — parking lots are extremely dense and
    # not a USGS quad feature; they would swamp the map with icons.
    ('amenity', "ranger_station|place_of_worship|school|shelter|drinking_water|"
                "water_point|toilets|fuel|hospital"),
    ('power', "substation|plant"),
    ('aeroway', "aerodrome"),
    ('highway', "trailhead"),
]

# min_zoom (real map zoom) at which each kind should first show. Landmark /
# natural points appear earlier; minor amenities only when zoomed right in.
_MIN_ZOOM = {
    # Water sources surface early — hikers plan around them.
    "spring": 11, "falls": 11, "hot_spring": 11, "geyser": 11,
    "peak": 11,
    "well": 13, "drinking_water": 12,
    "tank": 14, "gaging_station": 13, "pumping_plant": 13,
    "power_plant": 11, "windmill": 13,
    "camp_site": 11, "picnic_site": 13, "viewpoint": 11, "shelter": 13,
    "wilderness_hut": 12, "alpine_hut": 12, "information": 14,
    "hospital": 12, "boat_ramp": 13, "toilets": 14, "trailhead": 11,
    "fuel": 13, "parking": 14,
    # "Culture" (ranger_station/place_of_worship/school/cemetery/tower/
    # substation/aerodrome) and "Mines & relief" (mine/mine_shaft/quarry/
    # cave/benchmark) POIs are secondary map clutter — hold them back to z12
    # (min_zoom 14, minus the 2-zoom icon lead). Airports are the least
    # important of all, so they wait even later than the rest of that group.
    "cave": 14, "mine": 14, "mine_shaft": 14, "quarry": 14, "benchmark": 14,
    "tower": 14, "substation": 14,
    "ranger_station": 14, "place_of_worship": 14, "school": 14, "cemetery": 14,
    "aerodrome": 15,
}
_DEFAULT_MIN_ZOOM = 14


def _classify(tags: dict) -> tuple[str, str] | None:
    """Map OSM tags → (kind, kind_detail). kind must match a USGS sprite name."""
    g = tags.get
    mm, nat, hist = g("man_made"), g("natural"), g("historic")
    lei, tou, ame = g("leisure"), g("tourism"), g("amenity")
    pw, way, aero, hwy = g("power"), g("waterway"), g("aeroway"), g("highway")

    if nat == "spring" or nat == "hot_spring":
        return "spring", nat
    if nat == "geyser":
        return "spring", "geyser"
    if nat in ("peak", "volcano"):
        return "peak", nat
    if hwy == "trailhead":
        return "trailhead", "trailhead"
    if nat == "cave_entrance":
        return "cave", "cave_entrance"
    if nat == "sinkhole":
        return "cave", "sinkhole"
    if way == "waterfall":
        return "falls", "waterfall"
    if hist == "mine":
        return "mine", "mine"
    if hist in ("mine_shaft", "adit") or mm in ("mineshaft", "adit"):
        return "mine_shaft", (mm or hist)
    if mm == "water_well":
        return "well", "water_well"
    if mm in ("storage_tank", "water_tower", "reservoir_covered"):
        return "tank", mm
    if mm == "monitoring_station":
        return "gaging_station", "monitoring_station"
    if mm == "survey_point":
        return "benchmark", "survey_point"
    if mm == "pumping_station":
        return "pumping_plant", "pumping_station"
    if mm in ("tower", "mast"):
        return "tower", mm
    if mm in ("windmill", "watermill"):
        return "tower", mm
    if pw == "substation":
        return "substation", "substation"
    if pw == "plant":
        return "power_plant", "plant"
    if lei == "slipway":
        return "boat_ramp", "slipway"
    if lei == "firepit":
        return "camp_site", "firepit"
    if lei == "bird_hide":
        return "viewpoint", "bird_hide"
    if tou == "camp_site":
        return "camp_site", "camp_site"
    if tou == "picnic_site":
        return "picnic_site", "picnic_site"
    if tou == "viewpoint":
        return "viewpoint", "viewpoint"
    if tou == "wilderness_hut":
        return "wilderness_hut", "wilderness_hut"
    if tou == "alpine_hut":
        return "alpine_hut", "alpine_hut"
    if tou == "information":
        return "information", tags.get("information", "information")
    if ame == "ranger_station":
        return "ranger_station", "ranger_station"
    if ame == "place_of_worship":
        return "place_of_worship", "place_of_worship"
    if ame == "school":
        return "school", "school"
    if ame == "shelter":
        return "shelter", "shelter"
    if ame == "hospital":
        return "hospital", "hospital"
    if mm == "water_tap":
        return "drinking_water", "water_tap"
    if ame == "drinking_water":
        return "drinking_water", "drinking_water"
    if ame == "water_point":
        return "drinking_water", "water_point"
    if ame == "toilets":
        return "toilets", "toilets"
    if ame == "fuel":
        return "fuel", "fuel"
    if ame == "parking":
        return "parking", "parking"
    if aero == "aerodrome":
        return "aerodrome", "aerodrome"
    return None


def _ele_ft(tags: dict) -> float | None:
    raw = tags.get("ele")
    if not raw:
        return None
    try:
        return round(float(str(raw).split()[0].replace(",", "")) * 3.280839895, 1)
    except (ValueError, IndexError):
        return None


def fetch_pois(bbox: tuple[float, float, float, float], timeout: int = 180) -> list[dict]:
    w, s, e, n = bbox
    sel = "".join(
        f'nwr["{k}"~"^({v})$"]({s},{w},{n},{e});' for k, v in _SELECTORS
    )
    q = f"[out:json][timeout:160];({sel});out center tags;"
    return _overpass(q, timeout)


def _element_point(el: dict) -> tuple[float, float] | None:
    if el.get("type") == "node":
        if el.get("lat") is not None:
            return float(el["lat"]), float(el["lon"])
        return None
    c = el.get("center")
    if c and c.get("lat") is not None:
        return float(c["lat"]), float(c["lon"])
    return None


def fetch_pois_chunked(bbox) -> list[dict]:
    """POI nodes/ways/relations over a bbox — local extract if available."""
    reg = osm_source.region_elements(bbox)
    if reg is not None:
        return reg["nodes"] + reg["ways"] + reg["relations"]
    cells = _split_bbox(bbox)
    seen: set = set()
    out: list[dict] = []
    for i, cell in enumerate(cells):
        if i:
            time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_pois(cell)
        except Exception as exc:
            logger.warning("POI cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            key = (el.get("type"), el.get("id"))
            if key in seen:
                continue
            seen.add(key)
            out.append(el)
    return out


def collect_feature(tags: dict, geom, rows: dict) -> None:
    """Streaming-path: classify ONE feature as a POI and add it to ``rows`` (keyed
    by location+kind for in-run dedup). ``geom`` is shapely; non-Point geometries
    use a representative point (matches the legacy Overpass ``out center``)."""
    cls = _classify(tags)
    if not cls:
        return
    if geom is None or getattr(geom, "is_empty", True):
        return
    try:
        if geom.geom_type == "Point":
            lng, lat = float(geom.x), float(geom.y)
        else:
            p = geom.representative_point()
            lng, lat = float(p.x), float(p.y)
    except Exception:
        return
    kind, kind_detail = cls
    rows[(round(lat, 7), round(lng, 7), kind)] = {
        "osm_id": None,
        "name": tags.get("name") or "",
        "kind": kind,
        "kind_detail": kind_detail,
        "lat": lat,
        "lng": lng,
        "ele_ft": _ele_ft(tags),
        "source": SOURCE,
        "min_zoom": _MIN_ZOOM.get(kind, _DEFAULT_MIN_ZOOM),
    }


def flush_rows(bbox: tuple[float, float, float, float], rows: dict) -> int:
    """Replace prior osm_poi rows in ``bbox`` with ``rows`` (bulk insert). Count."""
    if not rows:
        logger.info("No OSM POIs classified for bbox %s", bbox)
        return 0

    w, s, e, n = bbox
    db = SessionLocal()
    try:
        # Replace prior osm_poi rows in this bbox so re-downloads don't duplicate.
        db.execute(text(
            # Containment rather than two ranges, so this keeps an index now
            # that idx_poi_search_lat_lng is gone — see app.main's index list.
            #
            # Note the one way these differ: `box()` normalises its corners,
            # while `BETWEEN` on a reversed range simply matches nothing. For a
            # DELETE that is the dangerous direction, so it is worth saying why
            # it cannot happen — routes/regions/bbox.py rejects any bbox that is
            # not west<east and south<north before a region is ever built.
            "DELETE FROM poi_search WHERE source = :src "
            "AND point(lng, lat) <@ box(point(:w, :s), point(:e, :n))"
        ), {"src": SOURCE, "s": s, "n": n, "w": w, "e": e})
        db.commit()
    except Exception:
        db.rollback()
    finally:
        db.close()

    inserted = 0
    items = list(rows.values())
    for i in range(0, len(items), BATCH_SIZE):
        db = SessionLocal()
        try:
            db.bulk_insert_mappings(PoiSearch, items[i:i + BATCH_SIZE])
            db.commit()
            inserted += len(items[i:i + BATCH_SIZE])
        except Exception as exc:
            db.rollback()
            logger.warning("POI insert batch failed: %s", exc)
        finally:
            db.close()

    logger.info("Indexed %d OSM POIs for bbox %s", inserted, bbox)
    return inserted


def build_region_pois(bbox: tuple[float, float, float, float]) -> int:
    """Fetch USGS point categories in bbox and upsert into poi_search — LEGACY
    Overpass / region_elements path (the single-pass builder uses collect_feature +
    flush_rows directly)."""
    from shapely.geometry import Point
    rows: dict = {}
    for el in fetch_pois_chunked(bbox):
        pt = _element_point(el)   # (lat, lon)
        if not pt:
            continue
        collect_feature(el.get("tags") or {}, Point(pt[1], pt[0]), rows)
    return flush_rows(bbox, rows)
