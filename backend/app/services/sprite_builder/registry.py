# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Name → drawing-function maps that drive the sheet build.

``SYMBOLS`` keys become MapLibre ``icon-image`` names; ``PATTERNS`` keys become
``fill-pattern`` names. The atlas builder iterates these to render every glyph.
"""

from __future__ import annotations

from app.services.sprite_builder import patterns as p
from app.services.sprite_builder import symbols as s

SYMBOLS = {
    "campground": s.sym_campground, "picnic": s.sym_picnic,
    "ranger_station": s.sym_ranger_station, "cemetery": s.sym_cemetery,
    "mine": s.sym_mine, "mine_shaft": s.sym_mine_shaft, "quarry": s.sym_quarry,
    "cave": s.sym_cave, "spring": s.sym_spring, "well": s.sym_well, "tank": s.sym_tank,
    "drinking_water": s.sym_drinking_water, "peak": s.sym_peak,
    "gaging_station": s.sym_gaging_station, "boat_ramp": s.sym_boat_ramp,
    "winter_rec": s.sym_winter_rec, "school": s.sym_school,
    "place_of_worship": s.sym_place_of_worship, "aerodrome": s.sym_aerodrome,
    "tower": s.sym_tower, "substation": s.sym_substation,
    "pumping_plant": s.sym_pumping_plant, "falls": s.sym_falls,
    "benchmark": s.sym_benchmark, "spot_elevation": s.sym_spot_elevation,
    "monument": s.sym_monument, "trailhead": s.sym_trailhead,
    # recreation & services (override stray Protomaps base icons)
    "toilets": s.sym_toilets, "parking": s.sym_parking, "hospital": s.sym_hospital,
    "fuel": s.sym_fuel, "viewpoint": s.sym_viewpoint, "information": s.sym_information,
    "shelter": s.sym_shelter, "alpine_hut": s.sym_alpine_hut, "park": s.sym_park,
    "forest": s.sym_forest, "restaurant": s.sym_restaurant, "cafe": s.sym_cafe,
    "fast_food": s.sym_fast_food, "bar": s.sym_bar, "supermarket": s.sym_supermarket,
    "convenience": s.sym_convenience, "library": s.sym_library, "museum": s.sym_museum,
    "theatre": s.sym_theatre,
    # line symbols
    "rail_tie": s.sym_rail_tie, "pylon": s.sym_pylon, "levee_tick": s.sym_levee_tick,
    "pipeline_marker": s.sym_pipeline_marker,
}

PATTERNS = {
    "marsh": p.pat_marsh, "swamp": p.pat_swamp, "wooded_swamp": p.pat_wooded_swamp,
    "mangrove": p.pat_mangrove, "orchard": p.pat_orchard, "vineyard": p.pat_vineyard,
    "sand": p.pat_sand, "gravel": p.pat_gravel, "scrub": p.pat_scrub,
    "tailings": p.pat_tailings, "mud": p.pat_mud, "reef": p.pat_reef,
}
