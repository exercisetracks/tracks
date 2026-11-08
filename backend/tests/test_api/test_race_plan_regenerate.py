# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race plans regenerate every time their page opens, and the training
calendar plans its race day the same way — so regenerating must be cheap and
must not disturb the watch when nothing changed.

The race reaches the watch as the calendar's race day
(training_plan/sync.py ``_race_day_fit``), in the calendar's own batch. It
used to be a file of its own, pushed by a route of its own that only a visit
to this page ever filled, while the calendar's race day went out as an empty
workout: the watch showed the race with none of its pacing."""
import base64
from datetime import date, datetime, timedelta, timezone

import pytest
from garmin_fit_sdk import Decoder, Stream

from app.api.coaching import race_helpers
from app.api.training_plan.sync import _build_fit_b64, _upload_items_for_user
from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.training_plan import PlannedWorkout, TrainingPlan, WatchPendingDelete
from app.models.user_settings import UserSettings


@pytest.fixture
def goal(db, user, monkeypatch):
    monkeypatch.setattr("app.api.coaching.race_helpers._get_running_metrics",
                        lambda db, user, us, goal: {"vdot": 50.0, "vdot_today": 50.0,
                                                     "adherence": None, "run_indices": None})
    g = TrainingGoal(user_id=user.id, goal_type="event", event_name="10K", event_sport="running",
                     event_distance_meters=10_000, event_date=date.today() + timedelta(days=3))
    db.add(g)
    db.flush()
    plan = TrainingPlan(goal_id=g.id, user_id=user.id, sport="running", vdot=50.0)
    db.add(plan)
    db.flush()
    db.add(PlannedWorkout(plan_id=plan.id, user_id=user.id, scheduled_date=g.event_date,
                          sport="running", workout_type="race", title="Race: 10K",
                          duration_minutes=0, distance_meters=10_000, steps=[]))
    db.query(UserSettings).filter_by(user_id=user.id).one().pace_coaching = True
    db.commit()
    return g


def _race_day(db, goal) -> PlannedWorkout:
    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).one()
    return db.query(PlannedWorkout).filter_by(plan_id=plan.id, workout_type="race").one()


def _decode(b64: str) -> dict:
    msgs, errors = Decoder(Stream.from_byte_array(bytearray(base64.b64decode(b64)))).read()
    assert not errors, errors
    return msgs


def test_the_calendars_race_day_is_the_race(client, user, db, goal):
    """A kilometre step each, between an open warm-up and cool-down, held to
    the race's pace — under the race session's own id and timestamp, which is
    the pair its schedule entry names. It was warm-up / open / cool-down."""
    w = _race_day(db, goal)
    msgs = _decode(_build_fit_b64(w, db))
    steps = msgs["workout_step_mesgs"]
    assert len(steps) == 10 + 2
    assert [s["duration_distance"] for s in steps[1:-1]] == [1000.0] * 10
    assert all(s.get("custom_target_speed_low") for s in steps[1:-1])
    file_id = msgs["file_id_mesgs"][0]
    assert file_id["serial_number"] == w.id
    assert round(file_id["time_created"].timestamp()) == w.fit_time_created // 1000


def test_watch_coaching_off_sends_the_race_unpaced_like_every_workout(client, user, db, goal):
    """The switch is about the watch buzzing, and the race day is a calendar
    workout like any other: its laps still name the kilometres, with no alarm."""
    db.query(UserSettings).filter_by(user_id=user.id).one().pace_coaching = False
    db.commit()
    steps = _decode(_build_fit_b64(_race_day(db, goal), db))["workout_step_mesgs"]
    assert len(steps) == 12
    assert not any(s.get("custom_target_speed_low") for s in steps)


def test_an_unchanged_race_is_the_same_calendar_file(client, user, db, goal):
    """The calendar is resent whenever any of its files' bytes change, so a
    race day rebuilt with nothing changed must come out byte for byte."""
    w = _race_day(db, goal)
    assert _build_fit_b64(w, db) == _build_fit_b64(w, db)


def test_a_changed_race_is_a_new_calendar_file(client, user, db, goal):
    w = _race_day(db, goal)
    before = _build_fit_b64(w, db)
    client.patch(f"/coaching/goals/{goal.id}/race-plan", json={"split_spread": 0.6})
    assert _build_fit_b64(w, db) != before


def test_a_race_file_from_before_is_taken_off_the_watch_and_forgotten(client, user, db, goal):
    """A RACE_*.fit pushed by the old route is a second copy of the race now:
    queued for deletion, which a cable carries out, and no longer named."""
    rp = RacePlan(goal_id=goal.id, user_id=user.id, fit_b64="AAAA",
                  watch_filename="RACE_1_2026-11-11.fit", watch_uploaded_at=datetime.now(timezone.utc))
    db.add(rp)
    db.commit()
    assert client.post(f"/coaching/goals/{goal.id}/race-plan/generate").status_code == 200
    db.refresh(rp)
    assert (rp.fit_b64, rp.watch_filename, rp.watch_uploaded_at) == (None, None, None)
    assert db.query(WatchPendingDelete).filter_by(user_id=user.id, filename="RACE_1_2026-11-11.fit").count() == 1


def test_race_plans_are_not_offered_as_files_of_their_own_to_anyone(client, user, db, goal):
    """The old route read every account's race files with no user filter, so
    one account's race went to whichever watch synced next."""
    other = User(name="Other")
    db.add(other)
    db.flush()
    g2 = TrainingGoal(user_id=other.id, goal_type="event", event_name="Their race", event_sport="running",
                      event_distance_meters=5_000, event_date=date.today() + timedelta(days=9))
    db.add(g2)
    db.flush()
    db.add(RacePlan(goal_id=g2.id, user_id=other.id, fit_b64="AAAA", watch_filename="RACE_9.fit"))
    db.commit()
    items = _upload_items_for_user(db, user.id)
    assert not [i for i in items if i["type"] != "workout"]
    race = [i for i in items if i["id"] == _race_day(db, goal).id]
    assert len(race) == 1


def test_the_race_plans_watch_state_is_its_race_days(client, user, db, goal):
    """'Synced to watch' follows the session that carries the race."""
    assert client.get(f"/coaching/goals/{goal.id}/race-plan").json()["watch_uploaded_at"] is None
    w = _race_day(db, goal)
    w.watch_uploaded_at = datetime.now(timezone.utc)
    db.commit()
    assert client.get(f"/coaching/goals/{goal.id}/race-plan").json()["watch_uploaded_at"] is not None


def test_the_calendar_plans_a_race_without_writing_a_race_plan(client, user, db, goal):
    """The row syncs and goal_id is unique: one made by a calendar build
    beside the one a phone makes on its first edit would collide. The race
    is planned on the defaults, as the phone plans it."""
    assert db.query(RacePlan).filter_by(goal_id=goal.id).count() == 0
    steps = _decode(_build_fit_b64(_race_day(db, goal), db))["workout_step_mesgs"]
    assert len(steps) == 12
    assert db.query(RacePlan).filter_by(goal_id=goal.id).count() == 0


def test_race_weather_is_fetched_once_per_place_and_day(monkeypatch):
    calls = []
    monkeypatch.setattr(race_helpers, "_fetch_weather_uncached",
                        lambda lat, lon, d: calls.append(1) or {"temperature_c": 12, "source": "forecast"})
    d = date.today() + timedelta(days=3)
    race_helpers._fetch_weather(46.5, 7.5, d)
    race_helpers._fetch_weather(46.5, 7.5, d)
    assert len(calls) == 1
    race_helpers._fetch_weather(46.6, 7.5, d)
    assert len(calls) == 2
