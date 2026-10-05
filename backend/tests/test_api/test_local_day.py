# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""An activity belongs to its day in the account's zone, in every figure.

Start times are stored in UTC, and the dashboard, the fitness model and the
coaching load used to take ``started_at.date()``: an 18:04 run in California
(01:04 UTC the next day) was tomorrow's square on the calendar and tomorrow's
load, so today read as a rest day. These pin the local day where each figure
is served, at both ends of the endpoints that have a cached all-time path and
a windowed one. The arithmetic itself is held to the phone's by
spec/fixtures/metrics.json.

Fixed dates throughout, and never ``today``: none of these figures is read
relative to the current date.
"""
from datetime import date, datetime, timezone

from app.api.coaching.helpers import _build_tss_by_date
from app.calculators.local_day import activity_local_date, local_day_start
from app.models.activity import Activity
from app.models.user_settings import UserSettings

# 18:04 PDT on Wednesday 30 September 2026.
EVENING_IN_LA = datetime(2026, 10, 1, 1, 4, 12, tzinfo=timezone.utc)


def _zone(db, user, tz):
    db.query(UserSettings).filter_by(user_id=user.id).one().timezone = tz
    db.commit()


def _run(db, user, started_at, **kw):
    a = Activity(user_id=user.id, device_id=1, sport="running", started_at=started_at,
                 duration_seconds=3600, distance_meters=10000.0, training_stress_score=80.0, **kw)
    db.add(a)
    db.commit()
    return a


class TestTheDashboard:

    def test_an_evening_run_in_california_is_on_its_own_days_square(self, client, user, db):
        _zone(db, user, "America/Los_Angeles")
        _run(db, user, EVENING_IN_LA)
        # The cached all-time slice and the windowed query both.
        assert client.get("/metrics/activity-calendar").json() == [{"date": "2026-09-30", "count": 1}]
        windowed = client.get("/metrics/activity-calendar",
                              params={"after": "2026-09-30", "before": "2026-09-30"}).json()
        assert windowed == [{"date": "2026-09-30", "count": 1}]

    def test_a_window_starts_at_local_midnight(self, client, user, db):
        """A window from the 1st began after the run; one ending the 30th holds it.
        By UTC midnight it was the other way round."""
        _zone(db, user, "America/Los_Angeles")
        _run(db, user, EVENING_IN_LA)
        assert client.get("/metrics/summary", params={"after": "2026-10-01"}).json()["activity_count"] == 0
        assert client.get("/metrics/summary", params={"after": "2026-09-01",
                                                       "before": "2026-09-30"}).json()["activity_count"] == 1

    def test_a_sunday_evening_run_is_in_its_own_week(self, client, user, db):
        """Sunday 4 October, 18:04 PDT, is Monday at 01:04 UTC — the UTC day put
        it in the next week's volume."""
        _zone(db, user, "America/Los_Angeles")
        _run(db, user, datetime(2026, 10, 5, 1, 4, tzinfo=timezone.utc))
        weeks = client.get("/metrics/weekly-volume").json()
        assert [w["week_start"] for w in weeks] == ["2026-09-28"]

    def test_a_morning_run_ahead_of_utc_is_not_the_day_before(self, client, user, db):
        # 07:30 in Sydney on 2 October is 21:30 UTC on the 1st.
        _zone(db, user, "Australia/Sydney")
        _run(db, user, datetime(2026, 10, 1, 21, 30, tzinfo=timezone.utc))
        assert client.get("/metrics/activity-calendar").json() == [{"date": "2026-10-02", "count": 1}]


class TestTrainingLoad:

    def test_an_evening_run_in_california_is_that_days_load(self, client, user, db):
        _zone(db, user, "America/Los_Angeles")
        _run(db, user, EVENING_IN_LA)
        for params in ({}, {"after": "2026-09-01"}):
            points = client.get("/metrics/training-load", params=params).json()
            assert [p["date"] for p in points if p["tss"] > 0] == ["2026-09-30"], params

    def test_the_coaching_load_is_the_same_days(self, user, db):
        """Readiness, the coaching signal and the plan's fitness goals all read
        this one series; the run is the 30th's load there too."""
        _zone(db, user, "America/Los_Angeles")
        _run(db, user, EVENING_IN_LA)
        us = db.query(UserSettings).filter_by(user_id=user.id).one()
        assert list(_build_tss_by_date(db, user.id, us)) == [date(2026, 9, 30)]

    def test_the_per_activity_load_dots_sit_on_the_local_day(self, client, user, db):
        _zone(db, user, "America/Los_Angeles")
        _run(db, user, EVENING_IN_LA)
        dots = client.get("/metrics/activity-load", params={"after": "2026-09-30"}).json()
        assert [d["date"] for d in dots] == ["2026-09-30"]


class TestTheRule:

    def test_an_unknown_zone_reads_as_utc(self):
        """An unreadable setting must not stop a figure, and the phone's
        ZoneOffsets falls back the same way."""
        assert activity_local_date(EVENING_IN_LA, "Mars/Olympus_Mons") == date(2026, 10, 1)
        assert activity_local_date(EVENING_IN_LA, None) == date(2026, 10, 1)

    def test_a_naive_start_is_utc(self):
        assert activity_local_date(datetime(2026, 10, 1, 1, 4), "America/Los_Angeles") == date(2026, 9, 30)

    def test_a_local_day_starts_at_its_own_midnight(self):
        assert local_day_start(date(2026, 9, 30), "America/Los_Angeles") == \
            datetime(2026, 9, 30, 7, 0, tzinfo=timezone.utc)
        assert local_day_start(date(2026, 10, 2), "Australia/Sydney") == \
            datetime(2026, 10, 1, 14, 0, tzinfo=timezone.utc)

    def test_a_day_that_skips_midnight_starts_at_the_change(self):
        """Santiago springs forward at 00:00 on 6 September 2026: the day's
        first instant is 04:00 UTC, which is 01:00 local."""
        start = local_day_start(date(2026, 9, 6), "America/Santiago")
        assert start == datetime(2026, 9, 6, 4, 0, tzinfo=timezone.utc)
        assert activity_local_date(start, "America/Santiago") == date(2026, 9, 6)
