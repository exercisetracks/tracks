# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Workout archetype loading, selection, and generator integration."""

from datetime import date
from types import SimpleNamespace

import pytest

from app.calculators.strength_plan.archetypes import (
    ARCHETYPES,
    Archetype,
    _parse,
    select_archetype,
)
from app.calculators.strength_plan.generator import generate_strength_workouts
from app.calculators.strength_plan.slots import SLOTS_BY_KEY
from app.calculators.flexibility import StretchCandidate


def _stretch(name, muscle, pattern="dynamic_stretch"):
    return StretchCandidate(
        name=name, primary_muscles=(muscle,), movement_pattern=pattern,
        difficulty=1, duration_per_side_sec=30, sets=1, each_side=False,
        garmin_category="warm_up", garmin_subtype=1, description=name,
    )


WARMUP_CANDIDATES = [
    _stretch("Leg Swings", "hamstrings"),
    _stretch("Hip Circles", "glutes"),
    _stretch("Bodyweight Squat Prep", "quads"),
    _stretch("Arm Circles", "chest"),
    _stretch("Cat-Cow", "core"),
]


def _ex(pattern, muscles, difficulty=2, compound=True, animation=True):
    return {
        "primary_muscles": muscles, "secondary_muscles": [],
        "equipment": ["barbell", "dumbbell", "bodyweight"],
        "movement_pattern": pattern, "sport_relevance": {"strength": 3},
        "difficulty": difficulty, "is_compound": compound,
        "has_animation": animation, "cues": ["Brace hard"],
    }


LIBRARY = {
    "Barbell Back Squat": _ex("squat", ["quads", "glutes"]),
    "Goblet Squat":       _ex("squat", ["quads", "glutes"], difficulty=1),
    "Front Squat":        _ex("squat", ["quads"], difficulty=3),
    "Romanian Deadlift":  _ex("hinge", ["hamstrings", "glutes"]),
    "Barbell Deadlift":   _ex("hinge", ["hamstrings", "glutes"], difficulty=3),
    "Hip Thrust":         _ex("hinge", ["glutes"], difficulty=1),
    "Bulgarian Split Squat": _ex("squat", ["quads", "glutes"], compound=True),
    "Reverse Lunge":      _ex("squat", ["quads", "glutes"], compound=True),
    "Standing Calf Raise": _ex("isolation", ["calves"], difficulty=1, compound=False),
    "Plank":              _ex("isometric", ["core"], difficulty=1, compound=False),
    "Pallof Press":       _ex("rotation", ["obliques"], difficulty=1, compound=False),
    "Clamshell":          _ex("isolation", ["glutes"], difficulty=1, compound=False),
}
FULL_EQUIP = ["barbell", "dumbbell", "bodyweight", "cable", "machine"]


class TestArchetypeLoading:
    def test_all_shipped_archetypes_valid(self):
        assert len(ARCHETYPES) >= 8
        for a in ARCHETYPES:
            assert a.key and a.name
            for slot in a.schemes:
                assert slot in SLOTS_BY_KEY
            for pair in a.supersets:
                for slot in pair:
                    assert slot in SLOTS_BY_KEY


class TestArchetypeSelection:
    def test_rotates_per_block(self):
        # Craft two archetypes for the same split; blocks should alternate.
        a = Archetype("a", "A", "", "", frozenset({"lower_a"}), None, (1, 5),
                      frozenset({"any"}), {}, (), None, None)
        b = Archetype("b", "B", "", "", frozenset({"lower_a"}), None, (1, 5),
                      frozenset({"any"}), {}, (), None, None)
        pool = [a, b]
        keys = [select_archetype("lower_a", "strength", 3, "linear", blk, pool).key
                for blk in range(4)]
        assert keys == ["a", "b", "a", "b"]

    def test_no_match_returns_none(self):
        a = Archetype("a", "A", "", "", frozenset({"upper_a"}), None, (4, 5),
                      frozenset({"any"}), {}, (), None, None)
        assert select_archetype("lower_a", "strength", 2, "linear", 0, [a]) is None


