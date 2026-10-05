# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Strength training plan generator (package).

Split out of a single ~1440-line module into per-concern files. Every public
name that was importable as `app.calculators.strength_plan.<name>` is re-exported
here, so external callers (api.training_plan.matching / injectors, and any test)
keep working unchanged.

Modules:
  loads.py          1RM estimation, %-1RM targets, gym-realistic weight rounding
  injuries.py       active-injury → exercise exclusion + load-reduction logic
  slots.py          session movement-slot taxonomy (squat/hinge/push/pull/…)
  exercises.py      slot-based, relevance-ranked per-session exercise picker
  periodization.py  reps/%-1RM prescriptions, rest tables, session duration
  mobility.py       library-driven weekly mobility session builder
  generator.py      the orchestrator: builds dated strength + mobility workouts

Science basis (see NOTES.md — Strength Training Feature section):
  - Pelland et al. 2024 meta-regression (67 studies): 10–20 sets/muscle/week
  - Sport-specific meta-analyses (running heavy+plyo ES -1.035; cycling ES 0.463;
    climbing dead-hang ES 1.23, RFD ES 0.91)
  - Concurrent training: strength BEFORE endurance; ≥6h separation for hard cardio
  - Progressive overload: Epley + Brzycki + Wathan 1RM average (±5% for 2–10 reps)
  - Periodization: linear (<20 sessions) → weekly undulating → DUP
  - Deload every 4–6 weeks; injuries from the health page drive exercise exclusions
"""

from .generator import generate_strength_workouts
from .loads import (
    conservative_starting_weight,
    estimate_1rm,
    round_weight_for_equipment,
    training_weight,
)

__all__ = [
    "estimate_1rm",
    "training_weight",
    "round_weight_for_equipment",
    "conservative_starting_weight",
    "generate_strength_workouts",
]
