# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A running race on race day (calculators/plan/race_day.py).

The race plan predicted from today's fitness and today's form: a race three
months out was predicted for a runner as tired as they are mid-build, and
never as the plan meant them to arrive. Now it is today's fitness plus the
gain the rest of the plan should bring, at the rate the plan has actually
been done, and the form its taper should leave. Each import and each
planned day left empty moves it, and on race day it is the measurement.
The phone replays spec/fixtures/race_day.json; the corpus test here fails
when a change is not regenerated into it.

Dates are fixed and passed in — nothing here reads the clock.
"""
import json
import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.calculators.plan import race_day as rd
from app.calculators.plan.base import _fmt_pace, vdot_to_paces
from app.calculators.plan.generator.plan import generate_training_plan

CORPUS = json.loads(Path("/spec/fixtures/race_day.json").read_text())
TODAY = date(2026, 3, 4)  # a Wednesday


def _day(n):
    return TODAY + timedelta(days=n)


def _session(n, sport="running", minutes=45, complete=False, recorded=False, wtype="easy"):
    return {"date": _day(n), "sport": sport, "workout_type": wtype, "minutes": minutes,
            "complete": complete, "recorded": recorded}


def _act(n, sport="running", minutes=45.0):
    return {"date": _day(n), "sport": sport, "minutes": minutes}


FOUR_WEEKS = [_session(-d) for d in range(1, 28, 2)]   # 14 runs, 630 minutes


def test_on_race_day_the_prediction_is_the_measured_fitness():
    """The projection converges by construction: no weeks left, no gain left."""
    assert rd.race_day_vdot(41.3, TODAY, TODAY, 1.0) == 41.3
    assert rd.race_day_vdot(41.3, TODAY, _day(-2), 1.0) == 41.3


def test_a_plan_followed_in_full_is_credited_the_plans_own_gain():
    """The race week of the calendar and the race plan's race day are one
    number: +0.1 VDOT a week to the race week, at most +1.5 over a plan."""
    race = _day(60)            # 62 days from this Monday: the race week is the 9th, 8 weeks on
    assert rd.plan_weeks(TODAY, race) == 9
    assert rd.race_day_vdot(40.0, TODAY, race, 1.0) == pytest.approx(40.8)
    assert rd.race_day_vdot(40.0, TODAY, _day(400), 1.0) == pytest.approx(41.5, abs=0.03)


def test_skipped_sessions_lower_the_race_day_fitness_at_once():
    """Nothing logged on a planned day is half the gain still to come, the
    day after — not the day the next run is imported."""
    full = rd.adherence(FOUR_WEEKS, [_act(-d) for d in range(1, 28, 2)], TODAY)
    half = rd.adherence(FOUR_WEEKS, [_act(-d) for d in range(1, 28, 4)], TODAY)
    assert full == 1.0
    assert half == pytest.approx((315 + 120) / (630 + 120))
    assert rd.race_day_vdot(40.0, TODAY, _day(60), half) < rd.race_day_vdot(40.0, TODAY, _day(60), full)


def test_a_run_on_another_day_still_counts():
    moved = rd.adherence(FOUR_WEEKS, [_act(-d) for d in range(2, 29, 2)], TODAY)
    assert moved > 0.9


def test_a_session_ticked_off_by_hand_counts_half():
    """It was probably done, but nothing measured it."""
    ticked = [{**s, "complete": True} for s in FOUR_WEEKS]
    assert rd.adherence(ticked, [], TODAY) == pytest.approx((315 + 120) / (630 + 120))
    recorded = [{**s, "complete": True, "recorded": True} for s in FOUR_WEEKS]
    # A recorded one is counted from its activity, not twice.
    assert rd.adherence(recorded, [_act(-d) for d in range(1, 28, 2)], TODAY) == 1.0


def test_a_climbing_session_barely_moves_a_10k():
    """Every run swapped for two hours on the wall: a twentieth of each minute
    is running fitness, so almost none of the gain still to come is credited."""
    climbing = rd.adherence(FOUR_WEEKS, [_act(-d, "rock_climbing", 120.0) for d in range(1, 28, 2)], TODAY)
    nothing = rd.adherence(FOUR_WEEKS, [], TODAY)
    assert climbing - nothing < 0.12
    assert rd.race_day_vdot(40.0, TODAY, _day(60), climbing) - 40.0 < 0.25


def test_rides_count_for_more_than_climbing_and_less_than_runs():
    rides = rd.adherence(FOUR_WEEKS, [_act(-d, "cycling") for d in range(1, 28, 2)], TODAY)
    climbs = rd.adherence(FOUR_WEEKS, [_act(-d, "climbing") for d in range(1, 28, 2)], TODAY)
    assert climbs < rides < 1.0


def test_a_triathletes_rides_do_not_stand_in_for_the_runs_they_skipped():
    """A plan's rides and swims are planned too, weighed by what they do for
    the run: riding every ride and skipping every run is not the run plan."""
    plan = [_session(-d, s) for d, s in zip(range(1, 28, 2), ["swimming", "cycling", "running"] * 5)]
    rides_only = [_act(s["date"].toordinal() - TODAY.toordinal(), "cycling") for s in plan if s["sport"] == "cycling"]
    assert rd.adherence(plan, rides_only, TODAY) < 0.75


def test_a_new_plan_is_not_read_as_a_runner_who_quit():
    """The first planned run missed is one missed run, not zero adherence."""
    assert rd.adherence([], [], TODAY) == 1.0
    assert rd.adherence([_session(-1)], [], TODAY) == pytest.approx(120 / 165)


def test_running_before_the_plan_began_is_not_the_plan():
    begun = [_session(-3), _session(-1)]
    assert rd.adherence(begun, [_act(-20), _act(-25)], TODAY) == rd.adherence(begun, [], TODAY)


def test_todays_session_is_not_missed_until_its_day_is_over():
    assert rd.adherence(FOUR_WEEKS + [_session(0, minutes=90)], [_act(-d) for d in range(1, 28, 2)], TODAY) == 1.0


def test_a_taper_shows_up_as_race_morning_freshness():
    """Mid-build fatigue no longer slows a race weeks away: race morning is
    read after the plan's remaining sessions, a taper included."""
    history = {_day(-d): (90.0 if d < 10 else 60.0) for d in range(0, 120)}
    taper = {_day(d): (30.0 if d % 2 else 0.0) for d in range(1, 14)}
    tsb_today = rd.race_day_tsb(history, taper, TODAY, TODAY)
    tsb_race = rd.race_day_tsb(history, taper, TODAY, _day(14))
    assert tsb_today < 0 < tsb_race
    assert rd.freshness_factor(tsb_race) < 1.0 < rd.freshness_factor(tsb_today)


