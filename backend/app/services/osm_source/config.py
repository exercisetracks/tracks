# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tunables, paths, and tooling probe for the local OSM source.

Holds the things the rest of the package depends on but that don't fit a single
processing stage: region-build chunking sizes, the Geofabrik catalogue URL/UA,
the ``osmium tags-filter`` selector (what features we keep in the slim cache),
the shared OSM cache dir, and the ``osmium`` availability check.
"""

from __future__ import annotations

import logging
import shutil
from pathlib import Path

from app.config import settings

logger = logging.getLogger(__name__)

# ── Region build chunking ─────────────────────────────────────────────────────
# A region's whole feature set is parsed into memory to build its vector tiles, so
# peak RAM scales with region size. To keep continent-scale downloads from OOM-ing,
# a region larger than BUILD_ONE_SHOT_SQDEG is split into a grid of cells ≤
# BUILD_CELL_DEG per side; each cell is built and merged separately, so peak memory
# is bounded by cell size, not region size. The common case (a state / small
# country) stays a single pass with no chunking overhead. These are tunable for the
# host's available RAM: smaller values are safer but make large downloads slower.
BUILD_ONE_SHOT_SQDEG = 6.0     # ≤ ~2.5°×2.5°: build in one pass (small regions)
BUILD_CELL_DEG = 2.0           # grid cell size when chunking a larger region

# Chunking only bounds the parse: each cell's features are appended to shared
# per-layer NDJSON, then ONE tippecanoe builds the whole region's tiles at once —
# so unlike the old per-cell tiler there is no low-zoom overview gap. To stop a
# feature being emitted by two adjacent cells, the orchestrator keeps only those
# whose representative point falls in the cell's unbuffered core; the cell extract
# is buffered so a feature crossing the core boundary still has its full geometry.
BUILD_CELL_BUFFER_DEG = 0.3

GEOFABRIK_INDEX_URL = "https://download.geofabrik.de/index-v1.json"
INDEX_MAX_AGE_S = 7 * 24 * 3600  # refresh the extract catalogue weekly
USER_AGENT = "Tracks/1.0 (self-hosted fitness app; trail data)"

# osmium tags-filter expressions — keep ONLY what the builders render. Big area
# keys (natural/landuse) are value-restricted so we don't cache every
# residential/farmland polygon; small keys (waterway/power/historic…) are kept
# whole. Mirrors the per-builder Overpass selectors.
_NATURAL = ("wood,scrub,heath,wetland,glacier,sand,bare_rock,scree,beach,"
            "grassland,mud,reef,shoal,tidalflat,water,spring,hot_spring,"
            "cave_entrance,sinkhole,geyser,peak,volcano")
_LANDUSE = "forest,orchard,vineyard,quarry,meadow,military"
_MAN_MADE = ("pipeline,dyke,embankment,water_well,water_tower,storage_tank,"
             "reservoir_covered,monitoring_station,survey_point,pumping_station,"
             "mast,tower,water_works,windmill,watermill,adit,mineshaft,water_tap")
_AMENITY = ("ranger_station,place_of_worship,school,shelter,drinking_water,"
            "water_point,toilets,fuel,hospital")
TAGS_FILTER_EXPR = [
    "nw/highway=path,track,footway,bridleway,steps,cycleway,trailhead,service,unclassified,tertiary,tertiary_link",
    "nwr/waterway",
    f"nwr/natural={_NATURAL}",
    f"nwr/landuse={_LANDUSE}",
    "nwr/boundary=protected_area,national_park,aboriginal_lands",
    "nwr/leisure=nature_reserve,slipway,firepit,bird_hide",
    "w/railway=rail,light_rail,narrow_gauge,tram,subway,preserved,funicular,monorail",
    "nwr/power=line,minor_line,substation,plant",
    f"nwr/man_made={_MAN_MADE}",
    "nwr/historic=mine,mine_shaft,adit",
    "nwr/tourism=camp_site,picnic_site,viewpoint,wilderness_hut,alpine_hut,information",
    f"nwr/amenity={_AMENITY}",
    "nwr/aeroway=aerodrome",
    "r/route=hiking,foot",
]


def _osm_dir() -> Path:
    d = Path(settings.map_data_dir) / "osm"
    d.mkdir(parents=True, exist_ok=True)
    return d


def osmium_available() -> bool:
    return shutil.which("osmium") is not None
