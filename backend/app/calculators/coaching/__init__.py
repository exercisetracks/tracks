# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Rule-based coaching engine.

Given today's readiness, training load (CTL/ATL/TSB), sport history, and an
optional training goal it produces 1–3 concrete workout recommendations and a
7-day forward plan.

Package layout
──────────────
- tables.py          calibration constants and lookup tables (pure data)
- models.py          result dataclasses (TrainingSignal, WorkoutRecommendation,
                     CoachingResult)
- selection.py       intensity-tier, sport-ranking, HR-range and duration logic
- recommendation.py  description/reasoning text + single-recommendation builder
- signal.py          goal/periodisation adjustments and CTL/ATL load math
- engine.py          public entry points (compute_recommendations / weekly_plan)

Everything importable from the old flat module remains importable as
`app.calculators.coaching.<name>`.
"""

from __future__ import annotations

from app.calculators.coaching.engine import (
    compute_recommendations,
    compute_weekly_plan,
)
from app.calculators.coaching.models import (
    CoachingResult,
    TrainingSignal,
    WorkoutRecommendation,
)

__all__ = [
    "CoachingResult",
    "TrainingSignal",
    "WorkoutRecommendation",
    "compute_recommendations",
    "compute_weekly_plan",
]
