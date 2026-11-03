# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A triathlon's courses: one file for the whole race, a file per leg, or both.

Organisers publish either, so a race plan takes either: the whole course
(the plan's ordinary course_segments / course_path) is split at T1 and T2
into its legs, and any leg's own file (tri_leg_courses) replaces its stretch
of the whole. Each leg's course then shapes its targets as a single-sport
course would (race_plan.sport_plan's triathlon branch).

The split. Course files carry no timestamps, so where the athlete changed
sport cannot be read from speed. What a triathlon course does have is a
transition zone the legs come back to: the swim ends beside it and the bike
returns to it. So T1 is the point near the swim's nominal length that comes
closest to the course's start, and T2 the point near swim + bike that comes
closest to T1 — and where nothing in the window comes within 500 m of the
zone (a point-to-point course), the nominal distance is used instead. The
user corrects either by dragging it on the course profile (tri_transitions_m),
which always wins.

Everything is pure arithmetic over the stored segments and path, and the
phone's port (com.tracks.core.race.TriCourse) is held to it by
spec/fixtures/race_plan.json.
"""
from __future__ import annotations

from .geo import _haversine_m

LEGS = ("swim", "bike", "run")
# How far either side of a nominal transition the split looks, as a share of
# the nominal distance, and how near the zone a point must come to count.
WINDOW = 0.3
ZONE_M = 500.0


def total_m(segments: list[dict]) -> float:
    return sum(float(s["distance_m"]) for s in segments or [])


def _path_positions(path: list[list], length: float) -> list[float]:
    """Each path point's distance along the course, the sampled path's own
    length scaled to the segments' (the path is downsampled, so it runs short)."""
    cum = [0.0]
    for a, b in zip(path, path[1:]):
        cum.append(cum[-1] + _haversine_m(a[0], a[1], b[0], b[1]))
    own = cum[-1] or 1.0
    return [c / own * length for c in cum]


def _nearest_in_window(path, pos, centre_m, zone, window_m):
    best = None
    for p, m in zip(path, pos):
        if abs(m - centre_m) > window_m:
            continue
        d = _haversine_m(p[0], p[1], zone[0], zone[1])
        if best is None or d < best[0]:
            best = (d, m, p)
    return best


def auto_transitions(segments: list[dict], path: list[list], splits: dict) -> list[float]:
    """[T1, T2] in metres along the whole course; see the module docstring."""
    length = total_m(segments)
    swim, bike = float(splits["swim_m"]), float(splits["bike_m"])
    nominal = [swim, swim + bike]
    if not path or len(path) < 2 or length <= 0:
        return [round(min(n, length), 1) for n in nominal]
    pos = _path_positions(path, length)
    out: list[float] = []
    zone = path[0]
    for i, n in enumerate(nominal):
        leg = swim if i == 0 else bike
        hit = _nearest_in_window(path, pos, n, zone, max(leg * WINDOW, 100.0))
        if hit is not None and hit[0] <= ZONE_M:
            out.append(round(hit[1], 1))
            zone = hit[2]
        else:
            out.append(round(min(n, length), 1))
    if out[1] <= out[0]:
        out[1] = round(min(out[0] + bike, length), 1)
    return out


def slice_segments(segments: list[dict], start_m: float, end_m: float) -> list[dict]:
    """The segments between two distances, a segment cut at either end kept
    in proportion (its gradient is the segment's own)."""
    out: list[dict] = []
    at = 0.0
    for s in segments or []:
        d = float(s["distance_m"])
        lo, hi = max(at, start_m), min(at + d, end_m)
        if hi - lo > 0.5:
            share = (hi - lo) / d if d else 0.0
            out.append({"distance_m": round(hi - lo, 1),
                        "elevation_gain_m": round(float(s.get("elevation_gain_m") or 0.0) * share, 1),
                        "gradient": s["gradient"]})
        at += d
    return out


def slice_path(path: list[list], length: float, start_m: float, end_m: float) -> list[list]:
    if not path:
        return []
    pos = _path_positions(path, length)
    return [p for p, m in zip(path, pos) if start_m - 1e-6 <= m <= end_m + 1e-6]


def leg_courses(segments, path, leg_files: dict | None, transitions: list | None, splits: dict) -> dict:
    """Each leg's course: {leg: {"segments", "path", "source"}}, source being
    "own" (its own file), "whole" (its stretch of the whole course) or None.

    `transitions` is the user's [T1, T2], else the automatic one.
    """
    leg_files = leg_files or {}
    out: dict = {}
    whole = bool(segments)
    cuts = None
    if whole:
        length = total_m(segments)
        cuts = list(transitions) if transitions and len(transitions) == 2 else auto_transitions(segments, path, splits)
        bounds = {"swim": (0.0, cuts[0]), "bike": (cuts[0], cuts[1]), "run": (cuts[1], length)}
    for leg in LEGS:
        own = leg_files.get(leg)
        if own and own.get("segments"):
            out[leg] = {"segments": own["segments"], "path": own.get("path") or [], "source": "own"}
        elif whole:
            a, b = bounds[leg]
            out[leg] = {"segments": slice_segments(segments, a, b),
                        "path": slice_path(path or [], length, a, b), "source": "whole"}
        else:
            out[leg] = {"segments": None, "path": [], "source": None}
    return {"legs": out, "transitions_m": cuts}
