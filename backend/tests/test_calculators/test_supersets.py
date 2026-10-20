# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Supersets form only where nobody crosses the gym, and first weights come from body weight."""
from app.calculators.strength_plan.loads import starting_weight
from app.calculators.strength_plan.periodization import _rest_seconds
from app.calculators.strength_plan.supersets import group_supersets

LIB = {
    "Goblet Squat": {"equipment": ["dumbbell"]},
    "Dumbbell Row": {"equipment": ["dumbbell"]},
    "Dip": {"equipment": ["bodyweight"]},
    "Leg Press": {"equipment": ["machine"]},
    "Lat Pull-down": {"equipment": ["cable", "machine"]},
    "Heavy Deadlift": {"equipment": ["barbell"]},
}


def _lift(name, muscles, reps=8, sets=3, rest=120):
    return {"type": "strength_exercise", "name": name, "primary_muscles": muscles, "reps": reps,
            "sets": sets, "rest_seconds": rest, "movement_pattern": "push", "phase": "main"}


def test_lifts_sharing_dumbbells_go_in_turn_with_a_rest_after_each():
    """The soul-crushing version was every set of one lift before the next."""
    steps = [_lift("Goblet Squat", ["quads"], rest=150), _lift("Dumbbell Row", ["lats"], rest=120),
             _lift("Dip", ["triceps"], rest=90)]
    out = group_supersets(steps, LIB)
    assert [s["type"] for s in out] == ["strength_exercise", "rest", "strength_exercise", "rest", "strength_exercise"]
    assert [s.get("duration_seconds") for s in out if s["type"] == "rest"] == [150, 120]
    group = out[0]["group"]
    assert group["kind"] == "superset" and group["rounds"] == 3 and group["rest_seconds"] == 90


def test_two_machines_never_pair_and_heavy_sets_stay_straight():
    """A leg press and a pull-down are across the room; a 5-rep deadlift wants full rest."""
    steps = [_lift("Leg Press", ["quads"]), _lift("Lat Pull-down", ["lats"]),
             _lift("Heavy Deadlift", ["hamstrings"], reps=5)]
    assert all("group" not in s for s in group_supersets(steps, LIB))


def test_a_muscle_is_never_worked_twice_in_one_superset():
    steps = [_lift("Goblet Squat", ["quads"]), _lift("Dip", ["quads"])]
    assert all("group" not in s for s in group_supersets(steps, LIB))


def test_a_first_weight_scales_with_body_weight_and_is_per_hand_for_dumbbells():
    """A 100 kg lifter and a 60 kg one should not both start at the bar."""
    heavy = starting_weight("squat", True, ["barbell"], 0.72, 100.0, "male")
    light = starting_weight("squat", True, ["barbell"], 0.72, 60.0, "male")
    assert heavy > light > 20
    assert starting_weight("push", True, ["dumbbell"], 0.72, 80.0, "male") < starting_weight("push", True, ["barbell"], 0.72, 80.0, "male") / 2
    assert starting_weight("push", True, ["bodyweight"], 0.72, 80.0, "male") == 0.0


def test_rest_runs_one_to_three_minutes_by_how_hard_the_movement_is():
    assert _rest_seconds("squat", 3, True, 5) == 180
    assert _rest_seconds("isolation", 3, False, 12) == 60
    assert _rest_seconds("isometric", 1, True, 30) == 60
    assert 60 < _rest_seconds("push", 3, True, 8) < 180
