# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Three-phase track-point smoother and LTTB downsampler.

Smoothing pipeline (applied once at parse time for new imports):
  1. Spike removal (×2 passes) — replaces outlier points with the mean of
                      their two immediate neighbours.  Two passes are run so
                      that adjacent bad points (which shield each other in a
                      single pass) are also caught.  Thresholds are tighter
                      than the physical maximums so that sensor glitches are
                      reliably eliminated.
  2. Rolling median — 7-point window reduces residual sensor noise while
                      preserving genuine rapid changes.

LTTB downsampling (applied per-request in the track API):
  Largest-Triangle-Three-Buckets algorithm preserves the visual shape of
  all metrics while reducing point count to a configurable cap.
"""

import statistics
from datetime import datetime, timezone

# ── per-field spike removal thresholds ───────────────────────────────────────
# Max allowed deviation from the mean of the two immediate neighbours.
# Tighter than the physical maximums — sensor glitches produce much larger
# jumps than legitimate changes between consecutive 1-second samples.
# speed: m/s, heart_rate: bpm, altitude: m, cadence: rpm/spm, power: W
_SPIKE_DELTA: dict[str, float] = {
    "speed":      3.0,
    "heart_rate": 15.0,
    "altitude":   20.0,
    "cadence":    15.0,
    "power":      300.0,
}

# Two passes so adjacent bad points (which shield each other in a single
# pass) are caught on the second sweep.
_SPIKE_PASSES = 2

_ROLLING_WINDOW = 7   # wider window → more robust against residual noise
_ROLLING_FIELDS = ("heart_rate", "altitude", "cadence", "power", "grit", "flow")

# Resolution cap for LTTB downsampling
RESOLUTION_CAPS: dict[str, int | None] = {
    "low":    500,
    "medium": 1500,
    "high":   3000,
    "raw":    None,
}


# ── smoothing ─────────────────────────────────────────────────────────────────

def smooth(points: list[dict]) -> list[dict]:
    """
    Return a new list of smoothed data-point dicts.
    Timestamps and GPS coordinates are never modified.
    """
    if len(points) < 3:
        return points

    out = [dict(p) for p in points]

    for _ in range(_SPIKE_PASSES):
        for field, max_delta in _SPIKE_DELTA.items():
            _remove_spikes(out, field, max_delta)

    for field in _ROLLING_FIELDS:
        _rolling_median(out, field, _ROLLING_WINDOW)

    return out


def _remove_spikes(points: list[dict], field: str, max_delta: float) -> None:
    n = len(points)
    for i in range(1, n - 1):
        v = points[i].get(field)
        if v is None:
            continue
        prev = points[i - 1].get(field)
        nxt  = points[i + 1].get(field)
        if prev is None or nxt is None:
            continue
        interp = (prev + nxt) / 2.0
        if abs(v - interp) > max_delta:
            points[i][field] = round(interp, 4)


def _rolling_median(points: list[dict], field: str, window: int) -> None:
    n    = len(points)
    half = window // 2
    # Snapshot of original (pre-smoothed) values so the window always
    # reads from the same pass rather than cascading changed values.
    orig = [p.get(field) for p in points]
    for i in range(n):
        start = max(0, i - half)
        end   = min(n, i + half + 1)
        vals  = [orig[j] for j in range(start, end) if orig[j] is not None]
        if vals:
            points[i][field] = statistics.median(vals)


def recompute_summary(activity_dict: dict, points: list[dict]) -> dict:
    """
    Return a copy of activity_dict with avg/max stats recomputed from the
    smoothed track.  Only overwrites fields that have at least one non-null
    track value.
    """
    speeds   = [p["speed"]      for p in points if p.get("speed")      is not None]
    hrs      = [p["heart_rate"] for p in points if p.get("heart_rate") is not None]
    cadences = [p["cadence"]    for p in points if p.get("cadence")    is not None]
    powers   = [p["power"]      for p in points if p.get("power")      is not None]

    out = dict(activity_dict)
    if speeds:
        out["avg_speed"] = statistics.mean(speeds)
        out["max_speed"] = max(speeds)
    if hrs:
        out["avg_heart_rate"] = round(statistics.mean(hrs))
        out["max_heart_rate"] = round(max(hrs))
    if cadences:
        out["avg_cadence"] = round(statistics.mean(cadences))
    if powers:
        out["avg_power"] = round(statistics.mean(powers))
    return out


# ── LTTB downsampling ─────────────────────────────────────────────────────────

def lttb(points: list[dict], threshold: int) -> list[dict]:
    """
    Largest-Triangle-Three-Buckets downsampling.

    Selects `threshold` points that best preserve the visual shape of
    the time series.  Always includes the first and last points.

    `points` must be dicts with a "recorded_at" key (datetime or ISO str).
    """
    n = len(points)
    if threshold <= 2 or n <= threshold:
        return points

    # Pre-compute elapsed seconds so we don't parse timestamps repeatedly.
    t0  = _ts(points[0]["recorded_at"])
    xs  = [(_ts(p["recorded_at"]) - t0).total_seconds() for p in points]
    ys  = [_primary_y(p) for p in points]

    sampled = [points[0]]
    every   = (n - 2) / (threshold - 2)
    a       = 0

    for i in range(threshold - 2):
        # Average of the next bucket — serves as the third triangle vertex.
        avg_start = int((i + 1) * every) + 1
        avg_end   = min(int((i + 2) * every) + 1, n)
        count     = avg_end - avg_start
        avg_x     = sum(xs[j] for j in range(avg_start, avg_end)) / count
        avg_y     = sum(ys[j] for j in range(avg_start, avg_end)) / count

        # Current bucket: pick the point maximising the triangle area.
        bkt_start = int(i * every) + 1
        bkt_end   = min(int((i + 1) * every) + 1, n)
        ax, ay    = xs[a], ys[a]

        max_area = -1.0
        best     = bkt_start
        for j in range(bkt_start, bkt_end):
            bx, by = xs[j], ys[j]
            area   = abs((ax - avg_x) * (by - ay) - (ax - bx) * (avg_y - ay))
            if area > max_area:
                max_area = area
                best     = j

        sampled.append(points[best])
        a = best

    sampled.append(points[-1])
    return sampled


def _ts(recorded_at) -> datetime:
    """Normalise a recorded_at value to a timezone-aware datetime."""
    if isinstance(recorded_at, datetime):
        return recorded_at if recorded_at.tzinfo else recorded_at.replace(tzinfo=timezone.utc)
    return datetime.fromisoformat(str(recorded_at)).replace(tzinfo=timezone.utc)


def _primary_y(point: dict) -> float:
    """Pick the best available numeric signal for LTTB triangle calculation."""
    for key in ("speed", "heart_rate", "altitude", "cadence", "power"):
        v = point.get(key)
        if v is not None:
            return float(v)
    return 0.0
