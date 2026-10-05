# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Build a single workout recommendation.

Combines the chosen tier/duration/sport with HR zones, a distance estimate,
a projected TSS, and the human-readable description + reasoning strings.
"""

from __future__ import annotations

from app.calculators.coaching.models import WorkoutRecommendation
from app.calculators.coaching.selection import _hr_range
from app.calculators.coaching.tables import (
    _DISTANCE_SPORTS,
    _SPORT_SPEED_KMH,
    _SPORT_TERRAIN,
    _TIER_LABEL,
    _TSS_PER_HOUR,
)


def _describe(sport: str, tier: str, duration_min: int,
              dist_km: float | None, hr_min: int | None, hr_max: int | None) -> str:
    """One-line, user-facing summary of the prescribed session."""
    terrain = _SPORT_TERRAIN.get(sport, "")
    terrain_str = f" on {terrain}" if terrain else ""
    label = _TIER_LABEL.get(tier, tier.capitalize())

    if tier == "rest":
        return "Rest day — full recovery. Light stretching or mobility work is fine."

    duration_str = f"{duration_min} min"
    dist_str     = f" (~{dist_km:.1f} km)" if dist_km else ""
    hr_str       = f", HR {hr_min}–{hr_max} bpm" if hr_min and hr_max else ""

    return f"{label} {sport.replace('_', ' ')}{terrain_str} — {duration_str}{dist_str}{hr_str}."


def _reasoning(tier: str, readiness: float, tsb: float,
               ctl_ramp: float | None, goal_note: str | None) -> str:
    """Explain *why* this session was chosen (TSB, ramp, goal).

    `readiness` is accepted for call-site stability but no longer surfaced:
    the readiness score degrades to a training-load-only estimate without
    daily health syncs, so we don't cite it in user-facing rationale.
    """
    parts = []

    tsb_desc = (
        "high accumulated fatigue" if tsb < -30 else
        "optimal training stimulus" if tsb < -10 else
        "maintaining form" if tsb < 5 else
        "feeling fresh" if tsb < 25 else
        "very fresh — rebuild base"
    )
    parts.append(f"TSB {tsb:+.0f} ({tsb_desc})")

    if ctl_ramp is not None and ctl_ramp > 8:
        parts.append(f"CTL ramp {ctl_ramp:+.1f} pts/week is high; injury risk elevated")

    if goal_note:
        parts.append(goal_note)

    return ". ".join(parts) + "."


def _build_recommendation(
    sport: str,
    tier: str,
    duration_min: int,
    lthr: float | None,
    readiness: float,
    tsb: float,
    ctl_ramp: float | None,
    goal_note: str | None,
) -> WorkoutRecommendation:
    """Assemble a full WorkoutRecommendation for one sport at the given tier."""
    if tier == "rest":
        return WorkoutRecommendation(
            sport=sport,
            intensity="rest",
            duration_minutes=0,
            distance_km=None,
            hr_min=None,
            hr_max=None,
            description=_describe(sport, "rest", 0, None, None, None),
            reasoning=_reasoning("rest", readiness, tsb, ctl_ramp, goal_note),
            projected_tss=0.0,
        )

    hr_min, hr_max = _hr_range(tier, lthr)

    # Distance estimate
    speed_map = _SPORT_SPEED_KMH.get(sport, {})
    speed_kmh = speed_map.get(tier, speed_map.get("aerobic"))
    dist_km: float | None = None
    if speed_kmh and sport in _DISTANCE_SPORTS:
        dist_km = round(speed_kmh * (duration_min / 60), 1)

    tss_per_hr = _TSS_PER_HOUR.get(tier, 60)
    projected_tss = round(tss_per_hr * (duration_min / 60), 1)

    return WorkoutRecommendation(
        sport=sport,
        intensity=tier,
        duration_minutes=duration_min,
        distance_km=dist_km,
        hr_min=hr_min,
        hr_max=hr_max,
        description=_describe(sport, tier, duration_min, dist_km, hr_min, hr_max),
        reasoning=_reasoning(tier, readiness, tsb, ctl_ramp, goal_note),
        projected_tss=projected_tss,
    )
