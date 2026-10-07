# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Running race prediction and pacing (VDOT / Daniels Running Formula, 3rd ed.).

Predicts finish time from VDOT (with a training-volume correction in the
marathon), builds per-lap HR ceilings matched to race distance, and computes
grade-aware km-by-km pace targets (equal-effort splits via the Minetti cost
multiplier).
"""
from __future__ import annotations

import math

from app.calculators.training_plan import calculate_vdot

from .formatting import _fmt_pace
from .grade import grade_cost_multiplier

# ±8% pace difference between first and last lap at |split_spread| = 1.0.
# Shared with the cycling lap-target ramp (same split convention).
_MAX_SPLIT_SPREAD = 0.08


def predict_race_time_sec(vdot: float, distance_m: float) -> float:
    """
    Binary search for the finish time T (seconds) where calculate_vdot(distance_m, T) == vdot.

    The Daniels VO2 / utilization model is monotonically decreasing in T for a
    fixed distance, so bisection converges quickly (60 iterations gives < 0.01s precision).
    """
    lo = distance_m / 12.0    # very fast upper bound (12 m/s = 43 km/h)
    hi = distance_m * 6.0     # very slow lower bound (6 sec/m)
    for _ in range(60):
        mid = (lo + hi) / 2
        if calculate_vdot(distance_m, mid) > vdot:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


# ── Marathon: the training-volume correction ─────────────────────────────────
#
# VDOT carries a fixed endurance curve, so a VDOT set by a 5 K predicts the
# marathon a runner would run with the volume behind them that Daniels'
# runners had. Recreational runners mostly do not have it, and race formulas
# of this kind are well calibrated up to the half marathon but at least ten
# minutes too fast in the marathon for half of recreational runners; weekly
# mileage is what corrects most of that (Vickers & Vertosick 2016, n=2,303).
#
# Tanda (2011) gives the marathon from exactly the data the app holds — mean
# weekly distance K and mean training pace P over the 8 weeks before:
#
#     marathon pace (s/km) = 17.1 + 140·exp(−0.0053·K) + 0.55·P
#
# (SEE ≈ 4 min, in 2:47–3:36 marathoners). Its own follow-up (Tanda 2022,
# J Hum Sport Exerc) found it wants individual correction for faster runners,
# and Vickers et al. found the pure race extrapolation too fast, so neither is
# used alone: the marathon is the mean of the two — a heuristic, unverified,
# chosen because the two err in opposite directions. Half marathons and
# shorter keep the VDOT prediction, which is calibrated there; ultras have no
# comparable model and keep it too.
MARATHON_BAND_M = (40_000.0, 44_000.0)
TRAINING_WINDOW_DAYS = 56
# A history that starts partway through the window reads as low volume.
_MIN_WEEKS_WITH_RUNS = 6


def tanda_marathon_sec(weekly_km: float, mean_pace_sec_km: float,
                       distance_m: float = 42_195.0) -> float:
    """Tanda (2011): marathon time from mean weekly km and mean training pace."""
    pace = 17.1 + 140.0 * math.exp(-0.0053 * weekly_km) + 0.55 * mean_pace_sec_km
    return pace * distance_m / 1000.0


def training_indices(runs: list[dict], today) -> tuple[float, float] | None:
    """(mean weekly km, mean pace s/km) over the 8 weeks before ``today``, from
    running activities as running_fitness.estimate_running_fitness takes them;
    None unless runs fall in at least six of the eight weeks."""
    km = sec = 0.0
    weeks = set()
    for r in runs:
        age = (today - r["date"]).days
        if not 0 <= age < TRAINING_WINDOW_DAYS:
            continue
        dist, dur = r.get("distance_m"), r.get("duration_s")
        if not dist or not dur or dist <= 0 or dur <= 0:
            continue
        km += dist / 1000.0
        sec += dur
        weeks.add(age // 7)
    if len(weeks) < _MIN_WEEKS_WITH_RUNS or km <= 0:
        return None
    return km / (TRAINING_WINDOW_DAYS / 7), sec / km


def predict_running_race_sec(vdot: float, distance_m: float,
                             indices: tuple[float, float] | None = None,
                             freshness: float = 1.0) -> float:
    """Finish time on a flat, neutral day: VDOT's, corrected by training volume
    in the marathon (see above) when the training indices are known.

    ``vdot`` arrives already scaled for race-day freshness (the race plan's TSB
    factor); ``freshness`` is that same factor as a time multiplier, so the
    volume model's half of a marathon is scaled with it."""
    sec = predict_race_time_sec(vdot, distance_m)
    if indices is not None and MARATHON_BAND_M[0] <= distance_m <= MARATHON_BAND_M[1]:
        sec = (sec + tanda_marathon_sec(indices[0], indices[1], distance_m) * freshness) / 2
    return sec


