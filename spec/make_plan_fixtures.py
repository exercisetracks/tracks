# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the endurance-plan corpus.

The backend's own endurance planner, run in-process: calculators/plan/ (the
generator, every sport's builders, the shared base) plus the pure assembly
passes in api/training_plan/injectors.py (field tests, custom workouts,
stretch-flow placement) and the fitness fingerprint in matching.py. The
phone's port in mobile/core (com.tracks.core.plan) replays every case and must
agree exactly — the same workouts on the same days, the same step counts, the
same notes character for character.

Regenerable: the Python stays the independent oracle. Rerun after changing
any of those files, and the Kotlin suite says whether the port still agrees.

Synthetic and deterministic. Goals, histories and pace bests are invented,
chosen to reach every branch: each sport family and discipline, 1–7 days a
week, every intensity clamp, imperial units, FTP and LTHR set and unset,
walk-break capacity tiers, taper-only and year-long plans, and fitness goals'
rolling plans (ramps inside and outside the slider, fatigued starts, anchors
before and after today). Dates are fixed, never "today".

Touches no database, but importing `app` reads config:

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_plan_fixtures.py
"""

from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from random import Random
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.calculators.plan import base  # noqa: E402
from app.calculators.plan import mtb  # noqa: E402
from app.calculators.plan.generator import metrics  # noqa: E402
from app.calculators.plan.generator import fitness as fitness_mod  # noqa: E402
from app.calculators.plan.generator import plan as plan_mod  # noqa: E402
from app.calculators.plan.generator import templates  # noqa: E402

OUT = Path("/spec/fixtures/plan.json")


def _plain(v):
    """A plan value in JSON's vocabulary: dates as ISO, everything else as is."""
    if isinstance(v, (date, datetime)):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    if isinstance(v, set):
        return sorted(_plain(x) for x in v)
    return v


# ── Helpers ──────────────────────────────────────────────────────────────────

