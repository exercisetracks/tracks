# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Running sessions a plan should and should not hold.

Each case here is a session the generator used to schedule and a coach would
not: a marathon-pace block in a 5K plan's long run, two fartleks in one base
week, threshold work off the bike for a triathlete who cannot yet run without
walk breaks. The reasoning is at the code; these pin the outcome.

Dates are fixed: the generator takes `today`, so nothing depends on when the
suite runs.
"""

from datetime import date, timedelta
from types import SimpleNamespace

from app.calculators.plan.base import _phase_for_week, vdot_to_paces
from app.calculators.plan.generator.dispatch import _HIGH_INTENSITY_TYPES
from app.calculators.plan.generator.plan import generate_training_plan
from app.calculators.plan.generator.templates import _rotating_template
from app.calculators.plan.generator.week import (
    RUN_QUALITY_CAPACITY_KM,
    SportCtx,
    quality_ready,
    running_capacity_km,
)
from app.calculators.plan.running import _run_long

MONDAY = date(2026, 3, 2)
PACES = vdot_to_paces(45.0)


def _paces_of(steps):
    return {s.get("pace") for s in steps if s["type"] == "run"}


def test_a_5k_or_10k_long_run_stays_easy_through_the_build():
    """The marathon-pace block made the long run a third hard session in a
    build week that already held intervals and a tempo run."""
    for race_m in (5000, 10000):
        assert _paces_of(_run_long(90, "build", PACES, race_distance_m=race_m)) == {"easy"}


def test_a_half_or_full_marathon_long_run_keeps_its_marathon_pace_block():
    for race_m in (21097, 42195):
        assert "marathon" in _paces_of(_run_long(90, "build", PACES, race_distance_m=race_m))


def test_a_rotated_base_week_moves_its_fartlek_rather_than_adding_one():
    """Every third base week wrote a second fartlek into Friday and left
    Thursday's where it was."""
    for week_num in range(9):
        template = _rotating_template("running", "base", week_num, "trail", "road_race")
        assert template.count("fartlek") == 1, (week_num, template)
        assert sum(1 for t in template if t != "rest") == 4, (week_num, template)


def test_quality_waits_until_a_runner_can_run_half_an_hour_without_walking():
    assert not quality_ready(SportCtx(family="running", sport="running", capacity_km=3.0))
    assert quality_ready(SportCtx(family="running", sport="running", capacity_km=5.0))
    # Only running has a walk-break model; the other sports are unaffected.
    assert quality_ready(SportCtx(family="cycling", sport="cycling"))


def test_a_triathlete_new_to_running_gets_no_run_quality_but_keeps_swim_and_bike():
    """Swim and bike history, no runs: the run leg starts on the novice
    capacity model, so its build-phase quality goes, the others' stays."""
    history = []
    for d in range(2, 56, 2):
        day = MONDAY - timedelta(days=d)
        history.append(SimpleNamespace(sport="cycling", started_at=day, distance_meters=40000.0,
                                       duration_seconds=5400))
        history.append(SimpleNamespace(sport="swimming", started_at=day, distance_meters=2000.0,
                                       duration_seconds=2700))
    goal = SimpleNamespace(event_date=MONDAY + timedelta(weeks=16), event_sport="triathlon",
                           event_distance_meters=51500.0, days_per_week=6, plan_intensity=1.0,
                           mtb_discipline=None, cycling_discipline=None)
    _, workouts = generate_training_plan(goal, history, [], MONDAY)
    hard = [w for w in workouts if w["workout_type"] in _HIGH_INTENSITY_TYPES
            and w["workout_type"] != "short_quality"]
    week = lambda w: (w["scheduled_date"] - MONDAY).days // 7  # noqa: E731
    # The week the run leg's capacity (no runs, 6 days a week) reaches 5 km.
    ready = next(n for n in range(16) if running_capacity_km(n * 6 / 5.0, 0.0) >= RUN_QUALITY_CAPACITY_KM)
    assert any(_phase_for_week(n, 16) == "build" for n in range(ready)), "premise: build before ready"
    assert min(week(w) for w in hard if w["sport"] == "running") >= ready
    assert {"swimming", "cycling"} <= {w["sport"] for w in hard if week(w) < ready}
