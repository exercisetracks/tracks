# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The calendar day an activity belongs to: the day in the account's zone.

Start times are stored in UTC, so ``started_at.date()`` is the UTC day, and an
18:04 run in California is 01:04 UTC the next day. Every figure that buckets
activities by day — the dashboard calendar and weekly volume, the fitness
model's daily load, readiness, the coaching signal, the plan's recent volume —
took that UTC day, so an evening session was counted tomorrow: today's load
read as a rest day and the next morning's form was a day out. Matching was
fixed first (2026-09-30); this is the one helper the rest of the server shares
with it, so no two figures can disagree about which day something happened.

The zone is the account's ``user_settings.timezone``, which the phone keeps in
step with where it is (mobile TimezoneSync). An unknown zone falls back to UTC
rather than failing the figure, and a naive datetime is taken as UTC, which is
how they are stored. The phone's copy is ``com.tracks.core.plan.Matching.localDate``
(with ``com.tracks.core.time.ZoneOffsets``), held to this by the ``local_dates``
cases in spec/fixtures/matching.json and the zoned histories in metrics.json.
"""

from __future__ import annotations

from datetime import date, datetime, time, timezone
from functools import lru_cache
from types import SimpleNamespace
from zoneinfo import ZoneInfo


@lru_cache(maxsize=64)
def _zone(tz_name: str | None):
    try:
        return ZoneInfo(tz_name) if tz_name else timezone.utc
    except Exception:
        return timezone.utc


def activity_local_date(started_at: datetime, tz_name: str | None) -> date:
    """The calendar day an activity happened on, in the account's time zone.

    Not ``started_at.date()``: start times are stored in UTC, so anything begun
    after 17:00 in California landed on tomorrow — an evening run ticked off
    the next day's workout and left its own day's unticked (found 2026-09-30, a
    run at 18:04 PDT).
    """
    if started_at.tzinfo is None:
        started_at = started_at.replace(tzinfo=timezone.utc)
    return started_at.astimezone(_zone(tz_name)).date()


def local_day_start(day: date, tz_name: str | None) -> datetime:
    """The instant ``day`` begins in the account's zone, for a range filter.

    A window "from 1 October" compared against ``started_at`` with a bare date
    starts at midnight UTC, which would drop the first evening of a window that
    the day buckets above now count, and keep one that belongs to the day
    before. On a day that skips midnight (a DST change at 00:00) this is the
    instant of the change, the first that day has: ZoneInfo reads a missing
    local time at the offset before the gap.
    """
    return datetime.combine(day, time(0), tzinfo=_zone(tz_name)).astimezone(timezone.utc)


def local_history(rows, tz_name: str | None) -> list[SimpleNamespace]:
    """Activity rows as the plan and coaching calculators read them: the start
    as the local *day*.

    Those calculators take a date in ``started_at`` already (``hasattr(...,
    "date")`` in plan/base.py, multisport.py and coaching/context.py), as their
    fixtures and the phone's ports do (``HistoryActivity.startedAt``,
    ``CoachingActivity.startedAt`` are dates). Converting once here, where the
    rows are loaded, keeps the zone out of every generator's signature. Only
    the fields those calculators read are carried.
    """
    return [
        SimpleNamespace(
            id=getattr(r, "id", None),
            sport=r.sport,
            started_at=activity_local_date(r.started_at, tz_name) if r.started_at else None,
            distance_meters=r.distance_meters,
            duration_seconds=r.duration_seconds,
        )
        for r in rows
    ]


def account_today(tz_name: str | None, now: datetime | None = None) -> date:
    """Today, in the account's zone.

    Not ``date.today()``: that is the server's day, and the server runs in UTC,
    so from 17:00 in California onward it already said tomorrow — the web
    showed tomorrow as today, today's recommended workout was tomorrow's, and a
    dose taken that evening was filed a day late (found 2026-10-06, 20:30 PDT).
    ``now`` is for tests.
    """
    return (now or datetime.now(timezone.utc)).astimezone(_zone(tz_name)).date()


def user_zone(db, user_id: int) -> str | None:
    """The account's zone name from its settings; None when never set."""
    from app.models.user_settings import UserSettings

    return db.query(UserSettings.timezone).filter(UserSettings.user_id == user_id).scalar()


def user_today(db, user_id: int) -> date:
    """[account_today] for a user, reading their zone from their settings."""
    return account_today(user_zone(db, user_id))


def local_moment(day: date, wall: time, tz_name: str | None) -> datetime:
    """The instant a wall-clock time on ``day`` happens in the account's zone.

    For a schedule's "08:00": eight in the morning where the account is, not
    08:00 UTC — which is 01:00 in California, when a dose reminder fired.
    """
    return datetime.combine(day, wall, tzinfo=_zone(tz_name))
