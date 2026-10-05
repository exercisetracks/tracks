# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime, timedelta

import pytest

from app.models.activity import Activity, PaceBest
from app.models.metrics import DailyMetric


def _make_activity(db, user, **kwargs):
    defaults = dict(
        user_id=user.id,
        device_id=1,  # test device created by the user fixture
        sport="running",
        started_at=datetime(2024, 6, 1, 8, 0, 0),
        duration_seconds=3600,
        avg_heart_rate=140,
        distance_meters=10000.0,
        training_stress_score=80.0,
    )
    defaults.update(kwargs)
    a = Activity(**defaults)
    db.add(a)
    db.commit()
    db.refresh(a)
    return a


def _make_metric(db, user, date, **kwargs):
    defaults = dict(user_id=user.id, date=date, resting_hr=52, hrv=65, sleep_hours=7.5)
    defaults.update(kwargs)
    m = DailyMetric(**defaults)
    db.add(m)
    db.commit()
    db.refresh(m)
    return m


class TestSummary:
    def test_empty_returns_zero_counts(self, client, user):
        resp = client.get("/metrics/summary")
        assert resp.status_code == 200
        data = resp.json()
        assert data["activity_count"] == 0
        assert data["total_distance_km"] is None

    def test_aggregates_distance_and_duration(self, client, user, db):
        _make_activity(db, user, distance_meters=10000, duration_seconds=3600)
        _make_activity(db, user, distance_meters=5000, duration_seconds=1800,
                       started_at=datetime(2024, 6, 2, 8, 0, 0))
        # Pass after date to bypass startup cache
        data = client.get("/metrics/summary", params={"after": "2024-01-01"}).json()
        assert data["activity_count"] == 2
        assert abs(data["total_distance_km"] - 15.0) < 0.01
        assert abs(data["total_duration_hours"] - 1.5) < 0.01


class TestTrainingLoad:
    def test_empty_returns_empty(self, client, user):
        assert client.get("/metrics/training-load").json() == []

    def test_single_activity_produces_load_point(self, client, user, db):
        _make_activity(db, user, training_stress_score=80.0)
        # Pass after date to bypass startup cache
        data = client.get("/metrics/training-load", params={"after": "2024-01-01"}).json()
        assert len(data) == 1
        assert data[0]["tss"] == 80.0
        assert data[0]["ctl"] > 0

    def test_tsb_equals_ctl_minus_atl(self, client, user, db):
        for i in range(5):
            _make_activity(db, user,
                           started_at=datetime(2024, 6, 1) + timedelta(days=i),
                           training_stress_score=70.0)
        data = client.get("/metrics/training-load", params={"after": "2024-01-01"}).json()
        for p in data:
            assert abs(p["tsb"] - (p["ctl"] - p["atl"])) < 0.15


class TestDailyMetrics:
    def test_empty_returns_empty(self, client, user):
        assert client.get("/metrics/daily").json() == []

    def test_returns_metrics_for_user(self, client, user, db):
        from datetime import date
        _make_metric(db, user, date(2024, 6, 1))
        resp = client.get("/metrics/daily")
        assert resp.status_code == 200
        assert len(resp.json()) == 1


class TestRacePredictions:
    def test_no_pace_bests_returns_empty(self, client, user):
        assert client.get("/metrics/race-predictions").json() == []

    def test_predicts_from_actual_pace_best(self, client, user, db):
        a = _make_activity(db, user)
        # Add a 5K best: 3.5 m/s ≈ 4:45/km pace
        db.add(PaceBest(activity_id=a.id, distance_meters=5000, avg_speed_mps=3.5))
        db.commit()
        data = client.get("/metrics/race-predictions").json()
        five_k = next((d for d in data if d["distance_meters"] == 5000), None)
        assert five_k is not None
        assert five_k["is_actual"] is True
        expected_secs = round(5000 / 3.5)
        assert five_k["predicted_time_seconds"] == expected_secs

    def test_extrapolates_longer_distances_with_riegel(self, client, user, db):
        a = _make_activity(db, user)
        db.add(PaceBest(activity_id=a.id, distance_meters=5000, avg_speed_mps=3.5))
        db.commit()
        data = client.get("/metrics/race-predictions").json()
        ten_k = next((d for d in data if d["distance_meters"] == 10000), None)
        assert ten_k is not None
        assert ten_k["is_actual"] is False
        assert ten_k["reference_distance_meters"] == 5000
        # 10K should be slower (more seconds) than 5K
        five_k = next(d for d in data if d["distance_meters"] == 5000)
        assert ten_k["predicted_time_seconds"] > five_k["predicted_time_seconds"]

    def test_no_extrapolation_when_only_longer_reference_exists(self, client, user, db):
        a = _make_activity(db, user)
        # Only marathon data — cannot extrapolate shorter distances
        db.add(PaceBest(activity_id=a.id, distance_meters=42195, avg_speed_mps=3.0))
        db.commit()
        data = client.get("/metrics/race-predictions").json()
        # 1K has no shorter reference → should not appear
        one_k = next((d for d in data if d["distance_meters"] == 1000), None)
        assert one_k is None

    def test_formatted_time_includes_minutes(self, client, user, db):
        a = _make_activity(db, user)
        db.add(PaceBest(activity_id=a.id, distance_meters=5000, avg_speed_mps=3.5))
        db.commit()
        data = client.get("/metrics/race-predictions").json()
        five_k = next(d for d in data if d["distance_meters"] == 5000)
        assert ":" in five_k["formatted_time"]


