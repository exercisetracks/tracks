# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Geometric turn detection for turn-by-turn courses.

A drawn/snapped track is just a polyline; to make a Garmin course navigate
turn-by-turn it needs `course_point` prompts (left / right / slight / sharp /
u-turn) at the junctions. Rather than depend on BRouter's version-specific
voice-hint output, we derive turns purely from the geometry: at each vertex we
compare the incoming and outgoing heading (each averaged over a short look-ahead
so GPS/snap jitter doesn't fire spurious turns) and classify the signed angle.

`detect_turns` is deterministic and works on any geometry (builder, GPX, FIT).
`is_turn_compatible` is the cheap heuristic behind the live "is this turn-by-turn
ready?" check — a track is compatible when it yields a sane set of real turns.
"""
from __future__ import annotations

import math

# Angle thresholds (degrees) for classifying a heading change into a turn type.
_STRAIGHT_MAX = 22.0     # below this = keep going straight (no prompt)
_SLIGHT_MAX = 50.0
_NORMAL_MAX = 115.0
_SHARP_MAX = 158.0       # above this = u-turn

_LOOK_M = 18.0           # average heading over ~this many metres each side
_MIN_GAP_M = 20.0        # don't emit two prompts closer than this


def _haversine_m(a, b) -> float:
    dx = (b[0] - a[0]) * math.cos(math.radians((a[1] + b[1]) / 2)) * 111320.0
    dy = (b[1] - a[1]) * 110540.0
    return math.hypot(dx, dy)


def _bearing(a, b) -> float:
    """Initial bearing a→b in degrees (0=N, 90=E), clockwise."""
    lat1, lat2 = math.radians(a[1]), math.radians(b[1])
    dlon = math.radians(b[0] - a[0])
    x = math.sin(dlon) * math.cos(lat2)
    y = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(dlon)
    return (math.degrees(math.atan2(x, y)) + 360.0) % 360.0


def _signed_delta(h1: float, h2: float) -> float:
    """h2 - h1 normalised to (-180, 180]. Positive = right (clockwise) turn."""
    d = (h2 - h1 + 180.0) % 360.0 - 180.0
    return d


def _point_ahead(coords, i, cum, look_m, forward: bool):
    """Vertex ~look_m before/after index i, for an averaged heading."""
    target = cum[i] + (look_m if forward else -look_m)
    j = i
    if forward:
        while j < len(coords) - 1 and cum[j] < target:
            j += 1
    else:
        while j > 0 and cum[j] > target:
            j -= 1
    return coords[j]


def _classify(delta: float) -> str | None:
    mag = abs(delta)
    right = delta > 0
    if mag < _STRAIGHT_MAX:
        return None
    if mag < _SLIGHT_MAX:
        return "slight_right" if right else "slight_left"
    if mag < _NORMAL_MAX:
        return "right" if right else "left"
    if mag < _SHARP_MAX:
        return "sharp_right" if right else "sharp_left"
    return "u_turn"


_LABELS = {
    "slight_left": "Slight left", "slight_right": "Slight right",
    "left": "Left", "right": "Right",
    "sharp_left": "Sharp left", "sharp_right": "Sharp right",
    "u_turn": "U-turn",
}


def detect_turns(coords: list[list[float]]) -> list[dict]:
    """Return course_point dicts [{d_m, lat, lng, type, name}] for each turn.

    coords: [[lng, lat, ele?], ...].
    """
    pts = [c for c in (coords or []) if c and c[0] is not None and c[1] is not None]
    if len(pts) < 3:
        return []

    cum = [0.0]
    for i in range(1, len(pts)):
        cum.append(cum[-1] + _haversine_m(pts[i - 1], pts[i]))

    turns: list[dict] = []
    last_d = -1e9
    for i in range(1, len(pts) - 1):
        before = _point_ahead(pts, i, cum, _LOOK_M, forward=False)
        after = _point_ahead(pts, i, cum, _LOOK_M, forward=True)
        if before == pts[i] or after == pts[i]:
            continue
        delta = _signed_delta(_bearing(before, pts[i]), _bearing(pts[i], after))
        kind = _classify(delta)
        if kind is None:
            continue
        if cum[i] - last_d < _MIN_GAP_M:
            # Keep the sharper of two adjacent detections.
            if turns and abs(delta) > turns[-1]["_mag"]:
                turns[-1] = {"d_m": round(cum[i], 1), "lat": pts[i][1], "lng": pts[i][0],
                             "type": kind, "name": _LABELS.get(kind, "Turn"), "_mag": abs(delta)}
            continue
        turns.append({"d_m": round(cum[i], 1), "lat": pts[i][1], "lng": pts[i][0],
                      "type": kind, "name": _LABELS.get(kind, "Turn"), "_mag": abs(delta)})
        last_d = cum[i]

    for t in turns:
        t.pop("_mag", None)
    return turns


def is_turn_compatible(coords: list[list[float]]) -> tuple[bool, int, str]:
    """Heuristic for the live turn-by-turn check. Returns (compatible, n_turns, reason).

    A track is considered turn-by-turn ready when it has enough length and yields
    at least one genuine turn. The API layer additionally snaps to roads (BRouter)
    before calling this so that a track which doesn't follow the network is caught.
    """
    pts = [c for c in (coords or []) if c and c[0] is not None and c[1] is not None]
    if len(pts) < 3:
        return False, 0, "Track is too short for turn-by-turn."
    turns = detect_turns(pts)
    if not turns:
        return False, 0, "No clear turns — will navigate as a follow-the-line course."
    return True, len(turns), f"Turn-by-turn ready ({len(turns)} turns)."