def helper_cases() -> dict:
    rng = Random("plan-helpers")
    paces = [359.99999999999994, 360.0, 299.5, 1.0, 59.99, 600.0, 245.1, 419.95] + \
        [round(rng.uniform(150, 700), rng.choice([1, 3, 7])) for _ in range(40)]
    fmt_pace = [{"sec": p, "imperial": imp, "expect": base._fmt_pace(p, imp)}
                for p in paces for imp in (False, True)]
    dists = [0, 50, 100, 400, 999, 1000, 1500, 1599, 1600, 1609, 2000, 5000, 10500, 42195, 100000]
    fmt_dist = [{"m": d, "imperial": imp, "expect": base._fmt_dist_m(d, imp)}
                for d in dists for imp in (False, True)]
    vdots = [20.0, 30.5, 42.0, 50.123, 65.0, 85.9]
    paces_cases = [{"vdot": v, "expect": base.vdot_to_paces(v)} for v in vdots]
    calc = [{"m": m, "s": s, "expect": base.calculate_vdot(m, s)}
            for m, s in [(5000, 1500), (10000, 3000), (21097, 5400), (42195, 12600), (3000, 700)]]
    phases = [[w, t, base._phase_for_week(w, t)] for t in range(1, 30) for w in range(t)]
    volume = []
    for t in (1, 3, 5, 8, 12, 20, 30):
        for cyc in (3, 4):
            for w in range(t):
                for start in (12.5, 40.0):
                    wr = t - w - rng.choice([0, 0.4, 0.86])
                    volume.append([w, t, start, 55.0, wr, cyc,
                                   base._weekly_volume_km(w, t, start, 55.0, wr, cycle_len=cyc)])
    capacity = [[ew, base._capacity_km(ew)] for ew in (-3.0, 0.0, 1.0, 4.5, 10.0, 25.3, 40.0)]
    days = []
    for fam, tpls in (("running", base._TEMPLATES["running"]), ("cycling", base._TEMPLATES["cycling"]),
                      ("mountain_biking", base._mtb_template_for("enduro")),
                      ("swimming", base._TEMPLATES["swimming"]), ("generic", base._TEMPLATES["generic"]),
                      ("rowing", base._TEMPLATES["rowing"]), ("hiking", base._TEMPLATES["hiking"]),
                      ("nordic_skiing", base._TEMPLATES["nordic_skiing"]),
                      ("alpine_skiing", base._TEMPLATES["alpine_skiing"]),
                      ("climbing", base._TEMPLATES["climbing"])):
        for phase, t in tpls.items():
            for dpw in range(0, 9):
                days.append({"template": t, "dpw": dpw, "family": fam,
                             "expect": base._apply_days_per_week(t, dpw, fam)})
    rotating = []
    for fam in ("running", "cycling", "mountain_biking", "swimming", "hiking", "triathlon", "paddling",
                "rowing", "nordic_skiing", "alpine_skiing", "climbing"):
        for phase in ("base", "build", "peak", "taper", "unknown"):
            for wk in range(4):
                for mtb_d, cy_d in (("trail", "road_race"), ("xco", "criterium"), ("ENDURO", "time_trial"),
                                    ("xcm", "hill_climb"), ("bogus", "bogus")):
                    rotating.append([fam, phase, wk, mtb_d, cy_d,
                                     templates._rotating_template(fam, phase, wk, mtb_d, cy_d)])
    css = [{"bests": b, "expect": base._css_pace_sec_per_100m(b)}
           for b in ([], [(400, 1.2)], [(400, 1.2), (200, 1.35)], [(200, 1.4), (400, 1.1), (400, 1.15)],
                     [(400, 1.5), (200, 1.2)])]
    best_vdot = [{"bests": b, "expect": base._best_vdot_from_pace_bests(b)}
                 for b in ([], [(1000, 5.0)], [(5000, 3.5), (10000, 3.3), (21097, 3.0)], [(3000, 0.0), (3000, 4.2)])]
    peak_long = [[d, fam, base._target_peak_long_km(d, fam)]
                 for fam in ("running", "cycling", "mountain_biking", "swimming", "hiking", "generic")
                 for d in (3000, 5000, 10000, 21097, 42195, 60000, 100000, 161000, 250000)]
    max_week = [[d, fam, base._max_weekly_km_for_race(d, fam)]
                for fam in ("running", "cycling", "mountain_biking", "rowing", "paddling")
                for d in (5000, 10000, 21000, 42000, 60000, 100000, 160000)]
    families = [[s, base._sport_family(s)] for s in (
        "running", "Trail Running", "e_biking", "Mountain Biking", "open_water_swimming", "indoor_rowing",
        "walking", "bouldering", "SUP", "powerlifting", "sprint_triathlon", "golf", "",
        "skiing", "Cross Country Skiing", "backcountry_skiing", "alpine_skiing", "snowboarding",
        "rock_climbing", "lap_swimming")]
    titles = [[t, fam, dist, dur, imp, base._workout_title(t, fam, dist, dur, imperial=imp)]
              for t in ("easy", "long", "field_test:ftp20", "field_test:weird", "made_up_type", "hill_sprints",
                        "short_quality", "threshold", "css", "arc")
              for fam, dist in (("running", 8123.4), ("running", None), ("running", 0.0), ("cycling", None),
                                ("rowing", None), ("climbing", None), ("swimming", None))
              for dur in (45,) for imp in (False, True)]
    pols = [[t, list(metrics._polarisation_check(t))] for t in (
        ["rest"] * 7, ["easy", "tempo", "rest", "intervals", "long", "rest", "easy"], [])]
    mono = [[loads, metrics._monotony_scores(loads)] for loads in (
        [], [5.0], [0.0, 0.0, 0.0], [30.0, 30.0, 30.0], [40.0, 0.0, 65.5, 30.0, 0.0, 120.25, 45.0],
        [0.1, 0.2, 0.3])]
    return {"fmt_pace": fmt_pace, "fmt_dist": fmt_dist, "vdot_paces": paces_cases, "calculate_vdot": calc,
            "phases": phases, "volume": volume, "capacity": capacity, "days_per_week": days,
            "rotating": rotating, "css": css, "best_vdot": best_vdot, "peak_long": peak_long,
            "max_weekly": max_week, "families": families, "titles": titles,
            "polarisation": pols, "monotony": mono}


def skills_cases() -> list:
    return [[d, k, seed, [list(x) for x in mtb._mtb_pick_skills(d, k, seed)]]
            for d in ("xco", "xcm", "enduro", "trail", "unknown")
            for k in (1, 3, 12)
            for seed in (0, 1, 7, 331, 2**31 + 5)]


# ── Whole plans ──────────────────────────────────────────────────────────────

def _history(today: date, rng: Random, sports: list[str]) -> list:
    out = []
    for _ in range(rng.randint(0, 30)):
        d = today - timedelta(days=rng.randint(0, 80))
        started = datetime(d.year, d.month, d.day, rng.randint(5, 20), 0) if rng.random() < 0.5 else d
        dist = rng.choice([None, 0.0, round(rng.uniform(1500, 90000), 1)])
        dur = rng.choice([None, 0, rng.randint(900, 14000)])
        out.append(SimpleNamespace(sport=rng.choice(sports + [None, "yoga"]), started_at=started,
                                   distance_meters=dist, duration_seconds=dur))
    return out


def _steady(today: date, weekly: dict[str, tuple[float, int]]) -> list:
    """Eight weeks of the same training: sport -> (km a week, seconds a week)."""
    out = []
    for sport, (km, secs) in weekly.items():
        for k in range(8):
            d = today - timedelta(days=7 * k + 3)
            out.append(SimpleNamespace(sport=sport, started_at=d, distance_meters=km * 1000,
                                       duration_seconds=secs))
    return out


def _history_json(history: list) -> list:
    return [{"sport": h.sport,
             "started": (h.started_at.date() if isinstance(h.started_at, datetime) else h.started_at).isoformat(),
             "distance": h.distance_meters, "duration": getattr(h, "duration_seconds", None)}
            for h in history]


