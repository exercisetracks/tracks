# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Per-activity metrics computed from raw data_points at import time.

Computed values
───────────────
efficiency_factor   NP/avg_hr  (cycling)  or  avg_speed_mps/avg_hr  (running)
aerobic_decoupling  (EF_first_half - EF_second_half) / EF_first_half × 100  (%)
power_curve         best rolling-avg watts at each standard duration
pace_curve          best rolling-avg speed (m/s) at each standard distance

Standard durations (seconds):  1, 5, 10, 30, 60, 120, 300, 600, 1200, 1800, 3600, 5400
Standard distances (meters):   400, 1000, 1609, 5000, 10000, 21097, 42195
"""

from __future__ import annotations
from statistics import mean

_POWER_DURATIONS = [1, 5, 10, 30, 60, 120, 300, 600, 1200, 1800, 3600, 5400]
_PACE_DISTANCES  = [400, 1000, 1609, 5000, 10000, 21097, 42195]

_CYCLING_SPORTS = {"cycling", "mountain_biking", "road_biking", "gravel_cycling",
                   "virtual_cycling", "indoor_cycling", "e_biking"}
_RUNNING_SPORTS = {"running", "trail_running", "treadmill_running", "street_running",
                   "track_running", "obstacle_racing"}


# ─────────────────────────────────────────
# Rolling-window helpers
# ─────────────────────────────────────────

def _best_rolling_power(powers: list[float], window_seconds: int,
                        timestamps: list[float]) -> float | None:
    """
    Best mean power over a rolling time window.

    Powers and timestamps are parallel lists.  Uses a two-pointer O(n) approach:
    advance the right pointer, drop left pointer when the window exceeds the target.
    """
    if len(powers) < 2 or window_seconds <= 0:
        return None

    best: float | None = None
    left = 0
    window_sum = 0.0

    for right in range(len(powers)):
        window_sum += powers[right]
        # Shrink window if it exceeds target duration
        while (timestamps[right] - timestamps[left]) > window_seconds and left < right:
            window_sum -= powers[left]
            left += 1

        current_duration = timestamps[right] - timestamps[left]
        if current_duration >= window_seconds * 0.9:  # allow 10% tolerance for missing samples
            avg = window_sum / (right - left + 1)
            if best is None or avg > best:
                best = avg

    return round(best, 1) if best is not None else None


def _best_rolling_speed(speeds: list[float], cum_dist: list[float],
                        target_dist: float) -> float | None:
    """
    Best mean speed (m/s) over a rolling distance window.

    speeds and cum_dist are parallel lists where cum_dist[i] is the
    cumulative distance (in metres) at sample i.  O(n) two-pointer approach.
    """
    if len(speeds) < 2 or target_dist <= 0:
        return None

    best: float | None = None
    left = 0
    speed_sum = speeds[0]

    for right in range(1, len(speeds)):
        speed_sum += speeds[right]
        # Shrink the left edge while the window covers more than target_dist
        while cum_dist[right] - cum_dist[left] > target_dist and left < right:
            speed_sum -= speeds[left]
            left += 1
        window_dist = cum_dist[right] - cum_dist[left]
        if window_dist >= target_dist * 0.9:
            avg = speed_sum / (right - left + 1)
            if best is None or avg > best:
                best = avg

    return round(best, 4) if best is not None else None


# ─────────────────────────────────────────
# Efficiency Factor
# ─────────────────────────────────────────

def compute_efficiency_factor(
    sport: str | None,
    avg_hr: int | None,
    normalized_power: int | None,
    avg_speed: float | None,
) -> float | None:
    """
    EF = NP / avg_hr  for cycling
    EF = avg_speed_mps / avg_hr  for running

    Returns None if input data is insufficient or EF would be nonsensical.
    """
    if not avg_hr or avg_hr <= 0:
        return None

    sport_lower = (sport or "").lower()

    if sport_lower in _CYCLING_SPORTS:
        if normalized_power and normalized_power > 0:
            return round(normalized_power / avg_hr, 3)
        return None

    if sport_lower in _RUNNING_SPORTS:
        if avg_speed and avg_speed > 0:
            return round(avg_speed / avg_hr, 4)
        return None

    return None


# ─────────────────────────────────────────
# Aerobic Decoupling
# ─────────────────────────────────────────

def compute_aerobic_decoupling(
    sport: str | None,
    data_points: list[dict],
) -> float | None:
    """
    Aerobic Decoupling (%) = (EF_first_half - EF_second_half) / EF_first_half × 100

    Positive value = cardiac drift (HR rising relative to power/pace in second half).
    Meaningful only for steady-state aerobic efforts > 30 min with HR and power/speed data.
    Returns None when data is insufficient.
    """
    if len(data_points) < 60:  # need enough samples for two meaningful halves
        return None

    sport_lower = (sport or "").lower()
    is_cycling = sport_lower in _CYCLING_SPORTS
    is_running = sport_lower in _RUNNING_SPORTS
    if not (is_cycling or is_running):
        return None

    mid = len(data_points) // 2
    first = data_points[:mid]
    second = data_points[mid:]

    def _ef_half(half: list[dict]) -> float | None:
        hrs = [p["heart_rate"] for p in half if p.get("heart_rate")]
        if not hrs:
            return None
        avg_hr = mean(hrs)
        if avg_hr <= 0:
            return None
        if is_cycling:
            powers = [p["power"] for p in half if p.get("power")]
            if not powers:
                return None
            return mean(powers) / avg_hr
        else:
            speeds = [p["speed"] for p in half if p.get("speed") and p["speed"] > 0]
            if not speeds:
                return None
            return mean(speeds) / avg_hr

    ef1 = _ef_half(first)
    ef2 = _ef_half(second)

    if ef1 is None or ef2 is None or ef1 == 0:
        return None

    return round((ef1 - ef2) / ef1 * 100, 2)


# ─────────────────────────────────────────
# Power Curve
# ─────────────────────────────────────────

def compute_power_curve(data_points: list[dict]) -> dict[int, float]:
    """
    Returns {duration_seconds: best_avg_watts} for each standard duration
    where enough data exists.  Only durations shorter than the activity are returned.
    """
    powers = [p["power"] for p in data_points if p.get("power") is not None]
    if not powers:
        return {}

    # Build parallel timestamp list (seconds from first point)
    ts_raw = [p["recorded_at"] for p in data_points if p.get("power") is not None]
    if not ts_raw:
        return {}

    t0 = ts_raw[0]
    timestamps = [(t - t0).total_seconds() for t in ts_raw]
    total_duration = timestamps[-1] if timestamps else 0

    result: dict[int, float] = {}
    for dur in _POWER_DURATIONS:
        if dur > total_duration:
            break
        best = _best_rolling_power(powers, dur, timestamps)
        if best is not None:
            result[dur] = best
    return result


# ─────────────────────────────────────────
# Pace Curve
# ─────────────────────────────────────────

def compute_pace_curve(data_points: list[dict]) -> dict[int, float]:
    """
    Returns {distance_meters: best_avg_speed_mps} for each standard distance
    where enough GPS/speed data exists.
    """
    # Filter to points with both speed and a recorded position
    pts = [p for p in data_points if p.get("speed") is not None and p["speed"] > 0]
    if len(pts) < 2:
        return {}

    speeds = [p["speed"] for p in pts]

    # Build cumulative distance from speed × elapsed_time between samples
    # Using recorded_at timestamps for Δt
    cum_dist: list[float] = [0.0]
    for i in range(1, len(pts)):
        dt = (pts[i]["recorded_at"] - pts[i - 1]["recorded_at"]).total_seconds()
        # average speed over the interval × time = distance
        seg = ((pts[i]["speed"] + pts[i - 1]["speed"]) / 2) * max(dt, 0)
        cum_dist.append(cum_dist[-1] + seg)

    total_dist = cum_dist[-1]
    result: dict[int, float] = {}
    for dist in _PACE_DISTANCES:
        if dist > total_dist:
            break
        best = _best_rolling_speed(speeds, cum_dist, dist)
        if best is not None:
            result[dist] = best
    return result


# ─────────────────────────────────────────
# Power-based TSS (Coggan)
# ─────────────────────────────────────────

def compute_power_tss(
    normalized_power: int | None,
    ftp: float | None,
    duration_seconds: int | None,
) -> float | None:
    """
    TSS = (duration_hours × NP² / FTP²) × 100
    Returns None if any input is missing or FTP is 0.
    """
    if not normalized_power or not ftp or not duration_seconds:
        return None
    if ftp <= 0:
        return None
    duration_hours = duration_seconds / 3600
    return round(duration_hours * (normalized_power / ftp) ** 2 * 100, 1)
