# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Stretch / mobility flow selection — the shared engine behind post-workout
stretch flows and the weekly standalone mobility session.

A coach picks a cool-down by asking "what did this session load, and what does
this athlete need to keep loose?" then choosing one good hold per area — static
holds and PNF for recovery, not balance poses. This module does the same:

  * `sport_target_muscles` / `strength_target_muscles` resolve which muscle
    groups a completed activity actually loaded (matching the stretch library's
    muscle vocabulary — the old code used names like "calves_back" that no row
    carried, so runners never got a calf stretch).
  * `select_stretch_flow` fills one stretch per target muscle from the eligible
    candidate pool, preferring recovery-appropriate movement patterns, spreading
    across difficulty instead of always taking the single easiest option, and
    rotating the pick week to week so a 16-week plan doesn't repeat the same
    four stretches every single day.

Callers (api/flexibility post-activity flows, and the weekly mobility injector)
build the eligible `StretchCandidate` list — applying the per-device animation
gate and the user's preferences/exclusions — then hand it here for selection.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from random import Random

# ── Sport → muscle groups loaded (stretch_library vocabulary) ────────────────
# Matched by substring against the activity's sport, first hit wins. Order is
# priority: earlier muscles are more important to stretch and get filled first.
SPORT_STRETCH_MUSCLES: dict[str, list[str]] = {
    "run":     ["hip_flexors", "calves", "hamstrings", "glutes", "quads", "lower_back"],
    "cycling": ["hip_flexors", "quads", "glutes", "lower_back", "upper_back", "chest"],
    "biking":  ["hip_flexors", "quads", "glutes", "lower_back", "upper_back", "chest"],
    "bike":    ["hip_flexors", "quads", "glutes", "lower_back", "upper_back", "chest"],
    "swim":    ["shoulders", "chest", "upper_back", "lats", "neck", "hip_flexors"],
    "climb":   ["forearms", "lats", "upper_back", "chest", "shoulders", "hip_flexors"],
    "boulder": ["forearms", "lats", "upper_back", "chest", "shoulders", "hip_flexors"],
    "paddl":   ["upper_back", "lats", "shoulders", "obliques", "forearms", "lower_back"],
    "kayak":   ["upper_back", "lats", "shoulders", "obliques", "forearms", "lower_back"],
    "canoe":   ["upper_back", "lats", "shoulders", "obliques", "forearms", "lower_back"],
    "row":     ["hamstrings", "glutes", "lower_back", "upper_back", "lats", "forearms"],
    # "hik" (not "hike") so it substring-matches "hiking" too.
    "hik":     ["calves", "quads", "hamstrings", "hip_flexors", "glutes", "feet"],
    "walk":    ["calves", "hip_flexors", "hamstrings", "lower_back"],
    "strength": ["hip_flexors", "hamstrings", "glutes", "chest", "shoulders",
                 "upper_back", "quads", "lower_back"],
}

DEFAULT_STRETCH_MUSCLES = [
    "hip_flexors", "hamstrings", "glutes", "lower_back", "upper_back", "quads",
]

# A weekly restorative mobility session works the whole body, spine-forward.
FULL_BODY_MOBILITY_MUSCLES = [
    "hip_flexors", "hamstrings", "glutes", "lower_back", "thoracic_spine",
    "upper_back", "quads", "chest", "calves", "shoulders",
]

# Movement-pattern suitability for a cool-down / recovery flow. Static holds and
# PNF develop ROM best after training; balance poses aren't stretches at all and
# are excluded. Lower rank = preferred.
POSTWORKOUT_PATTERN_RANK: dict[str, int] = {
    "static_stretch":     0,
    "pnf_stretch":        1,
    "yoga_pose":          2,
    "myofascial_release": 3,
    "dynamic_stretch":    4,
}
EXCLUDE_POSTWORKOUT_PATTERNS = frozenset({"balance_pose"})

# Natural ordering of a stretch flow: work down toward the floor so the session
# winds down (standing drills first, floor-based calming stretches last). A
# stretch with no position sits in the middle so it never jumps the arc.
POSITION_ORDER = {
    "standing": 0,
    "kneeling": 1,
    "seated": 2,
    "supine": 3,
    "prone": 3,
}
_POSITION_MIDDLE = 2  # NULL/unknown position slots between seated and floor

# Equipment a stretch requires you to *carry*. These disqualify a stretch from
# an equipment-free flow (post-workout cool-downs, done wherever training ended
# — a trailhead, a park). Bodyweight needs nothing; "mat" (grass/floor) and
# "wall" (a tree, fence, or bench) are satisfiable anywhere, so they stay in.
PORTABLE_STRETCH_EQUIPMENT = frozenset({"strap", "foam_roller", "block", "band"})

