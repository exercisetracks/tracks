# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline where a plan starts with no history: spec/fixtures/plan_start.json.

calculators/plan/starting.py (how often someone does a sport, read only when
they have no history of it) and the two generators that read it, run
in-process. The phone's port (com.tracks.core.plan.PlanStart, and the same
generators in mobile/core) replays every case in PlanStartFixtureTest.

Kept apart from plan.json on purpose: the generator is reworked often, and a
separate corpus keeps this one's cases readable — every level, for an event
plan and a fitness plan, plus the cases where history must win.

Regenerable, synthetic, fixed dates. Touches no database, but importing `app`
reads config:

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_plan_start_fixtures.py
"""

from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.calculators.plan import starting  # noqa: E402
from app.calculators.plan.generator import fitness as fitness_mod  # noqa: E402
from app.calculators.plan.generator import plan as plan_mod  # noqa: E402

OUT = Path("/spec/fixtures/plan_start.json")


def _plain(v):
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


def helper_cases() -> dict:
    levels = list(starting.LEVELS) + [None, "", "daily", "NEVER"]
    maps = [
        None, {}, "running", ["running"],
        {"running": "3_4"}, {"running": "bogus"},
        {"cycling": "5_plus"}, {"cycling": "never", "mountain_biking": "1_2"},
        {"cycling": "occasional", "mountain_biking": "bogus"},
    ]
    families = ["running", "cycling", "mountain_biking", "swimming", "hiking"]
    return {
        "levels": list(starting.LEVELS),
        "ramp_weeks": starting.RAMP_WEEKS,
        "starts": [{"level": lv, "expect": starting.no_history_start(lv)} for lv in levels],
        "frequency_for": [{"map": m, "family": f, "expect": starting.frequency_for(m, f)}
                          for m in maps for f in families],
        "intensity": [{"level": lv, "week": w,
                       "expect": starting.start_intensity(starting.no_history_start(lv), w)}
                      for lv in levels for w in range(0, 9)],
    }


def _history(today: date, sport: str, weekly_km: float) -> list:
    """Three runs a week for eight weeks: history that must beat any answer."""
    out = []
    for week in range(8):
        for day in (0, 2, 4):
            d = today - timedelta(weeks=week + 1, days=-day)
            out.append(SimpleNamespace(sport=sport, started_at=d, distance_meters=weekly_km * 1000 / 3))
    return out


def plan_cases() -> list:
    today = date(2026, 3, 4)
    cases = []

    def add(name, goal, history, level, **kw):
        g = SimpleNamespace(event_date=goal.get("event_date"), event_sport=goal.get("event_sport"),
                            event_distance_meters=goal.get("event_distance_meters"),
                            days_per_week=goal.get("days_per_week"), plan_intensity=goal.get("plan_intensity"),
                            mtb_discipline=None, cycling_discipline=None)
        vdot, workouts = plan_mod.generate_training_plan(
            goal=g, activity_history=history, pace_bests=[], today=today,
            activity_frequency=level, **kw)
        cases.append({
            "name": name, "today": today.isoformat(), "goal": _plain(goal),
            "history": [{"sport": h.sport, "started": h.started_at.isoformat(),
                         "distance": h.distance_meters} for h in history],
            "level": level, "kw": kw, "vdot": vdot, "workouts": _plain(workouts),
        })

    events = [
        ("run_10k", {"event_sport": "running", "event_distance_meters": 10000.0}),
        ("ride_100k", {"event_sport": "cycling", "event_distance_meters": 100000.0}),
        ("hike_20k", {"event_sport": "hiking", "event_distance_meters": 20000.0}),
    ]
    for tag, goal in events:
        goal = {**goal, "event_date": today + timedelta(weeks=12)}
        for level in list(starting.LEVELS) + [None]:
            add(f"{tag}_{level}", goal, [], level)
    run = {"event_sport": "running", "event_distance_meters": 21097.5,
           "event_date": today + timedelta(weeks=14)}
    add("run_half_history_beats_never", run, _history(today, "running", 30.0), "never")
    add("run_half_fingerprint_and_never", run, [], "never", base_effective_weeks=40.0)
    add("run_half_five_plus_three_days_imperial", {**run, "days_per_week": 3}, [], "5_plus", imperial=True)
    return cases


def fitness_cases() -> list:
    today = date(2026, 3, 2)
    cases = []

    def add(name, goal, ctl, atl, level, frequencies=None, **kw):
        g = SimpleNamespace(event_sport=goal.get("event_sport"),
                            ctl_ramp_per_week=goal.get("ctl_ramp_per_week"),
                            days_per_week=goal.get("days_per_week"),
                            fitness_sports=goal.get("fitness_sports"),
                            mtb_discipline=None, cycling_discipline=None)
        vdot, workouts = fitness_mod.generate_fitness_plan(
            goal=g, activity_history=[], pace_bests=[], today=today, ctl=ctl, atl=atl,
            anchor=None, activity_frequency=level, activity_frequencies=frequencies, **kw)
        cases.append({"name": name, "today": today.isoformat(), "goal": goal, "ctl": ctl, "atl": atl,
                      "level": level, "frequencies": frequencies, "kw": kw, "vdot": vdot,
                      "workouts": _plain(workouts)})

    for sport in ("running", "cycling"):
        for level in list(starting.LEVELS) + [None]:
            add(f"{sport}_{level}", {"event_sport": sport, "ctl_ramp_per_week": 2.0}, 0.0, 0.0, level)
    add("running_history_beats_five_plus", {"event_sport": "running", "ctl_ramp_per_week": 2.0},
        12.0, 10.0, "5_plus")
    add("running_just_under_one_ctl", {"event_sport": "running", "ctl_ramp_per_week": 0.0},
        0.9, 3.0, "3_4")
    # Several sports: each reads its own answer from the whole map.
    multi = {"event_sport": "running", "fitness_sports": ["running", "cycling"],
             "ctl_ramp_per_week": 2.0, "days_per_week": 5}
    add("multi_never_runs_rides_a_lot", multi, 0.0, 0.0, None,
        frequencies={"running": "never", "cycling": "5_plus"})
    add("multi_both_occasional", multi, 0.0, 0.0, None,
        frequencies={"running": "occasional", "cycling": "occasional"})
    add("multi_mtb_borrows_cycling", {**multi, "fitness_sports": ["mountain_biking", "swimming"]},
        0.0, 0.0, None, frequencies={"cycling": "3_4", "swimming": "never"})
    add("multi_level_only_for_first_sport", multi, 0.0, 0.0, "never")
    add("multi_history_beats_the_map", multi, 25.0, 25.0, None,
        frequencies={"running": "never", "cycling": "never"})
    return cases


def main() -> int:
    corpus = {"helpers": helper_cases(), "plans": plan_cases(), "fitness": fitness_cases()}
    OUT.write_text(json.dumps(corpus, separators=(",", ":"), allow_nan=False, ensure_ascii=False) + "\n")
    print(f"wrote {OUT}: {len(corpus['plans'])} event plans, {len(corpus['fitness'])} fitness plans")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
