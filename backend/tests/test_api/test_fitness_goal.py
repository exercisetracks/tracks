# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fitness goals through the API: created with a ramp, planned like events.

Until the fitness goal, only event goals got a plan; "Build Fitness" asked for a
target CTL nobody could name and then planned nothing.
"""
from datetime import date, timedelta

import pytest

from app.api.training_plan.generation import _goal_anchor
from app.models.coaching import TrainingGoal
from app.models.training_plan import PlannedWorkout, TrainingPlan

from ..test_sync.helpers import setup_admin


@pytest.fixture(autouse=True)
def no_background_refresh(monkeypatch):
    """Creating a planned goal refreshes plans on a thread, which would share
    this test's connection and race it."""
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)


def _fitness(client, h, **extra):
    return client.post("/coaching/goals", headers=h, json={
        "goal_type": "fitness", "event_sport": "running", "ctl_ramp_per_week": 3.0,
        "days_per_week": 4, **extra,
    })


def test_a_fitness_goal_gets_a_rolling_four_week_plan(client, db):
    h = setup_admin(client)
    goal = _fitness(client, h)
    assert goal.status_code == 201, goal.text
    assert goal.json()["ctl_ramp_per_week"] == 3.0

    resp = client.post(f"/coaching/goals/{goal.json()['id']}/plan/generate", headers=h)
    assert resp.status_code == 200, resp.text
    dates = [date.fromisoformat(w["scheduled_date"]) for w in resp.json()["workouts"]]
    today = date.today()
    assert dates and min(dates) >= today
    assert max(dates) <= today - timedelta(days=today.weekday()) + timedelta(days=27)


def test_the_background_refresh_rebuilds_fitness_plans(client, db, user):
    """The refresh on activity import is what keeps a rolling plan rolling; if
    it skipped fitness goals, the plan would run out after four weeks."""
    from app.api.training_plan.generation import _regenerate_future_workouts, _planned_now
    goal = TrainingGoal(user_id=user.id, goal_type="fitness", event_sport="cycling",
                        ctl_ramp_per_week=1.0, is_active=True)
    db.add(goal)
    db.commit()
    assert db.query(TrainingGoal).filter(_planned_now(date.today())).count() == 1
    _regenerate_future_workouts(db, goal, user.id)
    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).one()
    assert db.query(PlannedWorkout).filter_by(plan_id=plan.id).count() > 0


def test_a_weekly_volume_goal_still_gets_no_plan(client, db):
    h = setup_admin(client)
    goal = client.post("/coaching/goals", headers=h, json={
        "goal_type": "volume_target", "target_weekly_km": 40, "volume_sport": "running"})
    assert goal.status_code == 201, goal.text
    resp = client.post(f"/coaching/goals/{goal.json()['id']}/plan/generate", headers=h)
    assert resp.status_code == 400


@pytest.mark.parametrize("ramp", [-2.5, 6.5, 8.0])
def test_a_ramp_outside_the_slider_is_refused(client, db, ramp):
    """The slider runs −2 … +6; anything else came from a broken client."""
    assert _fitness(client, setup_admin(client), ctl_ramp_per_week=ramp).status_code == 422


def test_the_top_of_the_slider_is_accepted(client, db):
    """+6 is the slider's top now the plan can deliver it; a server still
    refusing it would reject what both clients offer."""
    assert _fitness(client, setup_admin(client), ctl_ramp_per_week=6.0).status_code == 201


def test_a_multi_sport_fitness_goal_plans_every_sport_it_names(client, db):
    """fitness_sports round-trips and the plan shares the week between them;
    before it, a fitness goal could only name one sport."""
    h = setup_admin(client)
    goal = _fitness(client, h, fitness_sports=["running", "cycling"], days_per_week=5)
    assert goal.status_code == 201, goal.text
    assert goal.json()["fitness_sports"] == ["running", "cycling"]
    resp = client.post(f"/coaching/goals/{goal.json()['id']}/plan/generate", headers=h)
    assert resp.status_code == 200, resp.text
    assert {"running", "cycling"} <= {w["sport"] for w in resp.json()["workouts"]}


def test_changing_a_fitness_goals_sports_rebuilds_its_plan(client, db):
    """Every edit that invalidates the plan regenerates it — there is no
    Regenerate button to fall back on."""
    h = setup_admin(client)
    goal = _fitness(client, h, days_per_week=5).json()
    client.post(f"/coaching/goals/{goal['id']}/plan/generate", headers=h)
    resp = client.patch(f"/coaching/goals/{goal['id']}", headers=h,
                        json={"fitness_sports": ["running", "swimming"]})
    assert resp.status_code == 200, resp.text
    assert resp.json()["fitness_sports"] == ["running", "swimming"]
    plan = client.get(f"/coaching/goals/{goal['id']}/plan", headers=h).json()
    assert "swimming" in {w["sport"] for w in plan["workouts"]}


def test_the_old_goal_types_are_gone(client, db):
    h = setup_admin(client)
    for gtype in ("ctl_target", "maintain"):
        assert client.post("/coaching/goals", headers=h,
                           json={"goal_type": gtype}).status_code == 422


def test_the_light_week_counts_from_the_goals_first_stamp_not_created_at():
    """A phone-made goal reaches the server whenever the phone next syncs; its
    `created_at` is that day, while the phone counts from the row's own stamp.
    Counting from created_at would put the light week in a different place on
    each device."""
    goal = TrainingGoal(clock={
        "goal_type": "0001772409600000-0000-aaaaaaaaaaaaaaaa",       # 2026-03-02 UTC
        "ctl_ramp_per_week": "0001773014400000-0003-bbbbbbbbbbbbbbbb",  # a week later
        "notes": "not a stamp",
    })
    assert _goal_anchor(goal) == date(2026, 3, 2)
    assert _goal_anchor(TrainingGoal(clock={})) is None
