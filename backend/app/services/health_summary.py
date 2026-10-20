# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Health page's daily rows, read without the minute-level series and kept
in Redis between requests.

Two things made the page slow once there were years of data, and they are
fixed separately.

**The query read far more than it returned.** Each day's `extra` holds its
all-day heart-rate and stress series, minutes of them; five years of that is
tens of megabytes, all of it fetched, decoded and then thrown away, because
the response carries only a handful of numbers read off it (average, lowest
and highest heart rate; when the night began and ended). The model derives
those in Python (`DailyMetric.avg_hr` and friends), which needs the whole
document. Here Postgres derives them instead and `extra` never leaves the
database. `tests/test_health_summary.py` holds the two derivations to the same
answer, so the properties on the model stay the definition and this is only a
faster way of asking.

**The answer was recomputed on every visit.** It is cached as serialised
bytes. Unlike the dashboard caches (app.api.metrics.caching) it is not
invalidated by the code that writes, because daily metrics are written from
many places — the FIT importers, phone sync, manual entries, re-parses — and
a missed one would serve a stale page until the TTL. Instead each entry is
stamped with a cheap fingerprint of the rows it was built from (how many and
the newest `updated_at`) and is used only while the fingerprint still matches.
The fingerprint reads two indexed columns, so a hit costs one small query and
a Redis GET. A write that bypasses `updated_at` (raw SQL) would not be seen
until the TTL; nothing here does that.
"""

import json
import logging
from datetime import date

import redis
from sqlalchemy import func, text
from sqlalchemy.orm import Session

from app.models.metrics import DailyMetric
from app.schemas.metrics import DailyMetricOut
from app.services.redis_client import get_redis

_log = logging.getLogger(__name__)

_PREFIX = "tracks:health-summary:"
# Safety net only: the fingerprint is what keeps entries honest.
_TTL_SECONDS = 24 * 3600

# Read off `extra` rather than stored — see the module docstring.
_DERIVED = ("sleep_start", "sleep_end", "avg_hr", "min_hr", "max_hr")
_STORED = [name for name in DailyMetricOut.model_fields if name not in _DERIVED]

# Mirrors DailyMetric._hr_values: each point is `[minute, bpm]`, the bpm is
# truncated to an int, and a point that is not a number is skipped rather than
# failing the day. The average is taken over the truncated
# values, as the property does — in Python, from the sum and count, because
# Postgres rounds halves up and Python rounds them to even, which put one day's
# average a tenth apart. The number test is a CASE inside the select
# list, not a WHERE beside it, because Postgres is free to evaluate the cast
# on a row the WHERE would have removed.
_SQL = text(f"""
    SELECT {", ".join(f"m.{c}" for c in _STORED)},
           m.extra -> 'sleep_stages' -> 0  ->> 'start' AS sleep_start,
           m.extra -> 'sleep_stages' -> -1 ->> 'end'   AS sleep_end,
           sum(hr.bpm)           AS hr_sum,
           count(hr.bpm)         AS hr_n,
           min(hr.bpm)           AS min_hr,
           max(hr.bpm)           AS max_hr
    FROM daily_metrics m
    LEFT JOIN LATERAL (
        SELECT CASE WHEN jsonb_typeof(p.value) = 'array'
                     AND jsonb_typeof(p.value -> 1) = 'number'
                    THEN trunc((p.value ->> 1)::numeric)::int END AS bpm
        FROM jsonb_array_elements(
            CASE WHEN jsonb_typeof(m.extra -> 'hr_series') = 'array'
                 THEN m.extra -> 'hr_series' ELSE '[]'::jsonb END
        ) AS p(value)
    ) hr ON true
    WHERE m.user_id = :user_id AND m.date >= :since
    GROUP BY m.id
    ORDER BY m.date
""")


def _fetch(db: Session, user_id: int, since: date) -> list[dict]:
    rows = db.execute(_SQL, {"user_id": user_id, "since": since})
    out = []
    for r in rows:
        row = dict(r._mapping)
        n, total = row.pop("hr_n"), row.pop("hr_sum")
        row["avg_hr"] = round(int(total) / n, 1) if n else None
        out.append(row)
    return out


def _fingerprint(db: Session, user_id: int, since: date) -> str:
    count, newest = db.query(
        func.count(DailyMetric.id), func.max(DailyMetric.updated_at),
    ).filter(DailyMetric.user_id == user_id, DailyMetric.date >= since).one()
    return f"{count}:{newest.isoformat() if newest else ''}"


def _key(user_id: int, since: date) -> str:
    # `since` moves with the calendar, so yesterday's entry is simply never
    # asked for again and ages out by TTL.
    return f"{_PREFIX}{user_id}:{since.isoformat()}"


def summary_json(db: Session, user_id: int, since: date) -> bytes:
    """The days from `since` on, as the serialised JSON the endpoint returns."""
    key = _key(user_id, since)
    stamp = _fingerprint(db, user_id, since)
    try:
        cached = get_redis().get(key)
    except redis.RedisError:
        cached = None
    if cached:
        cached_stamp, _, body = cached.partition(b"\n")
        if cached_stamp.decode() == stamp:
            return body

    # Through the schema, so the cached bytes are exactly what the endpoint
    # would have produced uncached (dates, timestamps, nulls).
    body = json.dumps(
        [DailyMetricOut(**row).model_dump(mode="json") for row in _fetch(db, user_id, since)],
        separators=(",", ":"),
    ).encode()
    try:
        get_redis().set(key, stamp.encode() + b"\n" + body, ex=_TTL_SECONDS)
    except redis.RedisError:
        _log.warning("Failed to cache health summary %s — will recompute next request", key)
    return body
