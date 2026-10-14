# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Thematic overlay build: turn the OSM data for an area's owned ground into one
multi-layer overlay part (trails / water / areas / infra / landuse) plus POI
rows. The build queue (services.map_builds) publishes the part.

Two paths produce the same result:
  * streaming (primary) — clip the region once, ``osmium export`` to GeoJSONSeq,
    and stream every feature through the builders' classifiers in a single pass.
  * legacy (fallback)   — chunked pyosmium parse used only when osmium / the
    streaming export is unavailable.

`build_overlay_part` tries streaming first and falls back to legacy. A user
cancellation (``DownloadCancelled``) always propagates out; it is never treated
as a build failure.
"""

import json
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

from app.services import download_cancel, osm_source, tippecanoe_writer

logger = logging.getLogger(__name__)


# Per-region overlay layers: (MVT layer name, feature-builder). Each
# build_region_*_features(bbox) returns [(geom, props, min_zoom), …]; one
# tippecanoe run tiles them all into a single multi-layer overlay.pmtiles.
def _feature_builders():
    from app.services.trail_builder import build_region_trail_features
    from app.services.water_builder import build_region_water_features
    from app.services.area_builder import build_region_areas_features
    from app.services.infra_builder import build_region_infra_features
    from app.services.landuse_builder import build_region_landuse_features
    return [
        ("trails", build_region_trail_features),
        ("water", build_region_water_features),
        ("areas", build_region_areas_features),
        ("infra", build_region_infra_features),
        ("landuse", build_region_landuse_features),
    ]


def _rep_in_bbox(geom, bbox) -> bool:
    """Does the geometry's representative point fall in (w,s,e,n)?

    Used in chunked (continent-scale) builds to assign each feature to exactly one
    cell, so adjacent cells (whose extracts overlap by a buffer) don't double-emit.
    """
    try:
        p = geom.representative_point()
    except Exception:
        return True
    w, s, e, n = bbox
    return w <= p.x <= e and s <= p.y <= n


def _clipper(owned):
    """geom → the part of it on `owned` ground, or None.

    Areas overlap, but each overlay feature belongs to exactly one area: the
    one that owns that ground (see map_store). Without the cut, the overlap of
    two areas would hold two copies of every trail in it, and the master —
    which tile-joins the parts, concatenating features — would draw each twice.
    """
    from shapely.prepared import prep
    if owned is None:
        return lambda g: g
    whole = prep(owned)

    def clip(geom):
        if whole.contains(geom):
            return geom
        if not whole.intersects(geom):
            return None
        try:
            cut = geom.intersection(owned)
        except Exception:
            return None
        if cut.is_empty:
            return None
        # A line cut by a polygon can come back as a GeometryCollection with
        # stray points where it grazes the edge; keep the kind tippecanoe draws.
        if cut.geom_type == "GeometryCollection":
            kind = "Line" if "Line" in geom.geom_type else "Polygon" if "Polygon" in geom.geom_type else "Point"
            parts = [g for g in cut.geoms if kind in g.geom_type]
            if not parts:
                return None
            from shapely.ops import unary_union
            cut = unary_union(parts)
        return cut

    return clip


def _ndjson_feature_line(geom, props: dict, mz: int, max_zoom: int = 15) -> str:
    """One tippecanoe NDJSON Feature line (per-feature minzoom = appearance zoom)."""
    from shapely.geometry import mapping
    feat = {
        "type": "Feature",
        "tippecanoe": {"minzoom": max(0, min(int(mz), max_zoom))},
        "properties": props,
        "geometry": mapping(geom),
    }
    return json.dumps(feat, separators=(",", ":"))


def build_overlay_part(owned, out_path, progress=None, cancel=None) -> bool:
    """Build the overlay (trails, water, areas, infra, landuse) for the `owned`
    ground into `out_path`, and index its POIs.

    `owned` is a shapely geometry in lon/lat; only OSM data inside its bounds is
    read, and every feature is cut to it. `progress(pct, detail)` reports.
    Returns True when the part was built — or there was nothing to build — and
    raises when it could not be: a build that lost its trails must fail, not
    finish as an area with no trails on it (audit F20). The streaming osmium
    path is tried first; the legacy pyosmium path is the fallback.
    """
    from pathlib import Path
    out_path = Path(out_path)
    progress = progress or (lambda pct, detail: None)
    try:
        if _build_thematic_streaming(owned, out_path, progress, cancel):
            return True
        logger.info("Streaming overlay build unavailable — using the legacy path")
    except download_cancel.DownloadCancelled:
        raise
    except Exception:
        logger.exception("Streaming overlay build failed — falling back")
    return _build_thematic_legacy(owned, out_path, progress, cancel)


def _build_thematic_streaming(owned, out_path, progress, cancel=None) -> bool:
    """Single-pass overlay build: clip the region ONCE, `osmium export` it to
    GeoJSONSeq, and stream every feature through the builders' classifiers in one
    pass (no pyosmium dict parse, no chunking, no 6× rescans). Linear features
    (trails/water/infra) and area polygons stream straight to per-layer NDJSON;
    public-land polygons accumulate for one dissolve; POIs accumulate for one DB
    upsert; hiking routes come from a light relation pass. Returns False to signal
    the caller to use the legacy path (osmium unavailable / export failed).
    """
    from shapely.geometry import shape
    from app.services import (trail_builder, water_builder,
                              area_builder, infra_builder, landuse_builder, poi_builder)

    bbox = list(owned.bounds)
    clip = _clipper(owned)
    src_dir = out_path.parent
    src_dir.mkdir(parents=True, exist_ok=True)
    region_pbf = osm_source.prepare_region_pbf(tuple(bbox), cancel=cancel)
    if region_pbf is None:
        return False

    layers = ("trails", "water", "areas", "infra", "landuse")
    ndjson = {n: src_dir / f".{n}.ndjson" for n in layers}
    for p in ndjson.values():
        p.unlink(missing_ok=True)

    linear_classifiers = (
        ("trails", trail_builder.classify_feature),
        ("water", water_builder.classify_feature),
        ("infra", infra_builder.classify_feature),
    )
    landuse_groups: dict = {}
    poi_rows: dict = {}
    geojson = None

    progress(10.0, "Extracting map features…")
    try:
        geojson = osm_source.export_geojson(region_pbf, cancel=cancel)
        if geojson is None:
            return False
        fhs = {n: open(ndjson[n], "a", encoding="utf-8")
               for n in ("trails", "water", "areas", "infra")}
        try:
            n_feat = 0
            for gdict, tags in osm_source.iter_geojson_features(geojson):
                try:
                    geom = shape(gdict)
                except Exception:
                    continue
                if geom.is_empty:
                    continue
                # POIs are indexed for the whole box (an upsert, so the
                # overlap with another area costs nothing); drawn features are
                # cut to the ground this part owns.
                poi_builder.collect_feature(tags, geom, poi_rows)
                n_feat += 1
                geom = clip(geom)
                if geom is None:
                    continue
                gt = geom.geom_type
                if gt in ("LineString", "MultiLineString"):
                    for layer, classify in linear_classifiers:
                        res = classify(tags, geom)
                        if res:
                            props, mz = res
                            fhs[layer].write(_ndjson_feature_line(geom, props, mz) + "\n")
                elif gt in ("Polygon", "MultiPolygon"):
                    res = area_builder.classify_feature(tags, geom)
                    if res:
                        props, mz = res
                        fhs["areas"].write(_ndjson_feature_line(geom, props, mz) + "\n")
                    landuse_builder.collect_feature(tags, geom, landuse_groups)
                if n_feat % 50000 == 0:
                    if cancel is not None:
                        cancel.raise_if_cancelled()
                    progress(min(60.0, 10.0 + n_feat / 5000.0),
                             f"Extracting map features… {n_feat:,}")

            # Hiking/foot routes (relations) — osmium export can't emit their geometry.
            try:
                for rel in osm_source.parse_route_relations(region_pbf):
                    for ls, props, mz in trail_builder._route_features([rel]):
                        ls = clip(ls)
                        if ls is not None:
                            fhs["trails"].write(_ndjson_feature_line(ls, props, mz) + "\n")
            except Exception:
                logger.exception("Route relation pass failed (non-fatal)")
        finally:
            for fh in fhs.values():
                fh.close()

        # Public lands: dissolve grouped parcels → one feature/label per area.
        try:
            landuse_feats = _clip_all(landuse_builder.dissolve_groups(landuse_groups), clip)
            if landuse_feats:
                tippecanoe_writer.features_to_ndjson(landuse_feats, ndjson["landuse"])
        except Exception:
            logger.exception("Landuse dissolve failed (non-fatal)")

        # POIs → DB upsert.
        try:
            poi_builder.flush_rows(tuple(bbox), poi_rows)
        except Exception:
            logger.exception("POI flush failed (non-fatal)")
    finally:
        region_pbf.unlink(missing_ok=True)
        if geojson is not None:
            geojson.unlink(missing_ok=True)

    _tile_part(ndjson, out_path, bbox, progress, cancel)
    return True


def _clip_all(feats, clip):
    out = []
    for geom, props, mz in feats or []:
        g = clip(geom)
        if g is not None:
            out.append((g, props, mz))
    return out


def _tile_part(ndjson: dict, out_path, bbox, progress, cancel) -> None:
    """One tippecanoe over all layers → the part. Failure raises (audit F20)."""
    try:
        tippecanoe_writer.build_overlay(
            ndjson, out_path,
            progress_cb=lambda pct: progress(min(99.0, 85.0 + pct * 0.14), f"Generating tiles… {pct:.0f}%"),
            cancel=cancel,
            # Features are already cut to the owned ground; the box keeps
            # tippecanoe's tile buffers from reaching past it.
            clip_bbox=bbox)
    finally:
        for p in ndjson.values():
            p.unlink(missing_ok=True)


def _build_thematic_legacy(owned, out_path, progress, cancel=None) -> bool:
    """Legacy chunked thematic build (fallback): plan_build_cells returns one cell
    for normal regions and a grid of memory-bounded cells for continent-scale ones.
    Each cell's OSM data is parsed once (pyosmium) and shared by every feature
    builder (+ POIs), then freed before the next cell. Used only when the streaming
    osmium-export path is unavailable.
    """
    bbox = list(owned.bounds)
    clip = _clipper(owned)
    src_dir = out_path.parent
    src_dir.mkdir(parents=True, exist_ok=True)
    builders = _feature_builders()
    cells = osm_source.plan_build_cells(tuple(bbox))
    n_cells = len(cells)

    # One NDJSON per layer, accumulated across cells (cleared up front).
    ndjson = {name: src_dir / f".{name}.ndjson" for name, _ in builders}
    for p in ndjson.values():
        p.unlink(missing_ok=True)

    def _safe_features(name, fn, cell_bbox):
        try:
            return fn(cell_bbox)
        except Exception:
            logger.exception("%s feature build failed (non-fatal)", name)
            return []

    for ci, (ext_bbox, core_bbox) in enumerate(cells):
        if cancel is not None:
            cancel.raise_if_cancelled()
        # One OSM extract+parse for this cell, shared (cached) by every builder + POIs.
        osm_source.region_elements(ext_bbox)
        try:
            results: dict[str, list] = {}
            with ThreadPoolExecutor(max_workers=len(builders) + 1) as ex:
                futs = {ex.submit(_safe_features, name, fn, ext_bbox): name
                        for name, fn in builders}
                poi_fut = ex.submit(_try_build_pois, ext_bbox)
                for fut in as_completed(list(futs) + [poi_fut]):
                    if fut is poi_fut:
                        fut.result()
                    else:
                        results[futs[fut]] = fut.result()
            for name, feats in results.items():
                if core_bbox is not None:   # chunked: keep features owned by this cell
                    feats = [f for f in feats if _rep_in_bbox(f[0], core_bbox)]
                feats = _clip_all(feats, clip)
                if feats:
                    tippecanoe_writer.features_to_ndjson(feats, ndjson[name])
        finally:
            osm_source.clear_cache(ext_bbox)   # free this cell before the next
        if n_cells > 1:
            progress(round((ci + 1) / n_cells * 80, 1), f"block {ci + 1} / {n_cells}")
        else:
            progress(40.0, "Extracting map features…")

    _tile_part(ndjson, out_path, bbox, progress, cancel)
    return True


def _try_build_pois(bbox) -> None:
    try:
        from app.services.poi_builder import build_region_pois
        build_region_pois(tuple(bbox))
    except Exception:
        logger.exception("POI build failed (non-fatal)")