def plan_cases() -> list:
    rng = Random("plan-generator")
    mon, wed, sun = date(2026, 3, 2), date(2026, 3, 4), date(2026, 3, 8)
    sports = ["running", "trail_running", "cycling", "gravel_cycling", "mountain_biking", "swimming",
              "rowing", "hiking", "triathlon", "kayaking", "Road Biking"]
    # Drawn from separately so the random cases above keep their sports.
    new_sports = ["open_water_swimming", "cross_country_skiing", "backcountry_skiing", "alpine_skiing",
                  "climbing", "skiing"]
    cases = []

    def add(name, today, goal, history, bests, **kw):
        g = SimpleNamespace(event_date=goal.get("event_date"), event_sport=goal.get("event_sport"),
                            event_distance_meters=goal.get("event_distance_meters"),
                            days_per_week=goal.get("days_per_week"), plan_intensity=goal.get("plan_intensity"),
                            mtb_discipline=goal.get("mtb_discipline"),
                            cycling_discipline=goal.get("cycling_discipline"))
        vdot, workouts = plan_mod.generate_training_plan(
            goal=g, activity_history=history, pace_bests=bests, today=today, **kw)
        cases.append({
            "name": name, "today": today.isoformat(),
            "goal": {k: _plain(v) for k, v in goal.items()},
            "history": _history_json(history),
            "bests": [list(b) for b in bests],
            "kw": kw,
            "vdot": vdot,
            "workouts": _plain(workouts),
        })

    run_bests = [(5000, 3.6), (10000, 3.35), (1000, 4.4)]
    swim_bests = [(200, 1.42), (400, 1.31)]

    add("marathon_sixteen_weeks_metric", mon,
        {"event_date": mon + timedelta(days=112), "event_sport": "running", "event_distance_meters": 42195.0},
        _history(mon, rng, ["running"]), run_bests)
    add("five_k_imperial_three_days", wed,
        {"event_date": wed + timedelta(days=45), "event_sport": "running", "event_distance_meters": 5000.0,
         "days_per_week": 3, "plan_intensity": 0.8}, [], run_bests, imperial=True)
    add("novice_runner_walk_breaks", sun,
        {"event_date": sun + timedelta(days=70), "event_sport": "running", "event_distance_meters": 21097.5},
        [], [], base_effective_weeks=0.0)
    add("ultra_hundred_k", mon,
        {"event_date": mon + timedelta(days=150), "event_sport": "trail_running",
         "event_distance_meters": 100000.0, "days_per_week": 6, "plan_intensity": 1.6}, [], run_bests)
    add("race_tomorrow", wed, {"event_date": wed + timedelta(days=1), "event_sport": "running"}, [], [])
    add("race_today_is_no_plan", wed, {"event_date": wed, "event_sport": "running"}, [], [])
    add("no_event_date", wed, {"event_date": None, "event_sport": "running"}, [], [])
    add("race_in_one_week_sunday", sun, {"event_date": sun + timedelta(days=7), "event_sport": "running",
                                         "event_distance_meters": 10000.0}, [], run_bests)
    add("xco_with_power", mon,
        {"event_date": mon + timedelta(days=84), "event_sport": "mountain_biking", "mtb_discipline": "XCO",
         "event_distance_meters": 30000.0, "days_per_week": 5}, [], [], ftp=250.0, threshold_hr=168.0)
    add("enduro_two_to_one_cycles", wed,
        {"event_date": wed + timedelta(days=98), "event_sport": "mountain_biking", "mtb_discipline": "enduro"},
        [], [], threshold_hr=160.0)
    add("xcm_hr_only", sun,
        {"event_date": sun + timedelta(days=63), "event_sport": "mountain_biking", "mtb_discipline": "xcm",
         "event_distance_meters": 80000.0, "days_per_week": 7}, [], [], threshold_hr=171.9)
    add("mtb_bogus_discipline", mon,
        {"event_date": mon + timedelta(days=40), "event_sport": "mountain_biking", "mtb_discipline": "downhill"},
        [], [], base_effective_weeks=20.0)
    for disc in ("road_race", "time_trial", "hill_climb", "criterium", "gravel"):
        add(f"cycling_{disc}", wed,
            {"event_date": wed + timedelta(days=91), "event_sport": "road_biking",
             "cycling_discipline": disc, "event_distance_meters": 120000.0},
            _history(wed, rng, ["cycling", "road_biking"]), [], ftp=280.0, threshold_hr=170.0)
    add("cycling_no_power_no_lthr", sun,
        {"event_date": sun + timedelta(days=30), "event_sport": "cycling", "days_per_week": 2}, [], [])
    add("swim_with_css", mon,
        {"event_date": mon + timedelta(days=77), "event_sport": "swimming", "event_distance_meters": 3800.0},
        [], swim_bests)
    add("swim_without_css", wed,
        {"event_date": wed + timedelta(days=35), "event_sport": "open_water_swimming"}, [], [])
    # One case per new sport and discipline, then variants that reach the
    # branches: CSS known or not, imperial, LTHR unset, short and long weeks.
    for n, (sport, dist, bests, kw) in enumerate((
            ("swimming", 1500.0, swim_bests, {}),
            ("swimming", None, [], {"imperial": True}),
            ("open_water_swimming", 10000.0, swim_bests, {"threshold_hr": 150.0}),
            ("open_water_swimming", 800.0, [], {}),
            ("rowing", 2000.0, [], {"threshold_hr": 172.0}),
            ("indoor_rowing", 5000.0, [], {}),
            ("hiking", None, [], {"threshold_hr": 160.0, "imperial": True}),
            ("hiking", 40000.0, [], {}),
            ("skiing", None, [], {"threshold_hr": 168.0}),
            ("cross_country_skiing", 50000.0, [], {}),
            ("backcountry_skiing", None, [], {"threshold_hr": 165.0, "imperial": True}),
            ("alpine_skiing", None, [], {"threshold_hr": 170.0}),
            ("alpine_skiing", None, [], {}),
            ("climbing", None, [], {}),
            ("bouldering", None, [], {"threshold_hr": 160.0}))):
        for dpw, weeks in ((None, 12), (6, 7)):
            add(f"sport_{n}_{sport}_{dpw}_{weeks}", mon,
                {"event_date": mon + timedelta(weeks=weeks), "event_sport": sport,
                 "event_distance_meters": dist, "days_per_week": dpw},
                _history(mon, rng, [sport]), bests, **kw)
    for sport in ("rowing", "hiking", "triathlon", "kayaking", "golf"):
        add(f"generic_{sport}", mon,
            {"event_date": mon + timedelta(days=rng.randint(20, 120)), "event_sport": sport,
             "event_distance_meters": rng.choice([None, 0.0, 15000.0])},
            _history(mon, rng, [sport]), run_bests, base_effective_weeks=rng.choice([None, 6.0, 80.0]))
    # Load sizing at the top end: capacity from volume, doubles, scaled caps.
    add("high_volume_marathoner", mon,
        {"event_date": mon + timedelta(days=98), "event_sport": "running", "event_distance_meters": 42195.0,
         "days_per_week": 5}, _steady(mon, {"running": (110.0, 36000)}), run_bests)
    add("strong_cyclist_three_days", wed,
        {"event_date": wed + timedelta(days=84), "event_sport": "cycling", "event_distance_meters": 160000.0,
         "days_per_week": 3}, _steady(wed, {"cycling": (400.0, 50000)}), [], ftp=300.0, threshold_hr=172.0)
    # Triathlon: every standard distance, the day counts, history or none.
    for dist, dpw, hist in ((25750.0, 3, False), (51500.0, 6, True), (113000.0, 5, True),
                            (226000.0, 7, True), (12950.0, 1, False), (51500.0, 2, False),
                            (113000.0, 4, False), (40000.0, None, True)):
        today = rng.choice([mon, wed, sun])
        add(f"triathlon_{int(dist)}_{dpw}", today,
            {"event_date": today + timedelta(days=rng.randint(40, 140)), "event_sport": "triathlon",
             "event_distance_meters": dist, "days_per_week": dpw,
             "plan_intensity": rng.choice([None, 0.8, 1.2])},
            _steady(today, {"swimming": (4.0, 5400), "cycling": (120.0, 16000), "running": (30.0, 10800)})
            if hist else [], rng.choice([[], run_bests, run_bests + swim_bests]),
            ftp=rng.choice([None, 240.0]), threshold_hr=rng.choice([None, 165.0]),
            imperial=rng.random() < 0.3)
    for i in range(14):
        sport = rng.choice(sports)
        today = rng.choice([mon, wed, sun, date(2026, 12, 29), date(2028, 2, 27)])
        add(f"random_{i}_{sport.replace(' ', '_').lower()}", today,
            {"event_date": today + timedelta(days=rng.randint(2, 220)), "event_sport": sport,
             "event_distance_meters": rng.choice([None, 5000.0, 21097.0, 42195.0, 60000.0, 160000.0]),
             "days_per_week": rng.choice([None, 1, 2, 3, 4, 5, 6, 7]),
             "plan_intensity": rng.choice([None, 0.3, 0.75, 1.0, 1.25, 1.9]),
             "mtb_discipline": rng.choice([None, "xco", "enduro"]),
             "cycling_discipline": rng.choice([None, "criterium", "time_trial"])},
            _history(today, rng, [sport, "running"]), rng.choice([[], run_bests, swim_bests]),
            ftp=rng.choice([None, 199.5, 310.0]), threshold_hr=rng.choice([None, 150.0, 177.7]),
            base_effective_weeks=rng.choice([None, 0.0, 3.3, 40.0]), imperial=rng.random() < 0.3,
            days_per_week=rng.choice([None, 3, 5]))
    rng_new = Random("plan-generator-new-sports")
    for i in range(6):
        sport = rng_new.choice(new_sports + ["swimming", "rowing", "hiking"])
        today = rng_new.choice([mon, wed, sun, date(2026, 12, 29)])
        add(f"random_new_{i}_{sport}", today,
            {"event_date": today + timedelta(days=rng_new.randint(2, 120)), "event_sport": sport,
             "event_distance_meters": rng_new.choice([None, 1500.0, 5000.0, 42195.0]),
             "days_per_week": rng_new.choice([None, 1, 2, 3, 4, 5, 6, 7]),
             "plan_intensity": rng_new.choice([None, 0.3, 1.0, 1.9])},
            _history(today, rng_new, [sport]), rng_new.choice([[], swim_bests]),
            threshold_hr=rng_new.choice([None, 150.0, 177.7]), imperial=rng_new.random() < 0.3,
            base_effective_weeks=rng_new.choice([None, 3.3]))

    # Today's running fitness (running_fitness.py) as the API passes it: a
    # measured estimate sets the plan's VDOT, paces and progression; a profile
    # estimate only the paces; the effort floor alone the walk-break capacity.
    measured = {"vdot": 47.3, "source": "heart_rate", "measured": True, "effort_vdot": 41.2}
    hr_only = {"vdot": 44.0, "source": "heart_rate", "measured": True, "effort_vdot": None}
    profile = {"vdot": 24.1, "source": "profile", "measured": False, "effort_vdot": None}
    ten_k = {"event_date": wed + timedelta(days=70), "event_sport": "running", "event_distance_meters": 10000.0}
    add("fitness_measured_by_heart_rate", wed, ten_k, _history(wed, Random("rf-1"), ["running"]), run_bests,
        running_fitness=measured)
    add("fitness_measured_without_an_effort", wed, ten_k, [], [], running_fitness=hr_only)
    add("fitness_from_profile_only", wed, ten_k, [], [], running_fitness=profile)
    add("fitness_from_profile_imperial", sun, dict(ten_k, event_distance_meters=42195.0), [], [],
        running_fitness=profile, imperial=True)
    add("fitness_triathlon_run_leg", mon,
        {"event_date": mon + timedelta(days=84), "event_sport": "triathlon", "event_distance_meters": 51500.0},
        _history(mon, Random("rf-2"), ["running", "cycling", "swimming"]), run_bests + swim_bests,
        running_fitness=measured, ftp=240)
    add("fitness_triathlon_profile", mon,
        {"event_date": mon + timedelta(days=84), "event_sport": "triathlon", "event_distance_meters": 25750.0},
        [], [], running_fitness=profile)
    add("fitness_ignored_by_cycling", mon,
        {"event_date": mon + timedelta(days=56), "event_sport": "cycling", "event_distance_meters": 100000.0},
        [], [], running_fitness=measured)
    return cases


