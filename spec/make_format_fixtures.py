#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the formatting corpus against the web app's implementation.

Same approach as the sport taxonomy: the expected values come from the code
that is already shipping — `frontend/src/utils/formatUtils.js` and
`frontend/src/lib/weight.js` — evaluated under Node, not from the Kotlin port
that is meant to match it. A port is exactly the kind of work that looks right
and isn't, so grading it against itself would prove nothing.

Unlike the taxonomy fixtures, these stay regenerable: the JS remains the web
app's implementation, so it is still an independent oracle. Rerun after
changing a formatter in JS, and the Kotlin tests will tell you whether the port
still agrees.

Usage: python3 spec/make_format_fixtures.py
"""

import json
import subprocess
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
OUT = SPEC_DIR / "fixtures" / "format.json"

# Chosen to hit the boundaries rather than the middle: zero and null (which
# render as an em dash or "BW"), values that land exactly on a rounding
# boundary, paces whose seconds round up to 60, and durations either side of an
# hour.
SPEEDS = [None, 0, 0.5, 1.0, 2.5, 2.7777777, 3.0, 3.3333, 4.4704, 5.0, 8.0, 11.176]
DISTANCES = [None, 0, 1, 100, 999.5, 1000, 1609.34, 5000, 10000, 42195, 100000]
ELEVATIONS = [None, 0, 0.4, 0.5, 1, 100.5, 1234.56, -50]
DURATIONS = [None, 0, 1, 59, 60, 61, 599, 3599, 3600, 3661, 86399, 90061]
WEIGHTS = [None, 0, 1, 9.0718, 20, 22.6796, 47.5, 60.0, 70.307, 100]
HR_RATIOS = [-0.5, 0, 0.1, 0.25, 0.49, 0.5, 0.51, 0.75, 0.99, 1.0, 1.5]

_ORACLE = r"""
import "./scripts/extensionless-resolver.mjs";
const f = await import("./src/utils/formatUtils.js");
const w = await import("./src/lib/weight.js");
const input = JSON.parse(process.argv[2]);

const out = {
  pace_min_per_km: input.speeds.map((v) => f.fmtPaceMin(v)),
  pace_min_per_mile: input.speeds.map((v) => f.fmtPaceMinMi(v)),
  speed_metric: input.speeds.map((v) => f.formatSpeed(v, false)),
  speed_imperial: input.speeds.map((v) => f.formatSpeed(v, true)),
  pace_running_metric: input.speeds.map((v) => f.formatPace(v, true, false)),
  pace_cycling_metric: input.speeds.map((v) => f.formatPace(v, false, false)),
  pace_running_imperial: input.speeds.map((v) => f.formatPace(v, true, true)),

  distance_metric: input.distances.map((v) => f.formatDistance(v, false)),
  distance_imperial: input.distances.map((v) => f.formatDistance(v, true)),

  elevation_metric: input.elevations.map((v) => f.formatElevation(v, false)),
  elevation_imperial: input.elevations.map((v) => f.formatElevation(v, true)),

  elapsed: input.durations.map((v) => f.fmtElapsed(v)),

  weight_metric: input.weights.map((v) => w.fmtWeight(v, false)),
  weight_imperial: input.weights.map((v) => w.fmtWeight(v, true)),
  kg_to_display_metric: input.weights.map((v) => w.kgToDisplay(v, false)),
  kg_to_display_imperial: input.weights.map((v) => w.kgToDisplay(v, true)),

  hr_color: input.hrRatios.map((v) => f.hrColor(v)),
};
console.log(JSON.stringify(out));
"""


def oracle(payload: dict) -> dict:
    script = ROOT / "frontend" / ".format-oracle.mjs"
    script.write_text(_ORACLE)
    try:
        result = subprocess.run(
            ["node", ".format-oracle.mjs", json.dumps(payload)],
            cwd=ROOT / "frontend",
            capture_output=True, text=True, check=True,
        )
    finally:
        script.unlink(missing_ok=True)
    return json.loads(result.stdout)


def main() -> int:
    inputs = {
        "speeds": SPEEDS,
        "distances": DISTANCES,
        "elevations": ELEVATIONS,
        "durations": DURATIONS,
        "weights": WEIGHTS,
        "hrRatios": HR_RATIOS,
    }
    expected = oracle(inputs)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "_comment": (
            "GENERATED from frontend/src/utils/formatUtils.js and "
            "frontend/src/lib/weight.js by spec/make_format_fixtures.py. The "
            "shared oracle for the Kotlin port in mobile/core."
        ),
        "inputs": inputs,
        "expected": expected,
    }, indent=2) + "\n")

    total = sum(len(v) for v in expected.values())
    print(f"make_format_fixtures: {len(expected)} formatters, {total} cases "
          f"-> {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
