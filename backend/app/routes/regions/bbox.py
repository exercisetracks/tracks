# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Request validation shared by the region endpoints.

A bounding box arrives either as a JSON array (download body) or a comma string
(query params); both funnel through `parse_bbox`, which is also the trust
boundary before these coordinates reach go-pmtiles / osmium subprocess arguments.
"""

from fastapi import HTTPException

# Region names are user-supplied free text; cap the length before it reaches the
# DB / geocoder so an oversized value can't bloat a row or a log line.
MAX_NAME_LEN = 200


def parse_bbox(raw) -> list[float]:
    """Validate a request bbox → [west, south, east, north] of floats.

    Accepts a JSON array (download body) or a comma string (query params). Rejects
    anything that isn't four in-range, correctly-ordered numbers — these values
    flow into go-pmtiles/osmium subprocess arguments and the build, so they must be
    clean numerics (defence in depth; subprocesses are already argv-list, not shell).
    """
    parts = raw.split(",") if isinstance(raw, str) else (raw or [])
    if not isinstance(parts, (list, tuple)) or len(parts) != 4:
        raise HTTPException(400, "bbox must be west,south,east,north")
    try:
        w, s, e, n = (float(p) for p in parts)
    except (TypeError, ValueError):
        raise HTTPException(400, "bbox values must be numbers")
    if not (-180 <= w <= 180 and -180 <= e <= 180 and -90 <= s <= 90 and -90 <= n <= 90):
        raise HTTPException(400, "bbox out of range (lng ±180, lat ±90)")
    if w >= e or s >= n:
        raise HTTPException(400, "bbox must have west<east and south<north")
    return [w, s, e, n]
