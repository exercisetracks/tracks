# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The refactored leveling.py table functions must agree with the shared
golden corpus, and the equipment vocabulary must match the generated spec.

spec/fixtures/strength.json is the same file the JavaScript (spec/experience.js)
and Kotlin (spec/Strength.kt) suites read. The experience-level expected
values came from the ORIGINAL leveling.py (before its four table functions
were repointed at app.spec.strength), so this is the regression check that
the refactor changed no behaviour — see backend/tests/test_calculators/
test_leveling.py for the pre-existing literal-value tests that also still
pass against the refactored functions.
"""
import json
from pathlib import Path

import pytest

from app.api.strength.schemas import VALID_EQUIPMENT
from app.calculators.strength_plan.leveling import (
    experience_default_tier,
    experience_max_difficulty,
    experience_stage_floor,
    starting_weight_factor,
)
from app.spec.strength import EQUIPMENT, EXPERIENCE_LEVELS

FIXTURES = Path("/spec/fixtures/strength.json")


def _load():
    if not FIXTURES.exists():
        pytest.skip(f"shared fixtures not mounted at {FIXTURES}")
    return json.loads(FIXTURES.read_text())


class TestEquipmentParity:
    """The exact scenario spec/ exists to close: backend validation and
    frontend display provably drawing from the same 7 values."""

    def test_valid_equipment_matches_the_fixture(self):
        data = _load()
        assert VALID_EQUIPMENT == {e["value"] for e in data["equipment"]}

    def test_generated_equipment_matches_the_fixture(self):
        data = _load()
        assert [e["value"] for e in EQUIPMENT] == [e["value"] for e in data["equipment"]]
        for got, want in zip(EQUIPMENT, data["equipment"]):
            assert got == want

    def test_still_exactly_eight_values(self):
        assert len(VALID_EQUIPMENT) == 8


class TestExperienceGoldenCorpus:
    def test_fixtures_are_present_and_substantial(self):
        data = _load()
        assert len(data["default_tier_cases"]) >= 12
        assert set(data["experience_levels"]) == set(EXPERIENCE_LEVELS)

    def test_default_tier_matches(self):
        data = _load()
        mismatches = [
            (c["experience"], c["endurance_goal"], c["expected"], got)
            for c in data["default_tier_cases"]
            if (got := experience_default_tier(c["experience"], c["endurance_goal"])) != c["expected"]
        ]
        assert not mismatches

    def test_max_difficulty_matches(self):
        data = _load()
        mismatches = [
            (c["experience"], c["expected"], got)
            for c in data["max_difficulty_cases"]
            if (got := experience_max_difficulty(c["experience"])) != c["expected"]
        ]
        assert not mismatches

    def test_stage_floor_matches(self):
        data = _load()
        mismatches = [
            (c["experience"], c["expected"], got)
            for c in data["stage_floor_cases"]
            if (got := experience_stage_floor(c["experience"])) != c["expected"]
        ]
        assert not mismatches

    def test_starting_weight_factor_matches(self):
        data = _load()
        mismatches = [
            (c["experience"], c["expected"], got)
            for c in data["starting_weight_factor_cases"]
            if (got := starting_weight_factor(c["experience"])) != c["expected"]
        ]
        assert not mismatches


class TestGeneratedTableShape:
    def test_every_level_has_a_complete_row(self):
        from app.spec.strength import EXPERIENCE_TABLE

        for level in EXPERIENCE_LEVELS:
            row = EXPERIENCE_TABLE[level]
            assert row["label"]
            assert row["blurb"]
            assert "endurance" in row["default_tier"] and "strength" in row["default_tier"]
            assert isinstance(row["stage_floor"], int)
            assert isinstance(row["starting_weight_factor"], float)

    def test_unknown_fallback_tier_is_three(self):
        from app.spec.strength import UNKNOWN_FALLBACK_TIER

        assert UNKNOWN_FALLBACK_TIER == 3
        assert experience_default_tier(None, True) == 3
        assert experience_default_tier("not_a_level", False) == 3


class TestLevelOrderIsNotACopy:
    """The one-step-at-a-time suggester indexes into the level order, so the
    order is load-bearing: idx +/- 1 IS the rule. A hand-maintained copy that
    drifted from spec/strength.yaml would not raise — it would silently suggest
    the wrong level."""

    def test_level_order_comes_from_the_spec(self):
        from app.calculators.strength_plan.leveling import _LEVEL_ORDER

        assert _LEVEL_ORDER == list(EXPERIENCE_LEVELS)

    def test_order_runs_novice_to_advanced(self):
        # Pinned as behaviour rather than as a list literal: a reordered spec
        # should fail here with a readable name, not only as a corpus diff.
        assert EXPERIENCE_LEVELS.index("brand_new") < EXPERIENCE_LEVELS.index("returning")
        assert EXPERIENCE_LEVELS.index("returning") < EXPERIENCE_LEVELS.index("regular")
        assert EXPERIENCE_LEVELS.index("regular") < EXPERIENCE_LEVELS.index("advanced")

    def test_a_long_layoff_never_suggests_below_returning(self):
        from app.calculators.strength_plan.leveling import infer_experience_suggestion

        got = infer_experience_suggestion(
            "returning", {"sessions_12wk": 0, "e1rm_trend": "flat", "weeks_since_last": 52}
        )
        assert got is None or got["suggested"] != "brand_new"
