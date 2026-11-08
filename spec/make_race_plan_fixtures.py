#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the race-plan corpus: race_plan.sport_plan, every sport family.

The phone plans every sport's race itself (com.tracks.core.local.LocalRacePlans)
and replays each case here, so a running, cycling, mountain-bike, swim or
triathlon plan on the phone is the one the web shows. The cases carry no
weather, which the phone has no forecast for; race-morning freshness
(calculators/plan/race_day.py) the phone works out as the server does, so
cases with it are here for every sport. Inputs are synthetic; nothing is
personal.

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_race_plan_fixtures.py
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, "/app")

from app.api.coaching.race_helpers import _infer_triathlon_splits  # noqa: E402
from app.api.coaching.race_plan import race_projection, sport_plan  # noqa: E402
from app.calculators.race_predictor.triathlon import leg_courses  # noqa: E402

OUT = Path("/spec/fixtures/race_plan.json")


def _hills(km: int, amp: float = 0.05) -> list[dict]:
    """A course of 500 m segments over rolling hills."""
    out = []
    for i in range(km * 2):
        g = round(amp * math.sin(i / 3.0), 4)
        out.append({"distance_m": 500.0, "gradient": g, "elevation_gain_m": round(max(0.0, g * 500), 1)})
    return out


def _tri_course():
    """A whole triathlon course that returns to transition after the swim and
    the bike, with rolling hills on the bike and the run."""
    deg = 111_195.0
    path = []
    def go(north_m, west_m, ele):
        path.append([round(north_m / deg, 6), round(-west_m / deg, 6), ele])
    for i in list(range(9)) + list(range(7, -1, -1)):
        go(i * 100, 0, 2.0)
    for side in (lambda t: (0, t), lambda t: (t, 10), lambda t: (10, 10 - t), lambda t: (10 - t, 0)):
        for t in range(1, 11):
            n, w = side(t)
            go(n * 1000, w * 1000, round(40 * math.sin(len(path) / 4.0) + 50, 1))
    for i in list(range(1, 6)) + list(range(4, -1, -1)):
        go(-i * 1000, 0, round(20 * math.sin(i) + 30, 1))
    from app.calculators.race_predictor.geo import _haversine_m
    segs = []
    for a, b in zip(path, path[1:]):
        d = _haversine_m(a[0], a[1], b[0], b[1])
        if d > 0:
            g = (b[2] - a[2]) / d
            segs.append({"distance_m": round(d, 1), "elevation_gain_m": round(max(0.0, b[2] - a[2]), 1),
                         "gradient": round(g, 4)})
    return segs, path


def case(name, family, distance, *, vdot=None, ftp=None, css=None, max_hr=None, lthr=None,
         course=None, course_type="flat", spread=0.0, pace_hr=False, imperial=False,
         mtb=None, cycling=None, splits=None, path=None, leg_files=None, transitions=None,
         intensity=None, freshness=1.0, run_indices=None):
    rp = SimpleNamespace(course_segments=course, course_type=course_type, pace_hr_mode="pace_hr" if pace_hr else "pace",
                         split_spread=spread, triathlon_splits_m=splits, technicality_factor=None,
                         technicality_label=None, target_hr_ceiling=None, climb_target_w_per_kg=None,
                         course_path=path, tri_leg_courses=leg_files, tri_transitions_m=transitions,
                         intensity=intensity)
    goal = SimpleNamespace(mtb_discipline=mtb, cycling_discipline=cycling)
    metrics = {"family": family, "vdot": vdot, "run_indices": run_indices, "ftp": ftp, "css": css,
               "max_hr": max_hr, "lthr": lthr}
    laps, seconds, snap = sport_plan(family, metrics, rp, goal, distance, imperial=imperial,
                                     freshness_factor=freshness)
    return {
        "name": name, "family": family, "distance_m": distance,
        "metrics": metrics, "course_segments": course, "course_type": course_type, "split_spread": spread,
        "pace_hr": pace_hr, "imperial": imperial, "mtb_discipline": mtb, "cycling_discipline": cycling,
        "triathlon_splits_m": splits, "course_path": path, "tri_leg_courses": leg_files,
        "tri_transitions_m": transitions, "intensity": intensity, "freshness": freshness,
        "expected": {
            "laps": laps, "seconds": seconds,
            "triathlon_legs": (snap or {}).get("triathlon_legs"),
            "triathlon_splits_m": rp.triathlon_splits_m,
            "technicality_factor": rp.technicality_factor, "technicality_label": rp.technicality_label,
            "target_hr_ceiling": rp.target_hr_ceiling, "climb_target_w_per_kg": rp.climb_target_w_per_kg,
            "tri_courses": (leg_courses(course, path, leg_files, transitions,
                                        splits or _infer_triathlon_splits(distance))
                            if family == "triathlon" and (course or leg_files) else None),
        },
    }


