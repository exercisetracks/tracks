# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Exercise selection: slot-based, relevance-ranked per-session picker.

A session is not "N random names from a list" — it's an ordered set of movement
*slots* (see slots.py): squat, hinge, unilateral, calves, core, or horizontal
push / vertical pull / etc.  `_select_exercises` fills each slot from the full
exercise library, keeping only movements the user can actually do (equipment,
animation support, injury-safe, difficulty-appropriate) and ranking candidates
by the library's per-sport relevance score so the most goal-specific movement
wins.  Within the top few near-equivalent options it rotates week to week to
prevent accommodation and give a fresh-but-sensible routine — the way a coach
swaps a back squat for a front squat without changing the session's purpose.

Relevance ordering comes from each exercise's `sport_relevance` map, seeded from
meta-analysis effect sizes (see the Strength Training research notes).
"""

from __future__ import annotations

from random import Random

from .injuries import _exercise_is_safe
from .slots import (
    STRETCH_CATEGORIES,
    TIER_MAX_DIFFICULTY,
    Slot,
    is_unilateral,
    is_vertical_pull,
    split_slots,
)

# Sport families without their own sport_relevance key borrow another family's.
_RELEVANCE_FALLBACK: dict[str, str] = {
    "generic": "strength",
    "triathlon": "running",
    "swimming": "strength",
}

# How many of the top-ranked candidates per slot to rotate the pick among.
# Small enough that we never drop to a low-relevance movement, large enough
# that the routine visibly refreshes week to week.
_ROTATION_DEPTH = 3


def _relevance_score(ex: dict, sport_family: str) -> int:
    """Per-sport relevance for ranking. Falls back through a related family,
    then the generic/strength score, then any score present, then neutral 1."""
    rel = ex.get("sport_relevance") or {}
    if not rel:
        return 1
    key = sport_family if sport_family in rel else _RELEVANCE_FALLBACK.get(sport_family, "")
    if key and key in rel:
        return int(rel[key] or 1)
    for fallback in ("strength", "generic"):
        if fallback in rel:
            return int(rel[fallback] or 1)
    return int(max(rel.values()) or 1)


def _filter_by_equipment(name: str, ex: dict, available: set[str]) -> bool:
    """True if the user has at least one of the exercise's equipment options."""
    required = ex.get("equipment") or ["bodyweight"]
    return any(eq in available for eq in required)


def _eligible_pool(
    library: dict,
    equipment: list[str],
    active_injuries: list,
    tier: int,
    preferred: set[str],
    excluded: set[str],
    confirmed_no: set[str],
    max_difficulty: int | None = None,
) -> dict[str, dict]:
    """Return {name: ex} for every library entry the user can actually train:
    passes the animation gate, equipment, injury, difficulty, and exclusion
    filters. Preferred and custom entries bypass the animation and difficulty
    gates (the user opted into them explicitly).

    max_difficulty: optional extra ceiling layered under the tier cap (from
    the user's declared experience level — leveling.py)."""
    available = set(equipment)
    max_diff = TIER_MAX_DIFFICULTY.get(tier, 5)
    if max_difficulty is not None:
        max_diff = min(max_diff, max_difficulty)
    out: dict[str, dict] = {}

    for name, ex in library.items():
        if name in excluded:
            continue

        is_custom = bool(ex.get("_is_custom"))
        is_pref = name in preferred

        # Stretches leak into exercise_library under warm_up/pose/move Garmin
        # categories; never prescribe them as strength work.
        if not is_custom and ex.get("garmin_category") in STRETCH_CATEGORIES:
            continue

        # Animation gate (soft): with in-app 3D animations, a movement no longer
        # needs a *watch* animation to be prescribable — non-animating moves stay
        # in the pool and are merely demoted in ranking (see _rank_slot). Only a
        # device-CONFIRMED non-animating movement is hard-filtered, to protect
        # the watch-playback experience for users who rely on it. Custom and
        # preferred entries bypass even that.
        if name in confirmed_no and not (is_custom or is_pref):
            continue

        if not _filter_by_equipment(name, ex, available):
            continue

        if not _exercise_is_safe(ex, active_injuries):
            continue

        # Difficulty cap by tier (don't hand a beginner a Power Snatch).
        if not (is_custom or is_pref) and (ex.get("difficulty") or 1) > max_diff:
            continue

        out[name] = ex

    return out


def _slot_candidates(slot: Slot, pool: dict[str, dict]) -> list[str]:
    """Names in the eligible pool that satisfy a slot's movement contract:
    matching movement pattern, at least one target muscle, and the slot's
    unilateral constraint."""
    out = []
    for name, ex in pool.items():
        if ex.get("movement_pattern") not in slot.patterns:
            continue
        pm = set(ex.get("primary_muscles") or [])
        if not (pm & slot.muscles):
            continue
        if slot.exclude_muscles and (pm & slot.exclude_muscles):
            continue
        if slot.unilateral is True and not is_unilateral(name):
            continue
        if slot.unilateral is False and is_unilateral(name):
            continue
        if slot.vertical_pull is True and not is_vertical_pull(name):
            continue
        if slot.vertical_pull is False and is_vertical_pull(name):
            continue
        if slot.isolation_only and ex.get("is_compound", True):
            continue
        out.append(name)
    return out


