#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the fueling golden fixture corpus.

## Where the expected values come from

The ORIGINAL frontend/src/utils/fuelingUtils.js, evaluated under Node — the
only implementation of this calculator, frontend-only, with no backend
equivalent. Same rationale as spec/make_fixtures.py: grading the port against
the code that predates the spec is the only way the corpus proves anything.

frontend/src/utils/fuelingUtils.js now sources its thresholds from the
generated spec/fueling.js (its algorithm is unchanged and stays the oracle
implementation — see that file's header), so rerunning this would grade the
current implementation against itself for the threshold values, even though
the algorithm is still hand-written JS. **The corpus is frozen.** Extend it by
hand: add a case below, work out the expected value from spec/fueling.yaml,
and add it to spec/fixtures/fueling.json directly.

Usage: python3 spec/make_fueling_fixtures.py
"""

import json
import subprocess
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
OUT = SPEC_DIR / "fixtures" / "fueling.json"

DEFAULT_CARBS_CASES = [0.1, 0.5, 1, 1.0001, 1.5, 2, 2.0001, 3, 4, 4.0001, 5, 10]

# Chosen to hit every boundary: exactly-25/32C, just under each, humidity
# exactly at 70%, humidity but too cold to count, missing temp/humidity
# fields entirely, and the empty-object/null/undefined shapes a caller might
# pass before weather data has loaded.
WEATHER_CASES = [
    ("no_weather", None),
    ("mild", {"temperature_c": 15, "humidity_pct": 40}),
    ("exactly_25", {"temperature_c": 25, "humidity_pct": 40}),
    ("just_under_25", {"temperature_c": 24.9, "humidity_pct": 40}),
    ("hot_30", {"temperature_c": 30, "humidity_pct": 40}),
    ("exactly_32_very_hot", {"temperature_c": 32, "humidity_pct": 40}),
    ("just_under_32", {"temperature_c": 31.9, "humidity_pct": 40}),
    ("very_hot_38", {"temperature_c": 38, "humidity_pct": 50}),
    ("humid_only_exactly_70", {"temperature_c": 22, "humidity_pct": 70}),
    ("humid_only_69", {"temperature_c": 22, "humidity_pct": 69}),
    ("humid_but_cold_19", {"temperature_c": 19, "humidity_pct": 80}),
    ("humid_no_temp", {"temperature_c": None, "humidity_pct": 80}),
    ("temp_only_no_humidity", {"temperature_c": 28, "humidity_pct": None}),
    ("hot_and_humid", {"temperature_c": 27, "humidity_pct": 85}),
    ("empty_object", {}),
]

SIP_ML_CASES = [
    (60, 0.12, 15), (90, 0.08, 12), (80, 0.06, 10), (30, 0.12, 15), (100, 0.12, 15),
]

_LAPS_A = [{"target_sec_per_km": 300, "distance_m": 1000}] * 5
_LAPS_GAP = [
    {"target_sec_per_km": 300, "distance_m": 1000},
    {"target_sec_per_km": None, "distance_m": None},
    {"target_sec_per_km": 280, "distance_m": 2000},
]
_LAPS_ZERO = [
    {"target_sec_per_km": 300, "distance_m": 0},
    {"target_sec_per_km": 300, "distance_m": 1000},
]

PER_LAP_CASES = [
    ("5x1km_5min_km", _LAPS_A, 15, 15, 200),
    ("with_gap_lap", _LAPS_GAP, 12, 12, 150),
    ("zero_distance_lap", _LAPS_ZERO, 10, 10, 100),
    ("empty_laps", [], 15, 15, 200),
]

GET_PER_LAP_CASES = [
    ("basic_5k_mild", _LAPS_A, 1500, None, {"temperature_c": 15, "humidity_pct": 40}),
    ("hot_with_explicit_carbs", _LAPS_A, 1500, 75, {"temperature_c": 30, "humidity_pct": 50}),
    ("no_laps", [], 1500, None, None),
    ("no_predicted_seconds", _LAPS_A, None, None, None),
    ("very_hot_long_run", _LAPS_A, 14400, None, {"temperature_c": 35, "humidity_pct": 60}),
]

_ORACLE_JS = r"""
import "./scripts/extensionless-resolver.mjs";
const {
  defaultCarbsPerHour, computeFuelingParams, computeSipMl,
  computePerLapDrinks, getPerLapDrinks,
} = await import("./src/utils/fuelingUtils.js");
const input = JSON.parse(process.argv[2]);

console.log(JSON.stringify({
  defaultCarbs: input.defaultCarbs.map((h) => ({
    duration_hours: h, expected: defaultCarbsPerHour(h),
  })),
  params: input.params.map(([name, weather]) => ({
    name, weather, expected: computeFuelingParams(3, weather),
  })),
  sipMl: input.sipMl.map(([carbsPerHour, concentration, sipIntervalMin]) => ({
    carbsPerHour, concentration, sipIntervalMin,
    expected: computeSipMl(carbsPerHour, concentration, sipIntervalMin),
  })),
  perLap: input.perLap.map(([name, laps, sipIntervalMin, firstSipMin, perSipMl]) => ({
    name, laps, sip_interval_min: sipIntervalMin, first_sip_min: firstSipMin,
    per_sip_ml: perSipMl,
    expected: computePerLapDrinks(laps, sipIntervalMin, firstSipMin, perSipMl),
  })),
  getPerLap: input.getPerLap.map(([name, laps, predictedSeconds, carbs, weather]) => ({
    name, laps, predicted_seconds: predictedSeconds, fueling_plan_carbs: carbs, weather,
    expected: getPerLapDrinks(laps, predictedSeconds, carbs, weather),
  })),
}));
"""


def oracle(payload: dict) -> dict:
    script = ROOT / "frontend" / ".fueling-oracle.mjs"
    script.write_text(_ORACLE_JS)
    try:
        result = subprocess.run(
            ["node", ".fueling-oracle.mjs", json.dumps(payload)],
            cwd=ROOT / "frontend",
            capture_output=True, text=True, check=True,
        )
    finally:
        script.unlink(missing_ok=True)
    return json.loads(result.stdout)


def main() -> int:
    print(
        "make_fueling_fixtures: refusing to run by default — "
        "frontend/src/utils/fuelingUtils.js now sources its thresholds from "
        "the generated spec, so rerunning this grades that half of the "
        "implementation against itself.\n"
        "  spec/fixtures/fueling.json is a frozen artifact; extend it by "
        "hand, or call _regenerate() directly if you have checked out the "
        "pre-spec fuelingUtils.js to re-baseline against."
    )
    return 1


def _regenerate() -> int:
    payload = {
        "defaultCarbs": DEFAULT_CARBS_CASES,
        "params": WEATHER_CASES,
        "sipMl": SIP_ML_CASES,
        "perLap": PER_LAP_CASES,
        "getPerLap": GET_PER_LAP_CASES,
    }
    got = oracle(payload)

    fixtures = {
        "_comment": (
            "GENERATED from the original frontend/src/utils/fuelingUtils.js "
            "(before it sourced its thresholds from spec/fueling.js) via "
            "spec/make_fueling_fixtures.py, run under Node. There is no "
            "backend equivalent — the frontend is the sole oracle. Shared "
            "oracle for the JavaScript (utils/fuelingUtils.js) and Kotlin "
            "(spec/Fueling.kt) evaluators — see spec/codegen.py and "
            "spec/fueling.yaml."
        ),
        "default_carbs_per_hour_cases": got["defaultCarbs"],
        "fueling_params_cases": got["params"],
        "sip_ml_cases": got["sipMl"],
        "per_lap_drinks_cases": got["perLap"],
        "get_per_lap_drinks_cases": got["getPerLap"],
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(fixtures, indent=2) + "\n")
    print(f"make_fueling_fixtures: wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
