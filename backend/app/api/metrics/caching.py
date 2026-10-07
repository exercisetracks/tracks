# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-user caches for the all-time training-load and dashboard slices.

Both caches store pre-serialised JSON bytes keyed by ``(user_id, slot)`` so the
hot unfiltered dashboard/fitness requests skip recomputation and re-serialisation
entirely. ``user_id`` segregation is MANDATORY: without it a freshly created user
receives the previous user's cached metrics on first load (the original
multi-user bug).

Slots:
    training-load:  "all_time"
    dashboard:      "summary" | "by_sport" | "activity_calendar"
                    | "vo2max_history" | "weekly_volume"

Backed by Redis, not an in-process dict — this backend runs as multiple
uvicorn worker processes (UVICORN_WORKERS) plus a separate Celery worker
process (see app.tasks), each with its own memory. A process-local dict
cache here was invalidated only in whichever single process happened to run
the invalidating code, leaving every other worker to keep serving its own
stale copy indefinitely (same bug independently confirmed live in the
heatmap cache — see heatmap_cache.py's docstring). The warm_* functions
still run in a background thread (called at startup on the primary
worker), but now populate the one shared Redis cache every process reads
from, instead of a cache only that one process could ever see.
"""

from datetime import datetime, timezone
import json
import logging
import threading

import redis
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.calculators.mtb import active_mtb_discipline
from app.calculators import dashboard_stats
from app.models.activity import Activity, User, UserDevice
from app.services.redis_client import get_redis

from .helpers import (
    _claimed_device_ids,
    _compute_tload_points,
    _effective_threshold_hr,
    _get_user_settings,
    _account_zone,
    _slim_activity_rows,
)

_log = logging.getLogger(__name__)

_TLOAD_PREFIX = "tracks:tload:"
_DASH_PREFIX  = "tracks:dash:"
# Safety net, not the primary invalidation path (that's the invalidate_*
# functions, called explicitly after every write) — bounds how long a missed
# invalidation could strand a stale entry.
_CACHE_TTL_SECONDS = 7 * 24 * 3600

# Compact JSON separators reused for every cached payload.
_JSON_SEP = (",", ":")


def _all_user_ids(db: Session) -> list[int]:
    """Every user id — cache warmers iterate this, giving each user a private slice."""
    return [u.id for u in db.query(User.id).all()]


def _get_cached(key: str) -> bytes | None:
    try:
        return get_redis().get(key)
    except redis.RedisError:
        return None


def _set_cached(key: str, value: bytes) -> None:
    try:
        get_redis().set(key, value, ex=_CACHE_TTL_SECONDS)
    except redis.RedisError:
        _log.warning("Failed to write cache key %s — will recompute next request", key)


def _invalidate_prefix(prefix: str) -> None:
    try:
        r = get_redis()
        keys = list(r.scan_iter(match=f"{prefix}*"))
        if keys:
            r.delete(*keys)
    except redis.RedisError:
        _log.warning("Failed to invalidate cache prefix %s — stale entries may be served until their TTL expires", prefix)


# ── Training-load (all-time) cache ─────────────────────────────────────────────

def _tload_key(user_id: int) -> str:
    # The series runs through today, so a copy cached yesterday ends a day
    # short: the UTC date in the key retires it at the next day's first read.
    # (UTC rather than the account's zone keeps this a pure key lookup; a
    # zone ahead of UTC sees yesterday's series for at most its offset, and
    # the windowed path, which the dashboard's periods use, has no cache.)
    return f"{_TLOAD_PREFIX}{user_id}:all_time:{datetime.now(timezone.utc).date().isoformat()}"


def get_cached_tload(user_id: int) -> bytes | None:
    return _get_cached(_tload_key(user_id))


def set_cached_tload(user_id: int, data: bytes) -> None:
    _set_cached(_tload_key(user_id), data)


def _build_tload_bytes(db: Session, user_id: int) -> bytes:
    """Compute this user's all-time CTL/ATL/TSB series as serialised JSON bytes."""
    us = _get_user_settings(db, user_id)
    threshold_hr = _effective_threshold_hr(us)
    rows = _slim_activity_rows(db, user_id, us)
    points = _compute_tload_points(rows, threshold_hr, active_mtb_discipline(db, user_id),
                                   tz_name=_account_zone(us))
    serializable = [{**p, "date": p["date"].isoformat()} for p in points]
    return json.dumps(serializable, separators=_JSON_SEP).encode()


def warm_training_load_cache() -> None:
    """Pre-compute the all-time training-load for every user in the background."""
    def _warm():
        from app.database import SessionLocal
        try:
            db = SessionLocal()
            try:
                user_ids = _all_user_ids(db)
                for uid in user_ids:
                    set_cached_tload(uid, _build_tload_bytes(db, uid))
            finally:
                db.close()
            _log.info("Training-load cache ready — %d users", len(user_ids))
        except Exception:
            _log.exception("Training-load cache warm-up failed")
    threading.Thread(target=_warm, daemon=True, name="tload-warm").start()


def invalidate_training_load_cache() -> None:
    _invalidate_prefix(_TLOAD_PREFIX)


# ── Dashboard slices cache ─────────────────────────────────────────────────────

_DASH_SLOTS = ("summary", "by_sport", "activity_calendar", "vo2max_history", "weekly_volume")


def _dash_key(user_id: int, slot: str) -> str:
    return f"{_DASH_PREFIX}{user_id}:{slot}"


def get_cached_dash_slice(user_id: int, slot: str) -> bytes | None:
    return _get_cached(_dash_key(user_id, slot))


def _build_and_store_dash_cache(db, user_id: int, us) -> None:
    """Compute and store all dashboard slices for one user.

    The arithmetic lives in app.calculators.dashboard_stats, shared with the
    filtered endpoints and replayed by the phone's port; this only selects rows.
    """
    q = db.query(Activity).filter(Activity.started_at.isnot(None))
    q = q.filter(Activity.device_id.in_(_claimed_device_ids(db, user_id)))
    rows = q.with_entities(
        Activity.started_at,
        Activity.sport,
        Activity.distance_meters,
        Activity.duration_seconds,
    ).order_by(Activity.started_at, Activity.id).all()

    device_count = (
        db.query(func.count(UserDevice.device_id))
        .filter(UserDevice.user_id == user_id)
        .scalar() or 0
    )

    # vo2max history is filtered to THIS user's activities only, not by device.
    vo2max_rows = (
        db.query(Activity.started_at, Activity.vo2max_estimate, Activity.sport,
                 Activity.distance_meters, Activity.duration_seconds)
        # Runs too, for the pace-based estimate when no device reports one
        # (dashboard_stats.vo2max_history).
        .filter(Activity.user_id == user_id,
                or_(Activity.vo2max_estimate.isnot(None), Activity.sport.ilike("%run%")))
        .order_by(Activity.started_at)
        .all()
    )

    tz = _account_zone(us)
    slices = {
        "summary":           dashboard_stats.summary(rows, device_count),
        "by_sport":          dashboard_stats.by_sport(rows),
        "activity_calendar": dashboard_stats.activity_calendar(rows, tz),
        "vo2max_history":    dashboard_stats.vo2max_history(vo2max_rows, tz),
        "weekly_volume":     dashboard_stats.weekly_volume(rows, tz),
    }
    for slot, value in slices.items():
        set_cached_dash_slice(user_id, slot, json.dumps(value, separators=_JSON_SEP).encode())


def set_cached_dash_slice(user_id: int, slot: str, data: bytes) -> None:
    _set_cached(_dash_key(user_id, slot), data)


def warm_dashboard_cache() -> None:
    """Pre-compute dashboard slices for every user in the background."""
    def _warm():
        from app.database import SessionLocal
        try:
            db = SessionLocal()
            try:
                for uid in _all_user_ids(db):
                    us = _get_user_settings(db, uid)
                    _build_and_store_dash_cache(db, uid, us)
            finally:
                db.close()
            _log.info("Dashboard cache ready")
        except Exception:
            _log.exception("Dashboard cache warm-up failed")
    threading.Thread(target=_warm, daemon=True, name="dash-warm").start()


def invalidate_dashboard_cache() -> None:
    _invalidate_prefix(_DASH_PREFIX)


def _serve_dash_slice(db, user, slot: str) -> bytes:
    """Return cached bytes for a dashboard ``slot``, building this user's slices on a miss."""
    cached = get_cached_dash_slice(user.id, slot)
    if cached is not None:
        return cached
    us = _get_user_settings(db, user.id)
    _build_and_store_dash_cache(db, user.id, us)
    # Redis write inside _build_and_store_dash_cache may have failed (transient
    # outage) — degrade to null rather than 500 either way, matching the
    # cache-miss-tolerant design of get_cached_dash_slice/set_cached_dash_slice.
    return get_cached_dash_slice(user.id, slot) or b"null"
