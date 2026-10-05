# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""POI HTTP endpoints: text search, map-feature lookup, offline export, reindex.

  GET  /maps/poi/search           a place, a category, a coordinate or a corner
  GET  /maps/poi/features         POIs visible in a bbox/zoom → GeoJSON (icons)
  GET  /maps/poi/offline          every POI in a bbox, compact → the phone
  POST /maps/poi/reindex          kick off a background POI reindex
  POST /maps/poi/streets/reindex  rebuild street corners from an OSM extract

Every path is static (no dynamic ``/{...}`` segments), so registration order is
not load-bearing here.

SECURITY: every SQL value is passed as a *bound* parameter (``:q``, ``:tsq``,
``:eq``, ``:kinds``, bbox floats, …). Search tokens are regex-stripped to
``[a-z0-9]`` in search_query and street_tokens, and the only text built into a
statement by interpolation is a fixed fragment — the distance expression and
the ORDER BY clause, both module constants chosen from a closed set, never user
input. Do not introduce string interpolation of user data into SQL.
"""

from __future__ import annotations

import logging
import math

from fastapi import APIRouter, HTTPException, Query
from sqlalchemy import bindparam, text

from app.database import SessionLocal

from .categories import match_category
from .labels import ICON_ZOOM_LEAD, _SUPPRESS_CATEGORY, _kind_label
from .query_shapes import (
    parse_coordinates, split_intersection, split_near, strip_self_reference,
)
from .search_query import _JUNK_KINDS, _build_tsquery, _expand_plain, _search_tokens
from .streets import find_intersections

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/maps/poi", tags=["maps"])


def _parse_bbox(bbox: str | None) -> tuple[float, float, float, float] | None:
    """"w,s,e,n" → floats, or None if it is missing or malformed."""
    if not bbox:
        return None
    try:
        parts = [float(x) for x in bbox.split(",")]
    except (ValueError, TypeError):
        return None
    if len(parts) != 4:
        return None
    return parts[0], parts[1], parts[2], parts[3]


def _anchor_from_bbox(bbox: tuple[float, float, float, float] | None):
    """Where the user is looking: the viewport centre, and how big it is.

    The span matters as much as the centre. It is the scale that tells
    proximity what "near" means here — a continent-wide view should barely
    prefer the middle, a street-level one should prefer it strongly — so the
    same decay works at every zoom without a table of thresholds.
    """
    if bbox is None:
        return None
    w, s, e, n = bbox
    span = max(abs(e - w), abs(n - s), 0.01)
    return ((s + n) / 2.0, (w + e) / 2.0, span)


def _proximity_params(anchor) -> dict:
    """Bound parameters for the distance terms. Neutral when there is no anchor."""
    if anchor is None:
        # A scale of 0 switches the SQL's proximity factor off entirely.
        return {"clat": 0.0, "clng": 0.0, "coslat2": 1.0, "prox_scale": 0.0}
    clat, clng, span = anchor
    return {
        "clat": clat,
        "clng": clng,
        # A degree of longitude shrinks with latitude; without this, "nearest"
        # is wrong by a factor of two at 60°N.
        "coslat2": max(0.05, math.cos(math.radians(clat)) ** 2),
        # Squared, because the distance term is squared degrees — kept that way
        # to avoid a sqrt per row for a number only used for ordering.
        "prox_scale": (span * 0.75) ** 2,
    }


# Squared distance from the anchor, in degrees, longitude-corrected.
_D2 = ("((lat - :clat) * (lat - :clat) + "
       "(lng - :clng) * (lng - :clng) * :coslat2)")

# Continuous distance decay, replacing a three-step in-viewport/near/far
# multiplier. The steps were visible as a cliff: two equally good matches either
# side of the viewport edge ranked 2.0 against 1.2, so panning a few pixels
# reordered the list. This runs from about 2.0 on top of the anchor down to 0.6
# far away, smoothly, and collapses to 1.0 when there is no anchor at all.
_PROXIMITY = (
    f"CASE WHEN :prox_scale = 0 THEN 1.0 "
    f"ELSE 0.6 + 1.4 / (1.0 + {_D2} / :prox_scale) END"
)


@router.get("/search")
def search_poi(
    q: str = Query(..., min_length=1, max_length=200),
    limit: int = Query(10, ge=1, le=50),
    bbox: str | None = Query(None, description="Viewport bounds: w,s,e,n — biases results toward visible area"),
):
    """Search for a place, a kind of place, a coordinate or a street corner.

    Four shapes of question reach this one box, and answering all of them as
    "find a place whose name looks like this" is what makes a search feel dumb:

      39.75,-150.22     a position → a pin, no lookup at all
      Maple and Cedar   a street corner → where those two actually cross
      coffee            a category → cafes, nearest first
      coffee near Fairview  a category somewhere else → cafes near that place
      Granite Peak      a name → the existing full-text/fuzzy search

    They are tried in that order, cheapest and most specific first, and each
    falls through to the next when it does not apply. Anything unrecognised is
    a name search, which is what this endpoint always did.
    """
    db = SessionLocal()
    try:
        raw = q.strip()
        box = _parse_bbox(bbox)
        anchor = _anchor_from_bbox(box)

        # 1. A pasted coordinate is not a lookup; it is already an answer.
        position = parse_coordinates(raw)
        if position:
            return _collection([_pin_feature(*position)])

        # 2. A street corner, if two named streets really do meet there.
        corner = split_intersection(raw)
        if corner:
            found = _intersection_search(db, corner[0], corner[1], anchor, limit)
            if found:
                return _collection(found)

        # 3. "<something> near <somewhere>" — resolve the somewhere, then let
        #    the rest of the query run against that place instead of the map.
        subject = strip_self_reference(raw)
        near = split_near(raw)
        if near:
            head, tail = near
            moved = _resolve_anchor(db, tail)
            if moved is not None:
                anchor = moved
            # The subject narrows either way. If the place could not be
            # resolved — a typo, or somewhere this gazetteer has never heard of
            # — "coffee near Zzyzx" is still a request for coffee, and
            # answering it around the map beats answering it with nothing.
            subject = head

        # 4. A category: what the thing *is*, rather than what it is called.
        kinds = match_category(subject)
        if kinds:
            return _collection(_category_search(db, kinds, anchor, limit))

        # 5. A name.
        return _collection(_name_search(db, subject or raw, anchor, limit))

    except Exception as exc:
        logger.error("POI search failed: %s", exc)
        raise HTTPException(500, f"Search failed: {exc}")
    finally:
        db.close()


def _name_search(db, query_param: str, anchor, limit: int) -> list[dict]:
    """The original search: full-text, prefix and trigram, scored and ranked."""
    # Fallback tsquery matches nothing, so a symbols-only query just falls back
    # to the ILIKE/trigram clauses without a tsquery syntax error.
    tsq = _build_tsquery(query_param) or "zzqnomatchzz:*"
    eq = _expand_plain(query_param) or query_param

    params: dict = {"q": query_param, "limit": limit, "tsq": tsq, "eq": eq}
    params.update(_proximity_params(anchor))

    sql = f"""
        WITH scored AS (
        SELECT name, kind, kind_detail, lat, lng, source, min_zoom, ele_ft,
            GREATEST(
                CASE WHEN LOWER(name) = LOWER(:q) THEN 10.0 ELSE 0 END,
                CASE WHEN name ILIKE :q || '%' THEN 8.0 ELSE 0 END,
                -- token-AND prefix match (word-order-independent, abbreviation-aware)
                CASE WHEN to_tsvector('english', name) @@ to_tsquery('english', :tsq)
                    THEN 6.0 + COALESCE(ts_rank(to_tsvector('english', name),
                        to_tsquery('english', :tsq)), 0)
                    ELSE 0 END,
                CASE WHEN similarity(name, :eq) > :fuzz
                    THEN similarity(name, :eq) * 4.0 ELSE 0 END,
                CASE WHEN word_similarity(:eq, name) > :wfuzz
                    THEN word_similarity(:eq, name) * 3.0 ELSE 0 END
            ) AS match,
            -- Importance, kept apart from match quality — see below.
            CASE
                WHEN min_zoom IS NULL THEN 1.0
                WHEN min_zoom <= 2  THEN 4.0
                WHEN min_zoom <= 4  THEN 3.0
                WHEN min_zoom <= 7  THEN 2.0
                WHEN min_zoom <= 10 THEN 1.3
                ELSE 0.8
              END AS importance
        FROM poi_search
        WHERE
            (kind_detail IS NULL OR kind_detail NOT IN :junk)
            AND (
                name ILIKE :q || '%'
                OR to_tsvector('english', name) @@ to_tsquery('english', :tsq)
                -- The trigram *operators*, not the similarity()/word_similarity()
                -- function calls the scoring below still uses. Postgres can only
                -- use idx_poi_search_name_trgm's GIN index for the operator form —
                -- see the pg_trgm.similarity_threshold SET below, which is what
                -- makes `%`/`<%` mean the same threshold the function calls used
                -- to check explicitly. Written as a bare function-call comparison,
                -- this clause forced a sequential scan of the whole table on every
                -- search that reached it (queries with no ILIKE/tsvector hit,
                -- i.e. most of them) — 1.15M rows and 2-30s per query, found while
                -- testing this offline-search workstream on real data.
                OR name % :eq
                OR :eq <% name
            )
        )
        SELECT name, kind, kind_detail, lat, lng, source, min_zoom, ele_ft,
            match
            -- Importance scales a *strong* match and barely touches a weak
            -- one. Applied flat, as it used to be, a big city that matched
            -- one word by trigram outranked an exact hit on a small
            -- feature: "crescent lake" returned Salt Lake City, and "portland
            -- airport" put the city above the airport. Being famous is a
            -- tie-breaker between things the user might have meant, not a
            -- reason to answer a different question.
            * CASE WHEN match >= 6.0 THEN importance
                   WHEN match >= 4.0 THEN 1.0 + (importance - 1.0) * 0.3
                   ELSE 1.0 END
            * ({_PROXIMITY})
            AS score
        FROM scored
        -- A fuzzy-only match on a multi-word query is nearly always noise:
        -- "mount elbert" trigram-matching "Mount Isa" shares one common
        -- word and nothing else. Below this the answer is "no results",
        -- which is a better answer than a confident wrong one.
        WHERE match >= :floor
        ORDER BY score DESC
        LIMIT :limit
    """

    # Multi-word queries are specific, so a weak partial match on one of
    # their words means the user's place is simply not here. A single word
    # keeps the loose floor: "hollowbrook" half-typed should still find it.
    params["floor"] = 4.0 if len(_search_tokens(query_param)) > 1 else 1.0
    params["fuzz"] = 0.2
    params["wfuzz"] = 0.3
    params["junk"] = _JUNK_KINDS

    _set_trigram_thresholds(db, params["fuzz"], params["wfuzz"])

    # expanding=True so the junk tuple becomes a real IN list rather than
    # one opaque parameter.
    statement = text(sql).bindparams(bindparam("junk", expanding=True))
    rows = db.execute(statement, params).fetchall()
    return _features_from(rows, limit)


def _set_trigram_thresholds(db, fuzz: float, wfuzz: float) -> None:
    """Scoped to this transaction (is_local=true, the set_config() analogue of
    SET LOCAL) so it can never leak onto a pooled connection some other request
    picks up next — see the WHERE clause note on why the `%`/`<%` operators
    need these to match :fuzz/:wfuzz exactly."""
    db.execute(
        text("SELECT set_config('pg_trgm.similarity_threshold', :fuzz, true), "
             "set_config('pg_trgm.word_similarity_threshold', :wfuzz, true)"),
        {"fuzz": str(fuzz), "wfuzz": str(wfuzz)},
    )


# At most this many results sharing one name. A state can have dozens of Mill
# Creeks and Spring Creeks; a list of ten of them answers nothing, and the one
# the user meant is no likelier to be tenth than third.
_MAX_PER_NAME = 3


def _features_from(rows, limit: int) -> list[dict]:
    """Rows → Carmen GeoJSON, deduplicated and kept varied."""
    features: list[dict] = []
    seen: set[tuple] = set()
    per_name: dict[str, int] = {}

    for row in rows:
        m = row._mapping
        name = m["name"] or ""
        kind = m["kind"]
        kind_detail = m["kind_detail"]
        lat, lng = m["lat"], m["lng"]

        dedup_key = (name.lower(), kind or "", round(lat, 3), round(lng, 3))
        if dedup_key in seen:
            continue
        seen.add(dedup_key)

        key = name.lower()
        if key:
            if per_name.get(key, 0) >= _MAX_PER_NAME:
                continue
            per_name[key] = per_name.get(key, 0) + 1

        features.append(_feature(name, kind, kind_detail, lat, lng))
        if len(features) >= limit:
            break
    return features


def _feature(name: str, kind, kind_detail, lat: float, lng: float) -> dict:
    """One Carmen GeoJSON feature, labelled the way the geocoder expects."""
    category = _kind_label(kind, kind_detail)
    if kind_detail and kind_detail != kind:
        display_category = kind_detail.replace("_", " ").title()
    else:
        display_category = category

    # Suppress generic labels that add no information
    if display_category in _SUPPRESS_CATEGORY:
        display_category = ""

    # An unnamed feature is still worth returning when it was asked for by
    # category — "the nearest toilets" is a useful answer even though OSM never
    # gave that one a name. Falling back to the category label keeps it from
    # rendering as an empty row.
    shown = name or display_category or (kind or "").replace("_", " ").title()
    place_name = f"{shown}{f', {display_category}' if display_category and name else ''}"
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [lng, lat]},
        "id": f"poi.{shown}.{round(lat, 4)},{round(lng, 4)}",
        "place_name": place_name,
        "place_type": [kind or "poi"],
        "text": shown,
        "center": [lng, lat],
        "properties": {
            "name": shown,
            "place_name": place_name,
            "place_type": [kind or "poi"],
            "category": category,
            "kind": kind,
            "center": [lng, lat],
        },
    }


def _pin_feature(lat: float, lng: float) -> dict:
    """A pasted coordinate, handed straight back as a place."""
    shown = f"{lat:.5f}, {lng:.5f}"
    return {
        "type": "Feature",
        "geometry": {"type": "Point", "coordinates": [lng, lat]},
        "id": f"coord.{lat:.5f},{lng:.5f}",
        "place_name": shown,
        "place_type": ["coordinate"],
        "text": shown,
        "center": [lng, lat],
        "properties": {
            "name": shown,
            "place_name": shown,
            "place_type": ["coordinate"],
            "category": "Coordinate",
            "kind": "coordinate",
            "center": [lng, lat],
        },
    }


def _collection(features: list[dict]) -> dict:
    return {"type": "FeatureCollection", "features": features}


def _category_search(db, kinds: tuple[str, ...], anchor, limit: int) -> list[dict]:
    """Everything of a given kind, nearest first.

    Ordered by distance alone when there is somewhere to measure from, because
    that is what the question means: "coffee" is a request for the closest one,
    not the most notable one. Without an anchor there is no nearest, so it falls
    back to importance and at least answers with the well-known ones.

    Matching is case-folded against both `kind` and `kind_detail` because the
    two importers disagree on spelling — GNIS "Summit" and OSM "peak" are the
    same question.
    """
    params = {"kinds": tuple(k.lower() for k in kinds), "limit": limit * 4}
    params.update(_proximity_params(anchor))

    order = f"{_D2} ASC" if anchor is not None else "COALESCE(min_zoom, 99) ASC"
    sql = f"""
        SELECT name, kind, kind_detail, lat, lng, source, min_zoom, ele_ft
        FROM poi_search
        WHERE (LOWER(kind) IN :kinds OR LOWER(kind_detail) IN :kinds)
        ORDER BY {order}
        LIMIT :limit
    """
    statement = text(sql).bindparams(bindparam("kinds", expanding=True))
    rows = db.execute(statement, params).fetchall()
    return _features_from(rows, limit)


def _intersection_search(db, first: str, second: str, anchor, limit: int) -> list[dict]:
    """"Maple and Cedar" → the corners where those two streets actually meet.

    Returns an empty list when they never do, which is the common case for the
    many queries that merely *look* like a corner — "bed and breakfast" reaches
    here and costs one indexed array lookup before falling through to the
    ordinary name search.
    """
    rows = find_intersections(db, first, second, anchor, limit)
    features = []
    for row in rows:
        m = row._mapping
        names = list(m["names"])
        # "Maple Street & North Broadway" — the corner named the way a person
        # would say it, rather than as whichever street happened to be first.
        label = " & ".join(names[:3])
        lat, lng = m["lat"], m["lng"]
        features.append({
            "type": "Feature",
            "geometry": {"type": "Point", "coordinates": [lng, lat]},
            "id": f"junction.{m['id']}",
            "place_name": label,
            "place_type": ["intersection"],
            "text": label,
            "center": [lng, lat],
            "properties": {
                "name": label,
                "place_name": label,
                "place_type": ["intersection"],
                "category": "Intersection",
                "kind": "intersection",
                "streets": names,
                "center": [lng, lat],
            },
        })
    return features


def _resolve_anchor(db, place: str):
    """Where "near <place>" points, as an anchor, or None if it names nothing.

    Deliberately reuses the ordinary name search rather than a cheaper lookup:
    "near mt hood" should resolve the same way typing "mt hood" would, typos
    and abbreviations included. The span is a fixed 0.2° — about 20km — since a
    named place gives a point, not a viewport.
    """
    found = _name_search(db, place, None, limit=1)
    if not found:
        return None
    lng, lat = found[0]["center"]
    return (lat, lng, 0.2)


@router.get("/features")
def poi_features(
    bbox: str = Query(..., description="Viewport bounds: w,s,e,n"),
    zoom: int = Query(12, ge=0, le=20),
):
    """GeoJSON FeatureCollection of POIs visible at the given zoom and bbox.

    Used by MapLibre to render USGS-style icons on the map canvas. Feature
    count is capped by zoom level to avoid overwhelming the renderer.
    """
    try:
        parts = [float(x) for x in bbox.split(",")]
        if len(parts) != 4:
            raise ValueError
        w, s, e, n = parts
    except (ValueError, TypeError):
        raise HTTPException(400, "bbox must be four comma-separated floats: w,s,e,n")

    # Scale result cap with zoom — fewer at low zoom (overview), more at high zoom (detail)
    if zoom <= 5:
        limit = 80
    elif zoom <= 8:
        limit = 250
    elif zoom <= 11:
        limit = 450
    else:
        limit = 900

    db = SessionLocal()
    try:
        # Exclude place-label / administrative kinds — these are rendered by the
        # basemap's own label layers (or are boundary records), not POI icons.
        # Filtering here frees the result budget for genuine map features.
        zoom_cutoff = zoom + ICON_ZOOM_LEAD
        rows = db.execute(text("""
            SELECT name, kind, kind_detail, lat, lng, ele_ft, source, min_zoom
            FROM poi_search
            -- Containment against the point, not two independent ranges: a
            -- B-tree can only order on one of lat/lng, so `BETWEEN ... AND
            -- BETWEEN ...` scanned an entire latitude band and threw most of
            -- it away. `<@ box` is what idx_poi_search_geo indexes, and it
            -- carries min_zoom in the same tree. Inclusive on every edge, the
            -- same as BETWEEN was.
            WHERE point(lng, lat) <@ box(point(:w, :s), point(:e, :n))
              AND min_zoom <= :zoom_cutoff
              AND (kind IS NULL OR kind NOT IN (
                  'locality', 'neighbourhood', 'macrohood', 'microhood',
                  'administrative', 'country', 'region', 'county', 'borough', 'pseudo'
              ))
            ORDER BY min_zoom ASC
            LIMIT :limit
        """), {"s": s, "n": n, "w": w, "e": e, "zoom_cutoff": zoom_cutoff, "limit": limit}).fetchall()

        # Collapse large groupings of identical POIs (e.g. a line of a dozen
        # drinking_water nodes along an aqueduct, or every site in a campground)
        # into one representative per kind per grid cell. The cell shrinks with
        # zoom, so individual features separate back out as the user zooms in.
        # Named / more-important (lower min_zoom) features win the cell.
        cell = 24.0 / (2 ** max(zoom, 1))
        seen_cells: set = set()
        thinned = []
        for m in sorted((row._mapping for row in rows),
                        key=lambda m: (not (m["name"] or ""), m["min_zoom"] or 99)):
            key = (m["kind"], round(m["lat"] / cell), round(m["lng"] / cell))
            if key in seen_cells:
                continue
            seen_cells.add(key)
            thinned.append(m)

        features = [
            {
                "type": "Feature",
                "geometry": {"type": "Point", "coordinates": [m["lng"], m["lat"]]},
                "properties": {
                    "name": m["name"],
                    "kind": m["kind"],
                    "kind_detail": m["kind_detail"],
                    "ele_ft": m["ele_ft"],
                    "source": m["source"],
                    "min_zoom": m["min_zoom"],
                },
            }
            for m in thinned
        ]

        return {"type": "FeatureCollection", "features": features}

    except Exception as exc:
        logger.error("POI features failed: %s", exc)
        raise HTTPException(500, f"Features failed: {exc}")
    finally:
        db.close()


# A phone with no signal cannot ask `/search` a live question, so it carries a
# copy of the answer for whatever ground it downloaded instead. Same 3° cap as
# `/maps/route/offline/manifest` — regions larger than that stay covered for
# tiles and routing where they already are, and simply have no offline search
# of their own yet, matching that endpoint's own precedent rather than adding
# chunking logic nothing else here has.
_MAX_OFFLINE_BBOX_DEGREES = 3.0


@router.get("/offline")
def offline_poi(bbox: str = Query(..., description="w,s,e,n")):
    """Every POI in `bbox`, compact, for the phone's own offline search index.

    Unlike `/search` this carries no ranking and no viewport bias — there is no
    live database behind an offline query to widen a search against later, so
    the export has to be everything a phone might plausibly want to find, not
    just what today's map viewport would draw. `id` is `poi_search`'s own
    primary key, carried so the phone can de-duplicate a point that two
    overlapping region downloads both happen to cover.
    """
    try:
        parts = [float(x) for x in bbox.split(",")]
        if len(parts) != 4:
            raise ValueError
        w, s, e, n = parts
    except (ValueError, TypeError):
        raise HTTPException(400, "bbox must be four comma-separated floats: w,s,e,n")
    if (e - w) > _MAX_OFFLINE_BBOX_DEGREES or (n - s) > _MAX_OFFLINE_BBOX_DEGREES:
        raise HTTPException(
            400, f"Area too large for offline POI data (max {_MAX_OFFLINE_BBOX_DEGREES}° per side)"
        )

    db = SessionLocal()
    try:
        # Same exclusions as /features: administrative and place-label rows are
        # basemap furniture, not something a person searches an app for. The
        # blank-name filter has no equivalent there — /search never surfaces
        # one anyway, because an empty string cannot ILIKE-match a query — but
        # here it is exported unless excluded explicitly, and a point nothing
        # can ever type their way to is only ever bandwidth.
        rows = db.execute(
            text("""
                SELECT id, name, kind, lat, lng
                FROM poi_search
                WHERE point(lng, lat) <@ box(point(:w, :s), point(:e, :n))
                  AND name IS NOT NULL AND name != ''
                  AND (kind_detail IS NULL OR kind_detail NOT IN :junk)
                  AND (kind IS NULL OR kind NOT IN (
                      'locality', 'neighbourhood', 'macrohood', 'microhood',
                      'administrative', 'country', 'region', 'county', 'borough', 'pseudo'
                  ))
            """).bindparams(bindparam("junk", expanding=True)),
            {"s": s, "n": n, "w": w, "e": e, "junk": _JUNK_KINDS},
        ).fetchall()

        return {
            "points": [
                {"id": m["id"], "name": m["name"], "kind": m["kind"], "lat": m["lat"], "lng": m["lng"]}
                for m in (row._mapping for row in rows)
            ],
        }
    except Exception as exc:
        logger.error("offline POI export failed: %s", exc)
        raise HTTPException(500, f"Export failed: {exc}")
    finally:
        db.close()


@router.post("/reindex")
def reindex_poi():
    """Trigger a full POI reindex in the background (preserves GNIS data)."""
    import threading

    from app.services.poi_indexer import full_reindex

    threading.Thread(target=full_reindex, daemon=True).start()
    return {"status": "reindexing"}


@router.post("/streets/reindex")
def reindex_streets(pbf: str | None = Query(None, description="Path to an OSM .pbf")):
    """Rebuild the street-corner index from an OSM extract, in the background.

    Takes a path because coverage depends entirely on which file it reads: the
    region cache under /map-data/osm is filtered down to trail-relevant roads
    and has no residential or primary streets in it, so corners built from it
    are real but rural. Point this at an unfiltered Geofabrik extract to get a
    city's grid. See app.services.street_junctions for the full reasoning.
    """
    import os
    import threading

    from app.services.street_junctions import build_junctions

    target = pbf or _default_pbf()
    if target is None:
        raise HTTPException(400, "No OSM extract found — pass ?pbf=/path/to/extract.osm.pbf")
    # Confined to the map-data volume: this path is turned into a file read, and
    # an unconstrained one would read anything the backend can reach.
    root = os.path.realpath("/map-data")
    resolved = os.path.realpath(target)
    if not resolved.startswith(root + os.sep) or not resolved.endswith(".pbf"):
        raise HTTPException(400, "Extract must be a .pbf under /map-data")
    if not os.path.exists(resolved):
        raise HTTPException(404, f"No such extract: {target}")

    threading.Thread(target=build_junctions, args=(resolved,), daemon=True).start()
    return {"status": "indexing", "extract": resolved}


def _default_pbf() -> str | None:
    """The largest extract in the region cache — the best coverage on hand."""
    import glob
    import os

    candidates = glob.glob("/map-data/osm/*.osm.pbf")
    if not candidates:
        return None
    return max(candidates, key=os.path.getsize)
