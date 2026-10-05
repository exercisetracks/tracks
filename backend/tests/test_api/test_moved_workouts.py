# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Workouts the user moved stay where they were put through a regeneration.

Before this, every rebuild — the button, a goal edit, the refresh on activity
import — put each session back on the generator's day, so a tempo moved off a
travel day was back on it the next morning. The matching rule itself is
app.calculators.plan.moved, held to the phone by spec/fixtures/moved_workouts.json
(replayed below as well); these tests are about the server's use of it.
"""
import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

import pytest

from app.api.training_plan.generation import _regenerate_future_workouts
from app.calculators.plan.moved import keep_completed, keep_moved
from app.models.coaching import TrainingGoal
from app.models.training_plan import PlannedWorkout, TrainingPlan

from ..test_sync.helpers import setup_admin

CORPUS = json.loads(Path("/spec/fixtures/moved_workouts.json").read_text())


@pytest.fixture(autouse=True)
def no_background_refresh(monkeypatch):
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)


def _dates(rows):
    return [{**r, "scheduled_date": date.fromisoformat(r["scheduled_date"])} for r in rows]


@pytest.mark.parametrize("case", CORPUS["cases"], ids=lambda c: c["name"])
def test_the_moved_workout_corpus_still_holds(case):
    """The corpus is generated from this code; a change here that is not
    regenerated would leave the phone agreeing with an answer the server no
    longer gives."""
    generated = _dates(case["generated"])
    remaining, refresh = keep_moved(generated, _dates(case["moved"]),
                                    date.fromisoformat(case["today"]))
    assert [i for i, g in enumerate(generated) if any(g is r for r in remaining)] == case["expect"]["kept"]
    assert json.loads(json.dumps(refresh, default=str)) == case["expect"]["refresh"]


@pytest.mark.parametrize("case", CORPUS["completed_cases"], ids=lambda c: c["name"])
def test_the_completed_workout_corpus_still_holds(case):
    """The phone replays these too; a finished workout that claims its
    session here and not there would be done twice on one device's plan."""
    generated = _dates(case["generated"])
    remaining = keep_completed(generated, _dates(case["completed"]),
                               date.fromisoformat(case["today"]))
    assert [i for i, g in enumerate(generated) if any(g is r for r in remaining)] == case["expect"]["kept"]


def _plan_with_moved(db, user, *, done=False, days_ahead=2):
    """A fitness plan, then its first future quality session moved a day on."""
    goal = TrainingGoal(user_id=user.id, goal_type="fitness", event_sport="running",
                        ctl_ramp_per_week=2.0, days_per_week=5, is_active=True)
    db.add(goal)
    db.commit()
    _regenerate_future_workouts(db, goal, user.id)
    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).one()
    today = date.today()
    rows = (db.query(PlannedWorkout).filter(PlannedWorkout.plan_id == plan.id,
                                            PlannedWorkout.scheduled_date >= today)
            .order_by(PlannedWorkout.scheduled_date).all())
    w = next(r for r in rows if r.workout_type == "long")
    w.scheduled_date = w.scheduled_date + timedelta(days=days_ahead)
    w.moved_by_user = True
    w.duration_minutes = 1  # so a refresh is visible
    w.is_complete = done
    db.commit()
    return goal, plan, w


def test_a_moved_workout_survives_regeneration_on_its_new_day(db, user):
    goal, plan, w = _plan_with_moved(db, user)
    uid, day = w.uid, w.scheduled_date
    _regenerate_future_workouts(db, goal, user.id)
    kept = db.query(PlannedWorkout).filter_by(uid=uid).one()
    assert kept.scheduled_date == day
    assert kept.generation == db.get(TrainingPlan, plan.id).generation


