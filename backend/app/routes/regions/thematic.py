# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Thematic overlay build: turn a downloaded region's OSM data into one
multi-layer ``overlay.pmtiles`` (trails / water / areas / infra / landuse) plus
POI rows, then rebuild the master overlay.

Two paths produce the same result:
  * streaming (primary) — clip the region once, ``osmium export`` to GeoJSONSeq,
    and stream every feature through the builders' classifiers in a single pass.
  * legacy (fallback)   — chunked pyosmium parse used only when osmium / the
    streaming export is unavailable.

`build_thematic` tries streaming first and falls back to legacy. A user
cancellation (``DownloadCancelled``) always propagates out; it is never treated
as a build failure.
"""

import json
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed

from app.services import (
    download_cancel, osm_source, region_merger, region_registry, tippecanoe_writer,
)

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


def build_thematic(region_id: int, bbox: list[float], cancel=None) -> None:
    """Build the region overlay. Primary path is the single-pass osmium-export
    streaming builder (fast, bounded memory); on any failure (osmium missing, export
    error) it falls back to the legacy chunked pyosmium path. A user cancellation
    propagates out (DownloadCancelled is never treated as a build failure)."""
    try:
        if _build_thematic_streaming(region_id, bbox, cancel):
            return
        logger.info("Streaming thematic build unavailable for region %s — using legacy", region_id)
    except download_cancel.DownloadCancelled:
        raise
    except Exception:
        logger.exception("Streaming thematic build failed for region %s — falling back", region_id)
    _build_thematic_legacy(region_id, bbox, cancel)


def _build_thematic_streaming(region_id: int, bbox: list[float], cancel=None) -> bool:
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

    src_dir = region_registry.source_dir(region_id)
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

    region_registry.update_status(region_id, "trails", progress=10.0,
                                  detail="Extracting map features…")
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
                # POIs can be nodes (Point), area-ways (Polygon) or lines — try all.
                poi_builder.collect_feature(tags, geom, poi_rows)
                n_feat += 1
                if n_feat % 50000 == 0:
                    if cancel is not None:
                        cancel.raise_if_cancelled()
                    region_registry.update_status(
                        region_id, "trails",
                        progress=min(60.0, 10.0 + n_feat / 5000.0),
                        detail=f"Extracting map features… {n_feat:,}")

            # Hiking/foot routes (relations) — osmium export can't emit their geometry.
            try:
                for rel in osm_source.parse_route_relations(region_pbf):
                    for ls, props, mz in trail_builder._route_features([rel]):
                        fhs["trails"].write(_ndjson_feature_line(ls, props, mz) + "\n")
            except Exception:
                logger.exception("Route relation pass failed for region %s (non-fatal)", region_id)
        finally:
            for fh in fhs.values():
                fh.close()

        # Public lands: dissolve grouped parcels → one feature/label per area.
        try:
            landuse_feats = landuse_builder.dissolve_groups(landuse_groups)
            if landuse_feats:
                tippecanoe_writer.features_to_ndjson(landuse_feats, ndjson["landuse"])
        except Exception:
            logger.exception("Landuse dissolve failed for region %s (non-fatal)", region_id)

        # POIs → DB upsert.
        try:
            poi_builder.flush_rows(tuple(bbox), poi_rows)
        except Exception:
            logger.exception("POI flush failed for region %s (non-fatal)", region_id)
    finally:
        region_pbf.unlink(missing_ok=True)
        if geojson is not None:
            geojson.unlink(missing_ok=True)

    # One tippecanoe over all layers → the region's overlay.pmtiles.
    def _tip_progress(pct):
        region_registry.update_status(
            region_id, "trails", progress=min(99.0, 85.0 + pct * 0.14),
            detail=f"Generating tiles… {pct:.0f}%")
    try:
        tippecanoe_writer.build_overlay(
            ndjson, src_dir / "overlay.pmtiles", progress_cb=_tip_progress, cancel=cancel,
            # Cut to this region's own box: Overpass hands back whole ways and
            # relations that merely cross it, so an unclipped archive reaches
            # well past the area the user paid for — and then collides with the
            # neighbouring region's copy of the same features. See build_overlay.
            clip_bbox=bbox)
    except download_cancel.DownloadCancelled:
        raise
    except Exception:
        logger.exception("Overlay tile build failed (streaming) for region %s", region_id)
    finally:
        for p in ndjson.values():
            p.unlink(missing_ok=True)

    try:
        region_merger.rebuild_master_overlay(write_trigger=False)
    except Exception:
        logger.exception("Master overlay rebuild failed for region %s (non-fatal)", region_id)
    return True


def _build_thematic_legacy(region_id: int, bbox: list[float], cancel=None) -> None:
    """Legacy chunked thematic build (fallback): plan_build_cells returns one cell
    for normal regions and a grid of memory-bounded cells for continent-scale ones.
    Each cell's OSM data is parsed once (pyosmium) and shared by every feature
    builder (+ POIs), then freed before the next cell. Used only when the streaming
    osmium-export path is unavailable.
    """
    src_dir = region_registry.source_dir(region_id)
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
            logger.exception("%s feature build failed for region %s (non-fatal)",
                             name, region_id)
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
                poi_fut = ex.submit(_try_build_pois, region_id, ext_bbox)
                for fut in as_completed(list(futs) + [poi_fut]):
                    if fut is poi_fut:
                        fut.result()
                    else:
                        results[futs[fut]] = fut.result()
            for name, feats in results.items():
                if core_bbox is not None:   # chunked: keep features owned by this cell
                    feats = [f for f in feats if _rep_in_bbox(f[0], core_bbox)]
                if feats:
                    tippecanoe_writer.features_to_ndjson(feats, ndjson[name])
        finally:
            osm_source.clear_cache(ext_bbox)   # free this cell before the next
        if n_cells > 1:
            region_registry.update_status(
                region_id, "trails", progress=round((ci + 1) / n_cells * 80, 1),
                detail=f"block {ci + 1} / {n_cells}")
        else:
            region_registry.update_status(region_id, "trails", progress=40.0,
                                          detail="Extracting map features…")

    # One tippecanoe run over all layers → the region's overlay.pmtiles.
    def _tip_progress(pct):
        region_registry.update_status(
            region_id, "trails", progress=min(99.0, 85.0 + pct * 0.14),
            detail=f"Generating tiles… {pct:.0f}%")
    try:
        tippecanoe_writer.build_overlay(
            ndjson, src_dir / "overlay.pmtiles", progress_cb=_tip_progress, cancel=cancel,
            clip_bbox=bbox)   # see the streaming path above
    except download_cancel.DownloadCancelled:
        raise
    except Exception:
        logger.exception("Overlay tile build failed for region %s (non-fatal)", region_id)
    finally:
        for p in ndjson.values():
            p.unlink(missing_ok=True)

    # Rebuild the single overlay master from every region's overlay.pmtiles.
    try:
        region_merger.rebuild_master_overlay(write_trigger=False)
    except Exception:
        logger.exception("Master overlay rebuild failed for region %s (non-fatal)", region_id)


def _try_build_pois(region_id: int, bbox) -> None:
    try:
        from app.services.poi_builder import build_region_pois
        build_region_pois(tuple(bbox))
    except Exception:
        logger.exception("POI build failed for region %s (non-fatal)", region_id)
