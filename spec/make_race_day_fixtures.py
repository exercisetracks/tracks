# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the race-day corpus: spec/fixtures/race_day.json.

calculators/plan/race_day.py — the race plan's race-day fitness and form,
and the pace progression the training plan shares with it — run in-process.
The phone's port (com.tracks.core.plan.RaceDay) replays every case in
RaceDayFixtureTest and must agree exactly: the race plan, the goal card and
the guided race on the phone are the web's only if these are.

Synthetic and deterministic: hand-picked cases for each rule (the window,
the plan's start, today's unfinished session, manual ticks, cross-training,
the eve of the race) and seeded random histories around them. Dates are
fixed.

Touches no database, but importing `app` reads config:

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_race_day_fixtures.py
"""

from __future__ import annotations

import json
import sys
from datetime import date, timedelta
from pathlib import Path
from random import Random

sys.path.insert(0, "/app")

from app.calculators.plan import race_day as rd  # noqa: E402

OUT = Path("/spec/fixtures/race_day.json")
TODAY = date(2026, 3, 4)  # a Wednesday


def _plain(v):
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, dict):
        return {(k.isoformat() if isinstance(k, date) else k): _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


def _day(n: int) -> date:
    return TODAY + timedelta(days=n)


def _session(n, sport="running", wtype="easy", minutes=45, complete=False, recorded=False):
    return {"date": _day(n), "sport": sport, "workout_type": wtype, "minutes": minutes,
            "complete": complete, "recorded": recorded}


def _act(n, sport="running", minutes=45.0):
    return {"date": _day(n), "sport": sport, "minutes": minutes}


def projection_cases() -> list:
    out = []
    for today in (TODAY, date(2026, 3, 2), date(2026, 3, 8)):  # a Wednesday, a Monday, a Sunday
        for ahead in (-3, 0, 1, 4, 5, 6, 7, 13, 30, 70, 104, 105, 200, 400):
            race = today + timedelta(days=ahead)
            for vdot in (31.8, 42.0, 55.5):
                for adh in (0.0, 0.37, 1.0):
                    out.append({"today": today, "race": race, "vdot": vdot, "adherence": adh,
                                "weeks": rd.plan_weeks(today, race),
                                "step": rd.vdot_step(rd.plan_weeks(today, race)),
                                "expect": rd.race_day_vdot(vdot, today, race, adh)})
    return out


def adherence_cases() -> list:
    cases = []

    def add(name, planned, done, today=TODAY):
        cases.append({"name": name, "planned": planned, "done": done, "today": today,
                      "expect": rd.adherence(planned, done, today)})

    weeks = [_session(-d) for d in range(1, 28, 2)]
    add("a new plan, nothing yet", [], [])
    add("a new plan, runs before it", [_session(0)], [_act(-5), _act(-9)])
    add("every session run", weeks, [_act(-d) for d in range(1, 28, 2)])
    add("half the sessions missed", weeks, [_act(-d) for d in range(1, 28, 4)])
    add("nothing at all done", weeks, [])
    add("ticked off by hand", [{**s, "complete": True} for s in weeks], [])
    add("ticked off and recorded", [{**s, "complete": True, "recorded": True} for s in weeks],
        [_act(-d) for d in range(1, 28, 2)])
    add("runs on other days", weeks, [_act(-d) for d in range(2, 29, 2)])
    add("rides instead of runs", weeks, [_act(-d, "cycling") for d in range(1, 28, 2)])
    add("swims instead of runs", weeks, [_act(-d, "swimming") for d in range(1, 28, 2)])
    add("climbing instead of runs", weeks, [_act(-d, "rock_climbing", 120.0) for d in range(1, 28, 2)])
    add("strength instead of runs", weeks, [_act(-d, "strength_training") for d in range(1, 28, 2)])
    add("yoga instead of runs", weeks, [_act(-d, "yoga", 60.0) for d in range(1, 28, 2)])
    add("an unknown sport", weeks, [_act(-d, None) for d in range(1, 28, 2)])
    add("more than planned", weeks, [_act(-d, minutes=90.0) for d in range(1, 28, 2)])
    add("today's session not yet run", weeks + [_session(0, minutes=60)], [_act(-d) for d in range(1, 28, 2)])
    add("today's session run", weeks + [_session(0, minutes=60, complete=True, recorded=True)],
        [_act(-d) for d in range(1, 28, 2)] + [_act(0, minutes=60.0)])
    add("today's session ticked by hand", weeks + [_session(0, minutes=60, complete=True)], [])
    add("sessions past the window", [_session(-d) for d in range(20, 60, 3)],
        [_act(-d) for d in range(40, 60, 3)])
    add("a plan ten days old", [_session(-10), _session(-7), _session(-4), _session(-1)],
        [_act(-25), _act(-20), _act(-7), _act(-1)])
    add("the plan begins with strength", [_session(-12, "strength_training", "strength", 30),
                                          _session(-9), _session(-5)], [_act(-11), _act(-9)])
    add("races are not training", weeks + [_session(-3, wtype="race", minutes=200)],
        [_act(-d) for d in range(1, 28, 2)])
    add("a triathlon plan's other sports", [_session(-d, s) for d, s in
                                            zip(range(1, 28, 2), ["swimming", "cycling", "running"] * 5)],
        [_act(-d, "cycling") for d in range(1, 28, 4)])
    add("sessions with no length", [_session(-d, minutes=None) for d in (3, 6, 9)], [_act(-3)])
    add("future sessions ignored", [_session(d) for d in range(1, 20, 2)], [])
    add("trail and treadmill runs", weeks, [_act(-d, s) for d, s in
                                            zip(range(1, 28, 2), ["trail_running", "treadmill_running"] * 7)])

    rng = Random("race-day-adherence")
    sports = ["running", "running", "running", "cycling", "swimming", "hiking", "rowing",
              "rock_climbing", "strength_training", "yoga", "trail_running", "mountain_biking",
              "cross_country_skiing", "kayaking", "alpine_skiing", None]
    types = ["easy", "long", "tempo", "intervals", "recovery", "race", "strength", "mobility"]
    for i in range(60):
        planned = [_session(-rng.randint(-3, 45), rng.choice(sports[:3] + ["strength_training", "cycling"]),
                            rng.choice(types), rng.choice([None, rng.randint(15, 150)]),
                            complete=rng.random() < 0.5, recorded=rng.random() < 0.5)
                   for _ in range(rng.randint(0, 30))]
        done = [_act(-rng.randint(-2, 45), rng.choice(sports), round(rng.uniform(5, 240), 2))
                for _ in range(rng.randint(0, 40))]
        add(f"random {i}", planned, done)
    return cases


def load_cases() -> list:
    rng = Random("race-day-load")
    out = []
    for i in range(30):
        sessions = [(_day(rng.randint(1, 20)), round(rng.uniform(0, 140), 6)) for _ in range(rng.randint(0, 25))]
        out.append({"sessions": sessions, "expect": rd.planned_load_by_day(sessions)})
    return out


def form_cases() -> list:
    rng = Random("race-day-form")
    out = []

    def add(name, history, planned, race):
        out.append({"name": name, "history": history, "planned": planned, "today": TODAY, "race": race,
                    "expect": rd.race_day_tsb(history, planned, TODAY, race)})

    steady = {_day(-d): 60.0 for d in range(1, 120)}
    taper = {_day(d): (30.0 if d % 2 else 0.0) for d in range(1, 14)}
    add("no history", {}, taper, _day(14))
    add("race today", steady, taper, TODAY)
    add("race past", steady, taper, _day(-3))
    add("race tomorrow", steady, taper, _day(1))
    add("race in two weeks, taper", steady, taper, _day(14))
    add("race in two weeks, resting", steady, {}, _day(14))
    add("planned load past the race", steady, {**taper, _day(20): 300.0}, _day(14))
    add("history after today ignored", {**steady, _day(3): 500.0}, taper, _day(14))
    add("a single old ride", {_day(-200): 150.0}, {}, _day(30))
    for i in range(40):
        start = rng.randint(1, 400)
        history = {_day(-d): round(rng.uniform(0, 180), 3) for d in range(0, start) if rng.random() < 0.6}
        planned = {_day(d): round(rng.uniform(0, 160), 3) for d in range(1, 120) if rng.random() < 0.5}
        add(f"random {i}", history, planned, _day(rng.choice([0, 1, 2, 7, 15, 40, 90, 118])))
    return out


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_race_day_fixtures.py — do not edit.",
        "constants": {
            "weekly_gain": rd.WEEKLY_GAIN, "max_gain": rd.MAX_GAIN, "adherence_days": rd.ADHERENCE_DAYS,
            "prior_minutes": rd.PRIOR_MINUTES, "manual_credit": rd.MANUAL_CREDIT, "transfer": rd.TRANSFER,
        },
        "projection": projection_cases(),
        "adherence": adherence_cases(),
        "load": load_cases(),
        "form": form_cases(),
        "freshness": [{"tsb": t, "expect": rd.freshness_factor(t)}
                      for t in (None, -100.0, -30.0, -29.9, -12.4, 0.0, 0.05, 7.5, 29.95, 30.0, 31.0, 250.0)],
    }
    # Dict keys that are dates (load, form) become ISO strings.
    for c in corpus["load"]:
        c["sessions"] = [[d, t] for d, t in c["sessions"]]
    OUT.write_text(json.dumps(_plain(corpus), indent=1, sort_keys=True) + "\n")
    print(f"wrote {OUT} ({len(corpus['adherence'])} adherence, {len(corpus['form'])} form cases)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
