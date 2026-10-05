# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tests for the slot-based strength exercise selector and periodization.

Uses a small synthetic exercise library (no DB) so the selection contracts are
asserted in isolation: upper splits produce upper-body movements, slots stay
distinct, equipment/injury/difficulty filters apply, and picks vary across
weeks without repeating within a week.
"""

from types import SimpleNamespace

import pytest

from datetime import date, timedelta

from app.calculators.strength_plan.exercises import _select_exercises
from app.calculators.strength_plan.generator import generate_strength_workouts
from app.calculators.strength_plan.leveling import (
    experience_default_tier,
    experience_max_difficulty,
    experience_stage_floor,
    starting_weight_factor,
)
from app.calculators.strength_plan.loads import (
    conservative_starting_weight,
    is_bodyweight_only,
)
from app.calculators.strength_plan.periodization import (
    _periodization_prescription,
    is_endurance_family,
)
from app.calculators.strength_plan.slots import (
    is_unilateral,
    is_vertical_pull,
    split_slots,
)


def _ex(pattern, muscles, *, equipment=("barbell",), compound=True,
        difficulty=2, animation=True, relevance=None):
    return {
        "movement_pattern": pattern,
        "primary_muscles": list(muscles),
        "secondary_muscles": [],
        "equipment": list(equipment),
        "is_compound": compound,
        "difficulty": difficulty,
        "has_animation": animation,
        "garmin_category": pattern,
        "sport_relevance": relevance or {"strength": 3},
    }


# A compact library that still exercises every slot in the upper/lower splits.
LIBRARY = {
    # Lower
    "Back Squat":       _ex("squat", ["quads", "glutes"]),
    "Front Squat":      _ex("squat", ["quads", "glutes"]),
    "Romanian Deadlift": _ex("hinge", ["hamstrings", "glutes"]),
    "Barbell Deadlift": _ex("hinge", ["hamstrings", "glutes", "lower_back"]),
    "Bulgarian Split Squat": _ex("squat", ["quads", "glutes"]),
    "Reverse Lunge":    _ex("squat", ["quads", "glutes"]),
    "Standing Calf Raise": _ex("isolation", ["calves"], compound=False),
    "Plank":            _ex("isometric", ["core"], compound=False),
    "Clamshell":        _ex("isometric", ["hip_abductors", "glutes"], compound=False,
                            equipment=("bodyweight",)),
    # Upper — push
    "Bench Press":      _ex("push", ["chest", "triceps"]),
    "Incline Press":    _ex("push", ["chest", "front_delts"]),
    "Overhead Press":   _ex("push", ["shoulders", "triceps"]),
    "DB Shoulder Press": _ex("push", ["shoulders", "triceps"], equipment=("dumbbell",)),
    "Cable Fly":        _ex("isolation", ["chest"], compound=False, equipment=("cable",)),
    "Triceps Pushdown": _ex("isolation", ["triceps"], compound=False, equipment=("cable",)),
    "Lateral Raise":    _ex("isolation", ["shoulders"], compound=False, equipment=("dumbbell",)),
    # Upper — pull
    "Barbell Row":      _ex("pull", ["upper_back", "lats", "biceps"]),
    "Chest-Supported Row": _ex("pull", ["mid_back", "rear_delts"]),
    "Pull-Up":          _ex("pull", ["lats", "biceps"], equipment=("bodyweight",)),
    "Lat Pulldown":     _ex("pull", ["lats", "biceps"], equipment=("cable",)),
    "Face Pull":        _ex("pull", ["upper_back", "rear_delts"], compound=False, equipment=("cable",)),
    "Biceps Curl":      _ex("isolation", ["biceps"], compound=False, equipment=("dumbbell",)),
    # A technical lift that only the top tiers should see.
    "Power Clean":      _ex("hinge", ["glutes", "hamstrings"], difficulty=5),
}

FULL_EQUIP = ["barbell", "dumbbell", "cable", "bodyweight", "machine"]

_LOWER_MUSCLES = {"quads", "hamstrings", "glutes", "calves", "hip_abductors", "core"}
_UPPER_MUSCLES = {"chest", "triceps", "shoulders", "front_delts", "side_delts",
                  "upper_back", "mid_back", "lats", "rear_delts", "biceps"}


def _muscles_of(picks):
    out = set()
    for name in picks:
        out.update(LIBRARY[name]["primary_muscles"])
    return out


class TestSplitPurity:
    def test_upper_split_has_no_lower_body(self):
        picks = _select_exercises("strength", 4, "upper_a", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5)
        assert picks
        assert not (_muscles_of(picks) & (_LOWER_MUSCLES - {"core"})), picks

    def test_lower_split_has_no_upper_body(self):
        picks = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5)
        assert picks
        assert not (_muscles_of(picks) & _UPPER_MUSCLES), picks

    def test_push_day_has_no_pull_movements(self):
        picks = _select_exercises("strength", 5, "ppl_push", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5)
        patterns = {LIBRARY[p]["movement_pattern"] for p in picks}
        assert "pull" not in patterns, picks

    def test_pull_day_gets_one_vertical_and_one_horizontal(self):
        picks = _select_exercises("strength", 5, "ppl_pull", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5)
        verticals = [p for p in picks if is_vertical_pull(p)]
        horizontals = [p for p in picks
                       if LIBRARY[p]["movement_pattern"] == "pull" and not is_vertical_pull(p)]
        assert verticals, picks
        assert horizontals, picks


class TestFilters:
    def test_equipment_gates_selection(self):
        # Bodyweight-only: barbell/dumbbell/cable movements must not appear.
        picks = _select_exercises("strength", 3, "lower_a", ["bodyweight"], LIBRARY, [],
                                  max_exercises=5)
        for p in picks:
            assert "bodyweight" in LIBRARY[p]["equipment"], p

    def test_difficulty_gate_hides_technical_lift_from_low_tier(self):
        picks = _select_exercises("strength", 2, "ppl_legs", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5, week_num=3)
        assert "Power Clean" not in picks

    def test_difficulty_gate_allows_technical_lift_at_top_tier(self):
        seen = set()
        for wk in range(8):
            seen.update(_select_exercises("strength", 5, "ppl_legs", FULL_EQUIP,
                                          LIBRARY, [], max_exercises=5, week_num=wk))
        assert "Power Clean" in seen

    def test_injury_excludes_banned_pattern(self):
        knee = SimpleNamespace(body_part="knee", severity=6)  # bans squat + plyo
        picks = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [knee],
                                  max_exercises=5)
        assert all(LIBRARY[p]["movement_pattern"] != "squat" for p in picks), picks

    def test_excluded_exercise_never_selected(self):
        picks = _select_exercises("strength", 4, "upper_a", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5, excluded={"Bench Press"})
        assert "Bench Press" not in picks

    def test_preferred_exercise_bubbles_up(self):
        picks = _select_exercises("strength", 4, "upper_a", FULL_EQUIP, LIBRARY, [],
                                  max_exercises=5, preferred={"Pull-Up"})
        assert "Pull-Up" in picks


class TestVariety:
    def test_no_repeat_within_a_week(self):
        used = set()
        for split in ("upper_a", "lower_a", "upper_b"):
            picks = _select_exercises("strength", 4, split, FULL_EQUIP, LIBRARY, [],
                                      max_exercises=4, week_num=0, used_this_week=used)
            assert not (set(picks) & used), (split, picks, used)
            used.update(picks)

    def test_accessories_change_across_weeks(self):
        # lower_a = [SQUAT(main), HINGE(main), LOWER_UNI, CALF, CORE_BRACE].
        # The accessory slots should still vary week to week within a block.
        wk0 = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                                max_exercises=5, week_num=0, block_num=0)
        wk1 = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                                max_exercises=5, week_num=1, block_num=0)
        wk2 = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                                max_exercises=5, week_num=2, block_num=0)
        assert not (wk0 == wk1 == wk2), "accessory rotation should vary picks across weeks"

    def test_same_seed_is_deterministic(self):
        a = _select_exercises("strength", 4, "upper_a", FULL_EQUIP, LIBRARY, [],
                              max_exercises=4, week_num=0, regen_salt="x")
        b = _select_exercises("strength", 4, "upper_a", FULL_EQUIP, LIBRARY, [],
                              max_exercises=4, week_num=0, regen_salt="x")
        assert a == b


class TestSlotHelpers:
    @pytest.mark.parametrize("name", ["Bulgarian Split Squat", "Single Leg RDL",
                                       "Reverse Lunge", "Step Up"])
    def test_unilateral_detection(self, name):
        assert is_unilateral(name)

    @pytest.mark.parametrize("name", ["Back Squat", "Barbell Deadlift", "Bench Press"])
    def test_bilateral_detection(self, name):
        assert not is_unilateral(name)

    @pytest.mark.parametrize("name", ["Pull-Up", "Weighted Chin-Up", "Lat Pulldown"])
    def test_vertical_pull_detection(self, name):
        assert is_vertical_pull(name)

    def test_climbing_supp_upper_override(self):
        slots = split_slots("supp_upper_core", "climbing")
        keys = [s.key for s in slots]
        assert "forearm_grip" in keys  # climbers get dedicated grip work
        assert keys != [s.key for s in split_slots("supp_upper_core", "running")]


class TestStartingWeight:
    def test_bodyweight_only_detection(self):
        assert is_bodyweight_only(["bodyweight"])
        assert not is_bodyweight_only(["bodyweight", "dumbbell"])
        assert not is_bodyweight_only(["barbell"])
        # Unspecified equipment is "unknown", not bodyweight — keep pattern defaults.
        assert not is_bodyweight_only(None)
        assert not is_bodyweight_only([])

    def test_pushup_gets_no_added_load(self):
        # The reported bug: a bodyweight push-up prescribed ~9 kg (20 lb).
        assert conservative_starting_weight("push", True, ["bodyweight"]) == 0.0

    def test_loaded_movement_keeps_its_default(self):
        # A barbell press still starts at a conservative external load.
        assert conservative_starting_weight("push", True, ["barbell"]) > 0.0
        assert conservative_starting_weight("squat", True, ["barbell", "dumbbell"]) > 0.0


class TestPeriodization:
    def test_endurance_family_classification(self):
        assert is_endurance_family("running")
        assert is_endurance_family("climbing")
        assert not is_endurance_family("strength")
        assert not is_endurance_family("generic")

    def test_endurance_scheme_is_heavier_and_lower_rep_than_hypertrophy(self):
        end = _periodization_prescription("weekly_undulating", 0, endurance=True)
        strg = _periodization_prescription("weekly_undulating", 0, endurance=False)
        assert end["reps"] < strg["reps"]
        assert end["pct_1rm"] > strg["pct_1rm"]

    def test_all_stages_return_valid_prescriptions(self):
        for endurance in (True, False):
            for stage in ("linear", "weekly_undulating", "dup"):
                for wk in range(3):
                    p = _periodization_prescription(stage, wk, endurance=endurance)
                    assert 1 <= p["reps"] <= 15
                    assert 0.5 <= p["pct_1rm"] <= 0.95
                    assert 5 <= p["rpe_target"] <= 10


class TestExperienceLeveling:
    def test_default_tier_mapping(self):
        # Endurance-primary goals keep strength supplementary (1-2).
        assert experience_default_tier("brand_new", endurance_goal=True) == 1
        assert experience_default_tier("advanced", endurance_goal=True) == 2
        # Strength-primary goals scale sessions/week with experience.
        assert experience_default_tier("brand_new", endurance_goal=False) == 2
        assert experience_default_tier("regular", endurance_goal=False) == 4
        assert experience_default_tier("advanced", endurance_goal=False) == 5
        # Unknown/never-asked falls back to the neutral default tier.
        assert experience_default_tier(None, endurance_goal=False) == 3

    def test_brand_new_never_sees_technical_lifts(self):
        # Even on tier 5 (which normally unlocks difficulty-5 lifts), a brand-new
        # lifter is capped at difficulty 2, so Power Clean stays hidden.
        seen = set()
        for wk in range(8):
            seen.update(_select_exercises(
                "strength", 5, "ppl_legs", FULL_EQUIP, LIBRARY, [],
                max_exercises=5, week_num=wk,
                max_difficulty=experience_max_difficulty("brand_new"),
            ))
        assert "Power Clean" not in seen

    def test_experienced_lifter_has_no_extra_difficulty_cap(self):
        assert experience_max_difficulty("regular") is None
        assert experience_max_difficulty("advanced") is None

    def test_stage_floor_lets_experienced_skip_novice_scheme(self):
        # A regular lifter with an empty logbook should not start on linear.
        assert experience_stage_floor("regular") == 20     # -> weekly_undulating
        assert experience_stage_floor("advanced") == 100   # -> dup
        assert experience_stage_floor("brand_new") == 0
        assert experience_stage_floor(None) == 0

    def test_brand_new_gets_lighter_cold_start(self):
        assert starting_weight_factor("brand_new") == 0.8
        assert starting_weight_factor("regular") == 1.0
        assert starting_weight_factor(None) == 1.0


class TestHybridRotation:
    """Main lifts stay stable within a training block (and progress the load);
    accessories keep rotating weekly. Blocks refresh the main picks."""

    def _mains(self, picks):
        # Main-slot picks in lower_a are the BILATERAL squat/hinge compounds
        # (the unilateral ones belong to the rotating LOWER_UNI accessory slot).
        return {
            n for n in picks
            if LIBRARY.get(n, {}).get("movement_pattern") in ("squat", "hinge")
            and LIBRARY.get(n, {}).get("is_compound")
            and not is_unilateral(n)
        }

    def test_mains_stable_within_a_block(self):
        # Weeks 0,1,2 of block 0 → identical main lifts.
        picks = [
            _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                              max_exercises=5, week_num=w, block_num=0)
            for w in range(3)
        ]
        mains = [self._mains(p) for p in picks]
        assert mains[0] == mains[1] == mains[2], mains

    def test_mains_stable_across_regen_salts(self):
        # The whole point: a background regen (new salt) must NOT reshuffle the
        # main lifts mid-block.
        a = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                              max_exercises=5, week_num=1, block_num=0, regen_salt="111")
        b = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                              max_exercises=5, week_num=1, block_num=0, regen_salt="999")
        assert self._mains(a) == self._mains(b)

    def test_mains_refresh_across_blocks(self):
        # Across many blocks the main picks should not be forever identical.
        seen = set()
        for block in range(6):
            picks = _select_exercises("strength", 4, "lower_a", FULL_EQUIP, LIBRARY, [],
                                      max_exercises=5, week_num=0, block_num=block)
            seen.add(frozenset(self._mains(picks)))
        assert len(seen) > 1, "main lifts should refresh block to block"


class TestDeloadAnchoring:
    """Deload placement is anchored to the goal start, not `today`, so a
    background regeneration on a different day never shifts the deload week."""

    def _deload_dates(self, today, anchor):
        goal = SimpleNamespace(strength_tier=3, strength_days_per_week=3,
                               event_date=None)
        workouts = generate_strength_workouts(
            goal, "strength", today, [], FULL_EQUIP, LIBRARY, {}, [],
            anchor_date=anchor,
        )
        return {w["scheduled_date"] for w in workouts
                if "Deload" in w.get("title", "")}

    def test_deload_week_is_stable_under_shifted_today(self):
        anchor = date(2026, 1, 5)               # a Monday
        early = self._deload_dates(date(2026, 1, 5), anchor)
        # Regenerate two weeks later — deloads must fall on the same calendar
        # dates (those still in the future), not shift with `today`.
        later = self._deload_dates(date(2026, 1, 19), anchor)
        overlap_ok = all(d in early for d in later if d >= date(2026, 1, 19))
        assert overlap_ok, (sorted(early), sorted(later))
        # Sanity: the first block's deload lands on week index 3 from anchor.
        assert date(2026, 1, 26) in early


def test_a_race_already_run_still_gets_a_strength_plan():
    """A goal left pointing at last week's race must plan like no race at all.

    Before the fix, the 12-week fallback span was still cut off at the past
    race date, so every candidate day counted as "after the race" and the plan
    silently held no strength or mobility sessions."""
    today = date.today()
    past = SimpleNamespace(strength_tier=3, strength_days_per_week=None,
                           event_date=today - timedelta(days=3))
    none = SimpleNamespace(strength_tier=3, strength_days_per_week=None, event_date=None)
    run_already = generate_strength_workouts(past, "hiking", today, [], FULL_EQUIP, LIBRARY, {}, [])
    no_race = generate_strength_workouts(none, "hiking", today, [], FULL_EQUIP, LIBRARY, {}, [])
    assert run_already
    assert [w["scheduled_date"] for w in run_already] == [w["scheduled_date"] for w in no_race]
