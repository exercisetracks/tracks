# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Public entry points for the coaching engine (the no-goal recommender).

Decision flow
─────────────
1.  Build the training signal (CTL/ATL/TSB, ramp rate, injury warning).
2.  Build a RecommenderContext: load/readiness + modality-balance + recency/rest
    signals from the activity and in-app session history (``context.py``).
3.  Match it against the situation catalogue and pick a hero plus alternates of
    different modalities, each with rendered coaching copy (``situations.py`` /
    ``select.py`` / ``library.py``).

Goal/periodisation does not steer the choice here: when a goal plan is active the
scheduled ``plan/`` system owns the dashboard slot instead of this recommender.

`compute_recommendations` produces today's options; `compute_weekly_plan`
projects 7 days forward by feeding each day's recommended TSS back into the
load model.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Sequence

from app.calculators.coaching.context import build_context
from app.calculators.coaching.models import CoachingResult
from app.calculators.coaching.select import select_recommendations
from app.calculators.coaching.signal import _build_signal, _compute_ctl_atl
from app.calculators.readiness import ReadinessResult


def compute_recommendations(
    readiness_result: ReadinessResult,
    ctl: float,
    atl: float,
    ctl_7d_ago: float | None,
    activity_history: Sequence,      # Activity rows from last 90 days
    goal,                            # TrainingGoal ORM row or None
    threshold_hr: float | None,
    today: date,
    n_recommendations: int = 3,
    strength_session_dates: Sequence[date] | None = None,
) -> CoachingResult:
    """
    Compute today's workout recommendations.

    Builds a RecommenderContext from the load signal and activity/session
    history, matches it against the situation catalogue, and returns a hero
    recommendation plus alternates of *different* modalities (cardio / strength /
    mobility / rest). Goal/periodisation intentionally does not steer the choice:
    when a goal plan is active the scheduled ``plan/`` system owns this slot, and
    this recommender is the no-plan experience.

    Args:
        readiness_result: output from compute_readiness()
        ctl, atl:         today's chronic/acute training load
        ctl_7d_ago:       CTL from 7 days ago (for ramp rate)
        activity_history: recent Activity rows for modality/sport signals
        goal:             active TrainingGoal or None (feeds the signal only)
        threshold_hr:     LTHR in bpm (for HR zone targets)
        today:            date to compute for
        n_recommendations: how many distinct options to return (hero + alternates)
        strength_session_dates: dates of in-app logged strength sessions, folded
                          into modality-balance signals alongside activities
    """
    signal = _build_signal(ctl, atl, ctl_7d_ago, goal, today)

    ctx = build_context(
        readiness_score=readiness_result.score,
        readiness_confidence=readiness_result.confidence,
        ctl=ctl,
        atl=atl,
        tsb=signal.tsb,
        ctl_ramp=signal.ctl_ramp,
        injury_risk=signal.injury_risk_warning,
        activity_history=activity_history,
        strength_session_dates=strength_session_dates,
        threshold_hr=threshold_hr,
        today=today,
    )

    recommendations = select_recommendations(ctx, n=n_recommendations, seed=today.isoformat())

    return CoachingResult(
        readiness=readiness_result,
        signal=signal,
        recommendations=recommendations,
    )


def compute_weekly_plan(
    readiness_result: ReadinessResult,
    tss_by_date: dict[date, float],
    activity_history: Sequence,
    goal,
    threshold_hr: float | None,
    today: date,
) -> list[dict]:
    """
    Project 7 days of recommendations.

    Each projected day recomputes CTL/ATL from the accumulated TSS including
    the recommended TSS from prior projected days.
    """
    projected_tss = dict(tss_by_date)  # copy
    plan = []

    for i in range(7):
        day = today + timedelta(days=i)
        load = _compute_ctl_atl(projected_tss, day)
        ctl, atl = load if load is not None else (0.0, 0.0)
        load_7d = _compute_ctl_atl(projected_tss, day - timedelta(days=7))
        ctl_7d = load_7d[0] if load_7d is not None else None

        result = compute_recommendations(
            readiness_result=readiness_result,
            ctl=ctl,
            atl=atl,
            ctl_7d_ago=ctl_7d,
            activity_history=activity_history,
            goal=goal,
            threshold_hr=threshold_hr,
            today=day,
            n_recommendations=1,
        )

        rec = result.recommendations[0] if result.recommendations else None
        plan.append({
            "date":        day,
            "ctl":         round(ctl, 1),
            "atl":         round(atl, 1),
            "tsb":         round(ctl - atl, 1),
            "recommendation": rec,
        })

        # Add projected TSS to the running total
        if rec:
            projected_tss[day] = projected_tss.get(day, 0.0) + rec.projected_tss

    return plan
