# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Regenerate the golden FIT bytes that pin the mobile encoders to the backend.

`mobile/core/.../fit/` carries a Kotlin port of
`app/calculators/fit_workout.py`, and the contract between them is that they
produce identical bytes — see `WorkoutFitTest` for why that is the bar rather
than a decode-and-check. This script prints the Kotlin constants that test
compares against.

The cases here and the inputs in `WorkoutFitTest` are two halves of one thing:
adding a case means editing both, and they are named identically so a mismatch
is obvious.

Run against a checkout with the backend importable — in this project that means
inside the container, which is what the wrapper below does:

    docker cp scripts/gen_fit_goldens.py backend:/tmp/ &&
        docker exec backend python /tmp/gen_fit_goldens.py

Paste the output over the constants at the foot of WorkoutFitTest.kt.
"""
import sys
import textwrap

sys.path.insert(0, "/app")


from datetime import date

from app.calculators import fit_workout
from app.calculators.fit_workout import (
    generate_schedule_fit,
    generate_strength_workout_fit,
    generate_workout_fit,
)

# The schedule stamps its own file_id with the wall clock, which would make
# every regeneration differ. Pinning it is what makes the golden reproducible;
# the Kotlin side takes the same value as a parameter for the same reason.
PINNED_NOW_MS = 1_788_000_018_000
fit_workout._now_ms = lambda: PINNED_NOW_MS

PACES = {"easy": 330.0, "threshold": 260.0, "marathon": 300.0,
         "interval": 240.0, "repetition": 225.0, "recovery": 360.0}
TS = 1756000000000
c = {}

c['run_coached'] = generate_workout_fit(
    name="Threshold Run", sport="running",
    plan_steps=[{"type": "warmup", "duration_min": 15, "pace": "easy"},
                {"type": "interval_set", "reps": 4, "distance_m": 1000,
                 "rest_sec": 90, "pace": "threshold"},
                {"type": "cooldown", "duration_min": 10, "pace": "easy"}],
    workout_id=101, time_created=TS, pace_coaching=True, paces=PACES)

c['mtb_coached'] = generate_workout_fit(
    name="Sweet Spot", sport="mountain_biking",
    plan_steps=[{"type": "warmup", "duration_min": 10, "intensity": "easy"},
                {"type": "effort_set", "reps": 3, "duration_min_each": 8,
                 "rest_min": 4, "intensity": "sweet_spot"},
                {"type": "cooldown", "duration_min": 10, "intensity": "recovery"}],
    workout_id=102, time_created=TS, pace_coaching=True, lthr=165, ftp=250)

c['fartlek_plain'] = generate_workout_fit(
    name="Fartlek", sport="running",
    plan_steps=[{"type": "walk", "duration_min": 5},
                {"type": "fartlek", "hard_min": 3, "easy_min": 2, "reps": 5,
                 "pace": "threshold"},
                {"type": "walk", "duration_min": 5}],
    workout_id=103, time_created=TS)

c['empty_fallback'] = generate_workout_fit(
    name="Open Run", sport="running", plan_steps=[], workout_id=104, time_created=TS)

c['strength'] = generate_strength_workout_fit(
    name="Upper Body",
    exercises=[{"type": "strength_exercise", "name": "Bench Press", "sets": 3,
                "reps": 8, "weight_kg": 60.0, "rest_seconds": 120,
                "garmin_category": "bench_press", "garmin_subtype": 0},
               {"type": "strength_exercise", "name": "Barbell Row", "sets": 1,
                "reps": 10, "weight_kg": 40.0, "rest_seconds": 90,
                "garmin_category": "row", "garmin_subtype": 3}],
    workout_id=105, time_created=TS, workout_type="strength",
    description="Push and pull")

c['yoga'] = generate_strength_workout_fit(
    name="Evening Mobility",
    exercises=[{"type": "mobility_exercise", "name": "Low Lunge",
                "duration_seconds": 45, "sets": 2, "each_side": True,
                "garmin_category": "pose", "garmin_subtype": 41},
               {"type": "mobility_exercise", "name": "Childs Pose",
                "duration_seconds": 60, "sets": 1, "each_side": False,
                "garmin_category": "pose", "garmin_subtype": 12}],
    workout_id=106, time_created=TS, workout_type="mobility")

# Blocks: a rest block, a 3-round circuit with a rest between rounds, and a
# superset with no group rest. Members' own `sets` are ignored inside groups.
c['strength_blocks'] = generate_strength_workout_fit(
    name="Circuit",
    exercises=[{"type": "strength_exercise", "name": "Goblet Squat", "sets": 2,
                "reps": 10, "weight_kg": 20.0, "rest_seconds": 60,
                "garmin_category": "squat", "garmin_subtype": 37},
               {"type": "rest", "duration_seconds": 120},
               {"type": "strength_exercise", "name": "Push Up", "sets": 5, "reps": 12,
                "garmin_category": "push_up", "garmin_subtype": 77,
                "group": {"uid": "g1", "kind": "repeat", "rounds": 3, "rest_seconds": 90}},
               {"type": "strength_exercise", "name": "Barbell Row", "reps": 8, "weight_kg": 40.0,
                "garmin_category": "row", "garmin_subtype": 3,
                "group": {"uid": "g1", "kind": "repeat", "rounds": 3, "rest_seconds": 90}},
               {"type": "strength_exercise", "name": "Bench Press", "reps": 6, "weight_kg": 60.0,
                "garmin_category": "bench_press", "garmin_subtype": 0,
                "group": {"uid": "g2", "kind": "superset", "rounds": 2, "rest_seconds": 0}},
               {"type": "strength_exercise", "name": "Pull Up", "reps": 5,
                "garmin_category": "pull_up", "garmin_subtype": 38,
                "group": {"uid": "g2", "kind": "superset", "rounds": 2, "rest_seconds": 0}}],
    workout_id=112, time_created=TS, workout_type="strength")

c['yoga_blocks'] = generate_strength_workout_fit(
    name="Flow Rounds",
    exercises=[{"type": "mobility_exercise", "name": "Cat Cow", "duration_seconds": 30,
                "sets": 1, "garmin_category": "pose", "garmin_subtype": 16,
                "group": {"uid": "f1", "kind": "repeat", "rounds": 2, "rest_seconds": 15}},
               {"type": "mobility_exercise", "name": "Childs Pose", "duration_seconds": 45,
                "each_side": False, "garmin_category": "pose", "garmin_subtype": 12,
                "group": {"uid": "f1", "kind": "repeat", "rounds": 2, "rest_seconds": 15}},
               {"type": "rest", "duration_seconds": 30},
               {"type": "mobility_exercise", "name": "Low Lunge", "duration_seconds": 40,
                "sets": 2, "each_side": True, "garmin_category": "pose", "garmin_subtype": 41}],
    workout_id=113, time_created=TS, workout_type="mobility")

# Stretches the Yoga app has no pose for go out under their own names: none,
# one only the strength app animates, and a name repeated (one title, one number).
c['yoga_own_names'] = generate_strength_workout_fit(
    name="Unwind",
    exercises=[{"type": "mobility_exercise", "name": "Couch Stretch", "duration_seconds": 60,
                "sets": 2, "each_side": True},
               {"type": "mobility_exercise", "name": "Tennis Ball Foot Release",
                "duration_seconds": 60, "sets": 1,
                "garmin_category": "warm_up", "garmin_subtype": 37},
               {"type": "mobility_exercise", "name": "Thunderbolt Pose", "duration_seconds": 60,
                "sets": 1, "garmin_category": "pose", "garmin_subtype": 76},
               {"type": "mobility_exercise", "name": "Couch Stretch", "duration_seconds": 30,
                "sets": 1, "each_side": True}],
    workout_id=118, time_created=TS, workout_type="flexibility")

# Coaching off, with paces present: every target must be suppressed.
c['uncoached_run'] = generate_workout_fit(
    name="Easy Day", sport="running",
    plan_steps=[{"type": "run", "duration_min": 45, "pace": "easy"}],
    workout_id=107, time_created=TS, pace_coaching=False, paces=PACES)

# Threshold HR but no FTP: the heart-rate branch, which nothing else reaches.
c['hr_only'] = generate_workout_fit(
    name="Skills", sport="cycling",
    plan_steps=[{"type": "ride", "duration_min": 40, "intensity": "skills"}],
    workout_id=108, time_created=TS, pace_coaching=True, lthr=170, ftp=None)

# No Garmin mapping: unknown category/exercise and no exercise_title block.
c['strength_unmapped'] = generate_strength_workout_fit(
    name="Odd Lifts",
    exercises=[{"type": "strength_exercise", "name": "Sandbag Toss", "sets": 2,
                "reps": 6, "rest_seconds": 60}],
    workout_id=109, time_created=TS, workout_type="strength")

# Multi-byte truncation on both string fields.
c['long_strings'] = generate_workout_fit(
    name="Wide — " + "é" * 60, sport="running",
    plan_steps=[{"type": "run", "duration_min": 20}],
    workout_id=110, time_created=TS, description="Detail — " + "ü" * 200)

# Twenty distinct definitions: forces the local-type table to wrap.
c['definition_wrap'] = generate_workout_fit(
    name="Widths", sport="running",
    plan_steps=[{"type": "activity", "duration_min": 10, "intensity": "a" * i}
                for i in range(1, 21)],
    workout_id=111, time_created=TS)

# A pool set: distance warm-up, stroke targets, equipment, named steps.
c['swim_pool'] = generate_workout_fit(
    name="Technique", sport="swimming",
    plan_steps=[{"type": "warmup", "distance_m": 300, "duration_min": 6, "intensity": "easy"},
                {"type": "interval_set", "reps": 6, "distance_m": 50, "rest_sec": 20,
                 "intensity": "easy", "stroke": "drill", "equipment": "swim_kickboard", "label": "Kick"},
                {"type": "interval_set", "reps": 1, "distance_m": 800, "rest_sec": 0,
                 "intensity": "aerobic", "stroke": "freestyle", "label": "Long 800"},
                {"type": "cooldown", "distance_m": 200, "duration_min": 4, "intensity": "easy"}],
    workout_id=114, time_created=TS, pace_coaching=True, lthr=165)

# Rowing: heart rate on steady state, stroke rate on pieces, the Indoor Row app.
c['row_coached'] = generate_workout_fit(
    name="AT Pieces", sport="rowing",
    plan_steps=[{"type": "warmup", "duration_min": 10, "intensity": "easy"},
                {"type": "activity", "duration_min": 20, "intensity": "ut2",
                 "spm_low": 18, "spm_high": 20},
                {"type": "effort_set", "reps": 3, "duration_min_each": 8, "rest_min": 3,
                 "intensity": "threshold", "label": "AT", "spm_low": 22, "spm_high": 24},
                {"type": "interval_set", "reps": 4, "distance_m": 500, "rest_sec": 120,
                 "intensity": "race_pace", "label": "2k pace 500", "spm_low": 28, "spm_high": 32}],
    workout_id=115, time_created=TS, pace_coaching=True, lthr=170)

# Ski touring routes to alpine/backcountry with heart-rate bands.
c['skimo'] = generate_workout_fit(
    name="Threshold", sport="backcountry_skiing",
    plan_steps=[{"type": "warmup", "duration_min": 15, "intensity": "easy"},
                {"type": "effort_set", "reps": 3, "duration_min_each": 10, "rest_min": 3,
                 "intensity": "threshold", "label": "Threshold"}],
    workout_id=116, time_created=TS, pace_coaching=True, lthr=165)

# Climbing: its own app, timed steps, no targets.
c['climbing'] = generate_workout_fit(
    name="Finger Strength", sport="climbing",
    plan_steps=[{"type": "effort_set", "reps": 6, "duration_sec_each": 10, "rest_sec": 180,
                 "intensity": "max_strength", "label": "Max hang"}],
    workout_id=117, time_created=TS, pace_coaching=True, lthr=165)

c['schedule'] = generate_schedule_fit(
    items=[{"scheduled_date": date(2026, 9, 1), "workout_id": 101, "time_created": TS},
           {"scheduled_date": date(2026, 9, 3), "workout_id": 102, "time_created": TS + 1000},
           {"scheduled_date": date(2026, 9, 5), "workout_id": 105, "time_created": TS + 2000}],
    plan_name="Autumn Block", plan_end=date(2026, 9, 30))


NAMES = {
    "run_coached": "RUN_COACHED",
    "mtb_coached": "MTB_COACHED",
    "fartlek_plain": "FARTLEK_PLAIN",
    "empty_fallback": "EMPTY_FALLBACK",
    "strength": "STRENGTH",
    "yoga": "YOGA",
    "uncoached_run": "UNCOACHED_RUN",
    "hr_only": "HR_ONLY",
    "strength_unmapped": "STRENGTH_UNMAPPED",
    "long_strings": "LONG_STRINGS",
    "definition_wrap": "DEFINITION_WRAP",
    "schedule": "SCHEDULE",
    "strength_blocks": "STRENGTH_BLOCKS",
    "yoga_blocks": "YOGA_BLOCKS",
    "yoga_own_names": "YOGA_OWN_NAMES",
    "swim_pool": "SWIM_POOL",
    "row_coached": "ROW_COACHED",
    "skimo": "SKIMO",
    "climbing": "CLIMBING",
}


def emit(name: str, raw: bytes) -> str:
    lines = textwrap.wrap(raw.hex(), 96)
    body = "\n".join('    "%s" +' % line for line in lines[:-1])
    body += '\n    "%s"' % lines[-1]
    return "private const val %s =\n%s\n" % (name, body)


for key, constant in NAMES.items():
    print(emit(constant, c[key]))
