# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Course wind-exposure models.

Both functions weight each course segment's heading against the wind direction
to estimate a net headwind and a time-penalty multiplier:
  - compute_wind_course_exposure         — running (linear Pugh 1971 model)
  - compute_cycling_wind_course_exposure — cycling (v² aerodynamic drag, far stronger)
"""
from __future__ import annotations

import math

from .geo import _bearing, _haversine_m


def _weighted_mean_headwind(path_points: list[list], wind_direction_deg: float) -> float | None:
    """
    Distance-weighted mean of cos(bearing − wind_dir) over the course.

    Returns a value in [−1, +1] (+1 = pure headwind, −1 = pure tailwind), or
    None if the course is too short / degenerate to score.
    """
    total_dist    = 0.0
    weighted_head = 0.0
    for i in range(1, len(path_points)):
        lat1, lon1 = path_points[i - 1][0], path_points[i - 1][1]
        lat2, lon2 = path_points[i][0],     path_points[i][1]
        d = _haversine_m(lat1, lon1, lat2, lon2)
        if d < 1.0:
            continue
        bear      = _bearing(lat1, lon1, lat2, lon2)
        cos_angle = math.cos(math.radians(bear - wind_direction_deg))
        weighted_head += cos_angle * d
        total_dist    += d

    if total_dist == 0:
        return None
    return weighted_head / total_dist


def _headwind_note(mean_head: float) -> str:
    """Human-readable summary of the course's net wind orientation."""
    if   mean_head >  0.50: return "Mostly headwind"
    elif mean_head >  0.15: return "Headwind bias"
    elif mean_head < -0.50: return "Mostly tailwind"
    elif mean_head < -0.15: return "Tailwind bias"
    else:                   return "Mixed / crosswind"


def compute_wind_course_exposure(
    path_points: list[list],
    wind_direction_deg: float,
    wind_mps: float,
) -> dict:
    """
    Compute running wind exposure by comparing each segment's bearing to wind.

    wind_direction_deg: meteorological — direction the wind comes FROM.
    Returns net_headwind_mps (positive = headwind, negative = tailwind),
    course_note label, and wind_factor multiplicative penalty.

    Science: Pugh (1971) — at ~4 m/s race pace, 1 m/s headwind ≈ +1.5–2% slowdown.
    Tailwind benefit is non-linear (< headwind penalty); capped at −2%.
    """
    if not path_points or len(path_points) < 2 or wind_mps <= 0:
        return {"net_headwind_mps": 0.0, "course_note": None, "wind_factor": 1.0}

    mean_head = _weighted_mean_headwind(path_points, wind_direction_deg)
    if mean_head is None:
        return {"net_headwind_mps": 0.0, "course_note": None, "wind_factor": 1.0}

    net_mps = mean_head * wind_mps

    if net_mps >= 0:
        wind_factor = 1.0 + net_mps * 0.015
    else:
        wind_factor = max(0.98, 1.0 + net_mps * 0.008)

    return {
        "net_headwind_mps": round(net_mps, 2),
        "course_note":      _headwind_note(mean_head),
        "wind_factor":      round(wind_factor, 4),
    }


def compute_cycling_wind_course_exposure(
    path_points: list[list],
    wind_direction_deg: float,
    wind_mps: float,
    rider_speed_mps: float = 10.0,
) -> dict:
    """
    Cycling-specific wind exposure model.

    Wind effect on a cyclist scales with (v_rider + v_wind_component)² — much
    larger than the linear Pugh model for running.  At 36 km/h into a 4 m/s
    headwind, aero drag increases by ~56%; with a tailwind, drag drops ~44%.

    Returns net_headwind_mps, course_note, and wind_factor (time multiplier).
    """
    if not path_points or len(path_points) < 2 or wind_mps <= 0:
        return {"net_headwind_mps": 0.0, "course_note": None, "wind_factor": 1.0}

    mean_head = _weighted_mean_headwind(path_points, wind_direction_deg)
    if mean_head is None:
        return {"net_headwind_mps": 0.0, "course_note": None, "wind_factor": 1.0}

    net_mps = mean_head * wind_mps

    # Aerodynamic power cost ratio: P ∝ (v + w)² * v vs v³, speed ∝ P^(1/3)
    v           = rider_speed_mps
    power_ratio = (v + net_mps) ** 2 * v / v ** 3
    speed_ratio = 1.0 / power_ratio ** (1 / 3)
    if net_mps >= 0:  # headwind
        wind_factor = max(1.0, 1.0 / speed_ratio)
        wind_factor = min(wind_factor, 1.30)          # cap at 30% penalty
    else:             # tailwind
        wind_factor = max(0.92, 1.0 / speed_ratio)    # cap tailwind benefit at 8%

    return {
        "net_headwind_mps": round(net_mps, 2),
        "course_note":      _headwind_note(mean_head),
        "wind_factor":      round(wind_factor, 4),
    }
