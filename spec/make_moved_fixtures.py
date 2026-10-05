# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the moved-workout corpus.

What a regeneration does with the workouts the user dragged to another day:
``app.calculators.plan.moved.keep_moved``, run over plans the backend's own
generator builds and over hand-made weeks that reach each branch of the
matching rule (same type, same role, sport tie-break, nearest date, across a
week boundary, completed, in the past, two moved workouts wanting one
session, no counterpart at all) — and, as ``completed_cases``, what
``keep_completed`` leaves of a day that already has finished workouts. The phone's port
(com.tracks.core.plan.MovedWorkouts) replays every case and must agree: the
same sessions dropped, the same content copied onto the same workouts.

Regenerable, like make_plan_fixtures.py: the Python is the oracle. Dates are
fixed, never "today".

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_moved_fixtures.py
"""

from __future__ import annotations

import json
import sys
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.calculators.plan.generator.fitness import generate_fitness_plan  # noqa: E402
from app.calculators.plan.moved import ROLES, keep_completed, keep_moved  # noqa: E402

OUT = Path("/spec/fixtures/moved_workouts.json")
MONDAY = date(2026, 10, 5)


def _plain(v):
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


def _case(name, generated, moved, today):
    remaining, refresh = keep_moved(generated, moved, today)
    kept = [i for i, g in enumerate(generated) if any(g is r for r in remaining)]
    return {
        "name": name,
        "today": today.isoformat(),
        "generated": _plain(generated),
        "moved": _plain(moved),
        "expect": {"kept": kept, "refresh": _plain(refresh)},
    }


def _w(day, wtype, sport="running", title=None, minutes=40):
    return {"scheduled_date": day, "sport": sport, "workout_type": wtype,
            "title": title or wtype.title(), "description": f"{wtype} {minutes}",
            "duration_minutes": minutes, "distance_meters": None,
            "steps": [{"type": "active", "duration_minutes": minutes}]}


def _m(uid, day, wtype, sport="running", done=False):
    return {"uid": uid, "scheduled_date": day, "sport": sport,
            "workout_type": wtype, "is_complete": done}


def hand_cases() -> list:
    d = MONDAY
    week = [_w(d, "easy"), _w(d + timedelta(1), "tempo", minutes=45),
            _w(d + timedelta(3), "intervals", minutes=50), _w(d + timedelta(4), "easy"),
            _w(d + timedelta(5), "strength", sport="strength_training"),
            _w(d + timedelta(6), "long", minutes=90),
            _w(d + timedelta(8), "tempo", minutes=50), _w(d + timedelta(13), "long", minutes=100)]
    return [
        _case("same_type_same_week_takes_its_content", week,
              [_m("a", d + timedelta(2), "tempo")], d),
        _case("nearest_of_two_same_role_sessions_wins", week,
              [_m("a", d + timedelta(4), "threshold")], d),
        _case("a_role_match_when_the_type_is_gone", week,
              [_m("a", d + timedelta(2), "fartlek")], d),
        _case("moved_into_the_next_week_matches_that_week", week,
              [_m("a", d + timedelta(10), "tempo")], d),
        _case("no_counterpart_leaves_it_unchanged", week,
              [_m("a", d + timedelta(2), "mobility", sport="strength_training")], d),
        _case("completed_claims_but_keeps_its_content", week,
              [_m("a", d + timedelta(2), "tempo", done=True)], d),
        _case("a_past_moved_workout_claims_nothing", week,
              [_m("a", d + timedelta(2), "tempo")], d + timedelta(3)),
        _case("two_moved_one_session_the_earlier_gets_it", week,
              [_m("b", d + timedelta(4), "long"), _m("a", d + timedelta(4), "long"),
               _m("c", d + timedelta(2), "long")], d),
        _case("same_type_beats_nearer_same_role", week,
              [_m("a", d + timedelta(3), "tempo")], d),
        _case("same_sport_breaks_a_type_tie",
              [_w(d + timedelta(1), "tempo", sport="cycling"), _w(d + timedelta(3), "tempo")],
              [_m("a", d + timedelta(2), "tempo")], d),
        _case("field_tests_are_one_role",
              [_w(d + timedelta(2), "field_test:ftp20", sport="cycling")],
              [_m("a", d + timedelta(4), "field_test:pmax5", sport="cycling")], d),
        _case("an_unknown_type_matches_only_itself",
              [_w(d + timedelta(2), "yoga_flow"), _w(d + timedelta(3), "easy")],
              [_m("a", d + timedelta(4), "yoga_flow"), _m("b", d + timedelta(5), "something")], d),
        _case("nothing_moved_changes_nothing", week, [], d),
    ]


def _completed_case(name, generated, completed, today):
    remaining = keep_completed(generated, completed, today)
    return {
        "name": name,
        "today": today.isoformat(),
        "generated": _plain(generated),
        "completed": _plain(completed),
        "expect": {"kept": [i for i, g in enumerate(generated) if any(g is r for r in remaining)]},
    }


def completed_cases() -> list:
    d = MONDAY
    day = [_w(d, "easy"), _w(d, "flexibility", sport="flexibility", minutes=10),
           _w(d, "strength", sport="strength_training"), _w(d + timedelta(1), "tempo")]
    return [
        _completed_case("a_finished_run_claims_its_session_and_the_rest_of_the_day_is_written",
                        day, [_m("a", d, "easy", done=True)], d),
        _completed_case("a_role_match_when_the_type_changed",
                        day, [_m("a", d, "recovery", done=True)], d),
        _completed_case("only_its_own_day_not_the_week",
                        day, [_m("a", d + timedelta(2), "tempo", done=True)], d),
        _completed_case("no_counterpart_claims_nothing",
                        day, [_m("a", d, "long", done=True)], d),
        _completed_case("before_today_claims_nothing",
                        day, [_m("a", d, "easy", done=True)], d + timedelta(1)),
        _completed_case("two_finished_one_session_one_claims_it",
                        [_w(d, "easy"), _w(d, "tempo")],
                        [_m("b", d, "easy", done=True), _m("a", d, "recovery", done=True)], d),
        _completed_case("same_sport_breaks_a_type_tie",
                        [_w(d, "easy", sport="cycling"), _w(d, "easy")],
                        [_m("a", d, "easy", done=True)], d),
    ]


def generated_cases() -> list:
    """Real plans: the moved workouts are sessions of the plan itself, moved
    two days later and asked back through a plan built one ramp step higher."""
    out = []
    for sport, dpw in (("running", 5), ("cycling", 4), ("running", 6)):
        goal = SimpleNamespace(event_sport=sport, days_per_week=dpw, ctl_ramp_per_week=2.0,
                               mtb_discipline=None, cycling_discipline=None)
        pb = [(5000, 5000 / 1500)] if sport == "running" else []
        _, before = generate_fitness_plan(goal, [], pb, MONDAY, 40.0, 40.0, MONDAY, ftp=240)
        goal.ctl_ramp_per_week = 3.0
        _, after = generate_fitness_plan(goal, [], pb, MONDAY + timedelta(2), 42.0, 40.0,
                                         MONDAY, ftp=240)
        moved = []
        for i, w in enumerate(before):
            if w["workout_type"] in ("race", "rest") or w["scheduled_date"] < MONDAY + timedelta(2):
                continue
            if i % 3 == 0:
                moved.append(_m(f"m{i}", w["scheduled_date"] + timedelta(2), w["workout_type"], w["sport"]))
        out.append(_case(f"generated_{sport}_{dpw}_days", after, moved, MONDAY + timedelta(2)))
    return out


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_moved_fixtures.py from app.calculators.plan.moved. Do not edit.",
        "roles": ROLES,
        "cases": hand_cases() + generated_cases(),
        "completed_cases": completed_cases(),
    }
    OUT.write_text(json.dumps(corpus, indent=1, ensure_ascii=False) + "\n")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB), {len(corpus['cases'])} cases")
    return 0


if __name__ == "__main__":
    sys.exit(main())
