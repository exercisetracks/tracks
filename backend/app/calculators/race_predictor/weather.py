# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Weather slowdown model: heat/humidity (Ely et al. 2007) + headwind (Pugh 1971).

Returns a multiplicative penalty (≥ 1.0) applied to a flat, calm prediction.
"""
from __future__ import annotations

import math


def weather_slowdown_factor(temp_c: float, humidity_pct: float,
                            wind_mps: float = 0.0, wind_angle_deg: float = 0.0) -> float:
    """
    Multiplicative slowdown factor > 1.0 for adverse conditions.

    Science basis:
    - Heat: ~0.3% per 1°C above 10°C optimal (Ely et al. 2007 / Cheuvront 2001).
      Humidity adds metabolic cost; combined effect modelled via wet-bulb offset.
    - Wind: Pugh (1971) headwind adds ~1% per km/h on an out-and-back course.
      wind_angle_deg=0 → pure headwind; 180 → pure tailwind.
      On a loop course the net effect is a slight penalty at all wind speeds
      (air resistance is non-linear); we approximate with a 30% headwind
      penalty (loop average).

    Returns 1.0 for cool, calm, low-humidity conditions (no slowdown).
    """
    # Wet-bulb approximation (simplified): use the higher of wet-bulb / dry-bulb
    wbt = temp_c - (1 - humidity_pct / 100) * 5.0

    optimal      = 10.0  # °C
    effective    = max(wbt, temp_c)
    heat_penalty = max(0.0, (effective - optimal) * 0.003)

    # Wind: decompose into a headwind component, then average over a loop course
    angle_rad     = math.radians(wind_angle_deg % 360)
    headwind_frac = math.cos(angle_rad)               # +1 = pure head, -1 = pure tail
    loop_factor   = max(0.0, headwind_frac * 0.3)     # ~30% headwind equivalent on a loop
    wind_penalty  = wind_mps * 0.004 * loop_factor    # ~0.4% per m/s headwind

    return 1.0 + heat_penalty + wind_penalty
