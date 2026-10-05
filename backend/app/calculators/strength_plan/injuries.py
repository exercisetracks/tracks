# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Injury adaptation: map active injuries to excluded exercises and load cuts.

Active injuries come from the health page (each has a `body_part` string and a
1–10 `severity`). This module decides, per exercise, whether it is safe to
include and — for minor injuries — how much to reduce the load.

Severity bands:
  minor (1–3):    keep the exercise, reduce load on affected muscles by 30%
  moderate (4–7): exclude if injured muscles are primary, or pattern is banned
  severe (8–10):  exclude if injured muscles are primary OR secondary
"""

from __future__ import annotations

# Maps injury body_part → primary muscles to protect.
_INJURY_MUSCLES: dict[str, list[str]] = {
    "knee":        ["quads", "hamstrings"],
    "shoulder":    ["shoulders", "upper_back", "chest"],
    "lower_back":  ["lower_back", "hamstrings"],
    "hip_flexor":  ["hip_flexors", "quads"],
    "hamstring":   ["hamstrings"],
    "calf":        ["calves"],
    "ankle":       ["calves"],
    "elbow":       ["biceps", "triceps", "forearms"],
    "forearm":     ["forearms", "biceps"],
    "wrist":       ["forearms", "chest"],
    "neck":        ["upper_back", "shoulders"],
    "hip":         ["glutes", "hip_abductors", "hip_flexors"],
    "groin":       ["hip_flexors", "quads"],
    "shin":        ["calves"],
    "foot":        ["calves"],
}

# Movement patterns excluded per injury (moderate+).
_INJURY_BANNED_PATTERNS: dict[str, list[str]] = {
    "knee":       ["squat", "plyometric"],
    "lower_back": ["hinge", "rotation"],
    "shoulder":   ["push", "pull"],
    "wrist":      ["push", "pull"],
    "elbow":      ["pull", "isolation"],
    "forearm":    ["pull", "isolation"],
    "hip_flexor": ["squat", "hinge"],
    "hamstring":  ["hinge"],
    "calf":       ["plyometric"],
    "ankle":      ["plyometric"],
}


def _injury_severity_band(severity: int) -> str:
    """Return 'minor' / 'moderate' / 'severe' from a 1–10 severity score."""
    if severity <= 3:
        return "minor"
    if severity <= 7:
        return "moderate"
    return "severe"


def _exercise_is_safe(exercise: dict, active_injuries: list) -> bool:
    """
    Return True if an exercise is safe to include given active injuries.

    Logic:
      minor:    reduce load but keep exercise (handled at weight prescription)
      moderate: exclude if injured muscles are in primary_muscles
                 OR movement_pattern is in banned patterns for that injury
      severe:   exclude if any injured muscles are in primary or secondary
    """
    if not active_injuries:
        return True

    primary   = exercise.get("primary_muscles", [])
    secondary = exercise.get("secondary_muscles", [])
    pattern   = exercise.get("movement_pattern", "")

    for inj in active_injuries:
        body_part = (inj.body_part or "").lower()
        band = _injury_severity_band(inj.severity)

        protected_muscles = _INJURY_MUSCLES.get(body_part, [])
        banned_patterns   = _INJURY_BANNED_PATTERNS.get(body_part, [])

        if band == "moderate":
            if any(m in primary for m in protected_muscles):
                return False
            if pattern in banned_patterns:
                return False
        elif band == "severe":
            if any(m in primary or m in secondary for m in protected_muscles):
                return False
            if pattern in banned_patterns:
                return False
        # minor: exercise stays but load is reduced (handled in weight calculation)

    return True


def _injury_load_modifier(exercise: dict, active_injuries: list) -> float:
    """
    Return a weight load modifier (0.0–1.0) for minor injuries.
    1.0 = no modification; 0.70 = reduce weight by 30%.
    """
    if not active_injuries:
        return 1.0

    primary = exercise.get("primary_muscles", [])
    worst   = 1.0

    for inj in active_injuries:
        body_part = (inj.body_part or "").lower()
        band = _injury_severity_band(inj.severity)
        if band != "minor":
            continue
        protected = _INJURY_MUSCLES.get(body_part, [])
        if any(m in primary for m in protected):
            worst = min(worst, 0.70)  # 30% reduction for minor injury

    return worst
