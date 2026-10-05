# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The stress curve, and the night's clock, as clients actually read them.

Both are answers to the same complaint about the Health page: it was drawing
one point per day for a quantity that moves all day, and it was drawing a run
of nights as durations with no notion of *when*. The data for both was already
in the files and already being discarded — stress at the point of taking the
day's mean, sleep timing at the point of totalling the stages.
"""

from datetime import date, timedelta

from app.models.metrics import DailyMetric


def _day(db, user, on, **kwargs):
    row = DailyMetric(user_id=user.id, date=on, **kwargs)
    db.add(row)
    db.commit()
    return row


class TestStressDetail:
    def test_a_day_of_readings_comes_back_in_order(self, client, user, db):
        _day(db, user, date.today(), avg_stress_level=40.0, extra={
            "stress_series": [[60, 30], [63, 28], [1200, 71]],
        })

        body = client.get("/health/stress").json()

        assert len(body) == 1
        assert body[0]["date"] == date.today().isoformat()
        assert body[0]["points"] == [[60, 30], [63, 28], [1200, 71]]

    def test_a_day_with_no_curve_is_absent_rather_than_empty(self, client, user, db):
        # Absence of a curve and a flat curve are different things, and every
        # day recorded before the parser kept the series is the former. A row
        # of empty points would draw as a day of zero stress.
        _day(db, user, date.today(), avg_stress_level=40.0)

        assert client.get("/health/stress").json() == []

    def test_the_window_is_honoured(self, client, user, db):
        today = date.today()
        for offset in (0, 5, 20):
            _day(db, user, today - timedelta(days=offset),
                 extra={"stress_series": [[60, 30 + offset]]})

        body = client.get("/health/stress", params={
            "after": (today - timedelta(days=7)).isoformat(),
        }).json()

        assert [row["date"] for row in body] == [
            (today - timedelta(days=5)).isoformat(),
            today.isoformat(),
        ]

    def test_a_lifetime_ask_is_capped_rather_than_served(self, client, user, db):
        # The curve is three-minute samples. A year of it is a solid block of
        # ink and megabytes to fetch for the privilege — the daily averages on
        # the metric rows are the readable answer at that scale, and free.
        today = date.today()
        _day(db, user, today, extra={"stress_series": [[60, 30]]})
        _day(db, user, today - timedelta(days=200),
             extra={"stress_series": [[60, 90]]})

        body = client.get("/health/stress", params={"after": "2000-01-01"}).json()

        assert [row["date"] for row in body] == [today.isoformat()]

    def test_another_user_s_curve_is_not_served(self, client, user, db):
        from app.models.activity import User

        other = User(name="Somebody Else")
        db.add(other)
        db.commit()
        _day(db, other, date.today(), extra={"stress_series": [[60, 30]]})

        assert client.get("/health/stress").json() == []


class TestTheNightsClock:
    def test_the_daily_metric_carries_when_the_night_ran(self, client, user, db):
        _day(db, user, date(2026, 8, 29), sleep_hours=7.0, extra={
            "sleep_stages": [
                {"start": "2026-08-29T04:30:00+00:00",
                 "end": "2026-08-29T05:30:00+00:00", "level": "light"},
                {"start": "2026-08-29T05:30:00+00:00",
                 "end": "2026-08-29T12:45:00+00:00", "level": "deep"},
            ],
        })

        body = client.get("/metrics/daily", params={"after": "2026-08-01"}).json()

        assert body[0]["sleep_start"] == "2026-08-29T04:30:00Z"
        assert body[0]["sleep_end"] == "2026-08-29T12:45:00Z"

    def test_a_night_with_no_timeline_reports_no_clock(self, client, user, db):
        # Its totals are real and stay; the chart says how many nights it
        # could not place rather than dropping them in silence.
        _day(db, user, date(2026, 8, 29), sleep_hours=7.0)

        body = client.get("/metrics/daily", params={"after": "2026-08-01"}).json()

        assert body[0]["sleep_hours"] == 7.0
        assert body[0]["sleep_start"] is None
        assert body[0]["sleep_end"] is None
