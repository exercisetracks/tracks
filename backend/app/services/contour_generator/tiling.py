# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Web-Mercator tile geometry helpers.

Convert a tile's (z, x, y) to its geographic bounds and test bbox overlap —
used to scope contour regeneration to a user-drawn area.
"""

from __future__ import annotations

import math
from typing import Sequence


def _tile_west_east(z: int, x: int) -> tuple[float, float]:
    """Return (west_lon, east_lon) for a TMS tile at zoom z, column x."""
    n = 2 ** z
    return x / n * 360.0 - 180.0, (x + 1) / n * 360.0 - 180.0


def _tile_bounds(z: int, x: int, y: int) -> tuple[float, float, float, float]:
    """Return (west, south, east, north) for a TMS tile."""
    n = 2 ** z
    west, east = _tile_west_east(z, x)
    lat1 = math.atan(math.sinh(math.pi * (1 - 2 * y / n)))
    lat2 = math.atan(math.sinh(math.pi * (1 - 2 * (y + 1) / n)))
    return west, math.degrees(lat2), east, math.degrees(lat1)


def _bbox_overlaps(a: Sequence[float], b: Sequence[float]) -> bool:
    """True if bbox *a* (w, s, e, n) overlaps bbox *b*."""
    return not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])