# How many top-ranked candidates per muscle to rotate the pick among.
_ROTATION_DEPTH = 5


def is_equipment_free(equipment) -> bool:
    """True when a stretch needs no gear the user has to bring along."""
    return not (set(equipment or ()) & PORTABLE_STRETCH_EQUIPMENT)


@dataclass(frozen=True)
class StretchCandidate:
    """A library or custom stretch eligible for auto-selection. Callers build
    these after applying the animation gate + user preferences/exclusions."""
    name: str
    primary_muscles: tuple[str, ...]
    movement_pattern: str
    difficulty: int
    duration_per_side_sec: int
    sets: int
    each_side: bool
    description: str
    garmin_category: str | None
    garmin_subtype: int | None
    is_custom: bool = False
    preferred: bool = False
    secondary_muscles: tuple[str, ...] = field(default_factory=tuple)
    equipment: tuple[str, ...] = ("bodyweight",)
    # Coaching content for the guided flow player (Phase 2).
    cues: tuple[str, ...] = field(default_factory=tuple)
    breath_cue: str | None = None
    position: str | None = None
    # How to get into and hold the pose: shown large on the phone during the hold.
    instructions: str | None = None


def sport_target_muscles(sport: str, fallback: list[str] | None = None) -> list[str]:
    """Muscle groups a sport loads, or a passed-in fallback, or a generic set."""
    s = (sport or "").lower()
    for pattern, muscles in SPORT_STRETCH_MUSCLES.items():
        if pattern in s:
            return list(muscles)
    if fallback:
        return list(fallback)
    return list(DEFAULT_STRETCH_MUSCLES)


def strength_target_muscles(trained_muscles: list[str]) -> list[str]:
    """Order a strength session's trained muscles into stretch priority, keeping
    only groups the stretch library can actually target and de-duplicating."""
    priority = SPORT_STRETCH_MUSCLES["strength"]
    ranked = sorted(
        set(trained_muscles),
        # Name breaks ties. Every muscle outside the priority list ties on the
        # first key, and a set's iteration order follows Python's per-process
        # string hashing, so without it the order changed between runs.
        key=lambda m: (priority.index(m) if m in priority else len(priority), m),
    )
    # Ensure the postural staples are present even if the log was sparse.
    for staple in ("hip_flexors", "lower_back", "upper_back"):
        if staple not in ranked:
            ranked.append(staple)
    return ranked


def _rank_key(c: StretchCandidate, pattern_rank: dict[str, int]) -> tuple:
    return (
        0 if c.preferred else 1,
        0 if c.is_custom else 1,
        pattern_rank.get(c.movement_pattern, 5),
        c.difficulty,
        c.name,
    )


def select_stretch_flow(
    candidates: list[StretchCandidate],
    target_muscles: list[str],
    *,
    count: int,
    seed: str,
    pattern_rank: dict[str, int] | None = None,
    exclude_patterns: frozenset[str] = EXCLUDE_POSTWORKOUT_PATTERNS,
    equipment_free: bool = False,
    min_count: int = 3,
    order_by_position: bool = False,
    closer_muscles: list[str] | None = None,
) -> list[StretchCandidate]:
    """
    Choose up to `count` stretches covering `target_muscles`, one per muscle in
    priority order, then a second pass over any remaining muscles if we're still
    under `min_count`.

    Within a muscle, candidates are ranked (preferred → custom → recovery-
    appropriate pattern → easier) and the pick is rotated among the top few via
    a per-(muscle, seed) shuffle, so the flow refreshes across a plan's weeks
    without ever dropping to an inappropriate movement. `seed` should include a
    per-week or per-regeneration component for that rotation to take effect.

    equipment_free: drop stretches that need a carried prop (strap, foam roller,
    block, band) so the flow works anywhere — used for post-activity cool-downs
    the athlete may do at a trailhead or park. A user's explicitly *preferred*
    stretch still bypasses the filter (same as the balance-pose exclusion).
    """
    pattern_rank = pattern_rank or POSTWORKOUT_PATTERN_RANK

    by_muscle: dict[str, list[StretchCandidate]] = defaultdict(list)
    for c in candidates:
        if c.movement_pattern in exclude_patterns and not c.preferred:
            continue
        if equipment_free and not c.preferred and not is_equipment_free(c.equipment):
            continue
        for m in c.primary_muscles:
            by_muscle[m].append(c)

    def _ordered(muscle: str) -> list[StretchCandidate]:
        pool = sorted(by_muscle.get(muscle, []), key=lambda c: _rank_key(c, pattern_rank))
        # Preferred stretches stay pinned to the front; rotate the pick among the
        # top near-equivalent others, keeping the rest as ordered fallbacks for
        # when the leaders are already used elsewhere in the flow.
        pref = [c for c in pool if c.preferred]
        others = [c for c in pool if not c.preferred]
        top = others[:_ROTATION_DEPTH]
        rest = others[_ROTATION_DEPTH:]
        Random(f"{seed}-{muscle}").shuffle(top)
        return pref + top + rest

    selected: list[StretchCandidate] = []
    seen: set[str] = set()

    def _fill(muscles: list[str], limit: int) -> None:
        for muscle in muscles:
            if len(selected) >= limit:
                break
            for c in _ordered(muscle):
                if c.name not in seen:
                    selected.append(c)
                    seen.add(c.name)
                    break

    _fill(target_muscles, count)

    # If sparse coverage left us short, take a second stretch from the highest
    # priority muscles that have another option available — but never exceed
    # `count`, which is always the hard upper bound.
    if len(selected) < min(min_count, count):
        _fill([m for m in target_muscles for _ in range(2)], count)

    if order_by_position:
        selected = order_flow_by_position(selected, closer_muscles)

    return selected


