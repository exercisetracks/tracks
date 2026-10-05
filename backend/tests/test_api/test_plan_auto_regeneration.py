# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Every edit that leaves a plan stale rebuilds it — there is no Regenerate
button any more (user decision, 2026-09-28).

Before this, PATCH /coaching/goals rebuilt the plan for a hand-picked list of
fields: turning strength on, changing its focus or the event's distance left
the plan built for the old goal until someone pressed Regenerate, and
activating a goal that had sat idle for a month showed a plan built for the
fitness of a month ago.
"""
from datetime import date, timedelta

import pytest

from app.models.training_plan import TrainingPlan

from ..test_sync.helpers import setup_admin


@pytest.fixture(autouse=True)
def no_background_refresh(monkeypatch):
    """Creating a planned goal refreshes plans on a thread, which would share
    this test's connection and race it."""
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)


def _event(client, h, **extra):
    resp = client.post("/coaching/goals", headers=h, json={
        "goal_type": "event", "event_name": "Half", "event_sport": "running",
        "event_date": (date.today() + timedelta(weeks=12)).isoformat(),
        "event_distance_meters": 21097, "days_per_week": 4, **extra,
    })
    assert resp.status_code == 201, resp.text
    gid = resp.json()["id"]
    assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200
    return gid


def _generation(db, gid):
    db.expire_all()
    return db.query(TrainingPlan).filter_by(goal_id=gid).one().generation


@pytest.mark.parametrize("change", [
    {"include_strength": True},
    {"strength_tier": 2},
    {"strength_days_per_week": 2},
    {"event_distance_meters": 42195},
    {"event_sport": "cycling"},
    {"days_per_week": 5},
])
def test_an_edit_the_plan_reads_rebuilds_it(client, db, change):
    h = setup_admin(client)
    gid = _event(client, h)
    before = _generation(db, gid)
    assert client.patch(f"/coaching/goals/{gid}", headers=h, json=change).status_code == 200
    assert _generation(db, gid) != before


@pytest.mark.parametrize("change", [{"notes": "tapering in Lisbon"}, {"event_name": "Lisbon Half"}])
def test_an_edit_no_plan_reads_leaves_it_alone(client, db, change):
    """A renamed race is the same race; rebuilding would reshuffle the
    strength and stretch picks for nothing."""
    h = setup_admin(client)
    gid = _event(client, h)
    before = _generation(db, gid)
    assert client.patch(f"/coaching/goals/{gid}", headers=h, json=change).status_code == 200
    assert _generation(db, gid) == before


def test_activating_a_goal_rebuilds_its_plan(client, db):
    h = setup_admin(client)
    first = _event(client, h)
    second = _event(client, h, event_name="Marathon", event_distance_meters=42195)
    before = _generation(db, first)
    assert client.patch(f"/coaching/goals/{first}", headers=h, json={"is_active": True}).status_code == 200
    assert _generation(db, first) != before
    assert _generation(db, second) is not None


def test_an_inactive_goal_is_not_rebuilt_until_it_is_activated(client, db):
    h = setup_admin(client)
    first = _event(client, h)
    _event(client, h, event_name="Marathon")   # makes the first inactive
    before = _generation(db, first)
    assert client.patch(f"/coaching/goals/{first}", headers=h, json={"days_per_week": 6}).status_code == 200
    assert _generation(db, first) == before


@pytest.fixture
def forced(monkeypatch):
    calls = []
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user_force", lambda uid: calls.append(uid))
    return calls


@pytest.mark.parametrize("field,value", [
    ("ftp_manual", 250), ("threshold_hr_mode", "manual"), ("equipment_available", ["barbell"]),
    ("hidden_sports", ["walking"]), ("units", "imperial"),
])
def test_a_setting_the_plan_reads_rebuilds_it(client, forced, field, value):
    h = setup_admin(client)
    assert client.patch("/users/me/settings", headers=h, json={field: value}).status_code == 200
    assert forced


def test_a_setting_no_plan_reads_rebuilds_nothing(client, forced):
    h = setup_admin(client)
    assert client.patch("/users/me/settings", headers=h, json={"accent_color": "blue"}).status_code == 200
    assert not forced


