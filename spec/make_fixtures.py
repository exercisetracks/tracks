#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build the golden fixture corpus for the sport taxonomy.

## What a fixture is for

Three languages evaluate the same rules with three hand-written loops. The rule
data can't drift — it's generated — but the loops can, and a subtly different
regex flavour or short-circuit order produces a client that classifies rides
differently with nothing failing. The fixtures are the shared oracle: one
input/output corpus that pytest, vitest, and Kotest all run.

## Where the expected values come from

The ORIGINAL implementation, frontend/src/utils/sportUtils.js, evaluated under
Node. That is deliberate. The spec was transcribed from that file, and a
transcription is exactly the kind of work that looks right and isn't — so the
expected outputs are taken from the code that was already shipping rather than
from the transcription. If the spec disagrees with it anywhere, the fixture
tests fail and the spec is wrong.

Once the original is deleted and the spec is the only implementation, rerunning
this script would make the fixtures self-referential. At that point the corpus
becomes a frozen artifact: extend it by hand, don't regenerate it.

## The corpus

Real (sport, sub_sport) pairs observed in the database, plus synthetic cases
chosen to pin the order-dependent rules — the ones a careless reordering would
break silently.

Usage: python3 spec/make_fixtures.py
"""

import json
import subprocess
from pathlib import Path

SPEC_DIR = Path(__file__).resolve().parent
ROOT = SPEC_DIR.parent
OUT = SPEC_DIR / "fixtures" / "sport_taxonomy.json"

# Pairs seen in a real database. Cheap insurance that the common path is right.
OBSERVED = [
    ("cycling", "indoor_cycling"),
    ("cycling", "mountain"),
    ("fitness_equipment", "yoga"),
    ("hiking", "generic"),
    ("rock_climbing", "bouldering"),
    ("rock_climbing", "indoor_climbing"),
    ("running", "generic"),
    ("running", "treadmill"),
    ("snowboarding", "backcountry"),
    ("snowboarding", "generic"),
    ("training", "cardio_training"),
    ("training", "strength_training"),
    ("training", "yoga"),
    ("walking", "generic"),
]

# Each of these exists to pin a specific ordering or exclusion. If a rule is
# reordered, at least one of these changes answer.
ORDERING = [
    # bouldering must beat climbing
    ("rock_climbing", ""),
    ("bouldering", ""),
    # the yoga rule must beat sport "training" (training/yoga is yoga)
    ("training", ""),
    ("yoga", ""),
    ("training", "pilates"),
    # hiking's exclusion list
    ("hiking", "mountain_biking"),
    ("walking", "cycling"),
    ("hiking", "rock_climbing"),
    # mtb must beat generic cycling
    ("mountain_biking", ""),
    ("cycling", "mountain_biking"),
    ("cycling", "downhill"),
    # indoor must beat mtb and generic cycling
    ("cycling", "virtual"),
    ("cycling", "trainer"),
    ("cycling", "indoor_cycling"),
    ("indoor_cycling", ""),
    # generic cycling family
    ("cycling", "road"),
    ("cycling", "gravel"),
    ("e_bike_fitness", ""),
    # running family
    ("running", "trail"),
    ("trail_running", ""),
    ("", "treadmill"),
    # everything else, one per branch
    ("swimming", "open_water"),
    ("rowing", "indoor_rowing"),
    ("triathlon", "transition"),
    ("alpine_skiing", ""),
    ("cross_country_skiing", ""),
    ("snowshoeing", ""),
    ("stand_up_paddleboarding", ""),
    ("kayaking", ""),
    ("elliptical", ""),
    ("", "stair_climbing"),
    ("basketball", ""),
    ("boxing", ""),
    ("golf", ""),
    ("inline_skating", ""),
    ("horseback_riding", ""),
    ("motorcycling", ""),
    # degenerate inputs — a client must not crash on these
    ("", ""),
    ("Rock Climbing", "Indoor Climbing"),   # spaces + case
    ("UNKNOWN_SPORT", "UNKNOWN_SUB"),
]

_ORACLE_JS = r"""
import "./scripts/extensionless-resolver.mjs";
const { getSportType } = await import("./src/utils/sportUtils.js");
const cases = JSON.parse(process.argv[2]);
console.log(JSON.stringify(cases.map(([sport, sub_sport]) =>
  getSportType({ sport, sub_sport })
)));
"""


def oracle(cases: list[tuple[str, str]]) -> list[str]:
    """Run the original JS implementation over the corpus."""
    script = ROOT / "frontend" / ".oracle.mjs"
    script.write_text(_ORACLE_JS)
    try:
        result = subprocess.run(
            ["node", ".oracle.mjs", json.dumps(cases)],
            cwd=ROOT / "frontend",
            capture_output=True, text=True, check=True,
        )
    finally:
        script.unlink(missing_ok=True)
    return json.loads(result.stdout)


def main() -> int:
    # The oracle is gone: sportUtils.js now delegates to the spec, so running
    # this would grade the implementation against itself and cheerfully bless
    # whatever it currently does. The corpus is a frozen artifact from here on.
    #
    # To ADD cases: append them to ORDERING, work out the expected value by
    # hand, and add it to the JSON directly. To REBASELINE against the original
    # (you almost certainly don't want to): check out sportUtils.js from before
    # commit `spec: single source of truth for the sport taxonomy` and delete
    # this guard.
    print(
        "make_fixtures: refusing to run — the original implementation this "
        "corpus was baselined against no longer exists.\n"
        "  spec/fixtures/sport_taxonomy.json is now a frozen artifact; extend "
        "it by hand.\n"
        "  See the note in main() if you genuinely need to rebaseline."
    )
    return 1


def _regenerate() -> int:
    cases = OBSERVED + ORDERING
    expected = oracle(cases)

    fixtures = [
        {"sport": sport, "sub_sport": sub_sport, "expected": got}
        for (sport, sub_sport), got in zip(cases, expected)
    ]

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "_comment": (
            "GENERATED from the original frontend/src/utils/sportUtils.js by "
            "spec/make_fixtures.py. Shared oracle for the Python, JavaScript, "
            "and Kotlin evaluators — see spec/codegen.py."
        ),
        "cases": fixtures,
    }, indent=2) + "\n")

    by_type: dict[str, int] = {}
    for f in fixtures:
        by_type[f["expected"]] = by_type.get(f["expected"], 0) + 1
    print(f"make_fixtures: {len(fixtures)} cases -> {OUT.relative_to(ROOT)}")
    print("  coverage:", ", ".join(f"{k}={v}" for k, v in sorted(by_type.items())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