def order_flow_by_position(
    selected: list[StretchCandidate],
    closer_muscles: list[str] | None = None,
) -> list[StretchCandidate]:
    """Reorder a stretch flow into a calming arc: standing → kneeling → seated →
    floor, so the session winds down toward the ground. If `closer_muscles` is
    given, a matching floor-based stretch is pulled to the very end as the
    calming closer. Stable within a position group (preserves selection order)."""
    if not selected:
        return selected

    closer = None
    if closer_muscles:
        wanted = set(closer_muscles)
        # Prefer a supine/prone stretch hitting a closer muscle; else any hit.
        floor = [c for c in selected
                 if POSITION_ORDER.get(c.position, _POSITION_MIDDLE) >= 3
                 and wanted & set(c.primary_muscles)]
        pick = floor or [c for c in selected if wanted & set(c.primary_muscles)]
        if pick:
            closer = pick[-1]

    body = [c for c in selected if c is not closer]
    body.sort(key=lambda c: POSITION_ORDER.get(c.position, _POSITION_MIDDLE))
    return body + ([closer] if closer else [])


# ── Hold times ───────────────────────────────────────────────────────────────
#
# How long each stretch is held at the moderate level, from what kind of
# stretch it is. The library's own times were never tuned (nearly every yoga
# pose said 60 s), so every stretch played the same length. The evidence:
#
# - Static stretching: ROM gains grow with time under stretch, with ~60 s per
#   muscle per session the dose behind most of the benefit (Behm et al. 2016,
#   Appl Physiol Nutr Metab; ACSM: 30-60 s for older adults). Large, stiff
#   lower-body muscles (hamstrings, hip flexors, adductors, glutes) need the
#   longer end; passive floor positions can be held longer than standing ones,
#   since nothing else has to work.
# - Small structures (neck, wrists, feet) respond to short holds and dislike
#   long ones: 30-40 s.
# - Poses that are mostly strength or balance (chair, planks, warrior III,
#   locust) are limited by fatigue, not tissue: 30 s.
# - Dynamic stretches and flows are repetitions, not holds: 45 s of them.
# - Restorative poses (child's pose, legs up the wall, savasana) are held for
#   minutes in restorative and yin practice; 2 min here, savasana 3.
# - Myofascial release: 60-120 s per area (Cheatham et al. 2015).
#
# The intensity levels scale these (QuickSession on the phone), so "moderate"
# averages about 60 s across a flow. Ported to the phone in Flexibility.kt
# (holdSeconds); the plan-injection fixtures keep the two identical.

_SMALL = {"neck", "forearms", "hands", "feet"}
_BIG_LOWER = {"hamstrings", "hip_flexors", "hip_adductors", "glutes", "hip_external_rotators",
              "quads", "groin", "calves"}
_RESTORATIVE = ("corpse", "savasana", "legs up the wall", "supported", "child's pose",
                "knees to chest", "thunderbolt", "hero pose", "bound angle", "butterfly")
_EFFORTFUL = ("chair", "plank", "four-limbed", "warrior iii", "warrior three", "locust", "bow pose",
              "dolphin", "half moon", "standing split", "bird dog", "boat", "eagle", "half chair",
              "upward facing dog", "up dog")
_CENTERING = ("mountain pose", "arms up", "prayer mudra", "throat lock", "all fours")
_INVERSION = ("shoulder stand", "plow")
_FLOOR = {"seated", "supine", "prone", "kneeling"}
_FLOOR_NAMES = ("pigeon", "seated", "lying", "supine", "fold", "forward bend", "straddle",
                "head to knee", "split", "cradle", "figure-four", "90/90", "frog", "couch", "garland")


