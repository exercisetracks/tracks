# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Fitness goal's rolling plan: a CTL ramp turned into weekly load.

Dates are anchored to a fixed Monday: the generator takes `today` as an input,
so nothing here depends on when the suite runs.
"""

from datetime import date, timedelta
from types import SimpleNamespace

import pytest

from app.calculators.coaching.signal import _build_signal
from app.calculators.plan.generator.fitness import (
    fitness_horizon_end,
    fitness_week_targets,
    generate_fitness_plan,
)

MONDAY = date(2026, 3, 2)
# A goal anchored a whole number of cycles before MONDAY starts its cycle on
# MONDAY, so the plan's fourth week is the light one.
LIGHT_LAST = MONDAY - timedelta(weeks=8)


def _goal(ramp, sport="running", dpw=4, sports=None):
    return SimpleNamespace(event_sport=sport, ctl_ramp_per_week=ramp, days_per_week=dpw,
                           mtb_discipline=None, cycling_discipline=None, fitness_sports=sports)


def _build_weeks(targets):
    return [t for t in targets if not t["recovery"]]


def test_the_weekly_load_moves_ctl_by_the_chosen_ramp():
    """The slider promises "+3 CTL / week"; a plan that delivers +1 or +6
    would make the number on it a lie."""
    targets = fitness_week_targets(50.0, 50.0, 3.0, MONDAY, LIGHT_LAST)
    start = 50.0
    for t in targets[:3]:
        assert not t["recovery"]
        assert t["ctl_end"] - start == pytest.approx(3.0, abs=0.1)
        start = t["ctl_end"]


def test_holding_at_zero_trains_at_the_current_ctl():
    """Maintain means a day's load equal to CTL — which is what CTL is."""
    targets = fitness_week_targets(42.0, 42.0, 0.0, MONDAY, LIGHT_LAST)
    for t in _build_weeks(targets):
        assert t["tss"] == pytest.approx(7 * 42.0, abs=0.2)
        assert t["ctl_end"] == pytest.approx(42.0, abs=0.1)


def test_every_fourth_week_counted_from_the_goal_is_lighter():
    """Counted from the plan's own first week, a plan rebuilt every few days
    would push the light week three weeks out forever. Counted from the goal,
    the same calendar week stays light however often it is rebuilt."""
    targets = fitness_week_targets(50.0, 50.0, 4.0, MONDAY, LIGHT_LAST)
    assert [t["recovery"] for t in targets] == [False, False, False, True]
    assert targets[3]["tss"] < min(t["tss"] for t in targets[:3]) * 0.7

    a_week_later = fitness_week_targets(50.0, 50.0, 4.0, MONDAY + timedelta(days=9), LIGHT_LAST)
    assert [t["recovery"] for t in a_week_later] == [False, False, True, False]
    assert a_week_later[2]["week_start"] == targets[3]["week_start"]


def test_a_deeply_fatigued_start_holds_the_first_week():
    """TSB below −30 is the High Risk band; building on top of it is how a
    plan hurts someone."""
    fresh = fitness_week_targets(50.0, 55.0, 3.5, MONDAY, LIGHT_LAST)
    buried = fitness_week_targets(50.0, 85.0, 3.5, MONDAY, LIGHT_LAST)
    assert fresh[0]["ramp"] == 3.5
    assert buried[0]["ramp"] == 0.0
    assert buried[1]["ramp"] == 3.5


def test_detraining_never_asks_for_negative_load():
    """At −2 from a low CTL the arithmetic wants less than nothing."""
    for t in fitness_week_targets(5.0, 5.0, -2.0, MONDAY, LIGHT_LAST):
        assert t["tss"] >= 0.0


def test_a_ramp_outside_the_slider_is_clamped():
    """A synced value from a bad client must not plan a +20 week."""
    assert fitness_week_targets(40.0, 40.0, 20.0, MONDAY, LIGHT_LAST) == \
        fitness_week_targets(40.0, 40.0, 6.0, MONDAY, LIGHT_LAST)


def test_the_top_of_the_slider_is_plus_six():
    """+6 is deliverable and the last ramp before the first week asks for
    about twice CTL (schemas/coaching.py); a synced +8 plans the +6 weeks."""
    assert fitness_week_targets(40.0, 40.0, 8.0, MONDAY, LIGHT_LAST) == \
        fitness_week_targets(40.0, 40.0, 6.0, MONDAY, LIGHT_LAST)
    assert fitness_week_targets(40.0, 40.0, 6.0, MONDAY, LIGHT_LAST) != \
        fitness_week_targets(40.0, 40.0, 5.5, MONDAY, LIGHT_LAST)


def _minutes(workouts):
    return sum(w["duration_minutes"] or 0 for w in workouts)


def test_a_steeper_ramp_plans_more_training():
    """The load targets have to reach the calendar, not stop at a number."""
    lo = _minutes(generate_fitness_plan(_goal(0.0), [], [], MONDAY, 35.0, 35.0, LIGHT_LAST)[1])
    hi = _minutes(generate_fitness_plan(_goal(4.0), [], [], MONDAY, 35.0, 35.0, LIGHT_LAST)[1])
    assert hi > lo * 1.2


def test_the_plan_covers_this_week_and_the_next_three():
    wed = MONDAY + timedelta(days=2)
    _, workouts = generate_fitness_plan(_goal(2.0, sport="cycling"), [], [], wed, 40.0, 40.0, LIGHT_LAST)
    dates = [w["scheduled_date"] for w in workouts]
    assert min(dates) >= wed
    assert max(dates) <= fitness_horizon_end(wed) == MONDAY + timedelta(days=27)
    assert {w["sport"] for w in workouts} == {"cycling"}
    assert all(w["workout_type"] != "race" for w in workouts)


def test_a_build_ramp_gets_quality_that_a_maintain_ramp_does_not():
    """From +3 a week uses the build template (two quality days) — holding
    fitness does not need the intensity of raising it."""
    def quality(ramp):
        _, ws = generate_fitness_plan(_goal(ramp, dpw=5), [], [], MONDAY, 40.0, 40.0, LIGHT_LAST)
        return sum(w["workout_type"] in ("intervals", "tempo") for w in ws)
    assert quality(4.0) > quality(0.0)


@pytest.mark.parametrize("ramp,phase", [(3.0, "build"), (0.0, "maintain"), (None, "maintain"),
                                        (-1.5, "recovery")])
def test_the_coaching_signal_names_a_fitness_goals_direction(ramp, phase):
    """A fitness goal has no date to count phases back from; before it had a
    phase at all, the signal read it as no goal."""
    goal = SimpleNamespace(goal_type="fitness", ctl_ramp_per_week=ramp, event_date=None)
    assert _build_signal(40.0, 45.0, 38.0, goal, MONDAY).phase == phase
