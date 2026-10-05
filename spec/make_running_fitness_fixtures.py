# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline today's running fitness: spec/fixtures/running_fitness.json.

calculators/plan/running_fitness.py — the VDOT every running pace is built
from — run in-process. The phone's port (com.tracks.core.plan.RunningFitness)
replays every case in RunningFitnessFixtureTest and must agree exactly.

Kept apart from plan.json: plan.json holds the generators, which take this
estimate as an input; this corpus holds how the estimate is reached, so a
change to one is readable without the other.

Synthetic and deterministic: hand-picked cases for each rule (layoffs,
old efforts, the HR method's exclusions, the watch fallback, the profile
prior) and seeded random histories around them. Dates are fixed.

Touches no database, but importing `app` reads config:

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_running_fitness_fixtures.py
"""

from __future__ import annotations

import json
import sys
from datetime import date, timedelta
from pathlib import Path
from random import Random

sys.path.insert(0, "/app")

from app.calculators.plan import running_fitness as rf  # noqa: E402

OUT = Path("/spec/fixtures/running_fitness.json")
TODAY = date(2026, 3, 4)


def _plain(v):
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    return v


def _effort(days_ago, distance_m=5000, minutes=20.0):
    return {"date": TODAY - timedelta(days=days_ago), "distance_m": distance_m,
            "speed_mps": distance_m / (minutes * 60)}


def _run(days_ago, pace=330.0, hr=145, km=8.0, sport="running", ascent=40.0,
         vo2max=None, avg_speed=None):
    return {"date": TODAY - timedelta(days=days_ago), "sport": sport, "distance_m": km * 1000,
            "duration_s": km * pace, "avg_speed": avg_speed, "avg_hr": hr,
            "ascent_m": ascent, "vo2max": vo2max}


HR = {"max_hr": 185, "resting_hr": 55}


def helper_cases() -> dict:
    days = [-3, 0, 3, 7, 7.5, 8, 14, 20, 21, 22, 30, 42, 55, 56, 57, 90, 365, 1000]
    hr = []
    for speed in (1.7, 1.8, 2.5, 3.03, 4.17, 5.5, 7.0, 7.1):
        for h in (80, 120, 120.5, 145, 160, 176, 178, 184):
            for mx, rest in ((185, 55), (190, 48), (175, 120), (200, 62)):
                hr.append({"speed": speed, "hr": h, "max": mx, "rest": rest,
                           "expect": rf.hr_run_vdot(speed, h, mx, rest)})
    profile_kw = [
        {},
        {"sex": "male"}, {"sex": "female"}, {"sex": "other"},
        {"birth_year": 1990}, {"birth_year": 2020}, {"birth_year": 1930},
        {"height_cm": 193, "weight_kg": 88}, {"height_cm": 152, "weight_kg": 50},
        {"height_cm": 170}, {"weight_kg": 70}, {"height_cm": 150, "weight_kg": 150},
        {"height_cm": 200, "weight_kg": 40},
        {"frequencies": {"running": "never"}}, {"frequencies": {"running": "5_plus"}},
        {"frequencies": {"running": "never", "cycling": "3_4"}},
        {"frequencies": {"running": "bogus"}}, {"frequencies": "running"},
        {"sex": "male", "birth_year": 1996, "height_cm": 193, "weight_kg": 88,
         "frequencies": {"running": "3_4"}},
        {"sex": "female", "birth_year": 1991, "height_cm": 165, "weight_kg": 60,
         "frequencies": {"running": "never"}},
        {"sex": "male", "birth_year": 1950, "height_cm": 175, "weight_kg": 95,
         "frequencies": {"running": "occasional"}},
        {"sex": "male", "birth_year": 2004, "height_cm": 185, "weight_kg": 65,
         "frequencies": {"running": "5_plus", "cycling": "5_plus"}},
    ]
    profile = [{"today": TODAY, "kw": kw, "expect": rf.profile_vdot(TODAY, **kw)} for kw in profile_kw]
    return {
        "detraining": [{"days": d, "expect": rf.detraining_factor(d)} for d in days],
        "hr_run": hr,
        "profile": profile,
    }


def estimate_cases() -> list:
    cases = []

    def add(name, efforts, runs, today=TODAY, **kw):
        cases.append({"name": name, "efforts": efforts, "runs": runs, "today": today, "kw": kw,
                      "expect": rf.estimate_running_fitness(efforts, runs, today, **kw)})

    steady = [_run(d) for d in (2, 5, 9, 12)]
    add("nothing at all", [], [])
    add("profile only", [], [], sex="female", height_cm=165, weight_kg=60, birth_year=1991,
        frequencies={"running": "never"})
    add("effort inside six weeks", [_effort(30)], [_run(1, hr=None)])
    add("effort at six weeks", [_effort(42)], [_run(1, hr=None)])
    add("effort just past six weeks", [_effort(43)], [_run(1, hr=None)])
    add("effort 200 days old", [_effort(200)], [_run(1, hr=None)])
    add("effort a year old", [_effort(365)], [_run(1, hr=None)])
    add("effort over a year old", [_effort(366)], [_run(1, hr=None)])
    add("recent effort then layoff", [_effort(21)], [])
    add("layoff past the plateau", [_effort(90)], [])
    add("best of several efforts", [_effort(3, minutes=26), _effort(60, 10000, 40), _effort(14, 3000, 13)],
        [_run(3, hr=None)])
    add("short and bad efforts ignored",
        [_effort(3, 1609, 5), _effort(3, 400, 1), _effort(3, 5000, 1), {**_effort(5), "speed_mps": 0.0}],
        [_run(1, hr=None)])
    add("hr lifts an easy runner", [_effort(3, minutes=5 * 330 / 60)], steady, **HR)
    add("hr below a real race", [_effort(10, minutes=19.0)], [_run(d, hr=178) for d in (2, 5, 9)], **HR)
    add("two hr runs not enough", [], steady[:2], **HR)
    add("three hr runs, odd median", [], [_run(2, hr=140), _run(5, hr=150), _run(9, hr=146)], **HR)
    add("four hr runs, even median", [], steady[:3] + [_run(14, hr=139)], **HR)
    for bad in ({"sport": "treadmill_running"}, {"sport": "trail_running"}, {"ascent": 400.0},
                {"ascent": None}, {"km": 3.0}, {"hr": 90}, {"hr": 184}, {"hr": None}, {"pace": 560.0},
                {"avg_speed": 3.2}, {"avg_speed": 0.0}, {"sport": "road_running"}):
        add(f"hr runs {bad}", [], [_run(d, **bad) for d in (2, 5, 9)], **HR)
    add("hr runs outside the window", [], [_run(d) for d in (91, 95, 100)], **HR)
    add("hr with max too close to rest", [], steady, max_hr=110, resting_hr=55)
    add("hr without resting hr", [], steady, max_hr=185)
    add("watch when hr cannot", [], [_run(d, vo2max=52.0) for d in (2, 5, 9)], max_hr=185)
    add("watch latest wins", [], [_run(2, vo2max=50.0), _run(9, vo2max=55.0)])
    add("watch implausible ignored", [], [_run(2, vo2max=120.0), _run(9, vo2max=48.0)])
    add("watch outside window", [], [_run(120, vo2max=52.0)])
    add("watch with layoff", [], [_run(40, vo2max=52.0)])
    add("hr preferred over watch", [], [_run(d, vo2max=60.0) for d in (2, 5, 9)], **HR)
    add("effort beats watch", [_effort(5, minutes=18.0)], [_run(5, vo2max=45.0)])
    add("future evidence ignored", [_effort(-10)], [_run(-5)], **HR)
    add("profile ignored once measured", [], steady, **HR, sex="female", height_cm=160, weight_kg=95)

    rng = Random("running-fitness")
    sports = ["running", "running", "running", "trail_running", "treadmill_running", "road_running"]
    for i in range(60):
        n_runs = rng.randint(0, 14)
        runs = []
        for _ in range(n_runs):
            runs.append(_run(
                rng.randint(0, 160), pace=round(rng.uniform(240, 520), 1),
                hr=rng.choice([None, rng.randint(95, 190)]), km=round(rng.uniform(2, 25), 2),
                sport=rng.choice(sports), ascent=rng.choice([None, round(rng.uniform(0, 500), 1)]),
                vo2max=rng.choice([None, None, round(rng.uniform(30, 70), 1)]),
                avg_speed=rng.choice([None, round(rng.uniform(2, 5.5), 3)])))
        efforts = [_effort(rng.randint(0, 500), rng.choice([1000, 1609, 5000, 10000, 21097, 42195]),
                           round(rng.uniform(3, 300), 2)) for _ in range(rng.randint(0, 6))]
        kw = {}
        if rng.random() < 0.7:
            kw["max_hr"] = rng.randint(160, 205)
        if rng.random() < 0.7:
            kw["resting_hr"] = rng.randint(38, 80)
        if rng.random() < 0.5:
            kw.update(sex=rng.choice(["male", "female", None]), birth_year=rng.choice([None, rng.randint(1945, 2008)]),
                      height_cm=rng.choice([None, rng.randint(150, 200)]),
                      weight_kg=rng.choice([None, rng.randint(45, 120)]),
                      frequencies=rng.choice([None, {"running": rng.choice(["never", "1_2", "5_plus"])}]))
        add(f"random {i}", efforts, runs, **kw)
    return cases


def main() -> int:
    corpus = {
        "_comment": "Generated by spec/make_running_fitness_fixtures.py — do not edit.",
        **helper_cases(),
        "estimates": estimate_cases(),
    }
    OUT.write_text(json.dumps(_plain(corpus), indent=1, sort_keys=True) + "\n")
    print(f"wrote {OUT} ({len(corpus['estimates'])} estimates)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
