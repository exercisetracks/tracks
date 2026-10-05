# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate USGS-style contour lines from Terrarium DEM tiles.

Reads 512×512 Terrarium-encoded WebP tiles from a PMTiles archive, computes
contour polylines using contourpy, and writes them as MVT vector tiles into a
new PMTiles archive served by go-pmtiles.

USGS 7.5-minute quad conventions:
  Index contours: every 5th intermediate contour (thicker line, labelled).
  Intermediate contours: base interval.
  Supplementary contours: half-interval where terrain is very flat (not implemented).

Zoom-dependent base intervals (matching USGS publication standards):
  z ≤  9 → 500 ft
  z = 10 → 200 ft
  z ≥ 11 → 100 ft (standard 7.5-minute quad interval)
(see INTERVAL_FT in constants.py for the authoritative per-zoom mapping)

Split out of a single ~570-line module into one file per concern; every name is
re-exported here so existing imports
(``from app.services.contour_generator import …``) keep working unchanged.

Modules:
  constants.py  tile geometry, intervals, the regen lock + shared logger
  dem.py        Terrarium WebP decode + neighbour-stitched elevation grids
  contours.py   contour level/index logic + contourpy → MVT line features
  tiling.py     (z,x,y) → geographic bounds + bbox-overlap test
  builder.py    per-tile worker + parallel build_contours_pmtiles archive writer
  regions.py    public region orchestration (build_region_contours / regenerate_contours)
"""

from __future__ import annotations

from .builder import (
    _contour_tile,
    _init_contour_worker,
    _process_one_contour_tile,
    build_contours_pmtiles,
)
from .constants import (
    CONTOUR_PAD_PX,
    INDEX_EVERY,
    INTERVAL_FT,
    MVT_EXTENT,
    PX_TO_MVT,
    TILE_PX,
    _contour_lock,
    logger,
)
from .contours import _contour_levels_ft, _generate_mvt_features, _is_index
from .dem import _decode_terrarium, _decode_tile, _elev_m_to_ft, _padded_elevation
from .regions import build_region_contours, regenerate_contours
from .tiling import _bbox_overlaps, _tile_bounds, _tile_west_east

__all__ = [
    # public API
    "build_contours_pmtiles",
    "build_region_contours",
    "regenerate_contours",
    # constants
    "MVT_EXTENT",
    "TILE_PX",
    "PX_TO_MVT",
    "CONTOUR_PAD_PX",
    "INTERVAL_FT",
    "INDEX_EVERY",
]
