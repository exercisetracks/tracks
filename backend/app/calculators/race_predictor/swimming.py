# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Swimming race prediction from CSS (Critical Swim Speed, Wakayoshi et al. 1993).

CSS is the swimming analogue of lactate-threshold pace. Intensity relative to
CSS is scaled by distance, with an open-water penalty for navigation/waves/turns.
"""
from __future__ import annotations


def predict_swim_time_sec(css_sec_per_100m: float, distance_m: float,
                          open_water: bool = False) -> float:
    """
    Predict swimming race finish time from CSS (Critical Swim Speed).

    CSS is the swimming equivalent of lactate threshold pace — the fastest
    speed sustainable for longer efforts (Wakayoshi et al. 1993).

    Intensity relative to CSS scales with distance:
    - Very short (< 400m): faster than CSS (sprint / anaerobic contribution)
    - Middle-distance (400-1500m): at or slightly above CSS
    - Long (> 1500m): CSS to slightly below (fatigue accumulates)

    Open water adds ~7% for navigation inefficiency, waves, and lack of
    push-off turns (Toussaint et al. 2002).

    Returns predicted seconds.
    """
    if   distance_m <=  200: intensity = 0.88
    elif distance_m <=  400: intensity = 0.92
    elif distance_m <=  800: intensity = 0.96
    elif distance_m <= 1500: intensity = 0.99
    elif distance_m <= 2000: intensity = 1.01
    elif distance_m <= 4000: intensity = 1.03
    else:                    intensity = 1.06

    predicted_sec = (distance_m / 100.0) * css_sec_per_100m * intensity
    if open_water:
        predicted_sec *= 1.07
    return round(predicted_sec, 1)
