# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""HR ceilings follow the pacing split.

The split slider on a race plan used to leave every lap's HR ceiling where it
was: the ceilings came from race position alone, so a hard negative split
still capped the first lap as high as an even one.
"""
from app.calculators.race_predictor import compute_hr_ceilings, compute_lap_paces


def test_a_negative_split_caps_hr_lower_early_and_higher_late():
    """Lap 1 of a negative split is run slower, so its ceiling must be lower."""
    even, _ = compute_lap_paces(1800.0, 5000.0, split_spread=0.0, max_hr=190)
    negative, _ = compute_lap_paces(1800.0, 5000.0, split_spread=1.0, max_hr=190)
    assert negative[0]["hr_ceiling"] < even[0]["hr_ceiling"]
    assert negative[-1]["hr_ceiling"] >= even[-1]["hr_ceiling"]


def test_a_ceiling_never_exceeds_max_hr():
    """Dividing a near-max finish by a fast lap's ramp would otherwise pass it."""
    ramp = [1.1, 1.0, 0.9]
    assert max(compute_hr_ceilings(190, 1500.0, 3, ramp)) <= 190


def test_no_ramp_is_the_position_curve_unchanged():
    """Callers without a split (and the existing fixtures) see no change."""
    assert compute_hr_ceilings(180, 10000.0, 5) == compute_hr_ceilings(180, 10000.0, 5, None)
