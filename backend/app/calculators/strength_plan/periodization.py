# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Periodization and session volume: sets/reps/%-1RM, rest, and duration.

`_periodization_prescription` returns the sets/reps/intensity for a given
progression stage and week; `_TIER_SETS` and the rest tables scale volume and
recovery to the training tier; `_session_duration_minutes` estimates the card's
displayed length.

Science basis (NOTES.md, Strength Training Feature section):
  - Pelland et al. 2024 meta-regression → set volume by tier
  - Linear → weekly-undulating → DUP progression by total session count
  - Supplementary tiers (1–2) use shorter rest to keep sessions ≤25 min
"""

from __future__ import annotations

from typing import Any


def _periodization_prescription(
    progression_stage: str,
    week_in_cycle: int,
    endurance: bool,
) -> dict[str, Any]:
    """
    Return reps, RPE, and % 1RM for a given periodization stage and week.

    Set count is NOT decided here — the generator applies `_TIER_SETS[tier]`
    so that training *volume* scales with the tier (5×/wk athlete → more sets)
    while the rep/load *scheme* is chosen by the athlete's goal below. Deload
    weeks are also applied by the generator (halve sets, ease intensity), so
    this function is deload-agnostic and always returns the working scheme.

    The scheme depends on whether strength is *supplementary to an endurance
    sport* or the primary goal — not on the tier:

      endurance=True  → heavy, low-rep neural strength. Improves economy and
        force output without adding mass or excess fatigue that would interfere
        with the endurance plan (Rønnestad & Mujika 2014; Beattie 2014).
      endurance=False → the full hypertrophy → strength → power spectrum with
        moderate-to-heavy loads and higher rep volume (general strength / mass).

    Progression stage advances with training age:
      linear (beginner) → weekly-undulating (intermediate) → DUP (advanced).
    """
    if endurance:
        if progression_stage == "linear":
            return {"reps": 6, "rpe_target": 7, "pct_1rm": 0.72}
        if progression_stage == "weekly_undulating":
            phase = week_in_cycle % 3
            if phase == 0:   # strength-hypertrophy
                return {"reps": 6, "rpe_target": 7, "pct_1rm": 0.78}
            if phase == 1:   # max strength
                return {"reps": 4, "rpe_target": 8, "pct_1rm": 0.85}
            return {"reps": 3, "rpe_target": 8, "pct_1rm": 0.88}  # power / RFD
        # DUP: session 0/1/2 = strength-hyp / max-strength / power
        session_type = week_in_cycle % 3
        if session_type == 0:
            return {"reps": 6, "rpe_target": 7, "pct_1rm": 0.75}
        if session_type == 1:
            return {"reps": 4, "rpe_target": 8, "pct_1rm": 0.85}
        return {"reps": 3, "rpe_target": 8, "pct_1rm": 0.88}

    # Strength / hypertrophy focus (sport_family strength or generic).
    if progression_stage == "linear":
        return {"reps": 8, "rpe_target": 7, "pct_1rm": 0.70}
    if progression_stage == "weekly_undulating":
        phase = week_in_cycle % 3
        if phase == 0:   # hypertrophy
            return {"reps": 10, "rpe_target": 8, "pct_1rm": 0.70}
        if phase == 1:   # strength
            return {"reps": 6, "rpe_target": 8, "pct_1rm": 0.80}
        return {"reps": 4, "rpe_target": 7, "pct_1rm": 0.85}    # power
    # DUP
    session_type = week_in_cycle % 3
    if session_type == 0:
        return {"reps": 10, "rpe_target": 8, "pct_1rm": 0.70}
    if session_type == 1:
        return {"reps": 5, "rpe_target": 8, "pct_1rm": 0.82}
    return {"reps": 3, "rpe_target": 9, "pct_1rm": 0.88}


# Sport families for which strength work is supplementary to endurance/power
# performance rather than the primary goal. Drives the rep/load scheme above.
# Both ski families count: an alpine skier's strength is in service of skiing
# (low-rep, heavy, scheduled around the conditioning days), not an end in itself.
_ENDURANCE_FAMILIES = frozenset({
    "running", "cycling", "mountain_biking", "hiking", "rowing",
    "climbing", "paddling", "triathlon", "swimming",
    "nordic_skiing", "alpine_skiing",
})


def is_endurance_family(sport_family: str) -> bool:
    return sport_family in _ENDURANCE_FAMILIES


# Sets per exercise per session, by tier (Pelland 2024 meta-regression):
#   5 = 5×/week dedicated strength
#   4 = 4×/week strength-primary
#   3 = 3×/week balanced (default)
#   2 = 2×/week supplementary
#   1 = 1×/week minimal maintenance
_TIER_SETS: dict[int, int] = {5: 5, 4: 4, 3: 3, 2: 2, 1: 2}

# Rest between sets (full-strength / focused programs)
_REST_BY_PATTERN: dict[str, int] = {
    "squat":      180,
    "hinge":      180,
    "push":       120,
    "pull":       120,
    "plyometric": 90,
    "rotation":   90,
    "isometric":  60,
    "isolation":  60,
    "carry":      120,
}

# Supplementary (tier 1-2) rest — shorter to keep sessions compact (≤25 min)
_REST_BY_PATTERN_SUPP: dict[str, int] = {
    "squat":      90,
    "hinge":      90,
    "push":       75,
    "pull":       75,
    "plyometric": 60,
    "rotation":   60,
    "isometric":  45,
    "isolation":  45,
    "carry":      75,
}


def _rest_seconds(pattern: str, tier: int) -> int:
    if tier <= 2:
        return _REST_BY_PATTERN_SUPP.get(pattern, 75)
    return _REST_BY_PATTERN.get(pattern, 120)


def _session_duration_minutes(exercises: list[str], library: dict,
                              sets: int, avg_reps: int, tier: int = 3,
                              max_minutes: int | None = None) -> int:
    """
    Estimate total session duration for display in the workout card.

    Based on: sets × (time_per_set + rest) + warmup/cooldown.

    max_minutes: user-configured session duration cap. When set, this overrides
    the tier-based default caps. Supplementary tiers (1-2) default to 20 min
    per the minimum effective dose evidence (Beattie et al. 2014, 2017).
    """
    if not exercises:
        return 0
    total_set_time_s = 0
    for name in exercises:
        ex = library.get(name, {})
        pattern  = ex.get("movement_pattern", "push")
        rest     = _rest_seconds(pattern, tier)
        set_time = avg_reps * 5 + rest
        total_set_time_s += sets * set_time
    minutes = round(10 + total_set_time_s / 60)
    if max_minutes is not None:
        return min(minutes, max_minutes)
    caps = {5: 75, 4: 60, 3: 50, 2: 20, 1: 15}
    return min(minutes, caps.get(tier, 45))
