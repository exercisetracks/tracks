# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Top-level plan orchestrator.

`generate_training_plan` schedules the whole block-periodised plan: it derives
phases, paces/power, and a week-by-week volume progression, then delegates each
week to `_generate_week`. The cross-week safety constraints live here:

  - Weekly volume ramp (~10%/wk, Damsted et al. 2018), capped.
  - Volume-intensity coupling: once intensity (build_idx) starts climbing in
    build/peak, the volume increase for the week is throttled so the two don't
    rise together (Foster 2001 monotony; Gabbett 2016 ACWR).
  - ACWR safeguard: acute load capped at 1.3× the 4-week chronic average.
  - Long-session progression with a taper-aware floor.

See the package docstring (`generator/__init__.py`) for the periodisation model
and literature references.
"""

from __future__ import annotations

import math
from datetime import date, timedelta
from typing import Sequence

from app.calculators.plan.base import (
    _DEFAULT_VDOT,
    _SPORT_DEFAULT_WEEKLY_KM,
    _best_vdot_from_pace_bests,
    _capacity_km,
    _css_pace_sec_per_100m,
    _current_weekly_km,
    _max_weekly_km_for_race,
    _phase_for_week,
    _sport_family,
    _target_peak_long_km,
    _weekly_volume_km,
    vdot_to_paces,
)
from app.calculators.plan.generator.week import (
    running_capacity_km,
    SportCtx,
    _easy_pace_kmh,
    _generate_week,
    km_to_tss,
)
from app.calculators.plan.starting import no_history_start, start_intensity, start_quality

_WEEKLY_GAIN_VOLUME = 0.10  # 10% weekly volume increase per Damsted et al. (2018)


def _goal_disciplines(goal) -> tuple[str, str]:
    """(mtb, road-cycling) discipline, normalised, unknown values defaulted."""
    raw_disc = (getattr(goal, "mtb_discipline", None) or "trail")
    mtb_discipline = raw_disc.lower() if isinstance(raw_disc, str) else "trail"
    if mtb_discipline not in {"xco", "xcm", "enduro", "trail"}:
        mtb_discipline = "trail"

    raw_cy_disc = (getattr(goal, "cycling_discipline", None) or "road_race")
    cycling_discipline = raw_cy_disc.lower() if isinstance(raw_cy_disc, str) else "road_race"
    if cycling_discipline not in {"road_race", "time_trial", "hill_climb", "criterium"}:
        cycling_discipline = "road_race"
    return mtb_discipline, cycling_discipline


def _effective_weeks_base(family: str, vdot: float | None,
                          base_effective_weeks: float | None) -> float:
    """Where the running walk-break capacity model starts (see _capacity_km)."""
    if family == "running" and vdot is not None:
        return max(0.0, (vdot - 20.0) * 0.7)
    return min(max(0.0, base_effective_weeks or 0.0) * 0.25, 8.0)


def running_vdots(running_fitness: dict | None,
                  pace_bests: list[tuple[int, float]]) -> tuple[float | None, float, float | None]:
    """(plan VDOT, pace VDOT, capacity VDOT) for a plan's running.

    ``running_fitness`` is running_fitness.estimate_running_fitness's answer —
    the one running-fitness number, which the API layer and the phone pass
    in. From it:

      - the plan's VDOT — what is stored, what the watch's pace targets are
        built from, what the weekly progression starts at — only when it was
        *measured*. A profile estimate is a guess, and a guess should shape the
        paces written in a session's notes, not drive a pace alarm;
      - the pace VDOT — the measured value, or else the profile estimate;
      - the capacity VDOT — the effort floor alone. Walk-break capacity is how
        far someone has actually run without stopping, which a heart-rate
        estimate of their aerobic ceiling does not show.

    Without an estimate (the fixtures that predate it) the plan reads the
    pace bests as it always did, with the old default.
    """
    if running_fitness is None:
        vdot = _best_vdot_from_pace_bests(pace_bests)
        return vdot, _DEFAULT_VDOT, vdot
    measured = running_fitness["vdot"] if running_fitness["measured"] else None
    return measured, running_fitness["vdot"], running_fitness.get("effort_vdot")


def generate_training_plan(
    goal,
    activity_history: Sequence,
    pace_bests: list[tuple[int, float]],
    today: date,
    ftp: int | None = None,
    max_hr: int | None = None,
    days_per_week: int | None = None,
    base_effective_weeks: float | None = None,
    imperial: bool = False,
    threshold_hr: int | None = None,
    activity_frequency: str | None = None,
    running_fitness: dict | None = None,
) -> tuple[float | None, list[dict]]:
    """
    Generate a full day-by-day training plan.

    ``activity_frequency`` is how often the person does this sport (a
    calculators/plan/starting.py level), read only when there is no history.
    ``running_fitness`` is today's running-fitness estimate (see
    ``running_vdots``); running plans and a triathlon's run leg read it.

    Returns (vdot, workouts). VDOT is None for non-running sports.

    Block periodisation (Issurin 2010):
      - Accumulation (base): volume ramps 10%/wk, ≤1 quality day/wk
      - Transmutation (build): volume plateaus, ≤2 quality days/wk, intensity rises
      - Realisation (peak): volume decreases slightly, peak quality, race-specific
      - Taper (Bosquet 2007): 2-3 weeks exponential volume decrease to 40-60%,
        maintain intensity and frequency

    Volume-intensity coupling:
      - Volume increases of >5% in a given week trigger automatic quality cap
        enforcement (no new quality types introduced that week)
      - ACWR-based safeguard: if acute load would exceed 1.3× chronic, cap volume
    """
    race_date       = goal.event_date
    race_distance_m = goal.event_distance_meters or 42195
    sport           = (goal.event_sport or "running").lower()
    family          = _sport_family(sport)
    dpw             = getattr(goal, "days_per_week", None) or days_per_week or 4
    plan_intensity  = max(0.5, min(1.5, float(getattr(goal, "plan_intensity", None) or 1.0)))

    mtb_discipline, cycling_discipline = _goal_disciplines(goal)

    lthr_int = int(threshold_hr) if threshold_hr else None

    if not race_date or race_date <= today:
        return None, []

    if family == "triathlon":
        return _generate_triathlon_plan(
            goal, activity_history, pace_bests, today, ftp=ftp, dpw=dpw,
            plan_intensity=plan_intensity, base_effective_weeks=base_effective_weeks,
            imperial=imperial, lthr=lthr_int, running_fitness=running_fitness)

    plan_monday = today - timedelta(days=today.weekday())
    total_days  = (race_date - plan_monday).days
    total_weeks = max(1, math.ceil(total_days / 7))

    # ── Paces / power ────────────────────────────────────────────────────────
    if family == "running":
        vdot, pace_vdot, capacity_vdot = running_vdots(running_fitness, pace_bests)
    else:
        vdot, pace_vdot, capacity_vdot = None, _DEFAULT_VDOT, None
    css  = _css_pace_sec_per_100m(pace_bests) if family == "swimming" else None

    base_vdot = vdot
    vdot_step = (
        min(_WEEKLY_GAIN_VOLUME, 1.5 / max(total_weeks, 1))
        if base_vdot is not None else 0.0
    )

    # ── Effective-weeks baseline ─────────────────────────────────────────────
    ew_base = _effective_weeks_base(family, capacity_vdot, base_effective_weeks)

    # ── Volume ───────────────────────────────────────────────────────────────
    current_km = _current_weekly_km(activity_history, family, today)
    # A runner's measured weekly distance bounds their continuous-run
    # capacity from below (week.running_capacity_km); none measured, none.
    # An onboarding answer is not a measurement: it moves the capacity model
    # through ew_base below, not through this.
    measured_km = current_km
    start = None
    no_history = current_km < 3
    if no_history:
        current_km = _SPORT_DEFAULT_WEEKLY_KM.get(family, 20.0)
        # No history of this sport: start from how often they say they do it
        # rather than one default athlete for everybody (starting.py).
        start = no_history_start(activity_frequency)
        if start is not None:
            current_km *= start["weekly_km_share"]
            if capacity_vdot is None:
                ew_base = max(ew_base, start["effective_weeks"])
    # The race sets how much volume the plan builds to — but never below what
    # the athlete already does. Capping a 100 km/week runner at a 10 km
    # plan's 45 km detrains them for the race they are training for.
    max_km = max(_max_weekly_km_for_race(race_distance_m, family), current_km)
    start_km = min(current_km, max_km)

    # ── Long-run progression ─────────────────────────────────────────────────
    target_long    = _target_peak_long_km(race_distance_m, family)
    taper_w_cnt    = min(3, max(1, total_weeks // 5))
    non_taper_cnt  = max(1, total_weeks - taper_w_cnt)
    first_long_km  = min(start_km * 0.35, target_long)

    cycle_len = 3 if (family == "mountain_biking" and mtb_discipline == "enduro") else 4

    def _guided_long(wk: int) -> float | None:
        if wk >= non_taper_cnt:
            taper_offset = wk - non_taper_cnt
            factors = [0.75, 0.60, 0.45]
            return target_long * factors[min(taper_offset, len(factors) - 1)]
        progress = (wk + 1) / non_taper_cnt
        guided   = first_long_km + (target_long - first_long_km) * progress
        if wk % cycle_len == cycle_len - 1:
            guided *= 0.75
        return max(guided, first_long_km)

    workouts: list[dict] = []
    build_idx = 0
    occurrence_counters: dict[str, int] = {}
    weekly_volumes: list[float] = []

    for week_num in range(total_weeks):
        week_start      = plan_monday + timedelta(weeks=week_num)
        is_race_week    = week_num == total_weeks - 1
        week_end        = race_date if is_race_week else week_start + timedelta(days=6)
        weeks_remaining = (race_date - week_start).days / 7
        phase  = _phase_for_week(week_num, total_weeks)
        vol_km = _weekly_volume_km(week_num, total_weeks, start_km, max_km,
                                   weeks_remaining, cycle_len=cycle_len)

        # ── Volume-intensity coupling: cap volume ramp when building intensity ──
        # When build_idx advanced in the previous week (new quality introduced),
        # limit this week's volume increase to 5% to prevent simultaneous climbing.
        if week_num > 0 and build_idx > 0 and phase in ("build", "peak"):
            prev_vol = weekly_volumes[-1] if weekly_volumes else vol_km
            max_allowed = prev_vol * (1.05 if phase == "build" else 1.03)
            vol_km = min(vol_km, max_allowed)

        # ── ACWR safeguard — cap at 1.3 acute:chronic ratio ───────────────────
        # Gabbett (2016): ACWR >1.5 = significantly elevated injury risk.
        # We cap at 1.3 for a conservative safety margin.
        if len(weekly_volumes) >= 4:
            chronic_load = sum(weekly_volumes[-4:]) / 4
            if chronic_load > 0:
                acwr = vol_km / chronic_load
                if acwr > 1.3:
                    vol_km = round(chronic_load * 1.3, 1)

        weekly_volumes.append(vol_km)

        week_vdot  = (base_vdot + week_num * vdot_step) if base_vdot is not None else None
        week_paces = vdot_to_paces(week_vdot or pace_vdot) if family == "running" else None

        eff_weeks = ew_base + week_num * (dpw / 5.0)
        cap_km    = running_capacity_km(eff_weeks, measured_km) if family == "running" else None

        # The week is sized in load (week.py): its kilometres at easy pace,
        # so quality sessions count for what they cost rather than for their
        # distance. The long-run guide stays a distance floor, bounded by 40%
        # of the week — the long-session share week.py keeps to. It was half
        # the week, which with a peak week's quality sessions on top planned
        # a lighter week at 130% of its load.
        ctx = SportCtx(family=family, sport=sport, paces=week_paces, ftp=ftp, css=css,
                       lthr=lthr_int, capacity_km=cap_km, mtb_discipline=mtb_discipline,
                       cycling_discipline=cycling_discipline, imperial=imperial)
        min_long_min = int(min(_guided_long(week_num), vol_km * 0.40)
                           / _easy_pace_kmh(family, week_paces) * 60)
        week_ws = _generate_week(
            week_start=week_start, week_end=week_end, phase=phase,
            week_tss=km_to_tss(vol_km, family, week_paces), ctx=ctx,
            build_idx=build_idx, race_distance_m=race_distance_m,
            is_race_week=is_race_week, days_per_week=dpw,
            occurrence_counters=occurrence_counters, today=today,
            intensity=plan_intensity * start_intensity(start, week_num),
            min_long_min=min_long_min, week_num=week_num,
            quality=start_quality(activity_frequency, week_num, no_history),
        )
        workouts.extend(week_ws)

        if week_num % cycle_len != cycle_len - 1 and phase in ("build", "peak"):
            build_idx += 1

    return vdot, workouts


# ─────────────────────────────────────────
# Triathlon
# ─────────────────────────────────────────

# Weekly load a triathlon plan builds to, by race distance (TSS): sprint
# ≈ 6–8 h a week at peak, Olympic ≈ 8–10 h, 70.3 ≈ 10–12 h, full ≈ 14–16 h
# (Friel, *The Triathlete's Training Bible*, annual-hours tables), at the
# ~56 TSS an hour an easy-heavy week averages.
_TRI_PEAK_TSS: list[tuple[float, float]] = [
    (40_000.0, 420.0), (80_000.0, 520.0), (170_000.0, 650.0),
]
_TRI_PEAK_TSS_FULL = 850.0
# Where a plan starts with no swim/bike/run history at all: about half of
# peak — enough to train the three sports, not a jump into race volume.
_TRI_START_SHARE = 0.5
_TRI_FAMILIES = ("swimming", "cycling", "running")


def _tri_peak_tss(race_distance_m: float) -> float:
    for limit, tss in _TRI_PEAK_TSS:
        if race_distance_m < limit:
            return tss
    return _TRI_PEAK_TSS_FULL


def _generate_triathlon_plan(goal, activity_history, pace_bests, today: date, *,
                             ftp, dpw: int, plan_intensity: float,
                             base_effective_weeks: float | None, imperial: bool,
                             lthr: int | None,
                             running_fitness: dict | None = None) -> tuple[float | None, list[dict]]:
    """A triathlon plan: swim, bike and run against one periodised load.

    The block periodisation, 3:1 cycles, 10% ramp, ACWR cap and taper are the
    single-sport plan's (above), applied to the week's *total* load — a
    triathlete's fatigue is one budget, and the taper has to be joint or the
    sport that kept its volume arrives tired. multisport.triathlon_week shares
    each week between the sports and schedules bricks.

    The load starts from the athlete's recent swim/bike/run hours (the last
    eight weeks, as easy-equivalent TSS), since a triathlete's history is in
    three sports and kilometres do not add across them.
    """
    from app.calculators.plan.generator.multisport import (
        recent_hours, triathlon_week,
    )
    from app.calculators.plan.load import EASY_IF, tss_per_hour

    race_date = goal.event_date
    race_distance_m = goal.event_distance_meters or 51_500.0
    plan_monday = today - timedelta(days=today.weekday())
    total_weeks = max(1, math.ceil((race_date - plan_monday).days / 7))

    vdot, pace_vdot, capacity_vdot = running_vdots(running_fitness, pace_bests)
    css = _css_pace_sec_per_100m(pace_bests)
    ew_base = _effective_weeks_base("running", capacity_vdot, base_effective_weeks)
    vdot_step = min(_WEEKLY_GAIN_VOLUME, 1.5 / max(total_weeks, 1)) if vdot is not None else 0.0

    hours = 0.0
    for fam in _TRI_FAMILIES:
        hours += recent_hours(activity_history, fam, today)
    peak = _tri_peak_tss(race_distance_m)
    start = hours / 8 * tss_per_hour(EASY_IF)
    # No swim/bike/run history: the intro weeks of an unanswered newcomer
    # (starting.py) — easy sessions only while the three sports' volume lands.
    no_history = start <= 0
    if no_history:
        start = peak * _TRI_START_SHARE
    max_tss = max(peak, start)
    run_km = _current_weekly_km(activity_history, "running", today)

    workouts: list[dict] = []
    counters: dict = {}
    weekly: list[float] = []
    build_idx = 0
    for week_num in range(total_weeks):
        week_start = plan_monday + timedelta(weeks=week_num)
        is_race_week = week_num == total_weeks - 1
        week_end = race_date if is_race_week else week_start + timedelta(days=6)
        phase = _phase_for_week(week_num, total_weeks)
        tss = _weekly_volume_km(week_num, total_weeks, start, max_tss,
                                (race_date - week_start).days / 7)
        # Volume–intensity coupling and ACWR, as the single-sport plan.
        if week_num > 0 and build_idx > 0 and phase in ("build", "peak") and weekly:
            tss = min(tss, weekly[-1] * (1.05 if phase == "build" else 1.03))
        if len(weekly) >= 4:
            chronic = sum(weekly[-4:]) / 4
            if chronic > 0 and tss / chronic > 1.3:
                tss = round(chronic * 1.3, 1)
        weekly.append(tss)

        week_vdot = vdot + week_num * vdot_step if vdot is not None else None
        paces = vdot_to_paces(week_vdot or pace_vdot)
        cap_km = running_capacity_km(ew_base + week_num * (dpw / 5.0), run_km)
        ctxs = [
            SportCtx(family="swimming", sport="swimming", css=css, lthr=lthr, imperial=imperial),
            # The triathlon bike leg is a time trial: steady, aero, no drafting.
            SportCtx(family="cycling", sport="cycling", ftp=ftp, lthr=lthr,
                     cycling_discipline="time_trial", imperial=imperial),
            SportCtx(family="running", sport="running", paces=paces, lthr=lthr,
                     capacity_km=cap_km, imperial=imperial),
        ]
        workouts.extend(triathlon_week(
            week_start=week_start, week_end=week_end, phase=phase, week_tss=tss,
            ctxs=ctxs, race_distance_m=race_distance_m, is_race_week=is_race_week,
            dpw=dpw, week_num=week_num, build_idx=build_idx, counters=counters,
            today=today, intensity=plan_intensity,
            quality=start_quality(None, week_num, no_history),
        ))
        if week_num % 4 != 3 and phase in ("build", "peak"):
            build_idx += 1
    return vdot, workouts
