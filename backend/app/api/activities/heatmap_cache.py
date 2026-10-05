# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Heatmap cache state + builders.

Pre-serialised JSON bytes for the unfiltered (and sport-only) heatmap, keyed by
(user_id, mode, sport). Filled on demand by the route handlers in heatmap.py,
which hold the only key material that can decrypt DataPoint.lat/lng — there's
no background warm-up (see git history for the pre-encryption version): a bare
background thread has no active crypto session and no way to get one, so it
could never decrypt GPS points for even one user, let alone all of them.

Backed by Redis, not an in-process dict — this backend runs as multiple
uvicorn worker processes (UVICORN_WORKERS) plus a separate Celery worker
process (see app.tasks), each with its own memory. A process-local dict
cache was invalidated only in whichever single process happened to run the
invalidating code — every other worker kept serving its own stale (often
empty, from before any data existed) copy forever. Confirmed live: repeated
identical requests alternated between real data and `[]` depending on which
of 4 backend workers happened to handle them, because only one had ever
recomputed since boot. Redis is one shared cache every process reads from
and invalidates together.

Modes:
  frequency               → [[lat, lng], ...]
  pace / heartrate / gradient → [[lat, lng, value], ...]  (speed m/s, bpm, altitude m)
"""

import json
import logging
import math
from itertools import groupby

import redis

from app.models.activity import Activity, DataPoint
from app.services.redis_client import get_redis

from .helpers import _claimed_device_ids

_log = logging.getLogger(__name__)

_HEATMAP_MODES = ("frequency", "pace", "heartrate", "gradient")

_CACHE_PREFIX = "tracks:heatmap:"
# Safety net, not the primary invalidation path (that's invalidate_heatmap_cache,
# called explicitly after every import) — bounds how long a missed invalidation
# could strand a stale entry.
_CACHE_TTL_SECONDS = 7 * 24 * 3600

# Drop GPS fixes closer than this to the previous kept fix.
# At 15 m: running tracks (3 m/s, 1 Hz) shrink ~5×; cycling tracks shrink ~2×.
_SIMPLIFY_MIN_M = 15.0
_SIMPLIFY_THR_SQ = (_SIMPLIFY_MIN_M / 111_000) ** 2  # degrees² (lng scaled per point)


def _cache_key(user_id: int, mode: str, sport: str) -> str:
    # user_id segregation is required for multi-user deployments so one
    # user's tracks never leak into another user's heatmap response.
    return f"{_CACHE_PREFIX}{user_id}:{mode}:{sport}"


def get_cached_heatmap(user_id: int, mode: str, sport: str) -> bytes | None:
    """Returns None on a cache miss OR a Redis error — either way the caller
    just recomputes, so a transient Redis blip degrades to slower responses,
    never a broken one."""
    try:
        return get_redis().get(_cache_key(user_id, mode, sport))
    except redis.RedisError:
        return None


def set_cached_heatmap(user_id: int, mode: str, sport: str, payload: bytes) -> None:
    try:
        get_redis().set(_cache_key(user_id, mode, sport), payload, ex=_CACHE_TTL_SECONDS)
    except redis.RedisError:
        _log.warning("Failed to cache heatmap for user %s — will recompute next request", user_id)


def _simplify_pts(pts: list, min_m: float = _SIMPLIFY_MIN_M) -> list:
    """Remove points closer than `min_m` to the previous kept point."""
    if len(pts) < 2:
        return pts
    thr_sq = _SIMPLIFY_THR_SQ if min_m == _SIMPLIFY_MIN_M else (min_m / 111_000) ** 2
    out  = [pts[0]]
    last = pts[0]
    for p in pts[1:]:
        dlat = p[0] - last[0]
        dlng = (p[1] - last[1]) * math.cos(math.radians(last[0]))
        if dlat * dlat + dlng * dlng >= thr_sq:
            out.append(p)
            last = p
    return out


def spacing_for_zoom(zoom: float) -> float:
    """Minimum useful point spacing, in metres, for a web-mercator zoom level.

    One screen pixel is ~156543/2^z metres at the equator, so points closer
    together than that can't be told apart once drawn. Returned spacing is
    floored at _SIMPLIFY_MIN_M because the stored geometry is already
    simplified to that — asking for finer detail than exists just wastes work.

    Latitude is deliberately ignored (the true figure scales by cos φ, making
    this an over-estimate away from the equator). Erring toward *fewer* points
    is the right bias for a heatmap on a phone, and the caller's viewport spans
    many latitudes anyway.
    """
    return max(_SIMPLIFY_MIN_M, 156_543.0 / (2 ** zoom))


def filter_tracks_bbox(tracks: list, bbox: tuple[float, float, float, float]) -> list:
    """Keep whole tracks that have at least one point inside the bbox.

    Tracks are kept intact rather than clipped: a clipped line would end
    abruptly at the viewport edge, and the client is about to draw it inside a
    clipping viewport regardless. The point of this filter is payload size, not
    geometric precision — the caller's whole GPS history can be tens of MB, and
    a phone on cellular should receive the part it's looking at.

    Points are [lat, lng] or [lat, lng, value]; bbox is (min_lng, min_lat,
    max_lng, max_lat) to match GeoJSON/MapLibre ordering.
    """
    min_lng, min_lat, max_lng, max_lat = bbox
    return [
        t for t in tracks
        if any(min_lat <= p[0] <= max_lat and min_lng <= p[1] <= max_lng for p in t)
    ]


def _value_col(mode: str):
    if mode == "pace":       return DataPoint.speed
    if mode == "heartrate":  return DataPoint.heart_rate
    if mode == "gradient":   return DataPoint.altitude
    return None


def _build_heatmap_bytes(db, mode: str = "frequency", sport: str = "",
                         claimed_ids: list | None = None) -> bytes:
    val_col = _value_col(mode)
    cols = [DataPoint.activity_id, DataPoint.lat, DataPoint.lng]
    if val_col is not None:
        cols.append(val_col)

    q = (
        db.query(*cols)
        .join(Activity, DataPoint.activity_id == Activity.id)
        .filter(DataPoint.lat.isnot(None), DataPoint.lng.isnot(None))
        .order_by(DataPoint.activity_id, DataPoint.recorded_at)
    )
    if val_col is not None:
        q = q.filter(val_col.isnot(None))
    if sport:
        q = q.filter(Activity.sport == sport)
    if claimed_ids is not None:
        q = q.filter(Activity.device_id.in_(claimed_ids))

    rows = q.all()
    tracks = []
    for _, grp in groupby(rows, key=lambda r: r.activity_id):
        grp = list(grp)
        if val_col is not None:
            pts = [[round(r.lat, 4), round(r.lng, 4), round(r[3], 3)] for r in grp]
        else:
            pts = [[round(r.lat, 4), round(r.lng, 4)] for r in grp]
        pts = _simplify_pts(pts)
        if len(pts) >= 2:
            tracks.append(pts)
    return json.dumps(tracks, separators=(",", ":")).encode()


def invalidate_heatmap_cache() -> None:
    """Discard every user's cached heatmap; next unfiltered request per
    (user, mode, sport) recomputes. Global (all users), matching this
    function's existing callers — none of them scope to one user today."""
    try:
        r = get_redis()
        keys = list(r.scan_iter(match=f"{_CACHE_PREFIX}*"))
        if keys:
            r.delete(*keys)
    except redis.RedisError:
        _log.warning("Failed to invalidate heatmap cache — stale entries may be served until their TTL expires")