def main() -> None:
    hills = _hills(40)
    tri = _tri_course()
    cases = [
        case("road race, flat", "cycling", 40_000, ftp=250, lthr=165),
        case("road race, rolling terrain", "cycling", 80_000, ftp=280, lthr=170, course_type="rolling", spread=0.4),
        case("time trial on a course", "cycling", 20_000, ftp=300, lthr=172, course=_hills(10, 0.02), cycling="time_trial"),
        case("hill climb", "cycling", 12_000, ftp=260, lthr=168, course=_hills(6, 0.07), cycling="hill_climb"),
        case("criterium with HR ceilings", "cycling", 30_000, ftp=240, max_hr=185, lthr=160, pace_hr=True,
             cycling="criterium"),
        case("gran fondo in miles", "cycling", 160_000, ftp=220, lthr=158, imperial=True, course=hills),
        case("mtb with FTP", "mountain_biking", 30_000, ftp=250, lthr=165, mtb="xco", course=_hills(15, 0.08)),
        case("mtb without FTP", "mountain_biking", 50_000, lthr=162, mtb="xcm", course_type="hilly", spread=-0.3),
        case("mtb enduro, no thresholds", "mountain_biking", 25_000, mtb="enduro"),
        case("pool swim", "swimming", 400, css=95.0),
        case("open water swim", "swimming", 3_800, css=105.0),
        case("sprint triathlon", "triathlon", 25_750, css=100.0, ftp=230, vdot=48.0),
        case("olympic triathlon, no swim test", "triathlon", 51_500, ftp=250, vdot=50.0, max_hr=188, pace_hr=True),
        case("70.3 with set splits", "triathlon", 113_000, css=98.0, ftp=260, vdot=52.0,
             splits={"swim_m": 1900, "bike_m": 90000, "run_m": 21097}),
        case("ironman, run only", "triathlon", 226_000, vdot=45.0),
        case("olympic on a whole course", "triathlon", 51_500, css=100.0, ftp=250, vdot=50.0,
             course=tri[0], path=tri[1]),
        case("olympic, own bike file over the whole", "triathlon", 51_500, ftp=250, vdot=50.0,
             course=tri[0], path=tri[1], leg_files={"bike": {"segments": _hills(41, 0.03), "path": []}}),
        case("olympic, the user's transitions", "triathlon", 51_500, css=100.0, ftp=250, vdot=50.0,
             course=tri[0], path=tri[1], transitions=[1700.0, 41000.0], pace_hr=True, max_hr=186),
        case("sprint, leg files only", "triathlon", 25_750, css=100.0, ftp=230, vdot=48.0,
             leg_files={"run": {"segments": _hills(5, 0.04), "path": []},
                        "swim": {"segments": [{"distance_m": 780.0, "elevation_gain_m": 0.0, "gradient": 0.0}], "path": []}}),
        case("hike on VDOT", "hiking", 20_000, vdot=42.0, course_type="mountainous"),
        case("rowing on VDOT with HR", "rowing", 2_000, vdot=50.0, max_hr=190, pace_hr=True),
        # The race-intensity slider (race_helpers.race_intensity), each way,
        # in every model it reaches: power, speed with no FTP, CSS, a
        # triathlon's three legs, VDOT's fallback, and a value past the end
        # of the slider, which is clamped.
        case("road race, ridden harder", "cycling", 40_000, ftp=250, lthr=165, course=_hills(20, 0.03),
             intensity=1.06),
        case("mtb without FTP, eased", "mountain_biking", 50_000, lthr=162, mtb="xcm", intensity=0.93),
        case("open water swim, pushed", "swimming", 3_800, css=105.0, intensity=1.08),
        case("olympic triathlon, held back", "triathlon", 51_500, css=100.0, ftp=250, vdot=50.0,
             course=tri[0], path=tri[1], intensity=0.95),
        case("hike, past the slider's end", "hiking", 20_000, vdot=42.0, intensity=1.5),
        # Running — the plan the race-plans screen and the guided race both
        # pace — then race-morning freshness (race_day.freshness_factor) in
        # every model it reaches: VDOT and the marathon's volume half, power,
        # speed with no FTP, CSS, a triathlon's three legs, VDOT's fallback.
        case("10 K, flat", "running", 10_000, vdot=45.0),
        case("10 K, hilly course, pace and HR", "running", 10_000, vdot=41.3, course=_hills(10, 0.04),
             pace_hr=True, max_hr=191, spread=0.5),
        case("half marathon in miles, rolling", "running", 21_097.5, vdot=38.0, course_type="rolling",
             imperial=True, spread=-0.2),
        case("marathon with its training volume", "running", 42_195, vdot=47.0, run_indices=[52.0, 331.0]),
        case("marathon, fresh on race morning", "running", 42_195, vdot=47.0, run_indices=[52.0, 331.0],
             freshness=0.976),
        case("10 K, fresh and pushed", "running", 10_000, vdot=45.0, freshness=0.985, intensity=1.04),
        case("5 K, tired", "running", 5_000, vdot=33.2, freshness=1.03),
        case("road race, tapered", "cycling", 80_000, ftp=280, lthr=170, course=_hills(20, 0.03),
             freshness=0.982),
        case("mtb with FTP, tapered", "mountain_biking", 30_000, ftp=250, lthr=165, mtb="xco",
             course=_hills(15, 0.08), freshness=0.99),
        case("mtb without FTP, tapered", "mountain_biking", 50_000, lthr=162, mtb="xcm", freshness=0.99),
        case("open water swim, tapered", "swimming", 3_800, css=105.0, freshness=0.98),
        case("olympic triathlon, tapered", "triathlon", 51_500, css=100.0, ftp=250, vdot=50.0,
             course=tri[0], path=tri[1], freshness=0.985),
        case("hike, tired", "hiking", 20_000, vdot=42.0, freshness=1.012),
    ]
    # What a race-day prediction is made of (race_plan.race_projection): the
    # page's "today" finish beside race day's, for every family it answers.
    projections = []
    for family, vdot, today_vdot, adherence, indices, form, sec, dist in (
        ("running", 46.1, 44.9, 0.86, None, {"today": -12.4, "race_day": 14.0}, 2743.6, 10_000.0),
        ("running", 44.9, 44.9, None, None, {"today": 3.1, "race_day": 3.1}, 1290.2, 5_000.0),
        ("running", 48.3, 47.0, 0.5, [52.0, 331.0], {"today": None, "race_day": None}, 12400.0, 42_195.0),
        ("running", 39.0, 38.2, 1.0, None, {"today": -35.0, "race_day": 31.5}, 6611.0, 21_097.5),
        ("running", 41.0, None, 0.333333, None, {"today": 0.0, "race_day": 9.9}, 3000.0, 10_000.0),
        ("triathlon", 50.4, 49.0, 0.71, None, {"today": -5.0, "race_day": 12.0}, 9000.0, 51_500.0),
        ("cycling", None, None, None, None, {"today": 1.0, "race_day": 2.0}, 7000.0, 80_000.0),
        ("swimming", None, None, None, None, {"today": None, "race_day": None}, 900.0, 1_500.0),
    ):
        metrics = {"vdot": vdot, "vdot_today": today_vdot, "adherence": adherence, "run_indices": indices}
        projections.append({"family": family, "metrics": metrics, "form": form, "actual_sec": sec,
                            "distance_m": dist,
                            "expect": race_projection(family, metrics, form, sec, dist)})
    OUT.write_text(json.dumps({"_comment": "Generated by spec/make_race_plan_fixtures.py. Do not edit.",
                               "cases": cases, "projections": projections}, indent=1) + "\n")


if __name__ == "__main__":
    main()