# ── Fitness goals (rolling plan from a CTL ramp) ─────────────────────────────

def fitness_cases() -> dict:
    rng = Random("plan-fitness")
    mon, wed, sun = date(2026, 3, 2), date(2026, 3, 4), date(2026, 3, 8)
    targets = []
    for ctl, atl in ((0.0, 0.0), (4.2, 3.0), (38.7, 41.3), (50.0, 85.0), (92.4, 60.1)):
        for ramp in (None, -2.0, -0.5, 0.0, 2.5, 6.0, 8.0, 12.0, -9.0):
            for today in (mon, wed, sun):
                for anchor in (None, today, today - timedelta(days=17), today + timedelta(days=40),
                               date(2025, 12, 31)):
                    targets.append({
                        "ctl": ctl, "atl": atl, "ramp": ramp, "today": today.isoformat(),
                        "anchor": anchor.isoformat() if anchor else None,
                        "expect": _plain(fitness_mod.fitness_week_targets(ctl, atl, ramp, today, anchor)),
                    })

    plans = []
    sports = ["running", "trail_running", "cycling", "mountain_biking", "swimming", "rowing",
              "hiking", "golf", "Road Biking"]

    def add(name, today, goal, ctl, atl, anchor, bests, history=(), **kw):
        g = SimpleNamespace(event_sport=goal.get("event_sport"),
                            ctl_ramp_per_week=goal.get("ctl_ramp_per_week"),
                            days_per_week=goal.get("days_per_week"),
                            mtb_discipline=goal.get("mtb_discipline"),
                            cycling_discipline=goal.get("cycling_discipline"),
                            fitness_sports=goal.get("fitness_sports"))
        vdot, workouts = fitness_mod.generate_fitness_plan(
            goal=g, activity_history=list(history), pace_bests=bests, today=today, ctl=ctl, atl=atl,
            anchor=anchor, **kw)
        plans.append({"name": name, "today": today.isoformat(), "goal": goal, "ctl": ctl, "atl": atl,
                      "anchor": anchor.isoformat() if anchor else None,
                      "history": _history_json(list(history)),
                      "bests": [list(b) for b in bests], "kw": kw, "vdot": vdot,
                      "workouts": _plain(workouts)})

    run_bests = [(5000, 3.6), (10000, 3.35), (1000, 4.4)]
    add("runner_building", mon, {"event_sport": "running", "ctl_ramp_per_week": 3.0}, 45.0, 48.0,
        mon - timedelta(weeks=2), run_bests)
    add("runner_new_no_history", wed, {"event_sport": "running", "ctl_ramp_per_week": 2.0}, 0.0, 0.0,
        None, [], base_effective_weeks=0.0)
    add("cyclist_aggressive_power", sun,
        {"event_sport": "cycling", "ctl_ramp_per_week": 7.5, "days_per_week": 6,
         "cycling_discipline": "criterium"}, 70.2, 64.0, sun - timedelta(days=30), [],
        ftp=280.0, threshold_hr=170.0)
    add("mtb_maintain", mon, {"event_sport": "mountain_biking", "ctl_ramp_per_week": 0.0,
                              "mtb_discipline": "enduro"}, 33.3, 30.0, mon, [], threshold_hr=160.0)
    add("swim_detrain", wed, {"event_sport": "swimming", "ctl_ramp_per_week": -2.0},
        25.0, 20.0, date(2025, 11, 5), [(200, 1.42), (400, 1.31)])
    add("fatigued_start", mon, {"event_sport": "running", "ctl_ramp_per_week": 5.0,
                                "days_per_week": 3}, 50.0, 90.0, mon - timedelta(weeks=1), run_bests,
        imperial=True)
    # Load sizing: doubles, trimmed days, scaled caps.
    add("runner_ctl80_doubles", mon, {"event_sport": "running", "ctl_ramp_per_week": 4.0,
                                      "days_per_week": 5}, 80.0, 80.0, mon, run_bests)
    add("cyclist_ctl20_seven_days_trims", wed, {"event_sport": "cycling", "ctl_ramp_per_week": 0.0,
                                                "days_per_week": 7}, 20.0, 18.0, mon, [])
    add("mtb_top_of_slider", sun, {"event_sport": "mountain_biking", "ctl_ramp_per_week": 6.0,
                                   "days_per_week": 4, "mtb_discipline": "xco"}, 55.0, 50.0, None, [],
        ftp=260.0)
    # Several sports: shares from history, placement, triathlon expanded.
    mixed = _steady(mon, {"running": (30.0, 10800), "cycling": (90.0, 12600), "hiking": (12.0, 9000)})
    add("multi_run_ride_hike", mon, {"event_sport": "running", "ctl_ramp_per_week": 3.0, "days_per_week": 6,
                                     "fitness_sports": ["running", "cycling", "hiking"]},
        48.0, 50.0, mon - timedelta(weeks=3), run_bests, history=mixed, ftp=250.0, threshold_hr=165.0)
    add("multi_no_history_two_days", wed, {"event_sport": "swimming", "ctl_ramp_per_week": 1.0,
                                           "days_per_week": 2,
                                           "fitness_sports": ["swimming", "mountain_biking", "rowing"]},
        30.0, 30.0, None, [])
    add("multi_same_family_twice", sun, {"event_sport": "trail_running", "ctl_ramp_per_week": 2.0,
                                         "fitness_sports": ["trail_running", "running", "Road Biking",
                                                            "strength_training"]},
        40.0, 38.0, sun, run_bests, history=mixed)
    # The newer sports: doubles (rowing, swimming), long days (hiking, skiing),
    # time-only sports, and all of them sharing one multi-sport week.
    for sport, dpw in (("rowing", 4), ("open_water_swimming", 3), ("hiking", 5),
                       ("cross_country_skiing", 6), ("alpine_skiing", 4), ("climbing", 4),
                       ("backcountry_skiing", 3)):
        add(f"new_sport_{sport}", wed, {"event_sport": sport, "ctl_ramp_per_week": 4.0, "days_per_week": dpw},
            45.0, 44.0, mon, [], threshold_hr=160.0)
    add("multi_new_sports", sun, {"event_sport": "hiking", "ctl_ramp_per_week": 3.0, "days_per_week": 6,
                                  "fitness_sports": ["hiking", "climbing", "rowing", "alpine_skiing"]},
        50.0, 52.0, sun - timedelta(weeks=2), [], history=mixed)
    add("fitness_triathlon", mon, {"event_sport": "triathlon", "ctl_ramp_per_week": 4.0, "days_per_week": 7},
        60.0, 55.0, mon - timedelta(weeks=1), run_bests + [(200, 1.42), (400, 1.31)], history=mixed,
        imperial=True)
    for i in range(10):
        sport = rng.choice(sports)
        today = rng.choice([mon, wed, sun, date(2026, 12, 29), date(2028, 2, 27)])
        add(f"random_{i}_{sport.replace(' ', '_').lower()}", today,
            {"event_sport": sport, "ctl_ramp_per_week": rng.choice([None, -2.0, 0.0, 0.5, 3.0, 6.5, 8.0]),
             "fitness_sports": rng.choice([None, [], [sport], [sport, rng.choice(sports)]]),
             "days_per_week": rng.choice([None, 1, 2, 3, 4, 5, 6, 7]),
             "mtb_discipline": rng.choice([None, "xco"]),
             "cycling_discipline": rng.choice([None, "time_trial"])},
            rng.choice([0.0, 12.5, 48.9, 101.0]), rng.choice([0.0, 20.0, 55.5, 140.0]),
            rng.choice([None, today - timedelta(days=rng.randint(0, 200))]),
            rng.choice([[], run_bests]), history=_history(today, rng, sports),
            ftp=rng.choice([None, 199.5, 310.0]), threshold_hr=rng.choice([None, 150.0, 177.7]),
            base_effective_weeks=rng.choice([None, 0.0, 3.3, 40.0]), imperial=rng.random() < 0.3,
            days_per_week=rng.choice([None, 3, 5]))
    for sport in ("open_water_swimming", "rowing", "hiking", "cross_country_skiing", "alpine_skiing",
                  "climbing"):
        add(f"new_sport_{sport}", wed, {"event_sport": sport, "ctl_ramp_per_week": 3.0, "days_per_week": 5},
            40.0, 42.0, wed - timedelta(weeks=3), [], threshold_hr=165.0)
    measured = {"vdot": 47.3, "source": "heart_rate", "measured": True, "effort_vdot": 41.2}
    profile = {"vdot": 24.1, "source": "profile", "measured": False, "effort_vdot": None}
    add("runner_measured_fitness", mon, {"event_sport": "running", "ctl_ramp_per_week": 2.0}, 40.0, 42.0,
        None, run_bests, running_fitness=measured)
    add("runner_profile_fitness", wed, {"event_sport": "running", "ctl_ramp_per_week": 1.0}, 0.0, 0.0,
        None, [], running_fitness=profile)
    add("runner_and_rider_measured", mon,
        {"event_sport": "running", "ctl_ramp_per_week": 2.0, "fitness_sports": ["running", "cycling"]},
        50.0, 50.0, None, run_bests, running_fitness=measured)
    return {"targets": targets, "plans": plans}


