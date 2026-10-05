# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Local OSM data source — replaces public Overpass for region imports.

The thematic builders (trail/water/area/infra/landuse/poi) used to each split a
region into ~0.4° cells and fire their own Overpass query per cell against flaky
public mirrors — ~70 round-trips per region, hours of 504 retries.  This module
serves the same data from a *local* OSM extract instead, so a whole region's
features are read in seconds and fully offline.

Strategy (disk-frugal, on-demand):
  1. Find the smallest Geofabrik extract whose bbox covers the requested area
     (state → country → continent), via the published extract index.
  2. Download it once, immediately ``osmium tags-filter`` it down to only the
     feature types the app renders (5–10× smaller), and cache that slim PBF.
     The raw download is deleted; only the filtered PBF is kept.
  3. Per region: ``osmium extract`` the exact bbox from the cached slim PBF into
     a tiny temp PBF, then either ``osmium export`` it to GeoJSONSeq and stream
     it (the primary path) or parse it with pyosmium into Overpass-JSON-shaped
     element dicts (the legacy path) — so the existing builders consume them
     unchanged.

Every public entry point degrades gracefully: any failure (no covering extract,
download/osmium/parse error, tooling missing) returns ``None`` and the caller
falls back to its original Overpass path.  This keeps the change safe.

This package splits the old single module by concern, re-exporting every name it
exposed so ``app.services.osm_source.<name>`` keeps working unchanged:
  * ``config``        — tunables, paths, tags-filter, osmium probe.
  * ``geofabrik``     — extract catalogue / ``find_extract``.
  * ``osmium_runner`` — download + slim-filter + bbox clip via the osmium CLI.
  * ``streaming``     — single-pass ``osmium export`` GeoJSONSeq path + routes.
  * ``region_parse``  — legacy pyosmium parse + cached ``region_elements`` API.
  * ``select``        — tag selector for the builders.
"""

from __future__ import annotations

from app.services.osm_source.config import (
    BUILD_CELL_BUFFER_DEG, BUILD_CELL_DEG, BUILD_ONE_SHOT_SQDEG,
    GEOFABRIK_INDEX_URL, INDEX_MAX_AGE_S, TAGS_FILTER_EXPR, USER_AGENT,
    _osm_dir, logger, osmium_available,
)
from app.services.osm_source.geofabrik import (
    _geom_bbox, _load_index, find_extract,
)
from app.services.osm_source.osmium_runner import (
    _extract_region, _lock_for, _run_osmium, ensure_source,
)
from app.services.osm_source.region_parse import (
    _compute, _key, _parse, clear_cache, plan_build_cells, region_elements,
)
from app.services.osm_source.select import select
from app.services.osm_source.streaming import (
    export_geojson, iter_geojson_features, parse_route_relations,
    prepare_region_pbf,
)

__all__ = [
    # config / tunables
    "BUILD_ONE_SHOT_SQDEG", "BUILD_CELL_DEG", "BUILD_CELL_BUFFER_DEG",
    "GEOFABRIK_INDEX_URL", "INDEX_MAX_AGE_S", "USER_AGENT", "TAGS_FILTER_EXPR",
    "osmium_available", "logger",
    # geofabrik catalogue
    "find_extract",
    # download / extract
    "ensure_source",
    # streaming export path
    "prepare_region_pbf", "export_geojson", "iter_geojson_features",
    "parse_route_relations",
    # cached region elements + cell planning
    "region_elements", "clear_cache", "plan_build_cells",
    # selector
    "select",
]
