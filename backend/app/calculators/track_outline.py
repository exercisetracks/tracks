# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The shape of one activity's track, normalised into a unit square — what a
route thumbnail in the activity list draws.

A port of the phone's ``com.tracks.core.api.TrackShapes.normalise``, so the
two lists draw the same outline for the same activity. Kept to the shape on
purpose: no coordinates, no scale, nothing anyone could navigate by. That is
what lets the browser cache it in ``localStorage`` (frontend
``lib/trackOutlines.js``) where a real track could not go — the server keeps
location encrypted at rest, and a list of everywhere someone has been sitting
in the clear in a browser profile would undo that.
"""

from __future__ import annotations

import math

# A thumbnail box holds nowhere near a thousand distinguishable points, so
# everything past this is drawn on top of itself. Sampled evenly rather than
# simplified properly, as on the phone: at this size nobody can tell.
MAX_POINTS = 96


def normalise(points: list[tuple[float, float]]) -> dict | None:
    """(lon, lat) pairs → ``{"points": [[x, y], …], "inset_x", "inset_y"}``.

    Both axes run 0..1 with the aspect ratio kept; the insets centre the
    shorter axis. Longitude is scaled by cos(latitude), the flat-earth
    approximation that is right enough over one activity. Screen y grows down.
    None for a track with no shape (fewer than two fixes, or never moved).
    """
    if len(points) < 2:
        return None
    mid_lat = sum(p[1] for p in points) / len(points)
    kx = math.cos(math.radians(mid_lat))
    xs = [p[0] * kx for p in points]
    ys = [p[1] for p in points]
    min_x, max_x, min_y, max_y = min(xs), max(xs), min(ys), max(ys)
    span_x, span_y = max_x - min_x, max_y - min_y
    span = max(span_x, span_y)
    if span <= 0:
        return None

    def at(i: int) -> list[float]:
        return [round((xs[i] - min_x) / span, 4), round(1 - (ys[i] - min_y) / span, 4)]

    step = max(1, len(points) // MAX_POINTS)
    out = [at(i) for i in range(0, len(points), step)]
    # Finish on the last fix whatever the stride did, so a loop closes where
    # it actually closed.
    last = at(len(points) - 1)
    if out[-1] != last:
        out.append(last)
    return {
        "points": out,
        "inset_x": round((span - span_x) / span / 2, 4),
        "inset_y": round((span - span_y) / span / 2, 4),
    }
