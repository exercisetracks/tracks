# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Training load calculator: CTL, ATL, TSB, and hrTSS estimation.

CTL (Chronic Training Load)  = fitness   — 42-day exponential moving average
ATL (Acute Training Load)    = fatigue   — 7-day exponential moving average
TSB (Training Stress Balance)= form      = CTL - ATL

When a device provides TSS directly, that value is used as-is.
When it's absent, hrTSS is estimated from heart rate and duration using the
athlete's LTHR. If LTHR is not available, it falls back to 87% of the
activity's recorded max HR (a common population-level approximation).

hrTSS formula: duration_hours * (avg_hr / threshold_hr)^2 * 100

With no heart rate at all — a run recorded on the phone, a ride logged with
no sensor — the load is estimated from what every activity has: its sport,
duration, distance and climb (``estimated_tss``, below). That used to count
as nothing, so someone who trains with only a phone had a flat fitness line
however much they trained, and a fitness plan (which sizes itself from CTL)
treated months of running as a first week.
"""

from datetime import timedelta
from math import exp
from statistics import median

from app.calculators.local_day import activity_local_date
from app.calculators.mtb import is_mtb, mtb_tss_multiplier
from app.calculators.road_cycling import apply_indoor_tss_multiplier
from app.spec.taxonomy import sport_type

_CTL_DECAY = exp(-1 / 42)
_ATL_DECAY = exp(-1 / 7)


def scale_tss(sport: str | None, tss: float | None, mtb_discipline: str | None) -> float | None:
    """The sport multipliers on an activity's computed load.

    MTB by the active MTB goal's discipline (`active_mtb_discipline`), indoor
    cycling +10%; no sport is both.

    Applied when load is *read*, never stored. The discipline comes from the
    synced goals, so a multiplier baked in at import would depend on which goal
    happened to be active on whichever device imported the ride first — and two
    devices importing the same file at different times would disagree for good.
    Evaluated here, every device computes the same load from the same goals.
    """
    if tss is None:
        return None
    if is_mtb(sport):
        return round(tss * mtb_tss_multiplier(mtb_discipline), 1)
    return apply_indoor_tss_multiplier(sport, tss)


def estimate_tss(activity, threshold_hr: float | None = None,
                 mtb_discipline: str | None = None,
                 calibration: dict[str, float] | None = None) -> float:
    """
    Return the best available TSS for an activity.

    Priority:
    1. Device-provided training_stress_score (most accurate — already accounts
       for the athlete's physiology via the device's own power/HR model).
    2. Stored effective_tss (power-based Coggan TSS or pre-computed hrTSS set
       at import time by the watcher or backfill endpoint).
    3. On-demand hrTSS estimated from avg HR, duration, and threshold HR
       (fallback when neither of the above is available).
    4. With no heart rate either, ``estimated_tss`` from the sport, duration,
       distance and climb — scaled by ``calibration`` (``load_calibration``,
       this athlete's measured sessions against the same estimate) if given.

    Args:
        activity:     SQLAlchemy Activity row.
        threshold_hr: Athlete's LTHR in bpm. When provided this is used
                      directly; when absent it falls back to 87% of the
                      activity's recorded max HR.
    """
    if activity.training_stress_score is not None:
        return float(activity.training_stress_score)

    # Use stored effective_tss if already computed at import (power-based or hrTSS)
    # (unscaled as stored — the sport multipliers apply here; see scale_tss)
    if hasattr(activity, "effective_tss") and activity.effective_tss is not None:
        return float(scale_tss(getattr(activity, "sport", None), float(activity.effective_tss),
                               mtb_discipline))

    if not activity.duration_seconds:
        return 0.0
    if not activity.avg_heart_rate:
        sport = getattr(activity, "sport", None)
        tss = estimated_tss(sport, activity.duration_seconds,
                            getattr(activity, "distance_meters", None),
                            getattr(activity, "total_ascent", None), mtb_discipline)
        return calibrated(tss, sport, calibration)

    if threshold_hr is None:
        max_hr = activity.max_heart_rate or int(activity.avg_heart_rate * 1.15)
        threshold_hr = max_hr * 0.87

    if threshold_hr <= 0:
        return 0.0

    hr_ratio = min(activity.avg_heart_rate / threshold_hr, 1.5)
    duration_hours = activity.duration_seconds / 3600
    return round(duration_hours * (hr_ratio ** 2) * 100, 1)


# ── Load with no heart rate ─────────────────────────────────────────────────
#
# TSS is ``hours × IF² × 100``, so estimating it is estimating one number: the
# session's intensity factor. With no heart rate or power, what is left to
# estimate it from is the sport, the speed (distance over duration) and the
# climb, and this uses each only where it actually says something.
#
# The scale is the one the planner already prescribes in (plan/load.py):
# hrTSS-shaped, an easy session about 0.75. That is deliberate. A fitness plan
# sizes its weeks from CTL and counts what it prescribes with those IFs, so a
# completed session estimated on the same scale is what keeps "the plan asked
# for 300 TSS" and "the chart says you did 300 TSS" the same claim.
#
# Why no athlete-relative pace (rTSS against a threshold pace)
# ────────────────────────────────────────────────────────────
# It was the obvious approach and it is wrong for exactly these users. A
# threshold pace has to come from somewhere, and with no heart rate the only
# source is the athlete's own best efforts — which, for someone who only ever
# jogs, are jogs. Daniels' VDOT from a 10 km jog puts threshold pace a few
# seconds faster than the jog, so every easy run scores IF ≈ 0.95 and CTL
# comes out about half as high again as it is: the plan then builds from
# fitness the person does not have. A per-sport default is wrong per session but unbiased across a
# history, and CTL is a 42-day average — it is the bias that matters.
#
# It also keeps the estimate a function of the activity alone. Nothing here
# reads settings or other activities, so the server and a phone with no server
# get the same number for the same file, today and after any later import.
#
# Base intensity per sport type (spec/sport_taxonomy.yaml), unstructured
# sessions as people actually do them — slightly above the planner's 0.75 for
# running, because an unplanned run is rarely a disciplined Zone 2 run:
_EST_IF: dict[str, float] = {
    "running": 0.78, "triathlon": 0.80, "nordic_skiing": 0.75,
    "team_sports": 0.75, "fitness_equipment": 0.70,
    "cycling": 0.70, "mtb": 0.72, "indoor_cycling": 0.72,
    "swimming": 0.72, "rowing": 0.72,
    "paddling": 0.62, "hiking": 0.60,
    "strength": 0.60, "climbing": 0.60, "bouldering": 0.60, "other": 0.60,
    "skiing": 0.55,             # lift-served: most of the elapsed time is not work
    "golf": 0.45, "mind_body": 0.45,
}

# Where absolute speed does say something about effort: on foot and on a road
# bike, the bottom of the range is a stroll or a café ride whoever is doing
# it, and the top is purposeful. Running is deliberately absent — 6:00/km is
# a recovery jog for one runner and a threshold run for another, so a runner's
# speed alone cannot place them (see above). The speed is *equivalent flat*
# speed, climb converted to distance, so a hike that is slow because it is
# steep is not scored as a stroll: 1 m up ≈ 8 m along on foot (Naismith's
# rule), ≈ 25 m on a bike (the same power lifts a rider 1 000 m in the hour it
# would carry them ~25–30 km on the flat).
# type -> (metres of flat per metre climbed, slow m/s, IF there, fast m/s, IF there)
_EST_SPEED_BANDS: dict[str, tuple[float, float, float, float, float]] = {
    "hiking":  (8.0, 1.1, 0.55, 2.2, 0.75),    # 4 km/h stroll … 8 km/h power walk / steep hike
    "cycling": (25.0, 4.2, 0.60, 8.3, 0.75),   # 15 km/h … 30 km/h
}

# Where speed cannot place the effort but climbing still forces it up: a
# trail run or a mountain-bike ride with a lot of vertical is harder than the
# same time on the flat whatever the pace. Added to the IF in proportion to
# the climb rate, full at _EST_CLIMB_CAP metres an hour. The cap also bounds
# what a noisy altitude trace can do — a phone's GPS altitude is filtered
# (core/run/RunTrack) but never exact.
_EST_CLIMB_IF: dict[str, float] = {"running": 0.12, "mtb": 0.10, "nordic_skiing": 0.10}
_EST_CLIMB_CAP = 800.0


# Stillness practices share mind_body with yoga and pilates in the taxonomy,
# but an hour of meditation or breathwork is not training: counted at
# mind_body's IF it would add ~20 TSS an hour to CTL and fatigue for sitting
# down. Matched on the sport name because the taxonomy does not separate them.
_EST_ZERO = ("meditat", "breath")


def estimated_intensity(sport: str | None, duration_seconds: float,
                        distance_meters: float | None, total_ascent: float | None) -> float:
    """The intensity factor ``estimated_tss`` scores a session at."""
    if sport and any(z in sport.lower() for z in _EST_ZERO):
        return 0.0
    kind = sport_type(sport)
    intensity = _EST_IF.get(kind, _EST_IF["other"])
    hours = duration_seconds / 3600
    climb = total_ascent if total_ascent and total_ascent > 0 else 0.0
    band = _EST_SPEED_BANDS.get(kind)
    if band is not None and distance_meters and distance_meters > 0:
        per_climb, slow, slow_if, fast, fast_if = band
        speed = (distance_meters + per_climb * climb) / duration_seconds
        speed = min(max(speed, slow), fast)
        intensity = slow_if + (fast_if - slow_if) * (speed - slow) / (fast - slow)
    bonus = _EST_CLIMB_IF.get(kind)
    if bonus is not None and climb > 0:
        intensity += bonus * min(climb / hours, _EST_CLIMB_CAP) / _EST_CLIMB_CAP
    return intensity


def estimated_tss(sport: str | None, duration_seconds: float | None,
                  distance_meters: float | None = None, total_ascent: float | None = None,
                  mtb_discipline: str | None = None) -> float:
    """TSS for an activity with no heart rate, power or device load.

    See the notes above for what it reads and why. The sport multipliers
    (scale_tss) apply as they do to every other computed load.
    """
    return scale_tss(sport, estimated_raw_tss(sport, duration_seconds, distance_meters, total_ascent),
                     mtb_discipline)


def estimated_raw_tss(sport: str | None, duration_seconds: float | None,
                      distance_meters: float | None = None,
                      total_ascent: float | None = None) -> float:
    """``estimated_tss`` before the sport multipliers — the form an import
    stores, as it stores every computed load (see scale_tss)."""
    if not duration_seconds or duration_seconds <= 0:
        return 0.0
    f = estimated_intensity(sport, duration_seconds, distance_meters, total_ascent)
    return round(duration_seconds / 3600 * (f * f) * 100, 1)


# ── Calibrating the estimate to the athlete ────────────────────────────────
#
# The estimate above is a population's: an unstructured run at IF 0.78. Many
# athletes have sessions that were *measured* — a strap, a power meter, a
# watch's own load — as well as ones that were not (the strap forgotten, the
# watch left at home, a run recorded on the phone). The measured ones say how
# this athlete's sessions of a sport actually compare to the population
# estimate for the same sport, distance and climb, and the unmeasured ones
# should be scaled by that.
#
# The scale is a ratio estimator: measured load ÷ estimated load, per sport
# type, over the athlete's most recent measured sessions of it. The median,
# not the mean, so one mis-recorded session (or a race) cannot move it; recent
# sessions only, so it follows fitness and threshold changes; clamped, so no
# history however strange can make the estimate absurd. A sport with too few
# measured sessions of its own borrows the athlete's pooled ratio across all
# sports, pulled halfway back to 1 — partial pooling: an athlete whose runs
# measure 20% above the estimate probably rides harder than average too, but
# the evidence for rides specifically is weaker.
#
# It is a function of the whole history passed in, so it must always be the
# whole history — never a date window, or an activity's load would depend on
# how far back the chart looking at it starts. Callers that read a window
# compute it from the full selection and pass it in.
#
# "Most recent" is by calendar date, ties by the ratio itself: the phone holds
# a date and not a time, and its rows arrive newest first, so any other order
# could pick a different twenty sessions on a day with two.

CAL_RECENT = 20
CAL_MIN_SESSIONS = 5
CAL_MIN_POOLED = 10
CAL_LIMITS = (0.5, 2.0)


def _clamp_ratio(k: float) -> float:
    lo, hi = CAL_LIMITS
    return lo if k < lo else hi if k > hi else k


def load_calibration(rows, threshold_hr: float | None = None,
                     mtb_discipline: str | None = None,
                     tz_name: str | None = None) -> dict[str, float]:
    """{sport type: scale, "*": pooled scale} for the no-heart-rate estimate.

    "Most recent" is by local day (``tz_name``, calculators/local_day.py), as
    every other day here is: two sessions either side of local midnight would
    otherwise order by their UTC day.
    """
    by_type: dict[str, list[tuple]] = {}
    pooled: list[tuple] = []
    for r in rows:
        dur = r.duration_seconds
        if not dur or dur <= 0:
            continue
        measured = (r.training_stress_score is not None
                    or getattr(r, "effective_tss", None) is not None or r.avg_heart_rate)
        if not measured:
            continue
        sport = getattr(r, "sport", None)
        est = estimated_tss(sport, dur, getattr(r, "distance_meters", None),
                            getattr(r, "total_ascent", None), mtb_discipline)
        got = estimate_tss(r, threshold_hr, mtb_discipline)
        if est <= 0 or got <= 0:
            continue
        key = (activity_local_date(r.started_at, tz_name).toordinal(), got / est)
        by_type.setdefault(sport_type(sport), []).append(key)
        pooled.append(key)
    out: dict[str, float] = {}
    for kind, keys in by_type.items():
        if len(keys) >= CAL_MIN_SESSIONS:
            recent = sorted(keys)[-CAL_RECENT:]
            out[kind] = _clamp_ratio(median([k[1] for k in recent]))
    if len(pooled) >= CAL_MIN_POOLED:
        recent = sorted(pooled)[-CAL_RECENT:]
        out["*"] = 1 + (_clamp_ratio(median([k[1] for k in recent])) - 1) / 2
    return out


def calibrated(tss: float, sport: str | None, calibration: dict[str, float] | None) -> float:
    """An estimate scaled by the athlete's own calibration, where there is one."""
    if not calibration or tss <= 0:
        return tss
    k = calibration.get(sport_type(sport), calibration.get("*"))
    return tss if k is None else round(tss * k, 1)


def calculate_ctl_atl_tsb(daily_loads: list[dict]) -> list[dict]:
    """
    Apply exponential moving averages to a sorted list of daily TSS values.

    Args:
        daily_loads: [{"date": date, "tss": float}, ...] sorted ascending.

    Returns:
        [{"date": date, "tss": float, "ctl": float, "atl": float, "tsb": float}, ...]
        One entry per calendar day in the range, with 0 TSS for rest days.
    """
    if not daily_loads:
        return []

    tss_by_date = {row["date"]: row["tss"] for row in daily_loads}

    # Fill every calendar day in the range so rest days decay properly.
    all_dates = []
    current = daily_loads[0]["date"]
    end = daily_loads[-1]["date"]
    while current <= end:
        all_dates.append(current)
        current += timedelta(days=1)

    result = []
    ctl = 0.0
    atl = 0.0

    for d in all_dates:
        tss = tss_by_date.get(d, 0.0)
        ctl = ctl * _CTL_DECAY + tss * (1 - _CTL_DECAY)
        atl = atl * _ATL_DECAY + tss * (1 - _ATL_DECAY)
        result.append({
            "date": d,
            "tss":  round(tss, 1),
            "ctl":  round(ctl, 1),
            "atl":  round(atl, 1),
            "tsb":  round(ctl - atl, 1),
        })

    return result