def load_cases(plans: list) -> list:
    """The per-workout load estimate (plan/load.py) over every step shape the
    plans produce — the week sizing depends on it, so it is held on its own."""
    from app.calculators.plan.load import workout_tss
    paces = base.vdot_to_paces(47.3)
    out = []
    for c in plans:
        for w in c["workouts"][:12]:
            for p in (None, paces):
                out.append({"steps": w["steps"], "paces": p, "expect": workout_tss(w["steps"], p)})
    return out


# ── Assembly ─────────────────────────────────────────────────────────────────

def field_test_cases(plans: list) -> list:
    from app.api.training_plan.injectors import _inject_field_tests
    out = []
    for c in plans:
        if not c["workouts"]:
            continue
        sport = c["goal"].get("event_sport")
        ws = [{**w, "scheduled_date": date.fromisoformat(w["scheduled_date"])} for w in c["workouts"]]
        got = _inject_field_tests(ws, SimpleNamespace(event_sport=sport), date(2026, 1, 1))
        out.append({"plan": c["name"], "sport": sport, "expect": _plain(got)})
    return out


def custom_cases() -> list:
    from app.api.training_plan.injectors import attach_custom_workouts
    d = date(2026, 3, 2)

    def sd(day, prim, sec, **kw):
        return {"scheduled_date": d + timedelta(days=day), "workout_type": "strength",
                "primary_muscles": prim, "secondary_muscles": sec, **kw}

    out = []
    scenarios = [
        ("two_muscle_overlap_takes_the_best_session",
         [sd(1, ["quads", "glutes"], ["hamstrings"]), sd(3, ["chest"], ["triceps"])],
         [(11, "Leg Day", {"quads", "hamstrings", "calves"}, [{"exercise_name": "Squat"}])], set()),
        ("one_muscle_is_not_enough",
         [sd(1, ["quads"], [])], [(12, "Quad", {"quads", "biceps"}, [])], set()),
        ("recently_used_is_skipped",
         [sd(1, ["quads", "glutes"], [])], [(13, "Legs", {"quads", "glutes"}, [])], {13}),
        ("a_taken_session_is_not_reused",
         [sd(1, ["quads", "glutes"], [], custom_workout_id=99), sd(2, ["quads", "glutes"], [])],
         [(14, "Legs", {"quads", "glutes"}, [{"exercise_name": "Lunge"}]),
          (15, "Legs B", {"quads", "glutes"}, [])], set()),
        ("ties_keep_the_first_session",
         [sd(4, ["back", "biceps"], []), sd(2, ["back", "biceps"], [])],
         [(16, "Pull", {"back", "biceps"}, [])], set()),
        ("no_muscles_no_match", [sd(1, ["quads", "glutes"], [])], [(17, "Empty", set(), [])], set()),
    ]
    existing = [{"scheduled_date": d + timedelta(days=2), "workout_type": "easy"},
                {"scheduled_date": d, "workout_type": "long"}]
    for name, strength, customs, recent in scenarios:
        got = attach_custom_workouts([dict(e) for e in existing], [dict(s) for s in strength], customs, recent)
        out.append({"name": name, "existing": _plain(existing), "strength": _plain(strength),
                    "customs": [[c[0], c[1], sorted(c[2]), c[3]] for c in customs],
                    "recent": sorted(recent), "expect": _plain(got)})
    return out


