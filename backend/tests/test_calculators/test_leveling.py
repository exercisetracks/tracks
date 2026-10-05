# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Experience-level mapping and the conservative history-based suggestion."""

from app.calculators.strength_plan.leveling import (
    experience_default_tier,
    experience_max_difficulty,
    experience_stage_floor,
    infer_experience_suggestion,
    starting_weight_factor,
)


class TestMappings:
    def test_default_tier(self):
        assert experience_default_tier("advanced", False) == 5
        assert experience_default_tier("brand_new", True) == 1
        assert experience_default_tier(None, False) == 3

    def test_difficulty_and_floor(self):
        assert experience_max_difficulty("brand_new") == 2
        assert experience_max_difficulty("advanced") is None
        assert experience_stage_floor("advanced") == 100
        assert starting_weight_factor("brand_new") == 0.8


class TestSuggestion:
    def test_no_change_on_thin_history(self):
        assert infer_experience_suggestion("returning", {
            "sessions_12wk": 4, "e1rm_trend": "flat", "weeks_since_last": 1,
        }) is None

    def test_upgrade_on_solid_progressing_base(self):
        s = infer_experience_suggestion("returning", {
            "sessions_12wk": 16, "e1rm_trend": "rising", "weeks_since_last": 0,
        })
        assert s["suggested"] == "regular"

    def test_upgrade_at_most_one_level(self):
        s = infer_experience_suggestion("brand_new", {
            "sessions_12wk": 40, "e1rm_trend": "rising", "weeks_since_last": 0,
        })
        assert s["suggested"] == "returning"  # not straight to advanced

    def test_no_upgrade_when_e1rm_falling(self):
        assert infer_experience_suggestion("returning", {
            "sessions_12wk": 20, "e1rm_trend": "falling", "weeks_since_last": 0,
        }) is None

    def test_no_upgrade_past_advanced(self):
        assert infer_experience_suggestion("advanced", {
            "sessions_12wk": 30, "e1rm_trend": "rising", "weeks_since_last": 0,
        }) is None

    def test_downgrade_after_long_layoff(self):
        s = infer_experience_suggestion("advanced", {
            "sessions_12wk": 0, "e1rm_trend": "flat", "weeks_since_last": 10,
        })
        assert s["suggested"] == "regular"

    def test_downgrade_never_below_returning(self):
        assert infer_experience_suggestion("returning", {
            "sessions_12wk": 0, "e1rm_trend": "flat", "weeks_since_last": 20,
        }) is None

    def test_none_current_treated_as_returning(self):
        # Unknown current level shouldn't crash; behaves like "returning".
        s = infer_experience_suggestion(None, {
            "sessions_12wk": 16, "e1rm_trend": "flat", "weeks_since_last": 0,
        })
        assert s["suggested"] == "regular"