class TestReadinessHistory:
    def test_empty_returns_list_with_neutral_scores(self, client, user):
        resp = client.get("/metrics/readiness-history?days=3")
        assert resp.status_code == 200
        data = resp.json()
        assert len(data) == 3
        # All default (no data) — score should be 50.0
        for point in data:
            assert point["score"] == 50.0

    def test_returns_correct_number_of_days(self, client, user):
        resp = client.get("/metrics/readiness-history?days=7")
        assert len(resp.json()) == 7


class TestPhoneOnlyLoad:
    """An athlete who trains with a phone and no watch: runs with distance,
    duration and climb, and never a heart rate, power or device TSS."""

    def _phone_runs(self, db, user, weeks=8, climb=0.0):
        today = datetime.now().replace(hour=7, minute=0, second=0, microsecond=0)
        for w in range(weeks):
            for day in (1, 3, 5):
                _make_activity(
                    db, user, sport="running",
                    started_at=today - timedelta(weeks=weeks - w) + timedelta(days=day),
                    duration_seconds=3000, distance_meters=9000.0, total_ascent=climb,
                    avg_heart_rate=None, max_heart_rate=None, training_stress_score=None,
                )

    def test_a_phone_only_history_builds_fitness(self, client, user, db):
        """With no heart rate these runs used to score zero, so CTL stayed
        flat however consistently someone trained."""
        self._phone_runs(db, user)
        data = client.get("/metrics/training-load", params={"after": "2000-01-01"}).json()
        assert data[-1]["ctl"] > 15
        assert all(p["tss"] > 0 for p in data if p["tss"])

    def test_the_climb_reaches_the_load_the_chart_shows(self, client, user, db):
        """The chart reads slim rows, not whole activities; if distance and
        ascent were not selected, a hilly run would quietly score as flat."""
        _make_activity(db, user, started_at=datetime(2024, 6, 1, 8), sport="trail_running",
                       duration_seconds=3600, distance_meters=9000.0, total_ascent=700.0,
                       avg_heart_rate=None, training_stress_score=None)
        _make_activity(db, user, started_at=datetime(2024, 6, 3, 8), sport="trail_running",
                       duration_seconds=3600, distance_meters=9000.0, total_ascent=0.0,
                       avg_heart_rate=None, training_stress_score=None)
        data = client.get("/metrics/training-load", params={"after": "2024-01-01"}).json()
        by_day = {p["date"]: p["tss"] for p in data}
        assert by_day["2024-06-01"] > by_day["2024-06-03"] * 1.15

    def test_a_phone_only_runner_is_not_planned_as_a_beginner(self, user, db):
        """A fitness plan builds from CTL, and below 1 it assumes there is no
        history and starts from the setup answer (or from nothing). Eight
        weeks of phone runs must be read as eight weeks of running."""
        from datetime import date

        from app.api.coaching.helpers import _build_tss_by_date, _ctl_atl_today
        from app.calculators.plan.generator.fitness import fitness_week_targets

        self._phone_runs(db, user)
        today = date.today()
        ctl, atl, _ = _ctl_atl_today(_build_tss_by_date(db, user.id, None), today)
        assert ctl > 15
        from_history = fitness_week_targets(ctl, atl, 0.0, today, today)
        from_nothing = fitness_week_targets(0.0, 0.0, 0.0, today, today)
        assert from_history[0]["tss"] > 100
        assert from_nothing[0]["tss"] == 0.0

    def test_a_windowed_chart_scores_an_activity_as_the_full_chart_does(self, client, user, db):
        """The calibration is read from the whole history. Read from a
        window instead, a run's load would change with how far back the
        chart looking at it starts."""
        start = datetime(2024, 3, 1, 7)
        for d in range(12):
            _make_activity(db, user, started_at=start + timedelta(days=d),
                           duration_seconds=3600, distance_meters=10000.0, total_ascent=0.0,
                           avg_heart_rate=None, training_stress_score=90.0)
        _make_activity(db, user, started_at=start + timedelta(days=40), duration_seconds=3600,
                       distance_meters=10000.0, total_ascent=0.0, avg_heart_rate=None,
                       training_stress_score=None)
        day = (start + timedelta(days=40)).date().isoformat()
        full = {p["date"]: p["tss"] for p in client.get(
            "/metrics/training-load", params={"after": "2000-01-01"}).json()}
        window = {p["date"]: p["tss"] for p in client.get(
            "/metrics/training-load", params={"after": day}).json()}
        dots = {p["date"]: p["tss"] for p in client.get(
            "/metrics/activity-load", params={"after": day}).json()}
        assert full[day] == window[day] == dots[day]
        # And it was calibrated: these runs measure well above the estimate.
        assert full[day] > 70