def compute_hr_ceilings(max_hr: int, distance_m: float, n_laps: int,
                        ramp: list[float] | None = None) -> list[int]:
    """
    Per-lap HR ceiling profile matched to race distance and position in the race.

    Science basis:
    - Short races (≤5 K): near-maximal effort throughout; start high (Billat et al. 2001, 2003).
    - Long races: start conservative below lactate threshold, build through race (Noakes,
      Daniels Running Formula 3rd ed.).
    - Final 10–20% of any race: finishing push toward VO2max / HRmax effort.
    - Power curve (pos^1.5) keeps HR low early and rises sharply in the final third.

    `ramp` (optional) is the split's per-lap pace multiplier from
    `_split_ramp` — above 1 on a slower lap. HR tracks speed roughly in
    proportion over the aerobic range, so each lap's ceiling is divided by
    its ramp: a negative split caps HR lower early and higher late, and the
    race average is unchanged because the ramp averages 1. Capped at max HR.
    Without it the profile is the position-only curve, as before.
    """
    if   distance_m <=  2_000: start_pct, end_pct = 0.90, 1.00
    elif distance_m <=  5_000: start_pct, end_pct = 0.82, 0.97
    elif distance_m <= 10_000: start_pct, end_pct = 0.78, 0.95
    elif distance_m <= 21_097: start_pct, end_pct = 0.75, 0.92
    elif distance_m <= 42_195: start_pct, end_pct = 0.70, 0.87
    else:                      start_pct, end_pct = 0.62, 0.80

    ceilings = []
    for i in range(n_laps):
        pos = i / max(n_laps - 1, 1)
        pct = start_pct + (end_pct - start_pct) * (pos ** 1.5)
        if ramp:
            ceilings.append(min(max_hr, round(pct * max_hr / ramp[i])))
        else:
            ceilings.append(round(pct * max_hr))
    return ceilings


def compute_lap_paces(
    predicted_sec: float,
    distance_m: float,
    split_spread: float = 0.0,
    lap_km: float = 1.0,
    course_segments: list[dict] | None = None,
    max_hr: int | None = None,
    terrain: bool = False,
) -> tuple[list[dict], float]:
    """
    Compute per-lap target paces for a race, correctly adjusting for grade.

    split_spread: −1.0 (positive split — fast start, fade) … 0 (even) … +1.0 (negative split).
    The maximum spread at |split_spread|=1.0 is ±8% between first and last lap.

    Grade handling (when course_segments are provided):
      - Each lap's target pace is scaled by the Minetti cost multiplier so that
        the runner expends equal effort regardless of terrain.
      - The total predicted time is adjusted by the course's mean cost multiplier
        relative to flat (so a hilly course predicts a longer finish time).

    With ``terrain`` and a course, the splits are the course's terrain
    (calculators/race_predictor/terrain.py) rather than one per ``lap_km``:
    each lap is a stretch of one kind of ground and carries ``kind``,
    ``label`` and ``start_km``, and the split ramp runs over distance rather
    than lap count, since the laps are no longer equal.

    Returns (laps, actual_total_sec).
      actual_total_sec includes weather + grade adjustments (not just VDOT flat prediction).
    """
    kinds: list[str] | None = None
    if terrain and course_segments:
        from .terrain import terrain_segments
        segs = terrain_segments(course_segments, distance_m)
        lap_dists = [s["distance_m"] for s in segs]
        n_laps = len(lap_dists)
        kinds = [s["kind"] for s in segs]
    else:
        n_full = int(distance_m / (lap_km * 1000))
        last_m = distance_m - n_full * lap_km * 1000
        n_laps = n_full + (1 if last_m > 10 else 0)

        lap_dists = [
            (last_m if (i == n_full and last_m > 10) else lap_km * 1000)
            for i in range(n_laps)
        ]

    # ── Per-lap grade multiplier from GPX segments ────────────────────────────
    lap_multipliers: list[float] = [1.0] * n_laps
    lap_gradients:   list[float] = [0.0] * n_laps
    if kinds is not None:
        for lap_i, s in enumerate(segs):
            lap_gradients[lap_i]   = s["gradient"]
            lap_multipliers[lap_i] = grade_cost_multiplier(s["gradient"])
    elif course_segments:
        for lap_i, grad in enumerate(_lap_gradients(lap_dists, course_segments)):
            lap_gradients[lap_i]   = grad
            lap_multipliers[lap_i] = grade_cost_multiplier(grad)

    # ── Adjust total time for course grade ───────────────────────────────────
    # weighted_dist = sum(d_i * m_i): flat-equivalent total distance in metres
    weighted_dist = sum(d * m for d, m in zip(lap_dists, lap_multipliers))
    # Without grade the weighted_dist == total_distance; with hills it's larger
    grade_time_factor = weighted_dist / distance_m   # > 1 on net uphill, ≈ 1 loop
    actual_total_sec  = predicted_sec * grade_time_factor

    # ── Split ramp (applied to equal-effort base) ─────────────────────────────
    # slope < 0 → pace decreases over laps → negative split (faster finish)
    # split_spread > 0 → negative split → slope < 0 ✓
    ramp = (_split_ramp_by_distance(lap_dists, split_spread) if kinds is not None
            else _split_ramp(n_laps, split_spread))

    # ── Base flat pace so that sum(d_i * m_i * ramp_i) == actual_total_sec ───
    # target_pace_i = base_flat * m_i * ramp_i
    # total_time = sum(d_i / 1000 * base_flat * m_i * ramp_i)
    #            = base_flat * sum(d_i * m_i * ramp_i) / 1000 = actual_total_sec
    denominator = sum(d * m * r for d, m, r in zip(lap_dists, lap_multipliers, ramp))
    base_flat   = actual_total_sec * 1000.0 / denominator  # sec/km on equivalent flat

    hr_ceilings = compute_hr_ceilings(max_hr, distance_m, n_laps, ramp) if max_hr else None

    laps: list[dict] = []
    cum_km = 0.0
    for i, (lap_dist, grad, mult, r) in enumerate(
        zip(lap_dists, lap_gradients, lap_multipliers, ramp)
    ):
        target_pace = base_flat * mult * r          # actual sec/km on this slope
        gap_pace    = base_flat * r                  # flat-equivalent effort (GAP)
        start_km    = cum_km
        cum_km     += lap_dist / 1000.0
        if kinds is not None and i == n_laps - 1:
            # Terrain pieces are fractions of the course, so their sum can sit
            # a hair either side of it; the finish is the distance, exactly
            # (the phone's port rounds the same value the same way).
            cum_km = distance_m / 1000.0
        extra = {}
        if kinds is not None:
            from .terrain import label as _terrain_label
            extra = {"kind": kinds[i], "label": _terrain_label(kinds[i], grad),
                     "start_km": round(start_km, 3)}
        laps.append({
            **extra,
            "lap":               i + 1,
            "distance_m":        round(lap_dist),
            "target_sec_per_km": round(target_pace, 1),
            "target_pace":       _fmt_pace(target_pace),
            "gradient":          round(grad, 4),
            "grade_multiplier":  round(mult, 4),   # used by frontend for realtime split recompute
            "grade_adj_sec":     round(gap_pace, 1),
            "grade_adj_pace":    _fmt_pace(gap_pace),
            "cumulative_km":     round(cum_km, 2),
            "hr_ceiling":        hr_ceilings[i] if hr_ceilings else None,
        })

    return laps, round(actual_total_sec, 1)


