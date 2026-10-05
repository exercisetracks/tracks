# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training-load signal, goal/periodisation adjustments, and CTL/ATL math.

This module turns raw load numbers and the active goal into a TrainingSignal
(TSB, ramp rate, injury warning, periodisation phase) and provides the
from-scratch CTL/ATL computation used by the forward-looking weekly plan.
"""

from __future__ import annotations

from datetime import date, timedelta

from app.calculators.coaching.models import TrainingSignal
from app.calculators.coaching.tables import _ATL_DECAY, _CTL_DECAY, _PHASE_WEEKS


def _event_phase(goal, today: date) -> tuple[float, str]:
    """Weeks-to-event and the matching periodisation phase for an event goal."""
    weeks_out = max((goal.event_date - today).days / 7, 0)
    phase = next(p for w, p in _PHASE_WEEKS if weeks_out <= w)
    return weeks_out, phase


def _ramp(goal) -> float:
    return float(getattr(goal, "ctl_ramp_per_week", None) or 0.0)


def _fitness_phase(ramp: float) -> str:
    """A fitness goal's phase is the direction it asks CTL to go — there is no
    date to count back from. The plan's own light weeks are not reflected: the
    signal describes the goal, the calendar shows the week."""
    if ramp > 0:
        return "build"
    if ramp < 0:
        return "recovery"
    return "maintain"


def _apply_goal(tier: str, base_duration: int, goal, today: date) -> tuple[str, int, str | None]:
    """
    Return (adjusted_tier, adjusted_duration_minutes, goal_note).
    Adjusts the intensity tier and duration based on the active goal.
    """
    if goal is None:
        return tier, base_duration, None

    gtype = goal.goal_type

    if gtype == "event" and goal.event_date:
        weeks_out, phase = _event_phase(goal, today)
        note = f"{goal.event_name or 'Event'} in {weeks_out:.0f} weeks — {phase} phase"

        if phase == "taper":
            tier = "easy" if tier not in ("rest", "walk", "easy") else tier
            base_duration = int(base_duration * 0.65)
        elif phase == "peak":
            # Maintain current tier, add a touch of quality if already hard
            pass
        elif phase == "build":
            if tier == "aerobic":
                tier = "tempo"
        elif phase == "base":
            # Push toward aerobic if currently easy
            if tier == "easy":
                tier = "aerobic"
            base_duration = int(base_duration * 1.10)

        return tier, base_duration, note

    if gtype == "fitness":
        note = f"Fitness goal: {_ramp(goal):+.1f} CTL / week"
        return tier, base_duration, note

    if gtype == "volume_target" and goal.target_weekly_km:
        note = f"Weekly volume target: {goal.target_weekly_km:.0f} km"
        # Boost duration slightly for volume goals
        base_duration = int(base_duration * 1.10)
        return tier, base_duration, note

    return tier, base_duration, None


def _build_signal(
    ctl: float,
    atl: float,
    ctl_7d_ago: float | None,
    goal,
    today: date,
) -> TrainingSignal:
    """Bundle TSB, ramp rate, injury warning, and goal phase into a signal."""
    tsb = ctl - atl
    ramp = round(ctl - ctl_7d_ago, 1) if ctl_7d_ago is not None else None
    warning = ramp is not None and ramp > 8.0

    phase = None
    if goal and goal.goal_type == "event" and goal.event_date:
        _, phase = _event_phase(goal, today)
    elif goal and goal.goal_type == "fitness":
        phase = _fitness_phase(_ramp(goal))

    return TrainingSignal(
        ctl=round(ctl, 1),
        atl=round(atl, 1),
        tsb=round(tsb, 1),
        ctl_ramp=ramp,
        injury_risk_warning=warning,
        phase=phase,
    )


def _compute_ctl_atl(tss_by_date: dict[date, float], end: date) -> tuple[float, float] | None:
    """
    Return (CTL, ATL) as of `end`, starting from zero.
    Returns None if there is no training data on or before `end`
    (used to distinguish "genuinely zero CTL" from "no data yet").
    """
    if not tss_by_date:
        return None
    start = min(tss_by_date)
    if end < start:
        return None  # end is before any recorded data
    ctl = atl = 0.0
    cur = start
    while cur <= end:
        tss = tss_by_date.get(cur, 0.0)
        ctl = ctl * _CTL_DECAY + tss * (1 - _CTL_DECAY)
        atl = atl * _ATL_DECAY + tss * (1 - _ATL_DECAY)
        cur += timedelta(days=1)
    return ctl, atl