def stretch_cases() -> list:
    from app.api.training_plan.injectors import inject_stretch_flows_with
    d = date(2026, 3, 2)
    workouts = [
        {"scheduled_date": d, "sport": "running", "workout_type": "easy"},
        {"scheduled_date": d, "sport": "running", "workout_type": "tempo"},
        {"scheduled_date": d + timedelta(days=1), "sport": "strength_training", "workout_type": "strength",
         "steps": [{"primary_muscles": ["quads", "glutes"]}, {"primary_muscles": None}, {"primary_muscles": ["chest"]}],
         "cooldown_theme": "lower"},
        {"scheduled_date": d + timedelta(days=2), "sport": "Cycling", "workout_type": "endurance"},
        {"scheduled_date": d + timedelta(days=3), "sport": "running", "workout_type": "rest"},
        {"scheduled_date": d + timedelta(days=4), "workout_type": "race"},
        {"scheduled_date": d + timedelta(days=5), "sport": "flexibility_training", "workout_type": "mobility"},
        {"scheduled_date": d + timedelta(days=5), "sport": "running", "workout_type": "long"},
        {"scheduled_date": d + timedelta(days=6), "workout_type": "custom_strength", "steps": []},
        {"scheduled_date": d + timedelta(days=7), "sport": "swimming", "workout_type": "aerobic"},
    ]
    calls = []

    def flow_for(sport, key, is_strength, muscles, theme):
        calls.append([sport, key, is_strength, muscles, theme])
        n = (len(calls) % 3) + 1
        steps = [{"exercise_name": f"S{i}", "duration_seconds": 30 + 15 * i,
                  "sets": 1 + i % 2, "each_side": i % 2 == 0} for i in range(n)]
        if key.endswith("-09"):
            steps.append({"exercise_name": "defaulted"})
        return {"steps": steps, "title": None if len(calls) % 2 else f"Flow {len(calls)}",
                "tagline": "" if len(calls) % 4 == 0 else f"tag {len(calls)}"}

    got = inject_stretch_flows_with([dict(w) for w in workouts], flow_for)
    return [{"workouts": _plain(workouts), "calls": calls, "expect": _plain(got)}]


