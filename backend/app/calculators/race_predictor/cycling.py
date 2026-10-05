# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Cycling race prediction and pacing (Critical Power model + aerodynamics).

Predicts finish time from FTP via a CP/W' model, solves steady-state speed from
power against drag/rolling/grade, builds per-lap power targets, and derives
per-lap HR ceilings.
"""
from __future__ import annotations

from .formatting import _fmt_pace
from .running import _lap_gradients, _split_ramp

# Standard road cycling aerodynamic defaults (road bike, drops position)
_CDA    = 0.36    # m²  — drag area (rider + bike)
_CRR    = 0.004   # —    rolling resistance coefficient
_RHO    = 1.20    # kg/m³ air density (sea level, ~15°C)
_M_KG   = 78.0    # kg  — typical rider + bike system mass
_G      = 9.81    # m/s²

# W' and CP model constants (Monod & Scherrer 1965, Morton 1996)
# CP ≈ 97% of FTP (FTP itself is ~1-hour sustainable power)
_CP_RATIO    = 0.97
_W_PRIME_KJ  = 22.0   # kJ — typical W' for trained cyclist


def _cycling_speed_at_power(watts: float, gradient: float = 0.0,
                            wind_mps: float = 0.0) -> float:
    """
    Solve for steady-state cycling speed (m/s) given power output.

    Physics: P = P_aero + P_rolling + P_grade
      P_aero    = 0.5 * CdA * ρ * (v + v_wind)² * v
      P_rolling = Crr * m * g * v
      P_grade   = m * g * grade * v

    wind_mps > 0 = headwind, < 0 = tailwind (effective wind speed along direction of travel).
    Solved via bisection since the cubic has no simple closed form.
    """
    if watts <= 0:
        return 0.0
    roll_and_grade = _CRR * _M_KG * _G + _M_KG * _G * gradient

    def net_power(v: float) -> float:
        apparent_v = v + wind_mps
        p_aero     = 0.5 * _CDA * _RHO * apparent_v * abs(apparent_v) * v
        p_other    = roll_and_grade * v
        return p_aero + p_other - watts

    # Bisect between 0.5 m/s (1.8 km/h) and 30 m/s (108 km/h)
    lo, hi = 0.5, 30.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if net_power(mid) < 0:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


def _power_for_speed(speed: float, gradient: float, wind_mps: float) -> float:
    """Steady-state power (W) required to hold `speed` on `gradient` into `wind_mps`."""
    roll_and_g = _CRR * _M_KG * _G + _M_KG * _G * gradient
    apparent_v = speed + wind_mps
    return 0.5 * _CDA * _RHO * apparent_v * abs(apparent_v) * speed + roll_and_g * speed


def _cycling_sustainable_power(ftp: float, duration_sec: float) -> float:
    """
    Mean maximal power sustainable for duration_sec using the Critical Power model.

      MMP(t) = W' / t + CP     (Monod & Scherrer 1965)

    CP ≈ 97% of FTP; W' ≈ 22 kJ for a trained cyclist.
    For very long durations (> 3h) the model under-predicts fatigue — we cap
    the intensity factor at 0.65 × FTP to avoid overestimating endurance output.
    """
    cp = ftp * _CP_RATIO
    mmp = cp + (_W_PRIME_KJ * 1000.0) / duration_sec
    # Cap: no race is ridden at above FTP for extended periods
    return min(mmp, max(ftp * 1.10, cp + (_W_PRIME_KJ * 1000.0) / 60))


def predict_cycling_time_sec(
    ftp: float,
    distance_m: float,
    course_segments: list[dict] | None = None,
    wind_mps: float = 0.0,
) -> float:
    """
    Predict cycling race finish time using Critical Power model + aerodynamics.

    Iterates: guess a time → compute sustainable power for that time →
    compute speed at that power (accounting for grade + wind) →
    recompute time from distance/speed → repeat until convergence.

    For course-aware plans, each segment's grade adjusts the required power.
    Returns finish time in seconds.
    """
    # Initial guess: 40 km/h on flat
    guess = distance_m / (40_000 / 3600)

    for _ in range(40):
        watts = _cycling_sustainable_power(ftp, guess)

        if course_segments:
            # Sum per-segment times, then extrapolate if the course is shorter than the plan
            total_time = 0.0
            for seg in course_segments:
                v = _cycling_speed_at_power(watts, seg["gradient"], wind_mps)
                total_time += seg["distance_m"] / max(v, 0.1)
            course_d = sum(s["distance_m"] for s in course_segments)
            if course_d > 0:
                total_time *= distance_m / course_d
            new_guess = total_time
        else:
            v         = _cycling_speed_at_power(watts, 0.0, wind_mps)
            new_guess = distance_m / max(v, 0.1)

        if abs(new_guess - guess) < 0.5:
            break
        guess = new_guess

    return round(guess, 1)


def compute_cycling_hr_ceilings(max_hr: int, distance_m: float, n_laps: int,
                                ramp: list[float] | None = None) -> list[int]:
    """
    Per-lap HR ceiling for cycling races.

    Cyclists can sustain a higher fraction of HRmax than runners at equivalent
    power outputs because cycling is non-weight-bearing (less muscle damage).
    Additionally, road racing often involves variable effort — climbs push HR
    above threshold while descents allow partial recovery.

    Basis: Coggan & Allen "Training and Racing with a Power Meter" — TT HR
    profiles; confirmed by real-world Ironman and road race data.

    Short TT (<25K, ~30-40min): 88→98% HRmax (very high throughout, small rise)
    Medium TT (40K, ~55-65min): 84→95% HRmax
    Long road (80K, ~2h):        78→90%
    Century (160K, ~4h):         70→83%
    Ultra (200K+, >5h):          62→78%

    `ramp` (optional) is the split's per-lap pace multiplier from
    `_split_ramp` — above 1 on a slower lap. HR tracks speed roughly in
    proportion over the aerobic range, so each lap's ceiling is divided by
    its ramp: a negative split caps HR lower early and higher late, and the
    race average is unchanged because the ramp averages 1. Capped at max HR.
    Without it the profile is the position-only curve, as before.
    """
    if   distance_m <=  25_000: start_pct, end_pct = 0.88, 0.98
    elif distance_m <=  50_000: start_pct, end_pct = 0.84, 0.95
    elif distance_m <=  90_000: start_pct, end_pct = 0.78, 0.90
    elif distance_m <= 160_000: start_pct, end_pct = 0.70, 0.83
    else:                       start_pct, end_pct = 0.62, 0.78

    ceilings = []
    for i in range(n_laps):
        pos = i / max(n_laps - 1, 1)
        pct = start_pct + (end_pct - start_pct) * (pos ** 1.5)
        if ramp:
            ceilings.append(min(max_hr, round(pct * max_hr / ramp[i])))
        else:
            ceilings.append(round(pct * max_hr))
    return ceilings


def compute_cycling_lap_targets(
    ftp: float,
    distance_m: float,
    split_spread: float = 0.0,
    lap_km: float = 1.0,
    course_segments: list[dict] | None = None,
    max_hr: int | None = None,
    predicted_sec: float | None = None,
    wind_mps: float = 0.0,
) -> tuple[list[dict], float]:
    """
    Compute per-lap power targets (watts + % FTP) for a cycling race.

    Mirrors compute_lap_paces() for running but uses watts as the primary target.
    Grade adjustment uses cycling physics — hills require more power, not just
    a different pace-per-km.

    Returns (laps, actual_total_sec).
    """
    n_full = int(distance_m / (lap_km * 1000))
    last_m = distance_m - n_full * lap_km * 1000
    n_laps = n_full + (1 if last_m > 10 else 0)
    lap_dists = [
        (last_m if (i == n_full and last_m > 10) else lap_km * 1000)
        for i in range(n_laps)
    ]

    # Base race time
    if predicted_sec is None:
        predicted_sec = predict_cycling_time_sec(ftp, distance_m, course_segments, wind_mps)

    # Mean maximal power for the full race duration
    base_watts = _cycling_sustainable_power(ftp, predicted_sec)

    # Per-lap grade from GPX
    lap_gradients: list[float] = [0.0] * n_laps
    if course_segments:
        lap_gradients = _lap_gradients(lap_dists, course_segments)

    # Split ramp (same convention as compute_lap_paces)
    ramp = _split_ramp(n_laps, split_spread)

    hr_ceilings = compute_cycling_hr_ceilings(max_hr, distance_m, n_laps, ramp) if max_hr else None

    laps: list[dict] = []
    cum_km    = 0.0
    total_sec = 0.0
    for i, (lap_dist, grad, r) in enumerate(zip(lap_dists, lap_gradients, ramp)):
        # Equal-effort target: ride this grade at the same speed as flat riding at
        # the ramp-scaled base power, then back out the power that holds that speed.
        flat_speed = _cycling_speed_at_power(base_watts * r, 0.0, wind_mps)
        target_w   = max(10.0, round(_power_for_speed(flat_speed, grad, wind_mps)))
        pct_ftp    = round(target_w / ftp * 100)

        lap_speed  = _cycling_speed_at_power(target_w, grad, wind_mps)
        lap_sec    = lap_dist / max(lap_speed, 0.1)
        total_sec += lap_sec
        cum_km    += lap_dist / 1000.0

        # Flat-equivalent (no-grade) time for pace comparison
        flat_speed_kg = _cycling_speed_at_power(target_w, 0.0, wind_mps)

        laps.append({
            "lap":               i + 1,
            "distance_m":        round(lap_dist),
            "target_watts":      int(target_w),
            "target_watts_pct_ftp": pct_ftp,
            # Keep pace fields so the schema stays compatible; derived from speed
            "target_sec_per_km": round(1000 / max(lap_speed, 0.01), 1),
            "target_pace":       _fmt_pace(1000 / max(lap_speed, 0.01)),
            "gradient":          round(grad, 4),
            "grade_multiplier":  1.0,   # not used for cycling
            "grade_adj_sec":     round(1000 / max(flat_speed_kg, 0.01), 1),
            "grade_adj_pace":    _fmt_pace(1000 / max(flat_speed_kg, 0.01)),
            "cumulative_km":     round(cum_km, 2),
            "hr_ceiling":        hr_ceilings[i] if hr_ceilings else None,
        })

    return laps, round(total_sec, 1)
