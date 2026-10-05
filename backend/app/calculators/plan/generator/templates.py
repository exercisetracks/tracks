# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Weekly template rotation — variety.

Picks the day-by-day session template for a sport family × phase, then rotates
slot positions by week number so consecutive weeks in the same phase don't feel
identical. This is purely about variety; volume, intensity, and polarisation
constraints are applied later in week generation.
"""

from __future__ import annotations

from app.calculators.plan.base import (
    _TEMPLATES,
    _cy_template_for,
    _mtb_template_for,
)


def _rotating_template(family: str, phase: str, week_num: int, mtb_discipline: str,
                       cycling_discipline: str) -> list[str]:
    """
    Return a weekly template with rotation for variety.

    Each sport family × phase has 2-3 sub-templates that rotate by week_num,
    ensuring consecutive weeks in the same phase don't feel identical.
    """
    if family == "mountain_biking":
        templates = _mtb_template_for(mtb_discipline)
    elif family == "cycling":
        templates = _cy_template_for(cycling_discipline)
    else:
        templates = _TEMPLATES.get(family, _TEMPLATES["generic"])

    base = list(templates.get(phase, templates["base"]))

    # Running: rotate quality slot position to vary the week feel. Slot 0 is
    # Monday (the week starts on plan_monday).
    if family == "running":
        rotation = week_num % 3
        if rotation == 1 and phase in ("build", "peak"):
            # Move Thursday's intervals to Wednesday; Wednesday's rest moves to Thursday
            base = list(base)
            if len(base) >= 6:
                base[2], base[3] = base[3], base[2]  # Swap Wed/Thu
        elif rotation == 2 and phase == "base":
            # Move the fartlek from Thursday to Wednesday. This used to write
            # an easy run into Wednesday and a second fartlek into Friday,
            # leaving Thursday's in place: two fartleks and five run days in a
            # base week, which the polarisation pass then had to undo.
            base = list(base)
            if len(base) >= 6:
                base[2], base[3] = "fartlek", "rest"

    # MTB: rotate skill session position
    if family == "mountain_biking":
        rotation = week_num % 2
        if rotation == 1:
            base = list(base)
            # Find skills and endurance, swap their positions if adjacent
            for i in range(len(base)):
                if base[i] == "skills":
                    for j in (i-1, i+1):
                        if 0 <= j < len(base) and base[j] == "endurance":
                            base[i], base[j] = base[j], base[i]
                            break
                    break

    return base
