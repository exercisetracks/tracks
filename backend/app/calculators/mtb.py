# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Mountain-biking specific helpers.

Used across the import pipeline (TSS multiplier), training plan generator,
race plan generator, and completion matching.

Science basis:
- Coggan TSS (NP-based) undershoots MTB cost by 15-30% because coasting
  depresses NP but descent demands real isometric/anaerobic load
  (Hurst 2012; Allen, Coggan & McGregor 2019).
- Discipline-specific multipliers reflect the relative descent + variability
  load above what NP captures.
"""
from __future__ import annotations

import math
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from sqlalchemy.orm import Session


# ── Sport classification ─────────────────────────────────────────────────────

MTB_SPORTS: frozenset[str] = frozenset({"mountain_biking", "trail_biking"})


def is_mtb(sport: str | None) -> bool:
    return bool(sport) and sport.lower().replace(" ", "_") in MTB_SPORTS


# ── Discipline taxonomy ──────────────────────────────────────────────────────

MTB_DISCIPLINES: tuple[str, ...] = ("xco", "xcm", "enduro", "trail")
DEFAULT_DISCIPLINE: str = "trail"   # used when user has no active MTB goal

# Race-distance heuristic when discipline isn't explicitly set on the goal
def infer_discipline_from_distance(distance_m: float | None) -> str:
    """Best-guess discipline from event distance. Used as a fallback only."""
    if not distance_m or distance_m <= 0:
        return DEFAULT_DISCIPLINE
    km = distance_m / 1000
    if km < 25:    return "xco"
    if km < 100:   return "xcm"
    return "xcm"  # ultra-mtb stays under XCM template


# ── TSS multiplier table ─────────────────────────────────────────────────────

# Multiplier applied on top of NP-based or HR-based TSS at import time.
# Trail and XCM share the conservative middle-ground; Enduro/XCO get the
# discipline-specific bumps the literature supports.
_DISCIPLINE_TSS_MULTIPLIER: dict[str, float] = {
    "xco":    1.00,   # Olympic XC: NP already captures most of the load
    "xcm":    1.10,   # Marathon XC: long duration on technical terrain
    "enduro": 1.20,   # Enduro: descents underrepresented by power
    "trail":  1.10,   # Recreational trail riding (default)
}


def mtb_tss_multiplier(discipline: str | None) -> float:
    """Return the TSS multiplier for the given MTB discipline (defaults to trail)."""
    if not discipline:
        return _DISCIPLINE_TSS_MULTIPLIER[DEFAULT_DISCIPLINE]
    return _DISCIPLINE_TSS_MULTIPLIER.get(discipline.lower(), _DISCIPLINE_TSS_MULTIPLIER[DEFAULT_DISCIPLINE])


# ── Active goal lookup ───────────────────────────────────────────────────────

def active_mtb_discipline(db: "Session", user_id: int) -> str | None:
    """
    Return the discipline of the user's active MTB event goal, or None if no
    such goal exists. Used at activity import time to pick the right TSS
    multiplier.
    """
    from app.models.coaching import TrainingGoal   # local import: avoid cycle

    goal = (
        db.query(TrainingGoal)
        .filter(
            TrainingGoal.user_id == user_id,
            TrainingGoal.is_active.is_(True),
            TrainingGoal.goal_type == "event",
            TrainingGoal.event_sport.in_(list(MTB_SPORTS)),
        )
        .order_by(TrainingGoal.event_date.asc().nulls_last())
        .first()
    )
    if goal is None:
        return None
    if getattr(goal, "mtb_discipline", None):
        return goal.mtb_discipline.lower()
    # Fall back to distance-based inference when no discipline tag exists
    return infer_discipline_from_distance(goal.event_distance_meters)


# ── Race plan helpers ────────────────────────────────────────────────────────

# HR ceiling factor relative to LTHR — Friel race-pacing guidance:
#   XCO/XCM: 0.97 (under LT keeps you fueling and reading the trail)
#   Enduro:  1.00 (descents legitimately push above LT; cap at LT for transfers)
#   Trail:   0.95 (recreational pace, no race goal)
_DISCIPLINE_HR_CEILING_PCT: dict[str, float] = {
    "xco":    0.97,
    "xcm":    0.97,
    "enduro": 1.00,
    "trail":  0.95,
}


def race_hr_ceiling(discipline: str | None, lthr: int | None) -> int | None:
    """Suggested HR ceiling during the race, derived from the user's LTHR."""
    if not lthr or lthr <= 0:
        return None
    d   = (discipline or DEFAULT_DISCIPLINE).lower()
    pct = _DISCIPLINE_HR_CEILING_PCT.get(d, _DISCIPLINE_HR_CEILING_PCT[DEFAULT_DISCIPLINE])
    return int(round(lthr * pct))


