# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Grade-adjusted running pace (Minetti et al. 2002).

Converts a slope (rise/run) into a pace multiplier so a runner expends equal
effort regardless of terrain. Uphills use Minetti directly; downhills scale
back Minetti's over-optimistic benefit and add a braking penalty on steep
grades (it ignores eccentric quad loading).
"""
from __future__ import annotations

_FLAT_COST = 2.5  # J/kg/m at zero grade


def _minetti_cost(g: float) -> float:
    """Metabolic cost J/kg/m for running at gradient g (rise/run), clamped ±45%."""
    g = max(-0.45, min(0.45, g))
    return (280.5 * g**5 - 58.7 * g**4 - 76.8 * g**3
            + 51.9 * g**2 + 19.6 * g + _FLAT_COST)


_DOWNHILL_BENEFIT_SCALE = 0.35   # fraction of Minetti's theoretical downhill benefit used
_BRAKING_ONSET          = -0.10  # grade where braking penalty starts
_BRAKING_RATE           = 2.50   # cost added per unit of grade beyond onset


def grade_cost_multiplier(gradient: float) -> float:
    """
    Multiplier for target pace to maintain equal effort on the given slope.

    Uphills:  pure Minetti 2002 (well-validated by treadmill studies).
    Downhills: Minetti significantly overestimates benefit because it ignores
      braking mechanics and eccentric quad loading.  We use 35 % of the
      theoretical benefit for mild grades, then add a braking-cost penalty
      that grows linearly beyond -10 % grade.  This produces:
        -5 %  →  ~15 % faster than flat   (realistic road race)
        -10 % →  ~20 % faster             (near peak benefit)
        -15 % →  ~10 % faster             (braking eating the gain)
        -20 % →  ~2 % slower              (turning point ≈ -19 %)
        -25 % →  ~20 % slower             (very steep technical)
    """
    g    = max(-0.45, min(0.45, gradient))
    cost = _minetti_cost(g)

    if g >= 0:
        # Uphills: Minetti as-is; clamp extreme values to stay physiologically feasible
        return min(3.0, max(0.5, cost / _FLAT_COST))

    # Downhills: scale back theoretical benefit and add braking penalty
    minetti_benefit = 1.0 - cost / _FLAT_COST          # > 0 because downhill is cheaper
    scaled_mult     = 1.0 - minetti_benefit * _DOWNHILL_BENEFIT_SCALE

    if g < _BRAKING_ONSET:
        scaled_mult += abs(g - _BRAKING_ONSET) * _BRAKING_RATE

    return max(0.70, scaled_mult)                       # never faster than ~43 % above flat


def grade_adjustment_factor(gradient: float) -> float:
    """
    GAP display factor: flat_cost / grade_cost.

    Multiply ACTUAL pace by this to get the flat-equivalent effort pace (GAP).
    grade_cost_multiplier() is the inverse — use it for computing target paces.
    """
    return 1.0 / grade_cost_multiplier(gradient)
