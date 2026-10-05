# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Slot taxonomy: session structure as an ordered list of movement contracts.

A coach doesn't pick N random exercises — they fill a session template:
"squat pattern, then hinge, then a unilateral, then calves, then core."
Each `Slot` is one such contract (movement pattern + target muscles), and
`split_slots` returns the ordered slot list for a split key. Selection
(`exercises._select_exercises`) fills each slot from the exercise library,
ranked by the library's per-sport relevance scores.

This replaces the old hardcoded name-list pools (`_PPL_PUSH`, `_UPPER_A`, …)
which bypassed the library's 180+ exercises and — because of a tier-scale
mismatch — were dead code for every tier except 2.
"""

from __future__ import annotations

from dataclasses import dataclass

# ── Muscle-group vocabularies (exercise_library primary_muscles values) ──────
LOWER_MUSCLES = frozenset({
    "quads", "hamstrings", "glutes", "calves", "hip_flexors",
    "hip_adductors", "hip_abductors", "hip_external_rotators",
})
CHEST_MUSCLES     = frozenset({"chest"})
V_PUSH_MUSCLES    = frozenset({"shoulders", "front_delts", "side_delts"})
H_PULL_MUSCLES    = frozenset({"upper_back", "mid_back", "rear_delts"})
V_PULL_MUSCLES    = frozenset({"lats"})
CORE_MUSCLES      = frozenset({"core", "abs"})
ROTATION_MUSCLES  = frozenset({"obliques", "abs", "core"})
HIP_STAB_MUSCLES  = frozenset({"hip_abductors", "hip_external_rotators"})
CALF_MUSCLES      = frozenset({"calves"})
BICEPS_MUSCLES    = frozenset({"biceps", "brachialis"})
TRICEPS_MUSCLES   = frozenset({"triceps"})
SIDE_DELT_MUSCLES = frozenset({"side_delts", "shoulders"})
REAR_DELT_MUSCLES = frozenset({"rear_delts"})
FOREARM_MUSCLES   = frozenset({"forearms"})
TRAP_MUSCLES      = frozenset({"traps"})

# Stretches leaked into exercise_library under these Garmin categories; they
# must never be selected as strength work regardless of pattern/muscle tags.
STRETCH_CATEGORIES = frozenset({"warm_up", "pose", "move"})

# Name fragments that identify unilateral lower-body work (no DB flag exists).
_UNILATERAL_FRAGMENTS = (
    "single leg", "single-leg", "bulgarian", "split squat", "step up",
    "step-up", "lunge", "pistol", "single arm", "single-arm", "one leg",
    "one-leg", "b-stance",
)

# The push/pull movement patterns don't distinguish vertical from horizontal,
# and many rows list "lats" among their muscles — so a pull day would pick two
# rows and no chin-up. Classify vertical pulls by name so V_PULL / H_PULL slots
# stay distinct (one overhead pull + one row, the way a coach programs it).
# "pullover" is the dumbbell-only lat movement: without it a home lifter with
# no bar, band or machine would have no overhead pull to fill the slot.
_VERTICAL_PULL_FRAGMENTS = (
    "pull-up", "pull up", "pullup", "chin-up", "chin up", "chinup",
    "pulldown", "pull-down", "pull down", "lat pull", "pullover",
)


def is_unilateral(name: str) -> bool:
    n = name.lower()
    return any(f in n for f in _UNILATERAL_FRAGMENTS)


def is_vertical_pull(name: str) -> bool:
    n = name.lower()
    return any(f in n for f in _VERTICAL_PULL_FRAGMENTS)


@dataclass(frozen=True)
class Slot:
    """One exercise pick: movement pattern(s) + muscle contract.

    unilateral: True → only unilateral movements; False → exclude them.
    compound:   True → rank compounds first; False → rank isolation first
                (accessory slots want a curl, not a weighted pull-up).
    vertical_pull: True → only overhead pulls; False → only rows.
    isolation_only: True → only single-joint movements (excludes compounds
                even when they touch the target muscle).
    """
    key: str
    patterns: frozenset
    muscles: frozenset
    unilateral: bool | None = None
    compound: bool | None = None
    vertical_pull: bool | None = None
    isolation_only: bool = False
    exclude_muscles: frozenset = frozenset()   # drop if any primary muscle here
    # "main"      → primary compound lift; kept STABLE for a whole training block
    #               so the user progresses the same movement week over week.
    # "accessory" → rotated weekly for variety (default).
    role: str = "accessory"


# ── Slot definitions ─────────────────────────────────────────────────────────
SQUAT        = Slot("squat",         frozenset({"squat"}),                LOWER_MUSCLES, unilateral=False, compound=True, role="main")
HINGE        = Slot("hinge",         frozenset({"hinge"}),                LOWER_MUSCLES, unilateral=False, compound=True, role="main")
LOWER_UNI    = Slot("lower_uni",     frozenset({"squat", "hinge"}),       LOWER_MUSCLES, unilateral=True)
CALF         = Slot("calf",          frozenset({"isolation"}),            CALF_MUSCLES)
HIP_STAB     = Slot("hip_stability", frozenset({"isolation", "isometric"}), HIP_STAB_MUSCLES)
H_PUSH       = Slot("h_push",        frozenset({"push"}),                 CHEST_MUSCLES, compound=True, role="main")
V_PUSH       = Slot("v_push",        frozenset({"push"}),                 V_PUSH_MUSCLES, compound=True, exclude_muscles=CHEST_MUSCLES, role="main")
H_PULL       = Slot("h_pull",        frozenset({"pull"}),                 H_PULL_MUSCLES, compound=True, vertical_pull=False, role="main")
V_PULL       = Slot("v_pull",        frozenset({"pull"}),                 V_PULL_MUSCLES, compound=True, vertical_pull=True, role="main")
CHEST_ACC    = Slot("chest_acc",     frozenset({"push", "isolation"}),    CHEST_MUSCLES, compound=False)
BICEPS_ACC   = Slot("biceps_acc",    frozenset({"pull", "isolation"}),    BICEPS_MUSCLES, compound=False, isolation_only=True)
TRICEPS_ACC  = Slot("triceps_acc",   frozenset({"push", "isolation"}),    TRICEPS_MUSCLES, compound=False, isolation_only=True)
SIDE_DELT    = Slot("side_delt_acc", frozenset({"isolation"}),            SIDE_DELT_MUSCLES, isolation_only=True)
REAR_DELT    = Slot("rear_delt_acc", frozenset({"pull", "isolation"}),    REAR_DELT_MUSCLES, compound=False)
TRAPS_ACC    = Slot("traps_acc",     frozenset({"pull"}),                 TRAP_MUSCLES)
FOREARM_GRIP = Slot("forearm_grip",  frozenset({"pull", "isolation", "isometric", "carry"}), FOREARM_MUSCLES)
CORE_BRACE   = Slot("core_brace",    frozenset({"isometric", "isolation"}), CORE_MUSCLES)
CORE_ROT     = Slot("core_rotation", frozenset({"rotation"}),             ROTATION_MUSCLES)
LOWER_COMP   = Slot("lower_compound", frozenset({"squat", "hinge"}),      LOWER_MUSCLES, unilateral=False, compound=True, role="main")
ANY_PULL     = Slot("pull",          frozenset({"pull"}),                 H_PULL_MUSCLES | V_PULL_MUSCLES, compound=True, role="main")
PLYO         = Slot("plyo",          frozenset({"plyometric"}),           LOWER_MUSCLES)


# ── Split → ordered slots ────────────────────────────────────────────────────
# Order = priority: selection fills top-down until max_exercises is reached,
# and keeps walking the remaining slots if an earlier one has no candidate.
_SPLIT_SLOTS: dict[str, list[Slot]] = {
    "upper_a":   [H_PUSH, H_PULL, V_PUSH, V_PULL, BICEPS_ACC, TRICEPS_ACC],
    "upper_b":   [V_PUSH, V_PULL, H_PUSH, H_PULL, SIDE_DELT, REAR_DELT],
    "lower_a":   [SQUAT, HINGE, LOWER_UNI, CALF, CORE_BRACE],
    "lower_b":   [HINGE, SQUAT, LOWER_UNI, HIP_STAB, CALF, CORE_BRACE],
    "ppl_push":  [H_PUSH, V_PUSH, CHEST_ACC, TRICEPS_ACC, SIDE_DELT],
    "ppl_pull":  [V_PULL, H_PULL, REAR_DELT, BICEPS_ACC, TRAPS_ACC],
    "ppl_legs":  [SQUAT, HINGE, LOWER_UNI, CALF, CORE_BRACE],
    "full_body": [LOWER_COMP, H_PUSH, ANY_PULL, LOWER_UNI, CORE_BRACE, V_PUSH],
    "supp_lower": [HINGE, LOWER_UNI, HIP_STAB, CALF, CORE_BRACE],
    "supp_upper_core": [H_PUSH, H_PULL, CORE_BRACE, CORE_ROT, V_PULL],
}

# Sport-specific overrides where the generic upper/core emphasis is wrong.
_SUPP_UPPER_BY_SPORT: dict[str, list[Slot]] = {
    "climbing": [V_PULL, H_PUSH, FOREARM_GRIP, CORE_BRACE, H_PULL],
    "paddling": [H_PULL, CORE_ROT, H_PUSH, CORE_BRACE, V_PULL],
}

# Splits that get a plyometric finisher for running/MTB athletes.
LEG_SPLITS = frozenset({"lower_a", "lower_b", "ppl_legs", "full_body", "supp_lower"})

# Max exercise difficulty by tier: casual/supplementary athletes shouldn't be
# prescribed technical lifts (Power Clean/Snatch are difficulty 4-5).
TIER_MAX_DIFFICULTY = {1: 3, 2: 3, 3: 4, 4: 5, 5: 5}


# Registry of every defined slot, keyed by its `.key`. Lets workout archetypes
# (data/archetypes/strength/*.json) reference slots by key and resolve them back
# to the canonical Slot contract — the single source of truth for movement
# selection stays here, not in the archetype data.
SLOTS_BY_KEY: dict[str, "Slot"] = {
    s.key: s for s in list(vars().values()) if isinstance(s, Slot)
}


def split_slots(split_type: str, sport_family: str) -> list[Slot]:
    """Ordered slot list for a split, with sport-specific overrides."""
    if split_type == "supp_upper_core" and sport_family in _SUPP_UPPER_BY_SPORT:
        return _SUPP_UPPER_BY_SPORT[sport_family]
    return _SPLIT_SLOTS.get(split_type, _SPLIT_SLOTS["full_body"])
