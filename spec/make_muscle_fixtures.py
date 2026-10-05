#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the muscle-groups golden fixture corpus.

## Where the expected values come from

The ORIGINAL frontend/src/utils/muscleGroups.js, evaluated under Node — the
only implementation that existed before spec/muscle_groups.yaml, and the file
this spec's tables were transcribed from. Same rationale as
spec/make_fixtures.py: grading a transcription against the code that predates
it is the only way the corpus proves the transcription is right.

frontend/src/utils/muscleGroups.js now delegates to spec/muscleActivation.js
(itself reading the generated spec/muscleGroups.js), so rerunning this would
grade the implementation against itself. **The corpus is frozen.** Extend it
by hand: add a case to the corpus lists below, work out the expected value
from spec/muscle_groups.yaml by hand, and add it to
spec/fixtures/muscle_groups.json directly.

Usage: python3 spec/make_muscle_fixtures.py
"""

import json
import subprocess
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
OUT = SPEC_DIR / "fixtures" / "muscle_groups.json"

# One case per branch worth pinning: the JS falsy-zero quirk on `repetitions`
# (0 and missing both fall back to 10 — see spec/muscle_groups.yaml), the
# high-rep and negative-rep clamps, non-"active" set filtering, unknown/blank
# category handling, category case-insensitivity, and a category
# (total_body) wide enough to exercise several muscles' totals at once.
ACTIVATION_CASES = [
    ("empty", []),
    ("single_squat", [{"set_type": "active", "exercise_category": "squat", "repetitions": 5}]),
    ("mixed_exercises", [
        {"set_type": "active", "exercise_category": "squat", "repetitions": 5},
        {"set_type": "active", "exercise_category": "bench_press", "repetitions": 8},
        {"set_type": "active", "exercise_category": "curl", "repetitions": 12},
    ]),
    ("non_active_filtered", [
        {"set_type": "active", "exercise_category": "squat", "repetitions": 5},
        {"set_type": "rest", "exercise_category": "squat", "repetitions": 5},
        {"set_type": "warmup", "exercise_category": "squat", "repetitions": 5},
    ]),
    ("unknown_category_ignored", [
        {"set_type": "active", "exercise_category": "65534", "repetitions": 5},
        {"set_type": "active", "exercise_category": "unknown", "repetitions": 5},
        {"set_type": "active", "exercise_category": "", "repetitions": 5},
        {"set_type": "active", "exercise_category": "not_a_real_category", "repetitions": 5},
    ]),
    ("case_insensitive_category", [{"set_type": "active", "exercise_category": "SQUAT", "repetitions": 5}]),
    ("high_rep_clamped_to_40", [{"set_type": "active", "exercise_category": "curl", "repetitions": 100}]),
    ("zero_reps_falls_back_to_10", [{"set_type": "active", "exercise_category": "curl", "repetitions": 0}]),
    ("missing_reps_falls_back_to_10", [{"set_type": "active", "exercise_category": "curl"}]),
    ("negative_reps_clamped_to_1", [{"set_type": "active", "exercise_category": "curl", "repetitions": -5}]),
    ("repeated_category_accumulates_and_counts", [
        {"set_type": "active", "exercise_category": "squat", "repetitions": 5},
        {"set_type": "active", "exercise_category": "squat", "repetitions": 5},
        {"set_type": "active", "exercise_category": "squat", "repetitions": 5},
    ]),
    ("total_body_wide_secondary_spread", [{"set_type": "active", "exercise_category": "total_body", "repetitions": 10}]),
]

CATEGORY_LABEL_CASES = [
    "bench_press", "squat", "deadlift", "total_body", "triceps_extension",
    "hip_hinge", "push", "pull",     # firmware extensions, absent from category_labels
    "some_custom_category",          # not in the table at all
    None, "",
]

MUSCLE_LABEL_CASES = [
    "chest", "front_delts", "calves_back", "adductors", "upper_back",
    "shoulders",           # used in total_body's secondary list, absent from muscle_labels
    "totally_unknown_key",
]

_ORACLE_JS = r"""
import "./scripts/extensionless-resolver.mjs";
const { computeMuscleActivation, categoryLabel, muscleLabel } =
  await import("./src/utils/muscleGroups.js");
const input = JSON.parse(process.argv[2]);
console.log(JSON.stringify({
  activation: input.activation.map(([name, sets]) => ({
    name, sets, expected: computeMuscleActivation(sets),
  })),
  categoryLabel: input.categoryLabel.map((key) => ({ key, expected: categoryLabel(key) })),
  muscleLabel: input.muscleLabel.map((key) => ({ key, expected: muscleLabel(key) })),
}));
"""


def oracle(payload: dict) -> dict:
    script = ROOT / "frontend" / ".muscle-oracle.mjs"
    script.write_text(_ORACLE_JS)
    try:
        result = subprocess.run(
            ["node", ".muscle-oracle.mjs", json.dumps(payload)],
            cwd=ROOT / "frontend",
            capture_output=True, text=True, check=True,
        )
    finally:
        script.unlink(missing_ok=True)
    return json.loads(result.stdout)


def main() -> int:
    print(
        "make_muscle_fixtures: refusing to run — "
        "frontend/src/utils/muscleGroups.js now delegates to the spec, so "
        "rerunning this would grade the implementation against itself.\n"
        "  spec/fixtures/muscle_groups.json is a frozen artifact; extend it "
        "by hand. See the note in main() to rebaseline from a pre-spec "
        "checkout if you genuinely need to."
    )
    return 1


def _regenerate() -> int:
    payload = {
        "activation": ACTIVATION_CASES,
        "categoryLabel": CATEGORY_LABEL_CASES,
        "muscleLabel": MUSCLE_LABEL_CASES,
    }
    got = oracle(payload)

    fixtures = {
        "_comment": (
            "GENERATED from the original frontend/src/utils/muscleGroups.js "
            "(before it became a thin wrapper over spec/muscleGroups.js + "
            "spec/muscleActivation.js) via spec/make_muscle_fixtures.py, run "
            "under Node. Shared oracle for the Python "
            "(app/calculators/muscle_activation.py), JavaScript "
            "(spec/muscleActivation.js), and Kotlin (spec/MuscleGroups.kt) "
            "evaluators — see spec/codegen.py and spec/muscle_groups.yaml."
        ),
        "activation_cases": [
            {
                "name": c["name"], "sets": c["sets"],
                "expected": {
                    "activation": c["expected"]["activation"],
                    "totals": c["expected"]["totals"],
                    "category_counts": c["expected"]["categoryCounts"],
                },
            }
            for c in got["activation"]
        ],
        "category_label_cases": got["categoryLabel"],
        "muscle_label_cases": got["muscleLabel"],
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(fixtures, indent=2) + "\n")
    print(f"make_muscle_fixtures: wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
