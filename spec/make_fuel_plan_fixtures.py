# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the fuelling corpus: calculators/fuel_plan.py and the race FIT encoder.

com.tracks.core.fuel.FuelPlan and com.tracks.core.fit.RaceFit replay every
case. Inputs are synthetic — invented products, invented plans — and nothing
is personal.

    docker run --rm --env-file .env -v "$PWD/backend:/app" -v "$PWD/spec:/spec" \\
        tracks-backend python /spec/make_fuel_plan_fixtures.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, "/app")

from app.calculators import fuel_plan as fp  # noqa: E402
from app.calculators.race_predictor.fit import generate_race_fit  # noqa: E402

OUT = Path("/spec/fixtures/fuel_plan.json")

PRODUCTS = [
    {"uid": "p-gel", "name": "Gel", "carbs_g": 25.0, "sodium_mg": 50, "fluid_ml": 0, "caffeine_mg": 0},
    {"uid": "p-big", "name": "Big Gel", "carbs_g": 40.0, "sodium_mg": 200, "fluid_ml": 0, "caffeine_mg": 100},
    {"uid": "p-mix", "name": "Drink Mix", "carbs_g": 45.5, "sodium_mg": 500, "fluid_ml": 500, "caffeine_mg": 0},
    {"uid": "p-chew", "name": "Chews", "carbs_g": 12.5, "sodium_mg": 30, "fluid_ml": 0, "caffeine_mg": 0},
    {"uid": "p-zero", "name": "Water", "carbs_g": 0.0, "sodium_mg": 0, "fluid_ml": 500, "caffeine_mg": 0},
]


def _laps(n_km: float, pace: float, spread: float = 0.0) -> list[dict]:
    laps = []
    whole = int(n_km)
    for i in range(whole):
        laps.append({"lap": i + 1, "distance_m": 1000.0,
                     "target_sec_per_km": pace * (1 + spread * (0.5 - i / max(1, whole)))})
    rest = round((n_km - whole) * 1000, 1)
    if rest > 0:
        laps.append({"lap": whole + 1, "distance_m": rest, "target_sec_per_km": pace})
    return laps


def main() -> None:
    fx: dict = {"targets": [], "timeline": [], "gut": [], "race_fit": []}

    for dur in (25, 60, 61, 119.5, 120, 150, 240, 241, 600):
        for weather in ((None, None), (None, 80.0), (18.0, 40.0), (26.0, 30.0), (22.0, 75.0), (19.0, 90.0), (33.0, 50.0)):
            for override in ({}, {"carbs": 72, "fluid": 900, "sodium": 1000, "interval": 15}):
                t = fp.targets(dur, temperature_c=weather[0], humidity_pct=weather[1], **override)
                fx["targets"].append({"duration_min": dur, "temperature_c": weather[0],
                                      "humidity_pct": weather[1], "override": override, "expected": t})

    cases = [
        (45, 30, 20, [], None),
        (95, 60, 20, [PRODUCTS[0]], _laps(21.0975, 290)),
        (190, 80, 20, [PRODUCTS[0], PRODUCTS[1]], _laps(42.195, 270, 0.06)),
        (190, 80, 15, [PRODUCTS[1], PRODUCTS[0]], _laps(42.195, 270, -0.06)),
        (300, 90, 20, [PRODUCTS[2], PRODUCTS[3], PRODUCTS[4]], None),
        (240, 90, 30, [PRODUCTS[4]], _laps(50.0, 288)),
        (60, 60, 20, PRODUCTS, _laps(10.0, 360)),
        (600, 100, 25, [PRODUCTS[3]], _laps(100.0, 360)),
    ]
    for dur, carbs, interval, products, laps in cases:
        tg = fp.targets(dur, carbs=carbs, interval=interval)
        fx["timeline"].append({"duration_min": dur, "targets": tg, "products": products, "laps": laps,
                               "expected": fp.timeline(dur, tg, products, laps)})

    race = "2026-12-06"
    workouts = []
    for i, (date, sport, mins, override) in enumerate([
        ("2026-08-01", "running", 90, None),          # 127 days out — outside the window
        ("2026-09-13", "running", 90, None),
        ("2026-09-15", "strength_training", 90, None),
        ("2026-09-20", "running", 60, None),          # too short
        ("2026-09-27", "cycling", 150, None),
        ("2026-10-04", "running", 100, None),
        ("2026-10-11", "running", 110, 55),           # hand-set
        ("2026-10-18", "running", 120, None),
        ("2026-10-25", "running", 120, None),
        ("2026-11-01", "running", 130, None),
        ("2026-11-08", "running", 140, None),
        ("2026-11-15", "running", 150, None),
        ("2026-11-26", "running", 90, None),          # rehearsal fortnight
        ("2026-12-06", "running", 200, None),         # race day itself — excluded
    ]):
        workouts.append({"uid": f"w{i:02d}", "scheduled_date": date, "sport": sport, "workout_type": "long",
                         "duration_minutes": mins, "fuel_carbs_per_hour": override})
    log_sets = [
        [],
        [{"uid": "l1", "planned_workout_uid": "w01", "date": "2026-09-13", "comfort": 5, "carbs_g": 60, "duration_min": 90}],
        [{"uid": "l1", "planned_workout_uid": "w01", "date": "2026-09-13", "comfort": 2, "carbs_g": 40, "duration_min": 90},
         {"uid": "l2", "planned_workout_uid": "w04", "date": "2026-09-27", "comfort": 3, "carbs_g": 90, "duration_min": 150},
         {"uid": "l3", "planned_workout_uid": "w05", "date": "2026-10-04", "comfort": 4, "carbs_g": 20, "duration_min": None},
         {"uid": "l4", "planned_workout_uid": "w07", "date": "2026-10-18", "comfort": 1, "carbs_g": 100, "duration_min": 120},
         {"uid": "l5", "planned_workout_uid": "w07", "date": "2026-10-19", "comfort": 5, "carbs_g": 120, "duration_min": 120}],
    ]
    for race_carbs in (60, 90):
        for logs in log_sets:
            fx["gut"].append({"race_carbs": race_carbs, "race_date": race, "workouts": workouts, "logs": logs,
                              "expected": fp.gut_training(race_carbs, race, workouts, logs)})

    for name, sport, laps, pace, hr, max_hr, products in (
        ("10K", "running", _laps(10.0, 300), True, False, None, [PRODUCTS[0]]),
        ("Half Marathon", "running", _laps(21.0975, 290, 0.04), True, False, None, [PRODUCTS[0], PRODUCTS[1]]),
        ("Marathon", "running", [dict(l, hr_ceiling=150 + i % 20) for i, l in enumerate(_laps(42.195, 280))],
         False, True, 190, [PRODUCTS[1], PRODUCTS[3]]),
        ("Gran Fondo", "cycling", _laps(40.0, 120), True, False, None, []),
    ):
        dur = sum(l["distance_m"] / 1000 * l["target_sec_per_km"] for l in laps) / 60.0
        tl = fp.timeline(dur, fp.targets(dur), products, laps)
        data = generate_race_fit(name, sport, laps, pace_coaching=pace, hr_coaching=hr, max_hr=max_hr,
                                 fuel_items=tl["items"], time_created_ms=1790000000000)
        fx["race_fit"].append({"name": name, "sport": sport, "laps": laps, "pace_coaching": pace,
                               "hr_coaching": hr, "max_hr": max_hr, "fuel_items": tl["items"],
                               "time_created_ms": 1790000000000, "hex": data.hex()})

    OUT.write_text(json.dumps(fx, indent=1) + "\n")
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