def hold_seconds(c: StretchCandidate) -> int:
    """Seconds per side (per set) to hold [c] at the moderate level."""
    if c.is_custom and c.duration_per_side_sec:
        return c.duration_per_side_sec        # the person's own stretch, their own time
    name = c.name.lower()
    muscles = set(c.primary_muscles)
    pattern = c.movement_pattern
    if "corpse" in name or "savasana" in name:
        return 180
    small = bool(muscles) and muscles - {"calves"} <= _SMALL and bool(muscles & _SMALL)
    if pattern == "myofascial_release":
        return 60 if small and "feet" not in muscles else 90
    if "breath" in name:
        return 120
    if any(k in name for k in _RESTORATIVE):
        return 120 if (c.duration_per_side_sec or 0) >= 90 or "child" in name or "legs up" in name \
            or "supported" in name else 90
    if pattern == "dynamic_stretch":
        # A flow of several poses (a sun salutation) keeps its longer round.
        return max(45, c.duration_per_side_sec) if (c.duration_per_side_sec or 0) >= 90 else 45
    if pattern == "balance_pose" or any(k in name for k in _EFFORTFUL):
        return 30
    if any(k in name for k in _CENTERING):
        return 30
    if any(k in name for k in _INVERSION):
        return 45
    if pattern == "pnf_stretch":
        return 60                              # contract-relax rounds, not one long hold
    if small:
        return 40 if pattern == "static_stretch" else 45
    if "warrior" in name:
        return 45                              # the front thigh tires before anything lengthens
    # Standing and down-dog shapes hold the body up: never the long floor hold.
    upright = name.startswith("standing") or "dog" in name
    on_floor = not upright and (c.position in _FLOOR or any(k in name for k in _FLOOR_NAMES))
    if muscles & _BIG_LOWER:
        return 90 if on_floor else 60
    return 60 if on_floor else 45


def to_step(c: StretchCandidate) -> dict:
    """Convert a selected stretch into a PlannedWorkout `mobility_exercise` step."""
    return {
        "type": "mobility_exercise",
        "name": c.name,
        "duration_seconds": hold_seconds(c),
        "sets": c.sets or 1,
        "each_side": c.each_side or False,
        "description": c.description or "",
        "muscles": list(c.primary_muscles),
        "cues": list(c.cues),
        "breath_cue": c.breath_cue,
        "instructions": c.instructions,
        "position": c.position,
        "garmin_category": c.garmin_category,
        "garmin_subtype": c.garmin_subtype,
    }


# A stretch with no sides that works this many muscle groups at once is a
# whole-body one (Downward Dog: hamstrings, calves, upper back, lats, glutes).
WHOLE_BODY_MIN_MUSCLES = 3


def _is_whole_body(step: dict) -> bool:
    return (not step.get("each_side") and not step.get("side")
            and len(step.get("muscles") or []) >= WHOLE_BODY_MIN_MUSCLES)


def space_whole_body(steps: list[dict]) -> list[dict]:
    """Never one whole-body stretch straight after another.

    A two-set Downward Dog played as two holds back to back is the same pose
    held twice with nothing between, which is not what a flow is. So a
    whole-body stretch with several sets keeps one in its place and becomes a
    bridge for the rest: placed between the left and right sides of the next
    one-sided stretch -- Downward Dog, Runner's Lunge left, Downward Dog,
    Runner's Lunge right, the classic transition -- or after the next stretch
    when that one has no sides. A one-sided stretch split that way becomes two
    steps with ``side`` set and ``each_side`` off, which every player and the
    watch encoder already play as one hold each.

    Then nothing whole-body may sit beside anything whole-body: a pair is
    separated by moving the next other stretch between them, or, with none
    left and the two the same stretch, the second is dropped.
    """
    out: list[dict] = []
    bridges: list[dict] = []
    for step in steps:
        sets = int(step.get("sets") or 1)
        if _is_whole_body(step) and sets > 1:
            out.append(dict(step, sets=1))
            bridges.extend(dict(step, sets=1) for _ in range(sets - 1))
            continue
        if bridges and step.get("each_side") and sets == 1:
            out.append(dict(step, each_side=False, side="left"))
            out.append(bridges.pop(0))
            out.append(dict(step, each_side=False, side="right"))
            continue
        out.append(step)
        if bridges and not _is_whole_body(step):
            out.append(bridges.pop(0))
    for b in bridges:
        if out and not _is_whole_body(out[-1]):
            out.append(b)

    i = 1
    while i < len(out):
        if _is_whole_body(out[i]) and _is_whole_body(out[i - 1]):
            j = next((k for k in range(i + 1, len(out)) if not _is_whole_body(out[k])), None)
            if j is not None:
                out.insert(i, out.pop(j))
            elif out[i]["name"] == out[i - 1]["name"]:
                out.pop(i)
                continue
        i += 1
    return out
