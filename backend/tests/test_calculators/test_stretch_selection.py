# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tests for the shared stretch/mobility flow selector.

Pure — builds synthetic StretchCandidate pools and asserts the selection
contracts: target-muscle coverage, balance-pose exclusion, cross-plan variety,
and the sport → muscle resolution (including the calf-coverage and hiking
substring bugs the rewrite fixed)."""

import pytest

from app.calculators.flexibility import (
    POSITION_ORDER,
    StretchCandidate,
    order_flow_by_position,
    select_stretch_flow,
    sport_target_muscles,
    strength_target_muscles,
    to_step,
)
from app.calculators.strength_plan.flow_archetypes import (
    FLOW_ARCHETYPES,
    select_flow_archetype,
)


def _cand(name, muscles, pattern="static_stretch", difficulty=1, preferred=False,
          equipment=("bodyweight",), position=None):
    return StretchCandidate(
        name=name,
        primary_muscles=tuple(muscles),
        movement_pattern=pattern,
        difficulty=difficulty,
        duration_per_side_sec=60,
        sets=1,
        each_side=True,
        description="",
        garmin_category="pose",
        garmin_subtype=1,
        preferred=preferred,
        equipment=tuple(equipment),
        position=position,
    )


POOL = [
    _cand("Hip Flexor Stretch", ["hip_flexors"]),
    _cand("Couch Stretch", ["quads", "hip_flexors"]),
    _cand("Low Lunge", ["hip_flexors"], pattern="yoga_pose"),
    _cand("Standing Calf Stretch", ["calves"]),
    _cand("Downward Dog", ["calves", "hamstrings"], pattern="yoga_pose"),
    _cand("Hamstring Stretch", ["hamstrings"]),
    _cand("Seated Forward Fold", ["hamstrings"], pattern="yoga_pose", difficulty=2),
    _cand("Pigeon Pose", ["glutes"], pattern="yoga_pose"),
    _cand("Figure Four", ["glutes"]),
    _cand("Child's Pose", ["lower_back", "lats"], pattern="yoga_pose"),
    _cand("Cat-Cow", ["lower_back", "upper_back"], pattern="dynamic_stretch"),
    _cand("Thread the Needle", ["upper_back"], pattern="static_stretch"),
    _cand("Doorway Chest", ["chest"]),
    # A balance pose that must never appear in a cool-down flow.
    _cand("Tree Pose", ["glutes", "calves"], pattern="balance_pose"),
    _cand("Warrior III", ["hamstrings"], pattern="balance_pose"),
]


class TestCoverage:
    def test_covers_distinct_target_muscles(self):
        targets = ["hip_flexors", "calves", "hamstrings", "glutes", "lower_back"]
        flow = select_stretch_flow(POOL, targets, count=5, seed="s")
        covered = set()
        for c in flow:
            covered.update(c.primary_muscles)
        for muscle in targets:
            assert muscle in covered, f"{muscle} not covered by {[c.name for c in flow]}"

    def test_no_duplicate_stretches(self):
        flow = select_stretch_flow(POOL, ["hamstrings"] * 5, count=5, seed="s")
        names = [c.name for c in flow]
        assert len(names) == len(set(names))

    def test_respects_count(self):
        flow = select_stretch_flow(POOL, ["hip_flexors", "calves", "hamstrings"],
                                   count=2, seed="s")
        assert len(flow) <= 2


class TestPatternFiltering:
    def test_balance_poses_excluded(self):
        flow = select_stretch_flow(POOL, ["glutes", "hamstrings", "calves"],
                                   count=5, seed="s")
        assert all(c.movement_pattern != "balance_pose" for c in flow)

    def test_preferred_balance_pose_still_allowed(self):
        pref_pool = POOL + [_cand("Tree Pose Pref", ["glutes"],
                                  pattern="balance_pose", preferred=True)]
        flow = select_stretch_flow(pref_pool, ["glutes"], count=1, seed="s")
        assert flow and flow[0].name == "Tree Pose Pref"

    def test_static_preferred_over_yoga_for_recovery(self):
        # hamstrings has a static + a yoga option; static should rank first.
        flow = select_stretch_flow(POOL, ["hamstrings"], count=1, seed="fixed")
        assert flow[0].movement_pattern in ("static_stretch", "pnf_stretch")


class TestEquipmentFree:
    # A muscle whose only options need a carried prop, plus a bodyweight
    # alternative, so we can assert the filter picks the prop-free one.
    EQUIP_POOL = [
        _cand("Lying Hamstring Stretch with Strap", ["hamstrings"], equipment=("strap",)),
        _cand("Standing Hamstring Stretch", ["hamstrings"], equipment=("bodyweight",)),
        _cand("Foam Roll Quads", ["quads"], equipment=("foam_roller",)),
        _cand("Wall Calf Stretch", ["calves"], equipment=("wall",)),
    ]

    def test_equipment_free_drops_prop_stretches(self):
        flow = select_stretch_flow(self.EQUIP_POOL, ["hamstrings", "quads", "calves"],
                                   count=5, seed="s", equipment_free=True)
        names = [c.name for c in flow]
        assert "Lying Hamstring Stretch with Strap" not in names
        assert "Foam Roll Quads" not in names
        # Bodyweight + environmental (wall) stretches survive.
        assert "Standing Hamstring Stretch" in names
        assert "Wall Calf Stretch" in names

    def test_default_flow_keeps_prop_stretches(self):
        # The weekly at-home mobility session (equipment_free=False) may use gear.
        flow = select_stretch_flow(self.EQUIP_POOL, ["quads"], count=5, seed="s")
        assert any(c.name == "Foam Roll Quads" for c in flow)

    def test_preferred_prop_stretch_bypasses_filter(self):
        pool = [_cand("Strap Hamstring", ["hamstrings"], equipment=("strap",),
                      preferred=True)]
        flow = select_stretch_flow(pool, ["hamstrings"], count=1, seed="s",
                                   equipment_free=True)
        assert flow and flow[0].name == "Strap Hamstring"


class TestVariety:
    def test_flow_varies_with_seed(self):
        a = select_stretch_flow(POOL, ["hamstrings", "glutes", "hip_flexors"],
                                count=3, seed="week1")
        b = select_stretch_flow(POOL, ["hamstrings", "glutes", "hip_flexors"],
                                count=3, seed="week9")
        assert [c.name for c in a] != [c.name for c in b] or len(POOL) < 4

    def test_same_seed_deterministic(self):
        a = select_stretch_flow(POOL, ["hamstrings", "glutes"], count=2, seed="x")
        b = select_stretch_flow(POOL, ["hamstrings", "glutes"], count=2, seed="x")
        assert [c.name for c in a] == [c.name for c in b]


class TestTargetResolution:
    def test_running_targets_include_calves(self):
        assert "calves" in sport_target_muscles("trail_running")

    def test_hiking_substring_matches(self):
        # "hiking" must resolve to the hike template (the old "hike" key missed it).
        assert sport_target_muscles("hiking") == sport_target_muscles("hike")
        assert "calves" in sport_target_muscles("hiking")

    def test_unknown_sport_uses_default(self):
        assert sport_target_muscles("underwater_basket_weaving")

    def test_strength_targets_prioritise_and_add_staples(self):
        targets = strength_target_muscles(["chest", "triceps"])
        assert "chest" in targets
        # postural staples are always appended
        for staple in ("hip_flexors", "lower_back", "upper_back"):
            assert staple in targets


class TestToStep:
    def test_step_shape(self):
        step = to_step(_cand("Hip Flexor Stretch", ["hip_flexors"]))
        assert step["type"] == "mobility_exercise"
        assert step["name"] == "Hip Flexor Stretch"
        assert step["muscles"] == ["hip_flexors"]
        assert step["duration_seconds"] == 60


class TestPositionSequencing:
    def _flow(self):
        return [
            _cand("Supine Twist", ["glutes"], position="supine"),
            _cand("Standing Calf", ["calves"], position="standing"),
            _cand("Seated Fold", ["hamstrings"], position="seated"),
            _cand("Low Lunge", ["hip_flexors"], position="kneeling"),
        ]

    def test_orders_standing_to_floor(self):
        ordered = order_flow_by_position(self._flow())
        positions = [c.position for c in ordered]
        ranks = [POSITION_ORDER[p] for p in positions]
        assert ranks == sorted(ranks), positions
        assert positions[0] == "standing"

    def test_never_standing_after_supine(self):
        ordered = order_flow_by_position(self._flow())
        seen_floor = False
        for c in ordered:
            if POSITION_ORDER[c.position] >= 3:
                seen_floor = True
            elif seen_floor:
                assert False, "standing/kneeling appeared after a floor pose"

    def test_closer_pulled_to_end(self):
        ordered = order_flow_by_position(self._flow(), closer_muscles=["hip_flexors"])
        assert ordered[-1].name == "Low Lunge"

    def test_null_position_sits_in_middle(self):
        flow = [
            _cand("Unknown", ["core"], position=None),
            _cand("Standing", ["calves"], position="standing"),
            _cand("Supine", ["glutes"], position="supine"),
        ]
        names = [c.name for c in order_flow_by_position(flow)]
        assert names == ["Standing", "Unknown", "Supine"]

    def test_select_flow_applies_ordering(self):
        pool = [
            _cand("Supine Twist", ["glutes"], position="supine"),
            _cand("Standing Calf", ["calves"], position="standing"),
            _cand("Low Lunge", ["hip_flexors"], position="kneeling"),
        ]
        flow = select_stretch_flow(
            pool, ["calves", "hip_flexors", "glutes"],
            count=3, seed="s", order_by_position=True,
        )
        ranks = [POSITION_ORDER[c.position] for c in flow]
        assert ranks == sorted(ranks)


class TestFlowArchetypes:
    def test_all_load(self):
        assert len(FLOW_ARCHETYPES) >= 5
        for a in FLOW_ARCHETYPES:
            assert a.key and a.name

    def test_theme_match_preferred(self):
        a = select_flow_archetype("post_workout", "running", "posterior", 0)
        assert a is not None
        assert "posterior" in a.themes or "hips" in a.themes

    def test_context_filtered(self):
        # Every returned archetype must support the requested context.
        for vk in range(5):
            a = select_flow_archetype("weekly_mobility", "cycling", None, vk)
            if a:
                assert "weekly_mobility" in a.contexts



def test_muscles_outside_the_priority_list_order_by_name():
    """Ties must not follow set iteration order.

    Muscles the priority list does not name all rank equal; without a name
    tiebreak their order followed Python's per-process string hashing, so the
    same log produced a different flow from one server start to the next."""
    out = strength_target_muscles(["triceps", "neck", "biceps", "forearms"])
    tied = [m for m in out if m in {"triceps", "neck", "biceps", "forearms"}]
    assert tied == sorted(tied)