def test_a_setting_written_back_unchanged_rebuilds_nothing(client, forced):
    """Settings forms save whole; a rebuild on every save would reshuffle
    the strength picks each time someone changed their theme."""
    h = setup_admin(client)
    units = client.get("/users/me/settings", headers=h).json()["units"]
    assert client.patch("/users/me/settings", headers=h, json={"units": units}).status_code == 200
    assert not forced


def test_a_new_account_gets_the_general_recommended_date(client):
    h = setup_admin(client)
    resp = client.get("/coaching/goals/recommended-date", headers=h,
                      params={"sport": "running", "distance_m": 5000})
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["basis"] == "general"
    assert body["weeks"] == 4
    assert date.fromisoformat(body["date"]) > date.today()
    assert body["reasons"]


@pytest.mark.parametrize("rebuild", ["edit", "generate"])
def test_a_rebuild_leaves_the_days_before_today_as_they_were(client, db, rebuild):
    """Editing a plan after its first day used to wipe the days already
    behind it: the explicit generate endpoint deleted every workout of the old
    generation, so the plan's history went with each edit. The past stays,
    unchanged, in the new generation; today is rebuilt with the rest."""
    from app.models.training_plan import PlannedWorkout
    h = setup_admin(client)
    gid = _event(client, h)
    plan = db.query(TrainingPlan).filter_by(goal_id=gid).one()
    rows = (db.query(PlannedWorkout).filter_by(plan_id=plan.id)
            .order_by(PlannedWorkout.scheduled_date).all())
    # A plan some days old: its first session was yesterday, its second is today.
    past, current = rows[0], rows[1]
    past.scheduled_date = date.today() - timedelta(days=1)
    past.title, past.is_complete = "Done yesterday", True
    current.scheduled_date = date.today()
    current.title = "Old today"
    db.commit()
    past_id, current_id = past.id, current.id

    if rebuild == "edit":
        assert client.patch(f"/coaching/goals/{gid}", headers=h,
                            json={"days_per_week": 5}).status_code == 200
    else:
        assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200

    db.expire_all()
    kept = db.get(PlannedWorkout, past_id)
    assert kept is not None
    assert (kept.scheduled_date, kept.title, kept.is_complete) == (
        date.today() - timedelta(days=1), "Done yesterday", True)
    assert kept.generation == _generation(db, gid)
    assert db.get(PlannedWorkout, current_id) is None


@pytest.mark.parametrize("rebuild", ["edit", "generate"])
def test_a_rebuild_keeps_what_is_done_today_and_replaces_the_rest(client, db, rebuild):
    """A run finished this morning survives a rebuild this afternoon, and the
    day does not get that run a second time; the unfinished session beside it
    is rebuilt. The explicit endpoint used to delete the finished one, and the
    edit path to leave the whole day empty — the phone must match either."""
    from app.calculators.plan.moved import role
    from app.models.training_plan import PlannedWorkout
    h = setup_admin(client)
    gid = _event(client, h)
    plan = db.query(TrainingPlan).filter_by(goal_id=gid).one()
    rows = (db.query(PlannedWorkout).filter_by(plan_id=plan.id)
            .order_by(PlannedWorkout.scheduled_date).all())
    done, open_ = rows[0], rows[1]
    for r in (done, open_):
        r.scheduled_date = date.today()
    done.title, done.is_complete = "Done this morning", True
    db.commit()
    done_id, open_id, done_role = done.id, open_.id, role(done.workout_type)

    if rebuild == "edit":
        assert client.patch(f"/coaching/goals/{gid}", headers=h,
                            json={"days_per_week": 5}).status_code == 200
    else:
        assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200

    db.expire_all()
    kept = db.get(PlannedWorkout, done_id)
    assert (kept.title, kept.is_complete, kept.generation) == (
        "Done this morning", True, _generation(db, gid))
    assert db.get(PlannedWorkout, open_id) is None
    today_rows = db.query(PlannedWorkout).filter_by(plan_id=plan.id, scheduled_date=date.today()).all()
    assert [r.id for r in today_rows if role(r.workout_type) == done_role] == [done_id]
