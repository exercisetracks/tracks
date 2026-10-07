# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A plan is a function of the person's data, not of the moment or the device
that built it.

The phone builds the same plan from the same data (LocalPlanning.regenerate),
and either one's rebuild replaces the other's on the next sync. Anything else
the build reads — the clock, the day this server first saw the goal, a key
only some threads hold, thresholds as of the last restart — makes the two
calendars disagree, and every sync rewrites one with the other. These pin
each of those down.
"""
from datetime import date, datetime, timedelta, timezone

import pytest

import app.main as main
from app.api.training_plan import generation, injectors
from app.api.training_plan.helpers import _goal_anchor
from app.models.coaching import TrainingGoal
from app.models.flexibility import StretchLibrary
from app.models.strength import ExerciseLibrary
from app.seed import sync_library
from app.spec.library import EXERCISES, STRETCHES
from app.services import crypto_context, fit_import

from ..test_sync.helpers import setup_admin


@pytest.fixture
def library(db, monkeypatch):
    """The exercise and stretch libraries, as a server's startup seeds them —
    without them no strength or stretch session is planned at all. Not
    `seed_all`, which commits and would end the test's transaction."""
    sync_library(db.connection(), ExerciseLibrary, EXERCISES)
    sync_library(db.connection(), StretchLibrary, STRETCHES)
    monkeypatch.setattr(main, "_EXERCISE_LIBRARY_CACHE", None)


def _strength_goal(db, user, **extra) -> TrainingGoal:
    goal = TrainingGoal(user_id=user.id, goal_type="event", is_active=True, event_name="Half",
                        event_sport="running", event_date=date.today() + timedelta(weeks=8),
                        event_distance_meters=21097.0, days_per_week=4, include_strength=True,
                        strength_tier=3, **extra)
    db.add(goal)
    db.flush()
    return goal


def test_a_plan_built_at_another_moment_picks_the_same_exercises_and_stretches(db, user, library,
                                                                            monkeypatch):
    """The strength and stretch picks were salted with the clock, so the
    server's build and the phone's — or two builds a second apart — chose
    different exercises and stretches for the same days from the same data."""
    goal = _strength_goal(db, user)
    today = date.today()

    monkeypatch.setattr("time.time", lambda: 1_790_000_000.0)
    _, first = generation._plan_workout_dicts(db, goal, user.id, today)
    monkeypatch.setattr("time.time", lambda: 1_790_086_461.0)
    _, second = generation._plan_workout_dicts(db, goal, user.id, today)

    types = {w["workout_type"] for w in first}
    assert {"strength", "flexibility"} <= types, types
    assert first == second


def test_strength_blocks_count_from_the_goals_first_stamp_not_from_when_the_server_saw_it(
        db, user, library, monkeypatch):
    """A goal made on a phone reaches the server whenever the phone next
    syncs, so its `created_at` is that day; the phone counts its strength
    blocks from the row's own first stamp. Counting from created_at put the
    blocks — and so the main lifts — in different weeks on each device."""
    goal = _strength_goal(db, user)
    goal.clock = {**(goal.clock or {}), "goal_type": "0001772409600000-0000-aaaaaaaaaaaaaaaa"}  # 2026-03-02
    goal.created_at = datetime(2026, 4, 20, 12, tzinfo=timezone.utc)
    db.flush()
    seen = {}
    real = injectors.generate_strength_workouts

    def spy(**kw):
        seen["anchor"] = kw["anchor_date"]
        return real(**kw)

    monkeypatch.setattr(injectors, "generate_strength_workouts", spy)
    generation._plan_workout_dicts(db, goal, user.id, date.today())
    assert seen["anchor"] == _goal_anchor(goal) == date(2026, 3, 2)


def test_a_rebuild_after_an_import_reads_fresh_thresholds_and_the_imports_key(db, user, monkeypatch):
    """The rebuild an import schedules runs on a timer thread. It read the
    auto thresholds of the last restart (they were only recomputed at
    startup), and had no key, so it planned strength as if there were no
    injuries — while the phone uses today's thresholds and always holds the
    key."""
    calls = []
    monkeypatch.setattr("app.calculators.user_stats.recalculate_auto_values",
                        lambda _db, uid: calls.append(("recalculate", uid)))
    monkeypatch.setattr("app.api.training_plan.refresh_plans_for_user",
                        lambda uid: calls.append(("refresh", uid, crypto_context._current_key.get())))
    material = object()

    fit_import._do_plan_refresh(user.id, material)

    assert calls == [("recalculate", user.id), ("refresh", user.id, material)]
    assert crypto_context._current_key.get() is None


def test_a_plan_built_on_request_reads_injuries_with_the_sessions_key(client, db, library, monkeypatch):
    """The plan endpoints never set the session's key, so a plan built here
    never saw an injury the phone plans strength around."""
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)
    keys = []

    def injuries(db, user_id):
        keys.append(crypto_context._current_key.get())
        return []

    monkeypatch.setattr(injectors, "_get_active_injuries", injuries)
    h = setup_admin(client)
    resp = client.post("/coaching/goals", headers=h, json={
        "goal_type": "event", "event_name": "Half", "event_sport": "running",
        "event_date": (date.today() + timedelta(weeks=8)).isoformat(),
        "event_distance_meters": 21097, "include_strength": True, "strength_tier": 3,
    })
    assert resp.status_code == 201, resp.text
    gid = resp.json()["id"]
    assert client.post(f"/coaching/goals/{gid}/plan/generate", headers=h).status_code == 200
    assert client.patch(f"/coaching/goals/{gid}", headers=h, json={"days_per_week": 5}).status_code == 200
    assert len(keys) == 2 and all(k is not None for k in keys), keys