def _split_ramp(n_laps: int, split_spread: float) -> list[float]:
    """
    Per-lap pace ramp around 1.0 implementing the split-spread convention.

    split_spread > 0 → negative split (slope < 0, faster finish); the spread
    between first and last lap reaches ±8% at |split_spread| = 1.0.
    """
    mid   = (n_laps - 1) / 2.0
    slope = -split_spread * _MAX_SPLIT_SPREAD * 2.0 / max(n_laps - 1, 1)
    return [1.0 + slope * (i - mid) for i in range(n_laps)]


def _split_ramp_by_distance(lap_dists: list[float], split_spread: float) -> list[float]:
    """``_split_ramp`` for unequal laps: the same first-to-last spread, but
    placed by where each lap's middle falls on the course, so a long flat and
    a short climb next to each other get nearly the same factor."""
    total = sum(lap_dists)
    if total <= 0:
        return [1.0] * len(lap_dists)
    slope = -split_spread * _MAX_SPLIT_SPREAD * 2.0
    out, start = [], 0.0
    for d in lap_dists:
        out.append(1.0 + slope * ((start + d / 2) / total - 0.5))
        start += d
    return out


def _lap_gradients(lap_dists: list[float], course_segments: list[dict]) -> list[float]:
    """
    Distance-weighted mean gradient for each lap, walking the GPX segments in order.

    Segments are consumed left-to-right across laps so each metre of course
    contributes to exactly one lap.
    """
    gradients: list[float] = []
    seg_cursor   = 0
    seg_consumed = 0.0
    for lap_dist in lap_dists:
        lap_gain  = 0.0
        remaining = lap_dist
        while remaining > 0 and seg_cursor < len(course_segments):
            seg   = course_segments[seg_cursor]
            avail = seg["distance_m"] - seg_consumed
            take  = min(remaining, avail)
            lap_gain     += seg["gradient"] * take
            remaining    -= take
            seg_consumed += take
            if seg_consumed >= seg["distance_m"] - 0.001:
                seg_cursor  += 1
                seg_consumed = 0.0
        gradients.append(lap_gain / lap_dist if lap_dist > 0 else 0.0)
    return gradients
