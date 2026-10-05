# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Which planned workout an imported activity completes.

The result is written to synced fields, so every device must pick the same
workout from the same rows — com.tracks.core.plan.Matching.pickWorkout is the
phone's copy of these rules.
"""
from datetime import date, datetime, timezone
from types import SimpleNamespace

from app.api.training_plan.matching import match_activity_to_workout, pick_workout
from app.models.activity import Activity
from app.models.training_plan import PlannedWorkout

DAY = date(2026, 9, 25)


def _w(uid, sport="running", day=DAY, kind="easy", done=None):
    return SimpleNamespace(uid=uid, sport=sport, scheduled_date=day, workout_type=kind,
                           completed_activity_id=done)


def test_a_run_does_not_complete_a_workout_of_another_sport():
    """A run used to tick off whatever was planned that day, a strength session included."""
    got = pick_workout([_w("a", sport="strength_training"), _w("b", sport="trail_running")], DAY, "running")
    assert got.uid == "b"


def test_rest_race_other_days_and_matched_workouts_are_never_picked():
    cands = [_w("a", kind="rest"), _w("b", kind="race"), _w("c", day=date(2026, 9, 24)), _w("d", done=7)]
    assert pick_workout(cands, DAY, "running") is None


def test_a_hand_added_workout_is_completed_by_its_activity(db, user):
    """Workouts with no plan were excluded by a join on the plan; they are the user's plan too."""
    started = datetime.combine(DAY, datetime.min.time(), tzinfo=timezone.utc).replace(hour=7)
    lift = PlannedWorkout(user_id=user.id, plan_id=None, scheduled_date=DAY, sport="strength_training",
                          workout_type="strength", title="Lift", duration_minutes=30)
    run = PlannedWorkout(user_id=user.id, plan_id=None, scheduled_date=DAY, sport="running",
                         workout_type="easy", title="Easy run", duration_minutes=30)
    act = Activity(user_id=user.id, sport="running", started_at=started, duration_seconds=1800)
    db.add_all([lift, run, act])
    db.flush()

    match_activity_to_workout(db, act.id, user.id)

    db.refresh(run)
    db.refresh(lift)
    assert run.completed_activity_id == act.id and run.is_complete
    assert lift.completed_activity_id is None


def test_an_evening_run_completes_its_own_days_workout_not_tomorrows(db, user):
    """18:04 in California is 01:04 UTC the next day.

    Matched by the UTC day, the run ticked off tomorrow's easy run and left
    the one it was actually for open (a real run, 2026-09-30).
    """
    from app.models.user_settings import UserSettings

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None:
        us = UserSettings(user_id=user.id)
        db.add(us)
    us.timezone = "America/Los_Angeles"
    today = PlannedWorkout(user_id=user.id, plan_id=None, scheduled_date=date(2026, 9, 30), sport="running",
                           workout_type="easy", title="Easy run", duration_minutes=25)
    tomorrow = PlannedWorkout(user_id=user.id, plan_id=None, scheduled_date=date(2026, 10, 1), sport="running",
                              workout_type="easy", title="Easy run", duration_minutes=26)
    act = Activity(user_id=user.id, sport="running", started_at=datetime(2026, 10, 1, 1, 4, 12, tzinfo=timezone.utc),
                   duration_seconds=1800)
    db.add_all([today, tomorrow, act])
    db.flush()

    match_activity_to_workout(db, act.id, user.id)

    db.refresh(today)
    db.refresh(tomorrow)
    assert today.completed_activity_id == act.id
    assert tomorrow.completed_activity_id is None
