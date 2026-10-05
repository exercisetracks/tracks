# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate a USGS-style MapLibre sprite sheet (self-hosted, pure-Pillow).

USGS quad symbols are simple geometric glyphs (crosses, tents, tufts, stipple),
so we draw them directly with Pillow rather than carrying an SVG rasteriser. The
output is merged on top of the existing Protomaps `light` sprite so every icon
the style already references keeps resolving, while our USGS symbols/patterns are
added (and a few are overridden with a more quad-like look).

Produces map-data/sprite/usgs.{png,json} and usgs@2x.{png,json} (MapLibre sprite
spec). Run idempotently + non-blocking from the app lifespan, the same way
trail_logos.source_logos_async() sources emblems. A BUILD_VERSION sidecar makes
re-runs cheap: the sheet is rebuilt only when the code version changes or the
output is missing.

Two image groups:
  * point glyphs   — campground, spring, mine, benchmark, … (icon-image)
  * fill patterns  — marsh, orchard, sand, … (seamless tiles for fill-pattern)
  * line symbols   — rail_tie, pylon, levee_tick (symbol-placement: line)

This package splits the old single module by concern: ``palette`` (constants),
``primitives`` (drawing helpers), ``symbols`` / ``patterns`` (the glyph art),
``registry`` (name→function maps), ``atlas`` (render + shelf-pack), ``builder``
(version-gated orchestration). Every name the old module exposed is re-exported
here, so ``app.services.sprite_builder.<name>`` keeps working unchanged.
"""

from __future__ import annotations

from app.services.sprite_builder.atlas import (
    _load_base_icons, _pack, _render_glyph, _render_pattern,
)
from app.services.sprite_builder.builder import (
    _build_ratio, _is_current, build_sprite, build_sprite_async,
)
from app.services.sprite_builder.palette import (
    BASE_SPRITE, BROWN, BUILD_VERSION, CLEAR, GREEN, ICON_PX, INK, PAD,
    PATTERN_PX, RED, SPRITE_NAME, SS, TEAL, WATER, _sprite_dir,
)
from app.services.sprite_builder.patterns import (
    _tuft, pat_gravel, pat_mangrove, pat_marsh, pat_mud, pat_orchard, pat_reef,
    pat_sand, pat_scrub, pat_swamp, pat_tailings, pat_vineyard, pat_wooded_swamp,
)
from app.services.sprite_builder.primitives import (
    _circle, _line, _lw, _poly, _thick,
)
from app.services.sprite_builder.registry import PATTERNS, SYMBOLS
from app.services.sprite_builder.symbols import (
    sym_aerodrome, sym_alpine_hut, sym_bar, sym_benchmark, sym_boat_ramp,
    sym_cafe, sym_campground, sym_cave, sym_cemetery, sym_convenience,
    sym_drinking_water, sym_falls, sym_fast_food, sym_forest, sym_fuel,
    sym_gaging_station, sym_hospital, sym_information, sym_levee_tick,
    sym_library, sym_mine, sym_mine_shaft, sym_monument, sym_museum, sym_park,
    sym_parking, sym_peak, sym_picnic, sym_pipeline_marker, sym_place_of_worship,
    sym_pumping_plant, sym_pylon, sym_quarry, sym_rail_tie, sym_ranger_station,
    sym_restaurant, sym_school, sym_shelter, sym_spot_elevation, sym_spring,
    sym_substation, sym_supermarket, sym_tank, sym_theatre, sym_toilets,
    sym_tower, sym_trailhead, sym_viewpoint, sym_well, sym_winter_rec,
)

__all__ = [
    # palette / config
    "BASE_SPRITE", "BROWN", "BUILD_VERSION", "CLEAR", "GREEN", "ICON_PX", "INK",
    "PAD", "PATTERN_PX", "RED", "SPRITE_NAME", "SS", "TEAL", "WATER",
    # registries
    "SYMBOLS", "PATTERNS",
    # public entry points
    "build_sprite", "build_sprite_async",
    # point / line symbols
    "sym_aerodrome", "sym_alpine_hut", "sym_bar", "sym_benchmark",
    "sym_boat_ramp", "sym_cafe", "sym_campground", "sym_cave", "sym_cemetery",
    "sym_convenience", "sym_drinking_water", "sym_falls", "sym_fast_food",
    "sym_forest", "sym_fuel", "sym_gaging_station", "sym_hospital",
    "sym_information", "sym_levee_tick", "sym_library", "sym_mine",
    "sym_mine_shaft", "sym_monument", "sym_museum", "sym_park", "sym_parking",
    "sym_peak", "sym_picnic", "sym_pipeline_marker", "sym_place_of_worship",
    "sym_pumping_plant", "sym_pylon", "sym_quarry", "sym_rail_tie",
    "sym_ranger_station", "sym_restaurant", "sym_school", "sym_shelter",
    "sym_spot_elevation", "sym_spring", "sym_substation", "sym_supermarket",
    "sym_tank", "sym_theatre", "sym_toilets", "sym_tower", "sym_trailhead",
    "sym_viewpoint", "sym_well", "sym_winter_rec",
    # fill patterns
    "pat_gravel", "pat_mangrove", "pat_marsh", "pat_mud", "pat_orchard",
    "pat_reef", "pat_sand", "pat_scrub", "pat_swamp", "pat_tailings",
    "pat_vineyard", "pat_wooded_swamp",
]
