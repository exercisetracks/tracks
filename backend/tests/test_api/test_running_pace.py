# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The plan and the race prediction pace from today's running fitness.

Before this, both read the single fastest 3 km+ stretch of any run ever
recorded — so a personal best from years ago set this week's paces — and
everyone without history was paced as a 23-minute 5 K runner. These go through
the API, so they fail if either endpoint stops reading the shared estimate
(api/training_plan/helpers._get_running_fitness), not only if the estimator
itself is wrong (tests/test_calculators/test_running_fitness.py).

Dates are offsets from today, never fixed.
"""
from datetime import date, datetime, time, timedelta, timezone

import pytest

from app.calculators.plan.base import _fmt_pace, calculate_vdot, vdot_to_paces
from app.calculators.plan.running_fitness import profile_vdot
from app.calculators.race_predictor import predict_race_time_sec, predict_running_race_sec, training_indices
from app.models.activity import Activity, PaceBest
from app.models.metrics import DailyMetric
from app.models.training_plan import PlannedWorkout, TrainingPlan

from ..test_sync.helpers import setup_admin

USER_ID = 1  # the admin setup creates, first row of a reset table


@pytest.fixture(autouse=True)
def no_background_refresh(monkeypatch):
    """Creating a goal refreshes plans on a thread sharing this test's connection."""
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)


def _at(days_ago: int) -> datetime:
    return datetime.combine(date.today() - timedelta(days=days_ago), time(7), tzinfo=timezone.utc)


def _run(db, days_ago, km=8.0, pace=330.0, hr=145):
    a = Activity(user_id=USER_ID, sport="running", started_at=_at(days_ago),
                 distance_meters=km * 1000, duration_seconds=int(km * pace),
                 avg_heart_rate=hr, total_ascent=40.0)
    db.add(a)
    db.flush()
    return a


def _resting_hr(db, bpm=55):
    for d in range(10):
        db.add(DailyMetric(user_id=USER_ID, date=date.today() - timedelta(days=d), resting_hr=bpm))
    db.flush()


def _goal(client, h, distance=10000, weeks=10):
    resp = client.post("/coaching/goals", headers=h, json={
        "goal_type": "event", "event_name": "Race", "event_sport": "running",
        "event_date": (date.today() + timedelta(weeks=weeks)).isoformat(),
        "event_distance_meters": distance, "days_per_week": 4,
    })
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def _plan(client, db, h, gid):
    assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200
    db.expire_all()
    return db.query(TrainingPlan).filter_by(goal_id=gid).one()


def _first_easy_note(db, plan):
    for w in (db.query(PlannedWorkout).filter_by(plan_id=plan.id)
              .order_by(PlannedWorkout.scheduled_date).all()):
        for s in w.steps or []:
            if s.get("pace") == "easy" and s.get("note"):
                return s["note"]
    return None


def test_a_plan_is_paced_from_heart_rate_on_recent_runs_not_an_old_best(client, db):
    """Steady runs at 5:30/km and 145 bpm (max 185, resting 55) put this runner
    at VDOT 44.6; a 5 K in 17 minutes from 500 days ago — VDOT ~60 — is past
    anything it can say about today and must not set the paces."""
    h = setup_admin(client)
    assert client.patch("/users/me/settings", headers=h,
                        json={"max_hr_mode": "manual", "max_hr_manual": 185}).status_code == 200
    for d in (2, 5, 9, 12):
        _run(db, d)
    old = _run(db, 500, km=5.0, pace=204.0, hr=None)
    db.add(PaceBest(activity_id=old.id, distance_meters=5000, avg_speed_mps=5000 / (17 * 60)))
    _resting_hr(db)

    plan = _plan(client, db, h, _goal(client, h))
    assert plan.vdot == 44.6
    assert calculate_vdot(5000, 17 * 60) > 59


def test_without_runs_the_plan_is_paced_from_the_profile(client, db):
    """No runs: the paces come from sex, height, weight, age and how often the
    person runs — not the old VDOT 42 — and the plan stores no VDOT, so the
    watch is given no pace targets from a guess."""
    h = setup_admin(client)
    birth_year = date.today().year - 35
    assert client.patch("/users/me/settings", headers=h, json={
        "sex": "female", "height_cm": 165, "weight_kg": 60, "birth_year": birth_year,
        "activity_frequency": {"running": "never"},
    }).status_code == 200

    plan = _plan(client, db, h, _goal(client, h))
    assert plan.vdot is None
    want = profile_vdot(date.today(), sex="female", height_cm=165, weight_kg=60,
                        birth_year=birth_year, frequencies={"running": "never"})
    easy = _fmt_pace(vdot_to_paces(round(want, 1))["easy"])
    assert easy in _first_easy_note(db, plan)
    assert _fmt_pace(vdot_to_paces(42.0)["easy"]) not in _first_easy_note(db, plan)


def test_a_birth_year_in_the_future_is_refused(client):
    h = setup_admin(client)
    resp = client.patch("/users/me/settings", headers=h, json={"birth_year": date.today().year + 1})
    assert resp.status_code == 422


def test_the_marathon_prediction_carries_training_volume(client, db):
    """A runner doing 16 km a week predicts a slower marathon than VDOT alone
    says (Vickers & Vertosick 2016; Tanda 2011) — the goal card's predicted time
    reads the same corrected model the race plan does."""
    h = setup_admin(client)
    assert client.patch("/users/me/settings", headers=h,
                        json={"max_hr_mode": "manual", "max_hr_manual": 185}).status_code == 200
    runs = []
    for week in range(8):
        for extra in (0, 3):
            runs.append(_run(db, week * 7 + extra + 1))
    _resting_hr(db)
    gid = _goal(client, h, distance=42195, weeks=16)

    resp = client.get(f"/coaching/goals/{gid}/predicted-time", headers=h)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    vdot = body["vdot"]
    evidence = [{"date": a.started_at.date(), "distance_m": a.distance_meters,
                 "duration_s": a.duration_seconds} for a in runs]
    indices = training_indices(evidence, date.today())
    assert indices is not None and indices[0] == pytest.approx(16.0)
    assert body["predicted_seconds"] == round(predict_running_race_sec(vdot, 42195, indices), 1)
    assert body["predicted_seconds"] > predict_race_time_sec(vdot, 42195) + 10 * 60
