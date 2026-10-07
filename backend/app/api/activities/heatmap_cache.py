# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Heatmap cache state + builders.

Two layers, both per user:

**Base** — per mode, every track the user has, each tagged with its
activity's start time and sport: `[[started_utc_iso, sport, track], ...]`.
Built for all four modes in one pass over the data points (see
build_heatmap_bases), because the cost is decrypting lat/lng, and building the
modes separately decrypted every point four times — which the dashboard's
background prefetch of the other modes did on every cold load. Any sport and
date window is then a filter over a base, so a windowed request is served from
cache too. It used to be computed from scratch, which, once the dashboard
heatmap began following the period pills, was every dashboard load.

**Response** — the finished JSON bytes for one (mode, sport, after, before),
so a repeat request costs one Redis read rather than parsing the base again.

Filled on demand by the route handlers in heatmap.py,
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

Modes (each track):
  frequency               → [[lat, lng], ...]
  pace / heartrate / gradient → [[lat, lng, value], ...]  (speed m/s, bpm, altitude m)
"""

import json
import logging
import math
import time
from datetime import date, timedelta, timezone
from itertools import groupby

import redis

from app.models.activity import Activity, DataPoint
from app.services.redis_client import get_redis


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


def _base_key(user_id: int, mode: str) -> str:
    # user_id segregation is required for multi-user deployments so one
    # user's tracks never leak into another user's heatmap response.
    return f"{_CACHE_PREFIX}{user_id}:base:{mode}"


def _response_key(user_id: int, mode: str, sport: str,
                  after: date | None, before: date | None) -> str:
    return f"{_CACHE_PREFIX}{user_id}:resp:{mode}:{sport}:{after or ''}:{before or ''}"


def _build_lock_key(user_id: int) -> str:
    return f"{_CACHE_PREFIX}{user_id}:building"


# How long a cold build may hold the lock, and how long another request waits
# on it before giving up and building for itself. The lock is an optimisation,
# never a correctness requirement, so it expires rather than risk a stuck one.
_BUILD_LOCK_SECONDS = 120
_BUILD_WAIT_SECONDS = 60


def _redis_get(key: str) -> bytes | None:
    """None on a miss OR a Redis error — either way the caller computes, so a
    transient Redis blip degrades to slower responses, never a broken one."""
    try:
        return get_redis().get(key)
    except redis.RedisError:
        return None


def _redis_set(key: str, payload: bytes) -> None:
    try:
        get_redis().set(key, payload, ex=_CACHE_TTL_SECONDS)
    except redis.RedisError:
        _log.warning("Failed to cache heatmap entry %s — will recompute next request", key)


def get_cached_response(user_id: int, mode: str, sport: str,
                        after: date | None, before: date | None) -> bytes | None:
    return _redis_get(_response_key(user_id, mode, sport, after, before))


def set_cached_response(user_id: int, mode: str, sport: str,
                        after: date | None, before: date | None, payload: bytes) -> None:
    _redis_set(_response_key(user_id, mode, sport, after, before), payload)


def _tracks_geojson_key(user_id: int, sport: str, limit: int) -> str:
    return f"{_CACHE_PREFIX}{user_id}:geojson:{sport}:{limit}"


def get_cached_tracks_geojson(user_id: int, sport: str, limit: int) -> bytes | None:
    return _redis_get(_tracks_geojson_key(user_id, sport, limit))


def set_cached_tracks_geojson(user_id: int, sport: str, limit: int, payload: bytes) -> None:
    _redis_set(_tracks_geojson_key(user_id, sport, limit), payload)


def heatmap_base(db, user_id: int, mode: str, claimed_ids: list) -> list:
    """The base for `mode`, from cache or built (all modes at once) and cached.

    A cold dashboard asks for one mode and, moments later, prefetches the
    other three; without the lock each of those would start its own full
    build. The first takes the lock and builds; the rest wait for its result,
    and build for themselves only if it never comes.
    """
    cached = _redis_get(_base_key(user_id, mode))
    if cached is not None:
        return json.loads(cached)

    lock = _build_lock_key(user_id)
    try:
        holder = bool(get_redis().set(lock, b"1", nx=True, ex=_BUILD_LOCK_SECONDS))
    except redis.RedisError:
        holder = True
    if not holder:
        deadline = time.monotonic() + _BUILD_WAIT_SECONDS
        while time.monotonic() < deadline:
            time.sleep(0.25)
            cached = _redis_get(_base_key(user_id, mode))
            if cached is not None:
                return json.loads(cached)

    try:
        bases = build_heatmap_bases(db, claimed_ids)
        for m, base in bases.items():
            _redis_set(_base_key(user_id, m), json.dumps(base, separators=(",", ":")).encode())
    finally:
        if holder:
            try:
                get_redis().delete(lock)
            except redis.RedisError:
                pass
    return bases[mode]


def filter_base(base: list, sport: str, after: date | None, before: date | None) -> list:
    """The tracks in a base that match a sport and an inclusive date window.

    Start times are UTC ISO text, which compares in time order, and a bare
    date sorts before every time on that day — the same edges as the SQL this
    replaced (`started_at >= after`, `started_at < before + 1 day`). An
    activity with no start time never matches a window, as NULL never matched
    those comparisons.
    """
    lo = after.isoformat() if after else None
    hi = (before + timedelta(days=1)).isoformat() if before else None
    out = []
    for started, s, track in base:
        if sport and s != sport:
            continue
        if (lo or hi) and not started:
            continue
        if lo and started < lo:
            continue
        if hi and started >= hi:
            continue
        out.append(track)
    return out


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


def _utc_iso(dt) -> str:
    if dt is None:
        return ""
    if dt.tzinfo is not None:
        dt = dt.astimezone(timezone.utc).replace(tzinfo=None)
    return dt.isoformat(timespec="seconds")


# Rows per fetch while streaming data points. The whole history at once is
# every GPS point the user owns held in memory as ORM rows; streaming keeps
# only one batch, and grouping still works because the query is ordered.
_STREAM_BATCH = 50_000


def build_heatmap_bases(db, claimed_ids: list) -> dict[str, list]:
    """Every mode's base from one pass over the user's GPS points.

    Points without a mode's value are dropped from that mode before
    simplifying, as the per-mode queries this replaced filtered them in SQL —
    a stretch with no heart rate is a gap, not a guess.
    """
    bases: dict[str, list] = {m: [] for m in _HEATMAP_MODES}
    if not claimed_ids:
        return bases

    acts = {
        a.id: (_utc_iso(a.started_at), (a.sport or "").strip().lower())
        for a in db.query(Activity.id, Activity.started_at, Activity.sport)
        .filter(Activity.device_id.in_(claimed_ids))
    }
    q = (
        db.query(DataPoint.activity_id, DataPoint.lat, DataPoint.lng,
                 DataPoint.speed, DataPoint.heart_rate, DataPoint.altitude)
        .join(Activity, DataPoint.activity_id == Activity.id)
        .filter(DataPoint.lat.isnot(None), DataPoint.lng.isnot(None))
        .filter(Activity.device_id.in_(claimed_ids))
        .order_by(DataPoint.activity_id, DataPoint.recorded_at)
        .yield_per(_STREAM_BATCH)
    )
    value_index = {"pace": 3, "heartrate": 4, "gradient": 5}
    for aid, grp in groupby(q, key=lambda r: r.activity_id):
        grp = list(grp)
        started, sport = acts.get(aid, ("", ""))
        pts = _simplify_pts([[round(r.lat, 4), round(r.lng, 4)] for r in grp])
        if len(pts) >= 2:
            bases["frequency"].append([started, sport, pts])
        for mode, i in value_index.items():
            vpts = _simplify_pts([[round(r.lat, 4), round(r.lng, 4), round(r[i], 3)]
                                  for r in grp if r[i] is not None])
            if len(vpts) >= 2:
                bases[mode].append([started, sport, vpts])
    return bases


def invalidate_heatmap_cache(user_id: int | None = None) -> None:
    """Discard cached heatmaps — bases, responses and track GeoJSON alike; the
    next request rebuilds. Every user's when `user_id` is None (an import or a
    device claim, whose callers do not scope), else just that user's (an edit
    to one activity)."""
    pattern = f"{_CACHE_PREFIX}{user_id}:*" if user_id is not None else f"{_CACHE_PREFIX}*"
    try:
        r = get_redis()
        keys = list(r.scan_iter(match=pattern))
        if keys:
            r.delete(*keys)
    except redis.RedisError:
        _log.warning("Failed to invalidate heatmap cache — stale entries may be served until their TTL expires")
