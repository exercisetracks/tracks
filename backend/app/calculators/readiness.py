# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Readiness Score (0–100): how recovered and ready to train is the athlete today?

Hybrid model:
  - Physiological health data (HRV, sleep, resting HR) when available
  - Training load (TSB) when health data is sparse
  - Dynamic weights based on data availability

A higher score means better recovery.  Each component degrades gracefully
when data is missing.
"""

from __future__ import annotations
from dataclasses import dataclass
from datetime import date, timedelta
from math import exp
from statistics import mean
from typing import Sequence


@dataclass
class ReadinessResult:
    score: float                  # 0–100 overall
    hrv_score: float              # 0–40
    sleep_score: float            # 0–35
    resting_hr_score: float       # 0–25
    hrv_today: float | None
    hrv_baseline: float | None    # 7-day avg
    sleep_hours: float | None
    garmin_sleep_score: float | None
    resting_hr_today: float | None
    resting_hr_baseline: float | None
    training_score: float | None  # training-recovery component, 0–100 (acute model or TSB fallback)
    primary_driver: str           # "health_data" | "training_load" | "mixed" | "default"
    confidence: str               # "high" | "medium" | "low"
    notes: list[str]


# ─────────────────────────────────────────
# HRV  (40 pts)
# ─────────────────────────────────────────

def _score_hrv(today: float | None, baseline: float | None) -> float:
    if today is None or baseline is None or baseline <= 0:
        return 25.0  # neutral when data absent
    ratio = today / baseline
    if ratio >= 1.10:
        return 40.0
    if ratio >= 0.95:
        return 35.0
    if ratio >= 0.85:
        return 25.0
    if ratio >= 0.75:
        return 15.0
    return 5.0


# ─────────────────────────────────────────
# Sleep  (35 pts)
# ─────────────────────────────────────────

def _score_sleep(garmin_score: float | None, hours: float | None) -> float:
    if garmin_score is not None:
        if garmin_score >= 80:
            return 35.0
        if garmin_score >= 70:
            return 28.0
        if garmin_score >= 60:
            return 20.0
        if garmin_score >= 50:
            return 12.0
        return 5.0

    if hours is not None:
        if hours >= 8.0:
            return 35.0
        if hours >= 7.0:
            return 28.0
        if hours >= 6.0:
            return 18.0
        return 8.0

    return 20.0  # neutral


# ─────────────────────────────────────────
# Resting HR  (25 pts)
# ─────────────────────────────────────────

def _score_resting_hr(today: float | None, baseline: float | None) -> float:
    if today is None or baseline is None or baseline <= 0:
        return 18.0  # neutral
    ratio = today / baseline
    if ratio <= 0.95:
        return 25.0
    if ratio <= 1.05:
        return 20.0
    if ratio <= 1.15:
        return 12.0
    return 5.0


# ─────────────────────────────────────────
# TSB → 0–100
# ─────────────────────────────────────────

def _normalize_tsb(tsb: float) -> float:
    """Map Training Stress Balance onto a 0–100 recovery contribution.

    In the normal band (TSB ≥ −10) the response is linear — TSB −10 → 40,
    0 → 60, +20 → 100 (clamped) — matching the long-standing behaviour.

    Below the band we use a gentle exponential tail instead of the old hard
    clip at TSB −30. The clip meant every value past −30 collapsed to 0, so a
    day after a huge multi-day effort (TSB can reach −100 … −130) scored the
    same as a week into recovery: the readiness score was blind to how long it
    had been since the last workout. The tail keeps decaying toward — but never
    reaching — 0, so each rest day's fatigue decay lifts the score monotonically:

        TSB  −10 → 40     −30 → 28     −60 → 16     −100 → 8     −130 → 5
    """
    if tsb >= -10.0:
        return min(100.0, (tsb + 30.0) / 50.0 * 100.0)
    # Continuous with the linear branch at TSB −10 (both give 40).
    return 40.0 * exp((tsb + 10.0) / 56.0)


# ─────────────────────────────────────────
# Acute training-load recovery  (0–100)
# ─────────────────────────────────────────
# Daily readiness is an *acute* signal. After a hard/long session autonomic
# recovery returns over roughly 1–3 days (longer for big multi-day efforts) —
# see Garmin's "recovery time" and Whoop's overnight-HRV recovery. We model that
# with an "acute load" (a short ~2-day EMA of daily TSS, built by the caller)
# compared against the athlete's chronic load (CTL) as a personal-capacity
# reference — an acute:chronic workload ratio. Once acute load falls back to (or
# below) the chronic baseline, the athlete has recovered.
#
# This recovers far faster than TSB/Form (a ~2-week taper metric), which is why
# the earlier TSB-based readiness felt sluggish. It only *reads* training load;
# it does not alter any shared CTL/ATL/TSB/TSS calculation.

_ACUTE_TAU_DAYS = 2.0
_ACUTE_DECAY = exp(-1.0 / _ACUTE_TAU_DAYS)

_RECOVERY_MIDPOINT = 1.5    # acute = 1.5× chronic → 50% recovered
_RECOVERY_STEEPNESS = 1.7   # logistic slope: ratio 1.0 → ~70, ratio ≥3 → single digits
_CHRONIC_FLOOR = 20.0       # guard new/detrained athletes from divide-by-tiny


def acute_load_ema(daily_tss: Sequence[float]) -> list[float]:
    """Running ~2-day EMA of a day-by-day TSS series — the readiness recovery model.

    `daily_tss` must hold one value per consecutive calendar day (0 for rest
    days). Returns the acute load *after* each day. Shared by the coaching and
    readiness-history endpoints so both interpret recovery identically.
    """
    out: list[float] = []
    acute = 0.0
    for tss in daily_tss:
        acute = acute * _ACUTE_DECAY + tss * (1.0 - _ACUTE_DECAY)
        out.append(acute)
    return out


def _acute_recovery_score(acute_load: float, chronic_load: float) -> float:
    """Map the acute:chronic training-load ratio onto a 0–100 recovery score.

        ratio 0 (fully rested) → ~93     ratio 1.0 (normal day) → ~70
        ratio 1.5 → 50   ratio 2.0 → ~30   ratio ≥3 → single digits
    """
    ratio = acute_load / max(chronic_load, _CHRONIC_FLOOR)
    return 100.0 / (1.0 + exp(_RECOVERY_STEEPNESS * (ratio - _RECOVERY_MIDPOINT)))


# ─────────────────────────────────────────
# Public entry point
# ─────────────────────────────────────────

def compute_readiness(
    today_metric,                       # DailyMetric ORM row for today (may be None)
    recent_metrics: Sequence,           # last 7 DailyMetric rows (today excluded)
    tsb: float | None = None,           # Training Stress Balance (fallback signal only)
    acute_load: float | None = None,    # ~2-day EMA of TSS (acute recovery model)
    chronic_load: float | None = None,  # CTL — personal-capacity reference
) -> ReadinessResult:
    """
    Compute the readiness score from health data, a 7-day baseline, and the
    athlete's recent training load.

    The training component prefers the *acute recovery* model (a fast, 1-3 day
    timescale) when ``acute_load`` and ``chronic_load`` are supplied; it falls
    back to the slower TSB normalisation only when they are not.

    A hybrid model blends physiological markers and training load with
    dynamic weights depending on data availability:

      Data-rich   (health + training) → 55 % health / 45 % TSB   — high confidence
      Training-only                    → 85 % TSB + 15 % floor   — medium confidence
      Health-only                      → 100 % health            — medium confidence
      No data                          → 50                      — low confidence

    Args:
        today_metric:    DailyMetric row for today, or None if not yet available.
        recent_metrics:  Up to 7 most-recent prior DailyMetric rows (oldest first).
        tsb:             Training Stress Balance (CTL - ATL); fallback signal only.
        acute_load:      Acute training load (~2-day EMA of TSS) as-of today.
        chronic_load:    Chronic training load (CTL) as the capacity reference.
    """
    notes: list[str] = []

    # ── Extract today's values ───────────────────────────────────────────────
    hrv_today        = float(today_metric.hrv)          if today_metric and today_metric.hrv          else None
    rhr_today        = float(today_metric.resting_hr)   if today_metric and today_metric.resting_hr   else None
    sleep_hrs        = float(today_metric.sleep_hours)  if today_metric and today_metric.sleep_hours  else None
    sleep_score_val  = float(today_metric.sleep_score)  if today_metric and today_metric.sleep_score  else None

    # ── 7-day baselines ──────────────────────────────────────────────────────
    prior_hrv  = [float(m.hrv)        for m in recent_metrics if m.hrv]
    prior_rhr  = [float(m.resting_hr) for m in recent_metrics if m.resting_hr]

    hrv_baseline = mean(prior_hrv) if prior_hrv else None
    rhr_baseline = mean(prior_rhr) if prior_rhr else None

    # ── Component scores ─────────────────────────────────────────────────────
    hrv_sc  = _score_hrv(hrv_today, hrv_baseline)
    slp_sc  = _score_sleep(sleep_score_val, sleep_hrs)
    rhr_sc  = _score_resting_hr(rhr_today, rhr_baseline)
    physio  = hrv_sc + slp_sc + rhr_sc   # 0–100

    # Training-side readiness: prefer the fast acute recovery model when the
    # caller supplies acute + chronic load; otherwise fall back to slower TSB.
    # Both zero means no training history at all, which is unknown, not rested:
    # a brand-new account (or a phone on first launch) must read neutral, not
    # "Prime" — so that case falls through like a missing load.
    if acute_load is not None and chronic_load is not None and (acute_load or chronic_load):
        training_ready = _acute_recovery_score(acute_load, chronic_load)
    elif tsb is not None:
        training_ready = _normalize_tsb(tsb)
    else:
        training_ready = None

    # ── Data availability ────────────────────────────────────────────────────
    has_health = (
        hrv_today is not None
        or rhr_today is not None
        or sleep_hrs is not None
        or sleep_score_val is not None
    )

    # ── Hybrid blend ─────────────────────────────────────────────────────────
    if has_health and training_ready is not None:
        total = physio * 0.55 + training_ready * 0.45
        primary_driver = "mixed"
        confidence = "high"
    elif training_ready is not None:
        total = training_ready * 0.85 + 15.0
        primary_driver = "training_load"
        confidence = "medium"
    elif has_health:
        total = physio
        primary_driver = "health_data"
        confidence = "medium"
    else:
        total = 50.0
        primary_driver = "default"
        confidence = "low"

    total = round(total, 1)

    # ── Notes ────────────────────────────────────────────────────────────────
    if hrv_today is None:
        notes.append("No HRV data for today — using neutral estimate")
    elif hrv_baseline and hrv_today < hrv_baseline * 0.85:
        notes.append(f"HRV significantly below baseline ({hrv_today:.0f} vs {hrv_baseline:.0f} avg) — prioritise recovery")

    if sleep_score_val is None and sleep_hrs is None:
        notes.append("No sleep data — using neutral estimate")
    elif sleep_hrs is not None and sleep_hrs < 6.5:
        notes.append(f"Short sleep ({sleep_hrs:.1f}h) — consider an easy day")

    if rhr_today is None:
        notes.append("No resting HR data — using neutral estimate")
    elif rhr_baseline and rhr_today > rhr_baseline * 1.10:
        notes.append(f"Resting HR elevated ({rhr_today:.0f} vs {rhr_baseline:.0f} avg) — may indicate residual fatigue")

    if confidence == "low":
        notes.append("Not enough data for a reliable assessment — sync your watch or add health data")
    elif primary_driver == "training_load":
        notes.append("Score is primarily based on training load — health data would improve accuracy")
    elif primary_driver == "mixed":
        notes.append("Score blends health data and training load")

    if total >= 80:
        notes.append("Readiness is high — body is well recovered")
    elif total >= 60:
        notes.append("Readiness is moderate — listen to your body during the session")
    else:
        notes.append("Readiness is low — favour easy or recovery work today")

    return ReadinessResult(
        score=total,
        hrv_score=hrv_sc,
        sleep_score=slp_sc,
        resting_hr_score=rhr_sc,
        hrv_today=hrv_today,
        hrv_baseline=hrv_baseline,
        sleep_hours=sleep_hrs,
        garmin_sleep_score=sleep_score_val,
        resting_hr_today=rhr_today,
        resting_hr_baseline=rhr_baseline,
        training_score=training_ready,
        primary_driver=primary_driver,
        confidence=confidence,
        notes=notes,
    )


# ─────────────────────────────────────────
# History
# ─────────────────────────────────────────

def readiness_history(metrics, activities, today: date, days: int,
                      threshold_hr: float | None = None,
                      mtb_discipline: str | None = None,
                      tz_name: str | None = None) -> list[dict]:
    """Daily readiness for the `days` days ending `today`, oldest first.

    `metrics` are DailyMetric rows from seven days before the window onward;
    `activities` must be the same selection the live score uses — claimed
    devices, hidden sports removed, the whole history (CTL is a 42-day average,
    so starting it at the window would understate it) — and `threshold_hr` the
    same effective threshold. Each day is then scored exactly as the live gauge
    (api/coaching/daily.py) scored it: a history point that disagreed with what
    the gauge showed that morning would make the chart untrustworthy — so
    activities fall on their day in the account's zone (``tz_name``), as the
    gauge's load does (api/coaching/helpers._build_tss_by_date). Pure so
    the phone's port can be checked against it — see
    spec/make_metrics_fixtures.py.
    """
    from app.calculators.local_day import activity_local_date
    from app.calculators.training_load import calculate_ctl_atl_tsb, estimate_tss, load_calibration

    start = today - timedelta(days=days - 1)
    by_date = {m.date: m for m in metrics}

    calibration = load_calibration(activities, threshold_hr, mtb_discipline, tz_name)
    tss_by_date: dict[date, float] = {}
    for act in activities:
        d = activity_local_date(act.started_at, tz_name)
        tss_by_date[d] = tss_by_date.get(d, 0.0) + estimate_tss(act, threshold_hr, mtb_discipline, calibration)

    # Per-day CTL and acute load so the history uses the same acute recovery
    # model as the live readiness score (the fast, 1-3 day recovery timescale).
    ctl_by_date: dict[date, float] = {}
    atl_by_date: dict[date, float] = {}
    acute_by_date: dict[date, float] = {}
    if tss_by_date:
        extended = dict(tss_by_date)
        extended.setdefault(today, 0.0)
        daily = [{"date": d, "tss": t} for d, t in sorted(extended.items())]
        points = calculate_ctl_atl_tsb(daily)          # day-filled (0 on rest days)
        acute_series = acute_load_ema([p["tss"] for p in points])
        for p, acute in zip(points, acute_series):
            ctl_by_date[p["date"]] = p["ctl"]
            atl_by_date[p["date"]] = p["atl"]
            acute_by_date[p["date"]] = acute

    result = []
    current = start
    while current <= today:
        today_metric = by_date.get(current)
        prior = [
            by_date[current - timedelta(days=i)]
            for i in range(1, 8)
            if (current - timedelta(days=i)) in by_date
        ]
        # Before the first activity the live score saw an empty history, which
        # it reads as zero load (not unknown) — so the same here.
        ctl = ctl_by_date.get(current, 0.0)
        atl = atl_by_date.get(current, 0.0)
        tsb = ctl - atl if (ctl or atl) else None
        r = compute_readiness(today_metric, prior, tsb=tsb,
                              acute_load=acute_by_date.get(current, 0.0),
                              chronic_load=ctl)
        result.append({
            "date":             current,
            "score":            r.score,
            "hrv_score":        r.hrv_score,
            "sleep_score":      r.sleep_score,
            "resting_hr_score": r.resting_hr_score,
            "training_score":   r.training_score,
            "primary_driver":   r.primary_driver,
            "confidence":       r.confidence,
        })
        current += timedelta(days=1)
    return result
