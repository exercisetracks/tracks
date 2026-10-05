#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Baseline the strength (equipment + experience) golden fixture corpus.

## Where the expected values come from

The ORIGINAL backend/app/calculators/strength_plan/leveling.py — the
authoritative implementation for the four experience-level table functions,
evaluated in-process (this script must run where `app` importable, e.g.
`docker exec backend python spec/make_strength_fixtures.py` from /app, or
with PYTHONPATH=backend). The equipment list is transcribed from
frontend/src/lib/equipment.js (the richer superset) cross-checked against the
old backend/app/api/strength/schemas.py VALID_EQUIPMENT — see
spec/strength.yaml's header for why both agree.

leveling.py's four table functions now read from the generated
app/spec/strength.py, so rerunning this would grade the implementation
against itself. **The corpus is frozen.** Extend it by hand: add a case
below, work out the expected value from spec/strength.yaml, and add it to
spec/fixtures/strength.json directly.

Usage (from repo root, needs `app` importable):
    PYTHONPATH=backend python3 spec/make_strength_fixtures.py
"""

import json
import sys
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
OUT = SPEC_DIR / "fixtures" / "strength.json"

EQUIPMENT = [
    {"value": "bodyweight", "label": "Bodyweight", "description": "No equipment needed"},
    {"value": "dumbbell", "label": "Dumbbells", "description": "Fixed or adjustable"},
    {"value": "barbell", "label": "Barbell", "description": "Olympic bar + plates"},
    {"value": "cable", "label": "Cable machine", "description": "Pulley / functional trainer"},
    {"value": "machine", "label": "Machines", "description": "Leg press, lat pulldown, etc."},
    {"value": "kettlebell", "label": "Kettlebells", "description": "One or more kettlebells"},
    {"value": "band", "label": "Bands", "description": "Resistance or pull-up bands"},
    {"value": "pullup_bar", "label": "Pull-up bar", "description": "Doorway, wall or rig bar you can hang from"},
]


def main() -> int:
    print(
        "make_strength_fixtures: refusing to run — "
        "backend/app/calculators/strength_plan/leveling.py now reads its "
        "four table functions from the generated spec, so rerunning this "
        "would grade the implementation against itself.\n"
        "  spec/fixtures/strength.json is a frozen artifact; extend it by "
        "hand, or call _regenerate() directly against a pre-spec checkout of "
        "leveling.py if you genuinely need to rebaseline."
    )
    return 1


EXPERIENCE_LEVELS = ["brand_new", "returning", "regular", "advanced"]


def _regenerate() -> int:
    sys.path.insert(0, str(ROOT / "backend"))
    from app.calculators.strength_plan.leveling import (   # noqa: E402
        experience_default_tier,
        experience_max_difficulty,
        experience_stage_floor,
        starting_weight_factor,
    )

    # Levels are hardcoded here (not imported) so this script's corpus doesn't
    # depend on whichever symbols leveling.py happens to still export — the
    # whole point of _regenerate() is to run against a checkout where that
    # module predates the spec.
    levels_to_test = list(EXPERIENCE_LEVELS) + [None, "bogus_value", ""]

    default_tier_cases = [
        {
            "experience": lvl, "endurance_goal": endurance,
            "expected": experience_default_tier(lvl, endurance),
        }
        for lvl in levels_to_test
        for endurance in (True, False)
    ]
    max_difficulty_cases = [
        {"experience": lvl, "expected": experience_max_difficulty(lvl)} for lvl in levels_to_test
    ]
    stage_floor_cases = [
        {"experience": lvl, "expected": experience_stage_floor(lvl)} for lvl in levels_to_test
    ]
    starting_weight_factor_cases = [
        {"experience": lvl, "expected": starting_weight_factor(lvl)} for lvl in levels_to_test
    ]

    fixtures = {
        "_comment": (
            "GENERATED from the original "
            "backend/app/calculators/strength_plan/leveling.py (before its "
            "four table functions read from the generated spec) via "
            "spec/make_strength_fixtures.py. Backend is authoritative for "
            "experience levels; the equipment list is transcribed from "
            "frontend/src/lib/equipment.js (the superset), cross-checked "
            "against backend's old VALID_EQUIPMENT. Shared oracle for the "
            "Python, JavaScript (spec/experience.js), and Kotlin "
            "(spec/Strength.kt) evaluators — see spec/codegen.py and "
            "spec/strength.yaml."
        ),
        "equipment": EQUIPMENT,
        "experience_levels": list(EXPERIENCE_LEVELS),
        "default_tier_cases": default_tier_cases,
        "max_difficulty_cases": max_difficulty_cases,
        "stage_floor_cases": stage_floor_cases,
        "starting_weight_factor_cases": starting_weight_factor_cases,
    }

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(fixtures, indent=2) + "\n")
    print(f"make_strength_fixtures: wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