def fingerprint_cases() -> list:
    from app.api.training_plan.matching import _compute_completion_pct
    rng = Random("fingerprint")
    pct = []
    for _ in range(40):
        a = SimpleNamespace(sport=rng.choice(["running", "mountain_biking", "cycling", None]),
                            distance_meters=rng.choice([None, 0.0, round(rng.uniform(1000, 30000), 1)]),
                            duration_seconds=rng.choice([None, 0, rng.randint(600, 9000)]))
        w = SimpleNamespace(distance_meters=rng.choice([None, 0.0, round(rng.uniform(1000, 30000), 1)]),
                            duration_minutes=rng.choice([None, 0, rng.randint(10, 150)]),
                            workout_type=rng.choice(["easy", "tempo", "long"]))
        pct.append({"activity": vars(a), "workout": vars(w), "expect": _compute_completion_pct(w, a)})

    # The fold: the server's own step, applied one match at a time.
    from app.api.training_plan.matching import advance_fingerprint
    fold = []
    for _ in range(4):
        ew, vdot, n = 0.0, None, 0
        steps = []
        for _ in range(12):
            fam = rng.choice(["running", "running", "cycling"])
            a = {"distance_meters": rng.choice([None, round(rng.uniform(3000, 42195), 1)]),
                 "duration_seconds": rng.choice([None, rng.randint(900, 14000)])}
            wt = rng.choice(["easy", "tempo", "intervals", "long"])
            p = rng.choice([0.0, 0.3, 0.5, 0.72, 1.0, 1.4])
            ew, vdot, n = advance_fingerprint(ew, vdot, n, fam, a["distance_meters"],
                                              a["duration_seconds"], wt, p)
            steps.append({"family": fam, "activity": a, "type": wt, "pct": p,
                          "expect": {"effective_weeks": ew, "vdot": vdot, "sessions": n}})
        fold.append(steps)
    return {"completion": pct, "fold": fold}


def main() -> int:
    plans = plan_cases()
    corpus = {
        "_comment": "Generated by spec/make_plan_fixtures.py from the backend's own endurance planner. Do not edit.",
        "helpers": helper_cases(),
        "skills": skills_cases(),
        "plans": plans,
        "fitness": fitness_cases(),
        "load": load_cases(plans),
        "field_tests": field_test_cases(plans),
        "custom": custom_cases(),
        "stretch": stretch_cases(),
        "fingerprint": fingerprint_cases(),
    }
    OUT.write_text(json.dumps(corpus, separators=(",", ":"), allow_nan=False, ensure_ascii=False) + "\n")
    print(f"wrote {OUT} ({OUT.stat().st_size // 1024} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
