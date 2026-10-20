# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Health page's daily rows: derived in SQL, then cached.

See app.services.health_summary for why. The two risks are that the fast path
quietly disagrees with the model's own derivations, and that the cache serves a
page that no longer matches the data.
"""
import uuid
from datetime import date, datetime, timedelta, timezone

from app.models.metrics import DailyMetric
from app.schemas.metrics import DailyMetricOut
from app.services import health_summary

_LONG_AGO = date(2000, 1, 1)


def _day(db, user, offset, **fields):
    row = DailyMetric(
        date=date.today() - timedelta(days=offset), user_id=user.id,
        uid=str(uuid.uuid4()), **fields,
    )
    db.add(row)
    db.flush()
    return row


def test_the_sql_derivations_match_the_models_own(db, user):
    """The properties on DailyMetric are the definition; the query is only a
    faster way of asking. Odd shapes are included on purpose — a half-rounded
    average, a point that is not a number, a malformed series, a night with no
    timeline — because those are where two implementations drift apart."""
    _day(db, user, 1, extra={
        "hr_series": [[0, 61], [1, 62], [2, 62], [3, 63.9], [4, "x"], [5, None], 7, [6]],
        "sleep_stages": [
            {"start": "2026-08-29T06:16:00+00:00", "end": "2026-08-29T07:10:00+00:00"},
            {"start": "2026-08-29T07:10:00+00:00", "end": "2026-08-29T13:50:00+00:00"},
        ],
    })
    _day(db, user, 2, extra={"hr_series": [[0, 75], [1, 76], [2, 75], [3, 75], [4, 75]]})
    _day(db, user, 3, extra={"hr_series": "garbage", "sleep_stages": ["not a dict"]})
    _day(db, user, 4, extra={})
    _day(db, user, 5, sleep_hours=7.0)

    fast = [DailyMetricOut(**r).model_dump(mode="json")
            for r in health_summary._fetch(db, user.id, _LONG_AGO)]
    slow = [DailyMetricOut.model_validate(r).model_dump(mode="json")
            for r in db.query(DailyMetric).filter_by(user_id=user.id).order_by(DailyMetric.date)]

    assert fast == slow


def test_a_second_read_is_served_from_the_cache(db, user, monkeypatch):
    _day(db, user, 1, steps=100)
    first = health_summary.summary_json(db, user.id, _LONG_AGO)

    def boom(*a, **kw):
        raise AssertionError("went back to the database")

    monkeypatch.setattr(health_summary, "_fetch", boom)

    assert health_summary.summary_json(db, user.id, _LONG_AGO) == first


def test_an_edit_to_a_day_is_not_served_stale(db, user):
    """Writers do not tell the cache anything; the fingerprint is what notices."""
    row = _day(db, user, 1, steps=100)
    row.updated_at = datetime.now(timezone.utc) - timedelta(hours=1)
    db.flush()
    health_summary.summary_json(db, user.id, _LONG_AGO)

    row.steps = 250
    row.updated_at = datetime.now(timezone.utc)
    db.flush()

    assert b'"steps":250' in health_summary.summary_json(db, user.id, _LONG_AGO)


def test_a_new_day_is_not_served_stale(db, user):
    _day(db, user, 2, steps=1)
    health_summary.summary_json(db, user.id, _LONG_AGO)

    _day(db, user, 1, steps=2)

    assert health_summary.summary_json(db, user.id, _LONG_AGO).count(b'"date"') == 2


def test_one_users_page_is_never_another_users(db, user):
    from app.models.activity import User
    other = User(name="Other")
    db.add(other)
    db.flush()
    _day(db, user, 1, steps=111)
    _day(db, other, 1, steps=222)

    mine = health_summary.summary_json(db, user.id, _LONG_AGO)
    theirs = health_summary.summary_json(db, other.id, _LONG_AGO)

    assert b"111" in mine and b"222" not in mine
    assert b"222" in theirs and b"111" not in theirs


def test_the_window_is_honoured(db, user):
    _day(db, user, 1, steps=1)
    _day(db, user, 40, steps=2)

    since = date.today() - timedelta(days=29)

    assert health_summary.summary_json(db, user.id, since).count(b'"date"') == 1


def test_the_endpoint_returns_the_rows_as_json(db, user, client):
    _day(db, user, 1, steps=321, extra={"hr_series": [[0, 60], [1, 70]]})
    db.commit()

    body = client.get("/health/summary?days=30").json()

    assert body[-1]["steps"] == 321
    assert (body[-1]["min_hr"], body[-1]["max_hr"], body[-1]["avg_hr"]) == (60, 70, 65.0)