def _fmt_pace_sec_per_km(sec_per_km: float) -> str:
    """Display sec/km as M:SS/km, matching race_predictor._fmt_pace."""
    if sec_per_km <= 0:
        return "—"
    m = int(sec_per_km // 60)
    s = int(round(sec_per_km - m * 60))
    if s == 60:
        m += 1
        s = 0
    return f"{m}:{s:02d}/km"


# Synthetic per-lap gradient amplitudes (rise/run fractions, peak-to-peak).
# Used when no GPX is uploaded so the user still sees realistic per-lap pace
# variation from their chosen course_type. Sinusoidal alternation of climbs
# and descents matches how trail loops typically ride.
_COURSE_TYPE_GRAD_AMPLITUDE: dict[str, float] = {
    "flat":         0.000,
    "rolling":      0.025,   # ±2.5% on average
    "hilly":        0.055,   # ±5.5% — typical XC course
    "mountainous":  0.090,   # ±9% — Alpine / Enduro terrain
}


def mtb_grade_multiplier(grad: float) -> float:
    """
    Multiplicative pace factor for an MTB lap at the given gradient
    (rise/run fraction). 1.0 = no change vs the flat-equivalent baseline.

    Climbs cost more than equivalent descents save: descents saturate at the
    technical/braking limit, while climbs grow super-linearly as the rider
    crosses the lactate threshold.

    Used by both the backend MTB race-plan generator and the frontend
    recomputeLaps() so the two stay in sync — the frontend rebuilds each
    lap's pace as `baseFlatPace * grade_multiplier * split_ramp`, so a 1.0
    multiplier would erase all grade variation regardless of what the
    backend computed.

    Calibration (validated against the previous additive model):
      +5% grade  → ~1.20× (22% slower than flat)
      +10% grade → ~1.44× (44% slower, very steep)
      −5% grade  →  ~0.89× (~11% faster)
      −10% grade → ~0.87× (descent gains saturate)
    """
    if grad >= 0:
        pct = grad * 100
        return 1.0 + 0.035 * pct + 0.0009 * pct * pct
    pct = -grad * 100
    # Descent: asymptotic 14% pace gain. exp decay timescale 3.5% keeps the
    # 0/-5/-10 calibration above intact.
    return 1.0 - 0.14 * (1.0 - math.exp(-pct / 3.5))


def _synthesize_lap_gradients(n_laps: int, course_type: str | None) -> list[float]:
    """
    Build a phantom per-lap gradient profile from the user's course_type
    selection when no GPX is uploaded. Produces a sinusoidal climb/descent
    pattern so every km looks different to the rider, instead of identical
    pace across the whole race.
    """
    amp = _COURSE_TYPE_GRAD_AMPLITUDE.get((course_type or "rolling").lower(), 0.025)
    if amp == 0.0 or n_laps <= 0:
        return [0.0] * max(1, n_laps)
    # Two and a half cycles across the race, offset 1/4 cycle so it doesn't
    # start at zero — gives an early climb on lap 1 like most XC courses.
    return [amp * math.sin((i / max(n_laps - 1, 1)) * 2 * math.pi * 2.5 + math.pi / 4)
            for i in range(n_laps)]


def mtb_hr_only_laps(
    distance_m: float,
    total_sec: float,
    lap_km: float = 1.0,
    split_spread: float = 0.0,
    discipline: str | None = None,
    lthr: int | None = None,
    course_segments: list[dict] | None = None,
    course_type: str | None = None,
) -> tuple[list[dict], float]:
    """
    Per-lap pacing for an MTB race plan when no FTP is available.

    Splits the total distance into N kilometre laps (plus a final partial),
    derives per-lap gradient from the GPX course (when present) or from the
    course_type label (synthetic profile), applies the asymmetric MTB grade
    penalty, and stamps every lap with the discipline's race HR ceiling.

    Returns (laps, actual_total_sec). The total_sec returned reflects the
    sum of per-lap times, which may differ slightly from the input total_sec
    because grade penalties don't perfectly net to zero.
    """
    if distance_m <= 0 or total_sec <= 0 or lap_km <= 0:
        return [], 0.0

    _MAX_SPREAD = 0.08   # match race_predictor._MAX_SPLIT_SPREAD

    n_full = int(distance_m / (lap_km * 1000))
    last_m = distance_m - n_full * lap_km * 1000
    n_laps = n_full + (1 if last_m > 10 else 0)
    if n_laps == 0:
        n_laps = 1
    lap_dists = [
        (last_m if (i == n_full and last_m > 10) else lap_km * 1000)
        for i in range(n_laps)
    ]

    # Per-lap gradient: prefer GPX-derived, fall back to course_type synthesis.
    lap_grads: list[float] = [0.0] * n_laps
    if course_segments:
        seg_cursor, seg_consumed = 0, 0.0
        for lap_i, lap_dist in enumerate(lap_dists):
            lap_gain, remaining = 0.0, lap_dist
            while remaining > 0 and seg_cursor < len(course_segments):
                seg   = course_segments[seg_cursor]
                avail = seg["distance_m"] - seg_consumed
                take  = min(remaining, avail)
                lap_gain     += seg.get("gradient", 0.0) * take
                remaining    -= take
                seg_consumed += take
                if seg_consumed >= seg["distance_m"] - 0.001:
                    seg_cursor  += 1
                    seg_consumed = 0.0
            lap_grads[lap_i] = lap_gain / lap_dist if lap_dist > 0 else 0.0
    elif course_type and course_type.lower() != "flat":
        lap_grads = _synthesize_lap_gradients(n_laps, course_type)

    # Split ramp: −1 positive split, 0 even, +1 negative split.
    mid   = (n_laps - 1) / 2.0
    slope = -split_spread * _MAX_SPREAD * 2.0 / max(n_laps - 1, 1)
    ramp  = [1.0 + slope * (i - mid) for i in range(n_laps)]

    hr_ceiling = race_hr_ceiling(discipline, int(lthr) if lthr else None)

    # Per-lap pace = base_flat_pace × grade_multiplier × split_ramp.
    # This multiplicative form is what the frontend's recomputeLaps() expects;
    # using a non-trivial grade_multiplier keeps the FE display synced with
    # the backend's grade-adjusted pace.
    lap_mults = [mtb_grade_multiplier(g) for g in lap_grads]

    # Solve for base_flat_pace such that Σ(lap_dist × mult × ramp) × pace = total_sec
    weighted_dist_m = sum(d * m * r for d, m, r in zip(lap_dists, lap_mults, ramp))
    base_flat_pace = total_sec / (weighted_dist_m / 1000) if weighted_dist_m > 0 else 0.0

    laps: list[dict] = []
    cum_km    = 0.0
    total_acc = 0.0
    for i, (lap_dist, grad, r, mult) in enumerate(zip(lap_dists, lap_grads, ramp, lap_mults)):
        target_pace_sec_per_km = max(60.0, base_flat_pace * mult * r)
        # Flat-equivalent (split-ramp only, no grade) for the GAP column
        flat_pace_sec = base_flat_pace * r
        lap_sec = lap_dist / 1000 * target_pace_sec_per_km
        total_acc += lap_sec
        cum_km    += lap_dist / 1000

        laps.append({
            "lap":               i + 1,
            "distance_m":        round(lap_dist),
            "target_sec_per_km": round(target_pace_sec_per_km, 1),
            "target_pace":       _fmt_pace_sec_per_km(target_pace_sec_per_km),
            "gradient":          round(grad, 4),
            "grade_multiplier":  round(mult, 4),
            "grade_adj_sec":     round(flat_pace_sec, 1),
            "grade_adj_pace":    _fmt_pace_sec_per_km(flat_pace_sec),
            "cumulative_km":     round(cum_km, 2),
            "hr_ceiling":        hr_ceiling,
            "target_watts":      None,
            "target_watts_pct_ftp": None,
        })

    return laps, total_acc


def fueling_plan(predicted_seconds: float | None, discipline: str | None = None) -> dict:
    """
    Per-hour carbohydrate target (Jeukendrup 2014, eat-while-you-train review).
    Reminder cadence scales with predicted duration so a 5-h XCM doesn't get
    spammed every 15 min like a 90-min XCO.
    """
    hours = (predicted_seconds or 0) / 3600
    if hours <= 1.0:
        return {"carbs_g_per_h": 30, "reminder_min": 30,
                "notes": "Short race — top up before the start; one mid-race feed is plenty."}
    if hours <= 2.0:
        return {"carbs_g_per_h": 60, "reminder_min": 25,
                "notes": "Aim for 60 g/h from minute 30 — one gel / chew every ~20–25 min."}
    if hours <= 4.0:
        return {"carbs_g_per_h": 80, "reminder_min": 20,
                "notes": "80 g/h mixed carbs (glucose + fructose 2:1). Practice this in training."}
    return {"carbs_g_per_h": 90, "reminder_min": 20,
            "notes": "Ultra fueling: 90 g/h, mixed sources, include some real food after hour 3."}


def apply_mtb_tss_multiplier(
    sport: str | None,
    tss: float | None,
    db: "Session",
    user_id: int | None,
) -> float | None:
    """
    Multiply a raw TSS value by the user's MTB discipline factor when the
    activity is mountain biking. Returns the original TSS unchanged for any
    non-MTB sport or when tss is None.
    """
    if tss is None or not is_mtb(sport) or user_id is None:
        return tss
    discipline = active_mtb_discipline(db, user_id)   # None if no active goal
    return round(tss * mtb_tss_multiplier(discipline), 1)
