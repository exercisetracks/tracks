# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Grade-adjusted running pace (Minetti et al. 2002).

Converts a slope (rise/run) into a pace multiplier so a runner expends equal
effort regardless of terrain. Uphills use Minetti's running cost directly;
downhills take half of its benefit and add a braking penalty on steep grades
(it ignores eccentric quad loading).

Until 2026-11 this was fed Minetti's *walking* polynomial (280.5 … 19.6,
2.5 J/kg/m on the flat) under the running curve's name, which priced every
climb about a third dearer than his runners measured: 1.44× on a 5 % climb
against 1.30×, 1.96× on 10 % against 1.66×. hr_review.py already used the
running one. The running curve is also the one the field agrees with where
it can be checked: the ACSM treadmill equation gives 1.23× on 5 %, Strava's
heart-rate model about 1.18×, and Mount Washington's course record (12.2 km
at 12 %, 4:39/km) is roughly 1.7× a world-class runner's flat pace over the
distance — a rough estimate — against Minetti's 1.81×. Heart rate
lags a climb and a short one is run above threshold, which is why the
field data sit a little under the metabolic curve; equal metabolic effort is
what a race plan paces, so it keeps Minetti's.

The downhill was calibrated against Strava's data (much less benefit than
Minetti's −10 % runner, whose model overstated downhill race speeds threefold
in his own paper) and is kept: half the running curve's benefit is what 35 %
of the walking curve's was, within 0.03 down to −15 %, and a few hundredths
kinder on the steeper grades, where the braking term decides either way.
"""
from __future__ import annotations

_FLAT_COST = 3.6  # J/kg/m at zero grade, the running polynomial's own


def _minetti_cost(g: float) -> float:
    """Running's metabolic cost J/kg/m at gradient g (rise/run), clamped ±45%."""
    g = max(-0.45, min(0.45, g))
    return (155.4 * g**5 - 30.4 * g**4 - 43.3 * g**3
            + 46.3 * g**2 + 19.5 * g + _FLAT_COST)


_DOWNHILL_BENEFIT_SCALE = 0.50   # fraction of Minetti's theoretical downhill benefit used
_BRAKING_ONSET          = -0.10  # grade where braking penalty starts
_BRAKING_RATE           = 2.50   # cost added per unit of grade beyond onset


def grade_cost_multiplier(gradient: float) -> float:
    """
    Multiplier for target pace to maintain equal effort on the given slope.

    Uphills:  pure Minetti 2002 running (treadmill VO2; see the module notes).
      +2 % → 1.11 ×   +5 % → 1.30 ×   +10 % → 1.66 ×   +15 % → 2.06 ×
    Downhills: Minetti significantly overestimates benefit because it ignores
      braking mechanics and eccentric quad loading.  We use half of the
      theoretical benefit for mild grades, then add a braking-cost penalty
      that grows linearly beyond -10 % grade.  This produces:
        -5 %  →  ~12 % faster than flat   (realistic road race)
        -10 % →  ~20 % faster             (near peak benefit)
        -15 % →  ~12 % faster             (braking eating the gain)
        -20 % →  even with flat           (the turning point)
        -25 % →  ~16 % slower             (very steep technical)
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
