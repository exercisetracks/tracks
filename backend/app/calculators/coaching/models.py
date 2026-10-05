# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Result dataclasses returned by the coaching engine.

These are the public data shapes consumed by the coaching API layer:
the training-load signal, an individual workout recommendation, and the
top-level coaching result that bundles them with the readiness score.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.calculators.readiness import ReadinessResult


@dataclass
class TrainingSignal:
    ctl: float
    atl: float
    tsb: float
    ctl_ramp: float | None       # CTL[today] − CTL[7 days ago]
    injury_risk_warning: bool    # ramp > 8 pts/week
    phase: str | None = None     # event: base|build|peak|taper; fitness: build|maintain|recovery
    goal_note: str | None = None


@dataclass
class WorkoutRecommendation:
    sport: str
    intensity: str               # rest | walk | easy | aerobic | tempo | threshold | quality
    duration_minutes: int
    distance_km: float | None
    hr_min: int | None           # bpm
    hr_max: int | None           # bpm
    description: str
    reasoning: str
    projected_tss: float
    # ── Recommender v2 fields (additive; defaults keep older cached rows,
    #    which serialise via asdict() and rebuild via WorkoutRecommendation(**r),
    #    reconstructing without error) ──────────────────────────────────────────
    modality: str = "cardio"     # cardio | strength | mobility | rest
    title: str | None = None     # short label, e.g. "Aerobic base", "Lower-body strength"
    focus: str | None = None     # strength split or mobility muscle summary
    situation: str | None = None # matched situation key, e.g. "cardio.big_aerobic_base"


@dataclass
class CoachingResult:
    readiness: ReadinessResult
    signal: TrainingSignal
    recommendations: list[WorkoutRecommendation]
