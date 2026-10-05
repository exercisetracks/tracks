# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Geometry helpers for assembling route relations into ordered paths.

OSM relation members are only roughly ordered and individual ways may be
reversed. These helpers chain way geometries into connected runs and measure
true (gap-free) section length for the click-to-detail index.
"""

from __future__ import annotations

import math

from shapely.geometry import LineString


def _ways_to_lines(ways: list[dict]) -> list[LineString]:
    """OSM way elements (with `geometry`) → shapely LineStrings (lon, lat)."""
    out = []
    for w in ways:
        g = w.get("geometry")
        if not g or len(g) < 2:
            continue
        try:
            ls = LineString([(p["lon"], p["lat"]) for p in g])
        except Exception:
            continue
        if not ls.is_empty:
            out.append(ls)
    return out


def _assemble_coords(ways: list[dict]) -> list[list[float]]:
    """Chain way geometries into ordered [lng,lat] paths (connected runs).

    Grows connected chains greedily in BOTH directions (a way joins if either end
    touches the chain head or tail), starting a fresh chain whenever nothing else
    connects — so a fragmented section becomes a few ordered runs rather than the
    whole thing in raw input order (which produced a zig-zag with bogus length +
    elevation gain). The profile builder treats jumps between runs as gaps.
    """
    segs = [[(p["lon"], p["lat"]) for p in (w.get("geometry") or [])] for w in ways]
    segs = [s for s in segs if len(s) >= 2]
    if not segs:
        return []
    TOL = 2e-4  # ~22 m

    def near(a, b):
        return abs(a[0] - b[0]) < TOL and abs(a[1] - b[1]) < TOL

    used = [False] * len(segs)
    coords: list[list[float]] = []
    for start in range(len(segs)):
        if used[start]:
            continue
        used[start] = True
        chain = list(segs[start])
        grew = True
        while grew:
            grew = False
            tail, head = chain[-1], chain[0]
            for i, s in enumerate(segs):
                if used[i]:
                    continue
                if near(s[0], tail):
                    chain.extend(s[1:])
                elif near(s[-1], tail):
                    chain.extend(reversed(s[:-1]))
                elif near(s[-1], head):
                    chain[:0] = s[:-1]
                elif near(s[0], head):
                    chain[:0] = list(reversed(s[1:]))
                else:
                    continue
                used[i] = True
                grew = True
                break
        coords.extend([x, y] for x, y in chain)
    return [[round(x, 6), round(y, 6)] for x, y in coords]


def _coords_length_m(coords: list[list[float]]) -> float:
    """Length of a [lng,lat] polyline in metres."""
    tot = 0.0
    for i in range(1, len(coords)):
        ax, ay = coords[i - 1]
        bx, by = coords[i]
        dx = (bx - ax) * math.cos(math.radians((ay + by) / 2)) * 111320.0
        dy = (by - ay) * 110540.0
        tot += math.hypot(dx, dy)
    return tot


def _section_distance_m(ways: list[dict]) -> float:
    """True section length = sum of each member way's own length (no gap-jumps,
    unlike summing along the greedily-assembled chain)."""
    tot = 0.0
    for w in ways:
        g = w.get("geometry") or []
        if len(g) >= 2:
            tot += _coords_length_m([[p["lon"], p["lat"]] for p in g])
    return tot
