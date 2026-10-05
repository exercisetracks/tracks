# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Python evaluator must agree with the shared golden corpus.

spec/fixtures/sport_taxonomy.json is the same file the JavaScript and Kotlin
test suites read. Its expected values were produced by the original
frontend/src/utils/sportUtils.js — the implementation that was already shipping
— so these tests answer two questions at once: did the YAML transcription
change any behaviour, and do the three hand-written evaluators still agree.

If this fails after a spec edit, the spec is what changed. If it fails after an
evaluator edit, the evaluator diverged from the other two.
"""
import json
from pathlib import Path

import pytest

from app.spec.sport_taxonomy import FALLBACK, RULES, SPORT_TYPES
from app.spec.taxonomy import normalise, sport_type

# Mounted read-only into the container — see docker-compose.yml.
FIXTURES = Path("/spec/fixtures/sport_taxonomy.json")


def _cases():
    if not FIXTURES.exists():
        pytest.skip(f"shared fixtures not mounted at {FIXTURES}")
    return json.loads(FIXTURES.read_text())["cases"]


class TestGoldenCorpus:
    def test_fixtures_are_present_and_substantial(self):
        """A corpus that silently shrank to nothing would make every test below
        pass without checking anything."""
        cases = _cases()
        assert len(cases) >= 50
        assert {c["expected"] for c in cases} == set(SPORT_TYPES), (
            "corpus no longer covers every sport type"
        )

    def test_every_case_matches(self):
        mismatches = [
            (c["sport"], c["sub_sport"], c["expected"], sport_type(c["sport"], c["sub_sport"]))
            for c in _cases()
            if sport_type(c["sport"], c["sub_sport"]) != c["expected"]
        ]
        assert not mismatches, "\n".join(
            f"({s!r}, {ss!r}): expected {exp}, got {got}" for s, ss, exp, got in mismatches
        )


class TestOrderingInvariants:
    """The rules the spec explicitly warns are order-dependent. Stated here as
    behaviour so a reordering fails with a readable name rather than as one
    line in a corpus diff."""

    def test_bouldering_beats_climbing(self):
        assert sport_type("rock_climbing", "bouldering") == "bouldering"
        assert sport_type("rock_climbing", "indoor_climbing") == "climbing"

    def test_a_yoga_sub_sport_beats_bare_training(self):
        """Garmin files a logged yoga session as training/yoga; the sub-sport is
        what it was. Treating it as strength put yoga on the strength layout."""
        assert sport_type("training", "yoga") == "mind_body"
        assert sport_type("training", "pilates") == "mind_body"
        assert sport_type("fitness_equipment", "yoga") == "mind_body"
        assert sport_type("training", "") == "strength"
        assert sport_type("training", "strength_training") == "strength"

    def test_mtb_beats_generic_cycling(self):
        assert sport_type("mountain_biking", "") == "mtb"
        assert sport_type("cycling", "road") == "cycling"

    def test_indoor_beats_mtb_and_cycling(self):
        assert sport_type("cycling", "virtual") == "indoor_cycling"
        assert sport_type("cycling", "indoor_cycling") == "indoor_cycling"

    def test_hiking_excludes_bike_and_rock(self):
        assert sport_type("hiking", "generic") == "hiking"
        assert sport_type("hiking", "mountain_biking") == "mtb"

    def test_hikings_exclusion_can_strand_a_pair_on_the_fallback(self):
        """walking/cycling is `other`, not `cycling` — and that is the original
        behaviour, not a transcription slip.

        The hiking rule's exclusion rejects it because `combined` contains
        "cycl", but no later rule claims it either: the cycling rule tests
        `sport` for cycl|bik|ride (it's "walking") and `sub_sport` for the road
        family (it's "cycling"). So it falls all the way through.

        Pinned as a test because it looks like a bug and isn't worth
        rediscovering. If this pair ever needs to classify as cycling, the fix
        is a spec change, and this test is where the reasoning lives.
        """
        assert sport_type("walking", "cycling") == "other"


class TestDegenerateInput:
    @pytest.mark.parametrize("sport,sub", [
        (None, None), ("", ""), (None, "treadmill"), ("running", None),
    ])
    def test_missing_fields_do_not_raise(self, sport, sub):
        assert sport_type(sport, sub) in SPORT_TYPES

    def test_unknown_sport_falls_back(self):
        assert sport_type("quidditch", "seeker") == FALLBACK

    def test_normalisation_is_case_and_punctuation_insensitive(self):
        assert sport_type("Rock Climbing", "Indoor Climbing") == "climbing"
        assert sport_type("ROCK-CLIMBING", "INDOOR.CLIMBING") == "climbing"
        assert normalise("Rock Climbing!") == "rock_climbing_"


class TestGeneratedTable:
    def test_every_rule_targets_a_declared_type(self):
        """A typo in a rule's `type` would produce a value no layout handles."""
        unknown = {r["type"] for r in RULES} - set(SPORT_TYPES)
        assert not unknown, f"rules produce undeclared types: {unknown}"

    def test_fallback_is_a_declared_type(self):
        assert FALLBACK in SPORT_TYPES

    def test_rules_are_well_formed(self):
        for i, rule in enumerate(RULES):
            assert rule["any"], f"rule {i} ({rule['type']}) has no match conditions"
            for entry in list(rule["any"]) + list(rule.get("none", [])):
                conds = entry["all"] if "all" in entry else [entry]
                for cond in conds:
                    assert cond["field"] in ("sport", "sub_sport", "combined")
                    assert ("equals" in cond) != ("matches" in cond), (
                        f"rule {i}: condition needs exactly one of equals/matches"
                    )