def _rank_slot(
    names: list[str],
    pool: dict[str, dict],
    sport_family: str,
    slot: Slot,
    preferred: set[str],
) -> list[str]:
    """Sort a slot's candidates: preferred first, then most sport-relevant,
    then the slot's compound preference, then easiest (most accessible)."""
    def key(n: str) -> tuple:
        ex = pool.get(n, {})
        is_pref = 0 if n in preferred else 1
        # Soft watch-animation preference: among otherwise-equal candidates,
        # favour movements that animate on the watch (better watch UX) — but
        # never at the cost of relevance/class, so a more-relevant non-animating
        # lift still wins. Preferred picks aren't demoted.
        animates = 0 if (ex.get("has_animation") or n in preferred) else 1
        relevance = -_relevance_score(ex, sport_family)  # higher score first
        is_comp = ex.get("is_compound", True)
        difficulty = ex.get("difficulty") or 1
        if slot.compound is False:
            # Accessory slot: the movement *class* matters more than sport
            # relevance — a curl beats a (more "relevant") weighted pull-up.
            comp = 0 if not is_comp else 1
            return (is_pref, comp, relevance, animates, difficulty, n)
        comp = (0 if is_comp else 1) if slot.compound is True else 0
        return (is_pref, relevance, comp, animates, difficulty, n)

    return sorted(names, key=key)


def _rotate(
    ranked: list[str],
    preferred: set[str],
    seed: str,
) -> list[str]:
    """Keep preferred picks pinned to the front; rotate the choice among the
    top few near-equivalent candidates using a per-(sport, slot, week, regen)
    seed so the pick refreshes week to week without ever dropping to a
    low-relevance movement. Lower-ranked candidates stay in order as fallbacks
    for when the top picks are already used elsewhere in the week."""
    pref = [n for n in ranked if n in preferred]
    others = [n for n in ranked if n not in preferred]
    top = others[:_ROTATION_DEPTH]
    rest = others[_ROTATION_DEPTH:]
    Random(seed).shuffle(top)
    return pref + top + rest


def _select_exercises(
    sport_family: str,
    tier: int,
    split_type: str,
    equipment: list[str],
    library: dict,
    active_injuries: list,
    max_exercises: int = 6,
    preferred: set[str] | None = None,
    excluded: set[str] | None = None,
    week_num: int = 0,
    regen_salt: str = "",
    confirmed_no: set[str] | None = None,
    used_this_week: set[str] | None = None,
    max_difficulty: int | None = None,
    block_num: int = 0,
    return_slots: bool = False,
) -> list[str] | list[tuple[str | None, str]]:
    """
    Select exercises for one session by filling the split's movement slots.

    return_slots: when True, return ``(slot_key, name)`` pairs (slot_key is None
    for backfilled picks) so callers (workout archetypes) know which slot each
    pick filled. Default returns just the names for backward compatibility.

    The split (upper/lower/push/pull/legs/full_body/supp_*) maps to an ordered
    list of Slots. Each slot draws the most sport-relevant eligible movement
    that fits its pattern + muscle contract, rotating week to week among the
    top few. Slots are walked in priority order until `max_exercises` picks are
    made; empty slots (no equipment for that pattern) are skipped.

    used_this_week: names already scheduled earlier in the same training week —
    avoided so a 3–5 day block doesn't repeat the same lift on consecutive days.
    preferred / excluded: the user's explicit exercise preferences.
    """
    preferred = preferred or set()
    excluded = excluded or set()
    confirmed_no = confirmed_no or set()
    used_this_week = used_this_week or set()

    pool = _eligible_pool(
        library, equipment, active_injuries, tier,
        preferred, excluded, confirmed_no,
        max_difficulty=max_difficulty,
    )

    slots = split_slots(split_type, sport_family)
    result: list[str] = []
    slot_of: dict[str, str | None] = {}   # pick name -> slot key that filled it
    used_session: set[str] = set()

    def _take(candidates: list[str], avoid_week: bool) -> str | None:
        for n in candidates:
            if n in used_session:
                continue
            if avoid_week and n in used_this_week:
                continue
            return n
        return None

    for slot in slots:
        if len(result) >= max_exercises:
            break
        names = _slot_candidates(slot, pool)
        if not names:
            continue
        ranked = _rank_slot(names, pool, sport_family, slot, preferred)
        # Hybrid rotation: main compound slots stay STABLE for the whole training
        # block (seed keyed on block, not week or regen salt) so the user
        # progresses the same lift week over week; accessory slots keep rotating
        # weekly for variety.
        if slot.role == "main":
            seed = f"{sport_family}-{split_type}-{slot.key}-block{block_num}"
        else:
            seed = f"{sport_family}-{split_type}-{slot.key}-{week_num}-{regen_salt}"
        ordered = _rotate(ranked, preferred, seed=seed)
        # Prefer a movement not used earlier this week; if the whole slot was
        # used this week, allow a repeat rather than leaving the slot empty.
        pick = _take(ordered, avoid_week=True) or _take(ordered, avoid_week=False)
        if pick:
            result.append(pick)
            used_session.add(pick)
            slot_of[pick] = slot.key

    # Backfill: if equipment gaps left slots empty and we're short, pull the
    # most relevant remaining compound movements that fit any of the split's
    # slot patterns so the session still hits its target volume.
    if len(result) < max_exercises:
        wanted_patterns = frozenset().union(*(s.patterns for s in slots)) if slots else frozenset()
        extras = [
            n for n, ex in pool.items()
            if n not in used_session
            and ex.get("movement_pattern") in wanted_patterns
        ]
        extras.sort(key=lambda n: (
            0 if n in used_this_week else -1,          # unused-this-week first
            -_relevance_score(pool.get(n, {}), sport_family),
            0 if pool.get(n, {}).get("is_compound", True) else 1,
        ))
        for n in extras:
            if len(result) >= max_exercises:
                break
            result.append(n)
            used_session.add(n)
            slot_of.setdefault(n, None)

    if return_slots:
        return [(slot_of.get(n), n) for n in result]
    return result
