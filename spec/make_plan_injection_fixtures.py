# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Record the server's whole plan assembly, so the phone's can be held to it.

A plan the phone generates is the endurance plan plus what the server injects
after it: strength and weekly mobility sessions, and a stretch flow after each
training day. Each piece already has its own fixtures; this pins the assembly —
the order they run in, what each sees of the others, and the stretch-candidate
gate in front of them — end to end, over the real bundled library.

Runs the server's own functions with no database: the strength generator, the
custom-workout pass, the stretch-flow placement, and `_build_candidates` over
the spec library. Run inside the backend image:

    docker run --rm --env-file .env -v $PWD/backend:/app -v $PWD/spec:/spec \\
        tracks-backend python /spec/make_plan_injection_fixtures.py
"""

import json
import sys
from datetime import date, datetime
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.api.flexibility.generation import _build_candidates, generate_post_activity_stretch_flow  # noqa: E402
from app.api.training_plan.injectors import attach_custom_workouts, inject_stretch_flows_with  # noqa: E402
from app.calculators.plan.base import _sport_family  # noqa: E402
from app.calculators.plan.generator import plan as plan_mod  # noqa: E402
from app.calculators.strength_plan.generator import generate_strength_workouts  # noqa: E402
from app.spec.library import EXERCISES, STRETCHES  # noqa: E402

OUT = Path("/spec/fixtures/plan_injection.json")


def _plain(v):
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    if isinstance(v, set):
        return sorted(_plain(x) for x in v)
    return v


def _library() -> dict:
    """`get_exercise_library_cache()`'s shape, from the spec library."""
    return {
        r["name"]: {
            "garmin_category": r.get("garmin_category"),
            "garmin_subtype": r.get("garmin_subtype"),
            "has_animation": bool(r.get("has_animation")),
            "primary_muscles": r.get("primary_muscles") or [],
            "secondary_muscles": r.get("secondary_muscles") or [],
            "equipment": r.get("equipment") or ["bodyweight"],
            "movement_pattern": r.get("movement_pattern"),
            "sport_relevance": r.get("sport_relevance") or {},
            "difficulty": r.get("difficulty"),
            "is_compound": r.get("is_compound"),
            "cues": r.get("cues") or [],
        }
        for r in EXERCISES
    }


def _stretch_row(r: dict) -> SimpleNamespace:
    keys = ("name", "primary_muscles", "secondary_muscles", "movement_pattern", "difficulty",
            "duration_per_side_sec", "sets", "each_side", "description", "garmin_category",
            "garmin_subtype", "equipment", "cues", "breath_cue", "position")
    return SimpleNamespace(**{k: r.get(k) for k in keys})


# Cases span the three paths through the assembly: strength + flows, flows only
# (a goal without include_strength), and a user whose preferences and
# exclusions move both the exercise picks and the stretch pool.
CASES = [
    dict(name="running_5k_tier3_with_strength", today="2026-03-02", event="2026-06-14", sport="running",
         distance=5000, days=4, include_strength=True, tier=3, strength_days=None, salt="1772400000",
         equipment=["bodyweight", "dumbbell"], preferred=[], excluded=[], stretch_preferred=[],
         stretch_excluded=[], experience=None, units="metric"),
    dict(name="cycling_century_tier4_two_strength_days", today="2026-04-06", event="2026-08-30",
         sport="cycling", distance=160000, days=5, include_strength=True, tier=4, strength_days=2,
         salt="1775400000", equipment=["bodyweight", "dumbbell", "barbell", "bench"], preferred=[],
         excluded=[], stretch_preferred=[], stretch_excluded=[], experience="intermediate",
         units="imperial"),
    dict(name="marathon_without_strength_gets_flows_only", today="2026-01-05", event="2026-05-03",
         sport="running", distance=42195, days=5, include_strength=False, tier=3, strength_days=None,
         salt="1767600000", equipment=["bodyweight"], preferred=[], excluded=[], stretch_preferred=[],
         stretch_excluded=[], experience=None, units="metric"),
    dict(name="preferences_and_exclusions_move_both_pools", today="2026-02-02", event="2026-05-24",
         sport="trail_running", distance=21097, days=4, include_strength=True, tier=2,
         strength_days=None, salt="1770000000", equipment=["bodyweight", "dumbbell", "kettlebell"],
         preferred=["Goblet Squat"], excluded=["Push-Up"], stretch_preferred=["Butterfly Stretch"],
         stretch_excluded=["Cat Pose", "Bridge Pose"], experience="beginner", units="metric"),
]


def run_case(c: dict) -> dict:
    today = date.fromisoformat(c["today"])
    event = date.fromisoformat(c["event"])
    goal = SimpleNamespace(
        event_date=event, event_sport=c["sport"], event_distance_meters=c["distance"],
        days_per_week=c["days"], plan_intensity=None, mtb_discipline=None, cycling_discipline=None,
        include_strength=c["include_strength"], strength_tier=c["tier"],
        strength_days_per_week=c["strength_days"],
    )
    _, endurance = plan_mod.generate_training_plan(
        goal=goal, activity_history=[], pace_bests=[], today=today, days_per_week=c["days"],
        imperial=c["units"] == "imperial")
    endurance_in = _plain(endurance)

    candidates = _build_candidates(
        [_stretch_row(r) for r in STRETCHES], [], {},
        set(c["stretch_preferred"]), set(c["stretch_excluded"]))

    workouts = [dict(w) for w in endurance]
    if c["include_strength"]:
        strength = generate_strength_workouts(
            goal=goal, sport_family=_sport_family(c["sport"].lower()), today=today,
            existing_workouts=workouts, equipment=c["equipment"], library=_library(),
            strength_records={}, active_injuries=[], sessions_count=0, units=c["units"],
            preferred_exercises=set(c["preferred"]) or None,
            excluded_exercises=set(c["excluded"]) or None,
            session_max_minutes=None, regen_salt=c["salt"], confirmed_no_exercises=None,
            stretch_candidates=candidates, experience=c["experience"], anchor_date=None,
        )
        workouts = attach_custom_workouts(workouts, strength, [], set())

    if candidates:
        def flow_for(sport, variety_key, is_strength, primary_muscles, cooldown_theme):
            return generate_post_activity_stretch_flow(
                sport=sport, regen_salt=c["salt"], variety_key=variety_key,
                is_strength=is_strength, primary_muscles=primary_muscles,
                candidates=candidates, cooldown_theme=cooldown_theme, return_meta=True)
        workouts = inject_stretch_flows_with(workouts, flow_for)

    return {**c, "endurance": endurance_in, "candidates": [x.name for x in candidates],
            "expected": _plain(workouts)}


def main() -> int:
    out = {"cases": [run_case(c) for c in CASES]}
    OUT.write_text(json.dumps(out, indent=1, sort_keys=True))
    total = sum(len(c["expected"]) for c in out["cases"])
    print(f"wrote {OUT} ({len(out['cases'])} plans, {total} workouts)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
