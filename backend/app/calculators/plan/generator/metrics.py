# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training-load analytics.

Pure scoring helpers exposed for callers (the API layer / plan package) that
want to inspect a generated plan: polarisation balance and Foster monotony /
strain. They have no side effects and are not part of the generation control
flow — week generation enforces its constraints inline.

References
----------
- Seiler, S. (2010); Stöggl, T., & Sperlich, B. (2014). 80/20 polarised model.
- Foster, C. (1998, 2001). Monotony and strain as overtraining predictors.
"""

from __future__ import annotations

import math

from app.calculators.plan.generator.dispatch import _HIGH_INTENSITY_TYPES


def _polarisation_check(template: list[str]) -> tuple[int, int, float]:
    """
    Count low vs high intensity sessions. Return (low_count, high_count, ratio).

    Polarised 80/20: ≥80% low intensity, ≤20% high intensity
    (Seiler 2010; Stöggl & Sperlich 2014).

    Returns ratio: high / total (target: 0.15-0.20 for polarised).
    """
    total = sum(1 for t in template if t != "rest")
    high = sum(1 for t in template if t in _HIGH_INTENSITY_TYPES)
    low = total - high
    ratio = high / max(total, 1)
    return low, high, ratio


def _monotony_scores(weekly_loads: list[float]) -> dict:
    """
    Compute Foster monotony and strain metrics.

    Monotony = mean(daily_load) / std(daily_load)
    Strain = total_weekly_load × monotony

    Interpretation (Foster 1998, 2001):
      Monotony < 1.5 : safe, good variety
      Monotony 1.5-2.0: elevated risk
      Monotony > 2.0 : high risk of illness/overtraining
    """
    if not weekly_loads or len(weekly_loads) < 2:
        return {"monotony": 1.0, "strain": 0, "mean_load": 0, "sessions": len(weekly_loads)}
    mean_load = sum(weekly_loads) / len(weekly_loads)
    if mean_load == 0:
        return {"monotony": 1.0, "strain": 0, "mean_load": 0, "sessions": len(weekly_loads)}
    variance = sum((x - mean_load) ** 2 for x in weekly_loads) / len(weekly_loads)
    sd = math.sqrt(variance) if variance > 0 else 0.01
    monotony = mean_load / sd
    strain = sum(weekly_loads) * monotony
    return {
        "monotony": round(monotony, 2),
        "strain": round(strain, 1),
        "mean_load": round(mean_load, 1),
        "sessions": len(weekly_loads),
    }
