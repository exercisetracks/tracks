# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Weekly standalone mobility session.

Builds the ~25-minute "Mobility & Recovery" session that accompanies a week of
strength training (pushed to the watch as a Yoga workout). The stretch choices
come from the same library-driven selector as the post-workout flows
(`app.calculators.flexibility`), so the weekly session draws on the whole
stretch library, rotates week to week, and respects the user's per-device
animation support and preferences — rather than the fixed eight-pose list the
old hardcoded builder emitted every single week.

The eligible candidate pool is assembled by the caller (the strength injector,
which has DB + device context) and passed in, keeping this module pure.
"""

from __future__ import annotations

from app.calculators.flexibility import (
    FULL_BODY_MOBILITY_MUSCLES,
    select_stretch_flow,
    sport_target_muscles,
    to_step,
)
from .flow_archetypes import select_flow_archetype


def mobility_target_muscles(sport_family: str) -> list[str]:
    """Whole-body mobility coverage with the sport's tight spots pulled to the
    front, so a cyclist's session leads with hip flexors and thoracic spine
    while still covering the rest of the body."""
    sport_muscles = sport_target_muscles(sport_family, fallback=[])
    ordered: list[str] = []
    for m in sport_muscles[:3] + FULL_BODY_MOBILITY_MUSCLES:
        if m not in ordered:
            ordered.append(m)
    return ordered


def _mobility_steps(
    sport_family: str,
    candidates: list,
    week_num: int = 0,
    regen_salt: str = "",
) -> list[dict]:
    """Build the weekly mobility session's stretch steps from the eligible
    candidate pool, tailored to sport and rotated per week."""
    if not candidates:
        return []
    targets = mobility_target_muscles(sport_family)
    archetype = select_flow_archetype("weekly_mobility", sport_family, None, week_num)
    closer_muscles = list(archetype.closer_muscles) if archetype else None
    selected = select_stretch_flow(
        candidates, targets,
        count=8,
        min_count=6,
        seed=f"mobility-{sport_family}-{week_num}-{regen_salt}",
        order_by_position=True,
        closer_muscles=closer_muscles,
    )
    return [to_step(c) for c in selected]


def _mobility_description(sport_family: str) -> str:
    sport_note = {
        "running":         "Targets hip flexors, hamstrings, and calves — key running recovery areas.",
        "cycling":         "Counters hip flexor shortening and thoracic rounding from bike position.",
        "climbing":        "Forearm decompression, shoulder health, and hip opening.",
        "paddling":        "Thoracic rotation and shoulder mobility for efficient paddling.",
        "mountain_biking": "Hip and thoracic mobility to counter an aggressive bike position.",
        "hiking":          "Calf, hip, and lower-back recovery after trail demands.",
    }.get(sport_family, "Full-body mobility to support your training.")

    return (f"25 min active recovery and mobility session. {sport_note} "
            "Focus on breath and staying relaxed — this session aids adaptation "
            "from the week's training.")
