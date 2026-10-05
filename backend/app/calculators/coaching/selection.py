# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Scoring and selection helpers.

Stateless functions that turn the training signal into the building blocks of
a workout: which intensity tier to target, which sports to recommend, the HR
range for a tier, and how long the session should be.
"""

from __future__ import annotations

from datetime import date
from math import exp
from typing import Sequence

from app.calculators.coaching.tables import _ZONE_BY_TIER


def _intensity_tier(readiness: float, tsb: float) -> str:
    """
    Map (readiness score, TSB) onto a workout intensity tier.

    TSB zones (Friel):
        < -30  high accumulated fatigue
        -30→-10 optimal training stimulus
        -10→+5  grey zone (maintain)
        +5→+25  fresh
        > +25   very fresh / detraining risk
    """
    high = readiness >= 75
    mid  = 50 <= readiness < 75

    if tsb < -30:
        return "easy" if high else "rest"

    if tsb < -10:         # optimal training range
        if high:
            return "tempo"
        if mid:
            return "aerobic"
        return "easy"

    if tsb < 5:           # grey zone
        if high:
            return "aerobic"
        if mid:
            return "easy"
        return "walk"

    if tsb < 25:          # fresh
        if high:
            return "quality"
        if mid:
            return "tempo"
        return "aerobic"

    # very fresh / detraining risk
    if high:
        return "aerobic"  # rebuild base
    return "easy"


def _rank_sports(
    activity_history: Sequence,  # Activity ORM rows, last 90 days
    today: date,
    n: int = 3,
) -> list[str]:
    """
    Return up to n sports ranked by (frequency × recency_weight).

    Recency weight decays exponentially: activities from yesterday count more
    than activities from 3 months ago.
    """
    scores: dict[str, float] = {}
    for act in activity_history:
        sport = act.sport
        if not sport:
            continue
        # A local day already (api/coaching: local_history), as build_context reads it.
        d = act.started_at.date() if hasattr(act.started_at, "date") else act.started_at
        days_ago = max((today - d).days, 0)
        # Exponential decay over 90 days
        weight = exp(-days_ago / 30)
        scores[sport] = scores.get(sport, 0.0) + weight

    ranked = sorted(scores, key=lambda s: scores[s], reverse=True)
    return ranked[:n]


def _hr_range(tier: str, lthr: float | None) -> tuple[int | None, int | None]:
    """Convert a tier's %-of-LTHR band into an absolute bpm range."""
    zone = _ZONE_BY_TIER.get(tier)
    if zone is None or lthr is None:
        return None, None
    return int(lthr * zone[0]), int(lthr * zone[1])


def _base_duration_minutes(ctl: float) -> int:
    """Typical session duration calibrated to current fitness level."""
    if ctl < 20:
        return 35
    if ctl < 40:
        return 50
    if ctl < 60:
        return 70
    if ctl < 80:
        return 90
    return 110


def _tier_duration_factor(tier: str) -> float:
    """Scale base duration by tier: harder sessions are shorter."""
    return {
        "rest":       0.0,
        "walk":       0.7,
        "easy":       0.85,
        "aerobic":    1.0,
        "tempo":      0.75,
        "threshold":  0.65,
        "quality":    0.60,
    }.get(tier, 1.0)