def test_planned_load_is_summed_the_same_whatever_order_the_rows_arrive_in():
    a = [(_day(1), 10.1), (_day(1), 0.2), (_day(1), 33.3), (_day(2), 5.0)]
    assert rd.planned_load_by_day(a) == rd.planned_load_by_day(list(reversed(a)))


def test_the_race_week_paces_the_race_the_race_plan_predicts():
    """The calendar's last week and the race plan's race day were two numbers:
    the calendar added a weekly gain, the race plan none. Now both are
    race_day_vdot, adherence and all."""
    race = _day(60)
    goal = SimpleNamespace(event_date=race, event_sport="running", event_distance_meters=10_000.0,
                           days_per_week=5, plan_intensity=None, mtb_discipline=None, cycling_discipline=None)
    fitness = {"vdot": 40.0, "source": "heart_rate", "measured": True, "effort_vdot": 38.0}
    for adherence in (1.0, 0.4):
        _, workouts = generate_training_plan(goal=goal, activity_history=[], pace_bests=[], today=TODAY,
                                             running_fitness=fitness, adherence=adherence)
        monday = race - timedelta(days=race.weekday())
        notes = [s.get("note", "") for w in workouts if w["scheduled_date"] >= monday
                 for s in w.get("steps", []) if s.get("pace") == "easy"]
        want = _fmt_pace(vdot_to_paces(rd.race_day_vdot(40.0, TODAY, race, adherence))["easy"])
        assert notes and all(want in n for n in notes if re.search(r"\d:\d\d/km", n)), (adherence, notes)


def test_the_race_day_corpus_still_holds():
    """A change that moves these numbers must be regenerated for the phone."""
    d = date.fromisoformat
    for c in CORPUS["projection"]:
        assert rd.plan_weeks(d(c["today"]), d(c["race"])) == c["weeks"]
        assert rd.race_day_vdot(c["vdot"], d(c["today"]), d(c["race"]), c["adherence"]) == c["expect"], c
    for c in CORPUS["adherence"]:
        planned = [{**p, "date": d(p["date"])} for p in c["planned"]]
        done = [{**a, "date": d(a["date"])} for a in c["done"]]
        assert rd.adherence(planned, done, d(c["today"])) == c["expect"], c["name"]
    for c in CORPUS["form"]:
        history = {d(k): v for k, v in c["history"].items()}
        planned = {d(k): v for k, v in c["planned"].items()}
        assert rd.race_day_tsb(history, planned, d(c["today"]), d(c["race"])) == c["expect"], c["name"]
    for c in CORPUS["freshness"]:
        assert rd.freshness_factor(c["tsb"]) == c["expect"]


def test_adherence_is_read_from_the_plans_own_rows(db, user):
    """The plan's past days stay as they were — done, ticked, missed — and
    that history, with every activity since, is what adherence reads."""
    from app.api.training_plan.helpers import _plan_adherence, _plan_sessions
    from app.calculators.local_day import user_today
    from app.models.activity import Activity
    from app.models.coaching import TrainingGoal
    from app.models.training_plan import PlannedWorkout, TrainingPlan

    today = user_today(db, user.id)
    goal = TrainingGoal(user_id=user.id, goal_type="event", event_date=today + timedelta(days=40),
                        event_sport="running", event_distance_meters=10_000)
    db.add(goal)
    db.flush()
    plan = TrainingPlan(goal_id=goal.id, user_id=user.id, sport="running")
    db.add(plan)
    db.flush()
    run = Activity(user_id=user.id, sport="running", duration_seconds=2700,
                   started_at=datetime.combine(today - timedelta(days=6), datetime.min.time(), timezone.utc)
                   + timedelta(hours=8))
    db.add(run)
    db.flush()
    for days_ago, complete, activity in ((6, True, run.id), (4, True, None), (2, False, None)):
        db.add(PlannedWorkout(plan_id=plan.id, user_id=user.id, scheduled_date=today - timedelta(days=days_ago),
                              sport="running", workout_type="easy", title="Easy", duration_minutes=45,
                              is_complete=complete, completed_activity_id=activity))
    db.commit()
    sessions = _plan_sessions(db, plan.id, today)
    assert sorted((s["complete"], s["recorded"]) for s in sessions) == [(False, False), (True, False), (True, True)]
    # 45 run + 22.5 ticked of 135 planned, with the prior on both sides.
    assert _plan_adherence(db, user.id, plan.id, today) == pytest.approx((45 + 22.5 + 120) / (135 + 120))
