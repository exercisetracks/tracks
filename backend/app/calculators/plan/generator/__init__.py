# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training plan generator — main orchestration.

Coordinates phases, volume, templates, and workout builders into a complete
day-by-day training plan. Handles the volume-intensity coupling constraint:
intensity and volume must NOT climb simultaneously (Foster 2001: monotony
>2.0 strongly predicts illness and overtraining; Gabbett 2016: ACWR >1.5
increases injury risk).

This package splits the generator into loosely-coupled concerns; everything the
rest of the app imports stays importable from ``app.calculators.plan.generator``:

  - ``dispatch``  — ``_build_steps`` workout-builder routing + intensity sets
  - ``templates`` — ``_rotating_template`` weekly template + rotation for variety
  - ``metrics``   — ``_polarisation_check`` / ``_monotony_scores`` analytics
  - ``week``      — ``_generate_week`` single-week assembly + polarisation enforcement
  - ``plan``      — ``generate_training_plan`` cross-week scheduling orchestrator
  - ``fitness``   — ``generate_fitness_plan`` rolling 4-week plan for a fitness
                    goal (a CTL ramp instead of a race), built from ``week``

Block periodisation (Issurin 2010):
  - Accumulation (base):    High volume, low intensity. Volume ramps 10%/wk.
  - Transmutation (build):  Moderate volume, increasing intensity. Volume plateaus.
  - Realisation (peak):     Lower volume, peak intensity. Race-specific quality.
  - Taper:                  Decreasing volume, maintained frequency + intensity.

Polarised 80/20 enforcement (Seiler 2010, Stöggl & Sperlich 2014):
  - ≥80% of weekly sessions at low intensity (easy, recovery, endurance, skills)
  - ≤20% at high intensity (intervals, tempo, race-pace, threshold, VO₂max)
  - Minimal threshold/moderate intensity zone (the "black hole")
  - When weekly volume increases, all incremental volume goes to low-intensity sessions

Volume-intensity coupling constraint:
  - Base:       volume ≤ week×1.10, quality days ≤ 1/week
  - Build:      volume ≤ base_peak × 1.05, quality days ≤ 2/week
  - Peak:       volume ≤ base_peak × 0.90, quality days ≤ 2/week
  - Taper:      volume decreases exponentially per Bosquet et al. (2007)
  - A quality type is never introduced in the same week volume increases >5%
  - When ACWR would exceed 1.3, volume progression is capped

References
----------
- Foster, C., et al. (2001). A new approach to monitoring exercise training.
  *J Strength Cond Res*, 15(1), 109-115. TRIMP, monotony, strain.
- Gabbett, T. J. (2016). The training-injury prevention paradox.
  *Br J Sports Med*, 50(5), 273-280. ACWR >1.5 = significant injury risk.
- Issurin, V. B. (2010). New horizons for the methodology and physiology
  of training periodization. *Sports Med*, 40(3), 189-206. Block model.
- Bosquet, L., et al. (2007). Effects of tapering on performance:
  a meta-analysis. *Med Sci Sports Exerc*, 39(8), 1358-1365.
  Optimal taper: 2 weeks, exponential decrease to 40-60%, maintain intensity.
- Seiler, S. (2010). What is best practice for training intensity and
  duration distribution in endurance athletes? *Int J Sports Physiol
  Perform*, 5(3), 276-291. 80/20 polarised distribution.
- Stöggl, T., & Sperlich, B. (2014). Polarized training has greater impact.
  *Front Physiol*, 5, 33. POL: +11.7% VO₂max vs HIIT-only: +4.8%.
- Damsted, C., et al. (2018). Sudden increases >30% in weekly running
  volume associated with higher injury risk. *Br J Sports Med*, 52(15).
"""

from __future__ import annotations

from app.calculators.plan.generator.dispatch import (
    _HIGH_INTENSITY_TYPES,
    _LOW_INTENSITY_TYPES,
    _build_steps,
)
from app.calculators.plan.generator.metrics import (
    _monotony_scores,
    _polarisation_check,
)
from app.calculators.plan.generator.fitness import (
    fitness_horizon_end,
    fitness_week_targets,
    generate_fitness_plan,
)
from app.calculators.plan.generator.plan import generate_training_plan
from app.calculators.plan.generator.templates import _rotating_template
from app.calculators.plan.generator.week import _generate_week

__all__ = [
    "generate_training_plan",
    "generate_fitness_plan",
    "fitness_week_targets",
    "fitness_horizon_end",
    "_build_steps",
    "_generate_week",
    "_rotating_template",
    "_polarisation_check",
    "_monotony_scores",
    "_LOW_INTENSITY_TYPES",
    "_HIGH_INTENSITY_TYPES",
]