def test_a_moved_workout_takes_its_counterparts_content_and_the_week_gets_it_once(db, user):
    goal, plan, w = _plan_with_moved(db, user)
    uid, day = w.uid, w.scheduled_date
    _regenerate_future_workouts(db, goal, user.id)
    kept = db.query(PlannedWorkout).filter_by(uid=uid).one()
    assert kept.duration_minutes > 1
    monday = day - timedelta(days=day.weekday())
    longs = db.query(PlannedWorkout).filter(
        PlannedWorkout.plan_id == plan.id, PlannedWorkout.workout_type == "long",
        PlannedWorkout.scheduled_date >= monday,
        PlannedWorkout.scheduled_date < monday + timedelta(days=7)).all()
    assert [x.uid for x in longs] == [uid]


def test_the_generate_endpoint_keeps_moved_workouts_too(client, db):
    h = setup_admin(client)
    gid = client.post("/coaching/goals", headers=h, json={
        "goal_type": "fitness", "event_sport": "running", "ctl_ramp_per_week": 2.0,
        "days_per_week": 5}).json()["id"]
    first = client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).json()["workouts"]
    today = date.today()
    w = next(x for x in first if x["workout_type"] == "long" and date.fromisoformat(x["scheduled_date"]) >= today)
    new_day = (date.fromisoformat(w["scheduled_date"]) + timedelta(days=1)).isoformat()
    moved = client.patch(f"/coaching/plan/workouts/{w['id']}", headers=h, json={"scheduled_date": new_day})
    assert moved.json()["moved_by_user"] is True

    again = client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).json()["workouts"]
    assert [x["scheduled_date"] for x in again if x["id"] == w["id"]] == [new_day]


def test_a_patch_that_does_not_change_the_date_is_not_a_move(client, db):
    h = setup_admin(client)
    w = client.post("/coaching/plan/workouts", headers=h, json={
        "scheduled_date": date.today().isoformat(), "title": "Run"}).json()
    resp = client.patch(f"/coaching/plan/workouts/{w['id']}", headers=h,
                        json={"scheduled_date": w["scheduled_date"], "title": "Easy run"})
    assert resp.json()["moved_by_user"] is False


def test_a_completed_moved_workout_keeps_what_was_done(db, user):
    goal, plan, w = _plan_with_moved(db, user, done=True)
    uid = w.uid
    _regenerate_future_workouts(db, goal, user.id)
    assert db.query(PlannedWorkout).filter_by(uid=uid).one().duration_minutes == 1


def test_a_refreshed_moved_workout_is_sent_to_the_watch_again(db, user):
    """Its file on the watch describes the old session."""
    from app.models.training_plan import WatchPendingDelete
    goal, plan, w = _plan_with_moved(db, user)
    w.watch_filename = "OLD.FIT"
    w.watch_uploaded_at = datetime.now(timezone.utc)
    db.commit()
    _regenerate_future_workouts(db, goal, user.id)
    kept = db.query(PlannedWorkout).filter_by(uid=w.uid).one()
    assert kept.watch_filename is None and kept.watch_uploaded_at is None
    assert db.query(WatchPendingDelete).filter_by(filename="OLD.FIT").count() == 1


def test_moving_the_race_day_rebuilds_the_plan_around_it(client, db):
    """Dragging the race on the phone's calendar, or changing the date in the
    goal form, moves `event_date`; a plan still ending on the old day would
    taper for a race that is not there."""
    h = setup_admin(client)
    old = date.today() + timedelta(days=60)
    gid = client.post("/coaching/goals", headers=h, json={
        "goal_type": "event", "event_sport": "running", "event_name": "10K",
        "event_distance_meters": 10000, "event_date": old.isoformat()}).json()["id"]
    client.post(f"/coaching/goals/{gid}/plan/generate", headers=h)
    new = old + timedelta(days=7)
    assert client.patch(f"/coaching/goals/{gid}", headers=h,
                        json={"event_date": new.isoformat()}).status_code == 200
    workouts = client.get(f"/coaching/goals/{gid}/plan", headers=h).json()["workouts"]
    assert [w["scheduled_date"] for w in workouts if w["workout_type"] == "race"] == [new.isoformat()]
