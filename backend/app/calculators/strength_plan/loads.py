# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Load math: 1RM estimation, %-of-1RM targets, and gym-realistic rounding.

Pure numeric helpers with no dependency on the rest of the package. Used by the
plan generator to turn a measured (or seeded) lift into a prescribed weight, and
to snap that weight to a number the user can actually load on real equipment.
"""

from __future__ import annotations

import math

_LB_PER_KG = 2.20462

# ── Available gym weights, per equipment family ──────────────────────────────
# Prescriptions are rounded to the nearest value the user can realistically load.

# Standard fixed dumbbell weights available at most gyms (lb).
_DUMBBELL_LB = [5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100]

# Standard fixed dumbbell weights at most gyms (kg) — 2.5 kg increments in the
# common range, then 5 kg above 30 kg where you typically buy in pairs.
_DUMBBELL_KG = [2.5, 5, 7.5, 10, 12.5, 15, 17.5, 20, 22.5, 25, 27.5, 30, 35, 40, 45, 50]

# Cable/machine stack weights (lb) — 15 lb increments starting at 10.
_MACHINE_LB = [10, 25, 40, 55, 70, 85, 100, 115, 130, 145, 160, 175, 190, 205, 220, 235, 250]

# Cable/machine stack weights (kg) — 5 kg increments, matching almost all
# metric machine pin stacks.
_MACHINE_KG = list(range(5, 121, 5))

# Standard kettlebell sizes (kg) — same in both unit systems; kettlebells are
# sold in kg even in the US, though many lb-marked ones round to whole lbs.
_KETTLEBELL_KG = [8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48]


def estimate_1rm(weight_kg: float, reps: int) -> float | None:
    """
    Average of three well-validated 1RM formulas.
    Accuracy: ±5% for 2–10 reps.  Avoid using for >10 reps (±15–20% error).
    Returns None if reps < 1 or weight ≤ 0.
    """
    if reps < 1 or weight_kg <= 0:
        return None
    if reps == 1:
        return weight_kg

    # Only reliable in the 2–10 rep range
    reps = min(reps, 10)

    # Epley (1985): excellent for low reps
    epley = weight_kg * (1 + reps / 30.0)

    # Brzycki (1993): good for 6–10 reps
    brzycki = weight_kg * (36.0 / (37.0 - reps)) if reps < 37 else weight_kg * 1.33

    # Wathan (1994): moderate-rep accuracy
    wathan = (100 * weight_kg) / (48.8 + 53.8 * math.exp(-0.075 * reps))

    return round((epley + brzycki + wathan) / 3.0, 1)


def training_weight(estimated_1rm_kg: float, pct: float) -> float:
    """
    Return the target weight for a given % of 1RM, rounded to nearest 2.5 kg.
    Standard training plates come in 1.25 kg increments (2.5 kg per side).
    """
    raw = estimated_1rm_kg * pct
    return round(raw / 2.5) * 2.5


def _nearest(value: float, options: list) -> float:
    return min(options, key=lambda x: abs(x - value))


def round_weight_for_equipment(weight_kg: float, equipment: list,
                               units: str = "metric") -> float:
    """
    Round a prescribed weight to the nearest realistic gym weight in the
    user's display unit, then return the kg value to store in the database.

    The watch interprets exercise_weight as kg (FIT spec) and converts to
    the user-configured display unit. Rounding in the user's unit before
    that conversion ensures the watch shows a clean number — no more
    "20.4 kg" / "44.97 lb" remainders from prior unit round-trips.

    Increments per equipment family:
      dumbbell/cable: 5 lb (US)        | 2.5–5 kg (metric)
      machine:        15 lb stack pin  | 5 kg stack pin
      kettlebell:     standard kg sizes (sold by kg worldwide)
      barbell/BW:     2.5 lb plates    | 2.5 kg plates
    """
    if weight_kg <= 0:
        return 0.0

    eq = set(equipment or [])
    imperial = (units or "metric").lower() == "imperial"

    if "kettlebell" in eq and "barbell" not in eq and "dumbbell" not in eq:
        # Kettlebells: snap to actual KB sizes (kg) regardless of units —
        # gyms only stock specific sizes.
        return float(_nearest(weight_kg, _KETTLEBELL_KG))

    if "machine" in eq and "barbell" not in eq and "dumbbell" not in eq:
        if imperial:
            lb = weight_kg * _LB_PER_KG
            return _nearest(lb, _MACHINE_LB) / _LB_PER_KG
        return float(_nearest(weight_kg, _MACHINE_KG))

    if "dumbbell" in eq or "cable" in eq:
        if imperial:
            lb = weight_kg * _LB_PER_KG
            return _nearest(lb, _DUMBBELL_LB) / _LB_PER_KG
        return float(_nearest(weight_kg, _DUMBBELL_KG))

    # Barbell or bodyweight-only: 2.5 unit plate increments
    if imperial:
        lb = weight_kg * _LB_PER_KG
        return round(lb / 5.0) * 5.0 / _LB_PER_KG  # nearest 5 lb (2x 2.5 lb plates per side)
    return round(weight_kg / 2.5) * 2.5


def is_bodyweight_only(equipment: list | None) -> bool:
    """True when the movement carries no external load — its only equipment
    option is bodyweight. (An empty/omitted list is treated as *unknown*, not
    bodyweight, so callers that don't supply equipment keep the pattern
    defaults below.)"""
    eq = {e for e in (equipment or []) if e}
    return eq == {"bodyweight"}


def conservative_starting_weight(
    movement_pattern: str, is_compound: bool, equipment: list | None = None
) -> float:
    """
    Ultra-conservative starting weight for a new user with no FIT history.
    Targets RPE 5 (very easy) so the first sessions calibrate safely.
    These will be auto-escalated after 3 sessions.

    Science: start below minimum effective load; the algorithm ramps quickly
    in the first 3 calibration sessions then enters normal progression.

    (`is_compound` is accepted for caller compatibility but not currently used —
    the per-pattern defaults below already encode the compound/isolation split.)
    """
    # Calisthenics (push-ups, pull-ups, dips, planks) carry no added load for a
    # new trainee. A push-up prescribed at "10 kg" is both nonsensical and
    # confusing; loaded progressions only begin once the user logs a set with
    # real added weight. Keyed on equipment, not pattern, because the same
    # pattern spans loaded lifts (bench press) and bodyweight moves (push-up).
    if is_bodyweight_only(equipment):
        return 0.0

    # Bodyweight exercises: expressed as 0 kg added weight
    if movement_pattern in ("isometric", "plyometric"):
        return 0.0

    # Conservative kilograms for a new trainee
    defaults = {
        "squat":    20.0,  # just the bar
        "hinge":    20.0,  # just the bar / light dumbbells
        "push":     10.0,  # light dumbbells or assisted
        "pull":     0.0,   # bodyweight (assisted if needed)
        "rotation": 5.0,
        "isolation": 5.0,
        "carry":    10.0,
    }
    return defaults.get(movement_pattern, 10.0)
