# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared constants for the USGS sprite sheet: ink palette, sizing, paths.

All symbol/pattern drawing routines work in *fractional* coordinates (0..1 of a
square of side ``s``), so the only sizing that lives here is the logical @1x icon
and pattern dimensions plus the supersample/padding used when packing the sheet.
"""

from __future__ import annotations

from pathlib import Path

from app.config import settings

# Bump when symbol art changes so the sheet is regenerated on next launch.
BUILD_VERSION = "usgs-sprite-13"

SPRITE_NAME = "usgs"
BASE_SPRITE = "light"          # merged underneath for completeness
ICON_PX = 20                   # @1x logical size of point/line glyphs
PATTERN_PX = 24                # @1x logical size of fill-pattern tiles
SS = 4                         # supersample factor for crisp glyph edges
PAD = 2                        # transparent padding between packed icons

# ── USGS ink palette ──────────────────────────────────────────────────────────
INK   = (28, 26, 23, 255)      # culture (black)
WATER = (60, 110, 150, 255)    # hydrography (blue)
GREEN = (60, 122, 63, 255)     # vegetation
TEAL  = (46, 110, 96, 255)     # marsh/wetland tufts
BROWN = (138, 90, 43, 255)     # surface / relief
RED   = (200, 40, 30, 255)     # recreation accents
CLEAR = (0, 0, 0, 0)


def _sprite_dir() -> Path:
    d = Path(settings.map_data_dir) / "sprite"
    d.mkdir(parents=True, exist_ok=True)
    return d
