# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The plan answering fatigue it did not plan (calculators/plan/adapt.py).

Before this a 100 km ride the day before a planned run changed nothing, and
a week of riding instead of running left the next run as it was.
"""
from datetime import date, timedelta

from app.calculators.plan.adapt import adapt_to_fatigue
from app.calculators.plan.base import build_ramp

TUE = date(2026, 3, 3)


def _run(day, wtype="easy", minutes=40):
    return {"scheduled_date": day, "sport": "running", "workout_type": wtype,
            "duration_minutes": minutes, "distance_meters": 6000, "title": "Run", "description": "d",
            "steps": [{"type": "walk", "duration_min": 5},
                      {"type": "run", "duration_min": minutes - 10, "pace": "easy", "note": "Easy"},
                      {"type": "walk", "duration_min": 5}]}


def test_the_run_after_a_hundred_km_ride_moves():
    out = adapt_to_fatigue([_run(TUE)], today=TUE, ctl=40, atl=45,
                           recent=[(TUE - timedelta(days=1), "cycling", 220)])
    assert out[0]["scheduled_date"] == TUE + timedelta(days=1)
    assert out[0]["description"].startswith("Moved to Wednesday")


def test_a_big_ride_moves_only_the_next_session_not_the_week():
    out = adapt_to_fatigue([_run(TUE), _run(TUE + timedelta(days=2), "intervals")], today=TUE, ctl=40,
                           atl=45, recent=[(TUE - timedelta(days=1), "cycling", 220)])
    assert sorted(w["scheduled_date"] for w in out) == [TUE + timedelta(days=1), TUE + timedelta(days=2)]


def test_moderate_fatigue_shortens_and_eases_a_hard_session():
    out = adapt_to_fatigue([_run(TUE, "intervals", 50)], today=TUE, ctl=40, atl=63, recent=[])
    assert out[0]["workout_type"] == "easy"
    assert out[0]["duration_minutes"] == 35
    assert "Shortened and made easy" in out[0]["description"]


def test_deep_fatigue_with_nowhere_to_go_drops_the_session():
    sun = TUE + timedelta(days=5)
    out = adapt_to_fatigue([_run(sun)], today=sun, ctl=30, atl=70, recent=[])
    assert out == []


def test_riding_weighs_less_than_running_toward_a_run():
    ride = adapt_to_fatigue([_run(TUE)], today=TUE, ctl=45, atl=67,
                            recent=[(TUE - timedelta(days=k), "cycling", 90) for k in range(3)])
    run = adapt_to_fatigue([_run(TUE)], today=TUE, ctl=45, atl=67,
                           recent=[(TUE - timedelta(days=k), "running", 90) for k in range(3)])
    assert ride[0]["duration_minutes"] == 40, "the same fatigue from riding leaves the run alone"
    assert run[0]["duration_minutes"] < 40


def test_fresh_legs_change_nothing():
    plan = [_run(TUE), _run(TUE + timedelta(days=1), "intervals")]
    assert adapt_to_fatigue(plan, today=TUE, ctl=40, atl=38, recent=[]) == plan


def test_a_race_is_never_touched():
    race = dict(_run(TUE), workout_type="race")
    assert adapt_to_fatigue([race], today=TUE, ctl=30, atl=80, recent=[]) == [race]


def test_overall_fitness_lets_a_sport_build_faster_but_not_without_limit():
    assert build_ramp("running", 40, 38) == 1.10
    assert build_ramp("running", 70, 20) == 1.12
    assert build_ramp("cycling", 70, 20) == 1.15
    assert build_ramp("running", None, None) == 1.10