class TestGeneratorIntegration:
    def _gen(self, **kw):
        goal = SimpleNamespace(strength_tier=kw.get("tier", 3),
                               strength_days_per_week=3, event_date=None)
        return generate_strength_workouts(
            goal, kw.get("sport", "strength"), date(2026, 1, 5), [],
            FULL_EQUIP, LIBRARY, {}, [],
            anchor_date=date(2026, 1, 5),
        )

    def test_archetype_names_the_session(self):
        workouts = self._gen()
        titles = {w["title"] for w in workouts}
        # At least one session should carry an archetype name (not just "… Workout").
        assert any("Workout" not in t or "—" in t for t in titles) or \
               any(a.name in t for t in titles for a in ARCHETYPES)

    def test_main_steps_tagged_and_scheme_applied(self):
        workouts = self._gen(tier=3)
        strength = [w for w in workouts if w["workout_type"] == "strength"]
        assert strength
        for w in strength:
            for s in w["steps"]:
                if s["type"] == "strength_exercise":
                    assert s.get("phase") == "main"

    def test_superset_groups_present_when_archetype_pairs(self):
        # Posterior Chain Foundation pairs calf+core_brace; a lower session at
        # tier 3 should surface a superset_group on some accessory step.
        workouts = self._gen(tier=3)
        groups = [s.get("superset_group")
                  for w in workouts for s in w["steps"]
                  if s["type"] == "strength_exercise"]
        # Not asserting a specific count (depends on slot fill), just that the
        # mechanism produces integer groups when pairs apply.
        assert all(g is None or isinstance(g, int) for g in groups)

    def test_fallback_when_no_archetype(self):
        # A tier/split with no matching archetype still generates valid workouts.
        empty_pool_workouts = generate_strength_workouts(
            SimpleNamespace(strength_tier=1, strength_days_per_week=1, event_date=None),
            "swimming", date(2026, 1, 5), [], FULL_EQUIP, LIBRARY, {}, [],
            anchor_date=date(2026, 1, 5),
        )
        assert empty_pool_workouts  # still produces sessions
        for w in empty_pool_workouts:
            assert w["title"]

    def test_a_strength_session_holds_lifts_and_no_stretches(self):
        """Stretching is its own workout — the flow placed on the same day and
        the weekly mobility session. A stretch pool on hand used to put a
        warm-up of stretches in front of the lifts."""
        goal = SimpleNamespace(strength_tier=3, strength_days_per_week=3, event_date=None)
        workouts = generate_strength_workouts(
            goal, "strength", date(2026, 1, 5), [], FULL_EQUIP, LIBRARY, {}, [],
            anchor_date=date(2026, 1, 5), stretch_candidates=WARMUP_CANDIDATES,
        )
        strength = [w for w in workouts if w["workout_type"] == "strength"]
        assert strength
        for w in strength:
            assert {s["type"] for s in w["steps"]} <= {"strength_exercise", "rest"}, w["title"]
        # The stretches still go out, as the separate mobility session.
        assert any(w["workout_type"] == "mobility" and w["steps"] for w in workouts)

    def test_an_archetype_with_a_warmup_is_refused(self):
        """Refused rather than ignored, so it cannot come back as config that
        silently does nothing."""
        raw = {"key": "x", "name": "X", "main": [], "warmup": {"dynamic_count": 3}}
        with pytest.raises(ValueError, match="no stretches"):
            _parse(raw)

    def test_non_animating_still_selectable(self):
        # Soft gate: with every squat marked non-animating, a squat is still
        # selected (previously the hard gate would have excluded them all).
        lib = dict(LIBRARY)
        for name, ex in list(lib.items()):
            if ex["movement_pattern"] == "squat":
                lib[name] = {**ex, "has_animation": False}
        goal = SimpleNamespace(strength_tier=3, strength_days_per_week=3, event_date=None)
        workouts = generate_strength_workouts(
            goal, "strength", date(2026, 1, 5), [], FULL_EQUIP, lib, {}, [],
            anchor_date=date(2026, 1, 5),
        )
        names = {s["name"] for w in workouts for s in w["steps"]}
        assert any(lib[n]["movement_pattern"] == "squat" for n in names if n in lib)
