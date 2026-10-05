# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
The rolling plan for a Fitness goal: no race, a chosen CTL change per week.

An event plan works backwards from a date. A fitness goal has none; what it has
is a rate — ``ctl_ramp_per_week``, CTL points per week, −2 … +4 (0 = hold) —
and the athlete's current CTL and ATL. This module turns those into four weeks
of load targets and hands each week to the event generator's own
``_generate_week``, so workout choice, polarisation, the quality-day caps and
every sport family's builders are shared rather than written twice.

Load from the ramp
------------------
CTL is a 42-day exponential average: each day it moves by the fraction
``1 − k`` (k = training_load._CTL_DECAY) of the gap between the day's load and
itself. Holding a daily load L for seven days from CTL C therefore ends at

    C' = L + (C − L)·k⁷,   so to move CTL by r in a week   L = C + r / (1 − k⁷)

which is about C + 6.5·r TSS a day. The week's target is 7·L, and the next
week starts from the projected C'. Holding CTL (r = 0) means training at your
CTL every day, which is what CTL is.

Why rolling
-----------
The plan covers this week and the next three, and is rebuilt the way event
plans are (the generate endpoint and the refresh on activity import) from the
CTL actually measured. A week that went off plan moves the next target instead
of compounding, which a fixed long plan built from a guessed CTL could not do.

Every fourth week lighter
-------------------------
Counted from the goal's anchor Monday — the day its row was first written, the
same day on every device — not from the plan's first week. A plan rebuilt every
few days always starts "now"; counted from there the light week would always be
three weeks away and never arrive. The light week trains at 75% of the week's
starting CTL a day: CTL eases by about 4% while fatigue clears, the usual
3:1 shape (Foster 2001) the event generator also uses.

Form guard
----------
Starting deep in fatigue — TSB below −30, the "High Risk" band in
spec/zones.yaml — the first week holds CTL rather than building on it.

From load to the week generator
-------------------------------
The week's TSS goes to the week generator as it is: sessions are sized until
their estimated load (plan/load.py) carries it (week.py). It used to be turned
into kilometres at a flat 55 TSS an hour and capped at 80 km a week, and the
plan stopped delivering the ramp at about +3.5 a week — past CTL 50 it could
not hold CTL at all. What still bounds a week is the per-session ceilings at
the chosen days per week; the rebuild from measured CTL absorbs the rest.

Several sports
--------------
A fitness goal may name several sports (``fitness_sports``). The week's load
is one number either way — CTL is the sum over every sport — and multisport.py
shares it out by the athlete's recent history and places the sports so hard
days and impact days do not stack. A goal whose sport is triathlon trains the
three triathlon sports.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Sequence

from app.calculators.plan.base import (
    _capacity_km,
    _css_pace_sec_per_100m,
    _sport_family,
    vdot_to_paces,
)
from app.calculators.plan.generator.multisport import (
    fitness_multisport_week,
    fitness_shares,
)
from app.calculators.plan.generator.plan import (
    _effective_weeks_base, _goal_disciplines, running_vdots,
)
from app.calculators.plan.generator.week import (
    running_capacity_km,
    SportCtx,
    _easy_pace_kmh,
    _generate_week,
)
from app.calculators.plan.load import EASY_IF, tss_per_hour
from app.calculators.plan.starting import (
    frequency_for,
    no_history_start,
    start_intensity,
    start_quality,
)
from app.calculators.training_load import _CTL_DECAY

FITNESS_WEEKS = 4          # the rolling horizon, and the build/recovery cycle
# The slider's range (schemas/coaching.py CtlRamp says where the top comes
# from: the ramps the plan measurably delivers).
RAMP_MIN, RAMP_MAX = -2.0, 6.0
# k⁷ by repeated multiplication, not `**`: pow() may differ in the last bit
# between CPython's libm and the JVM, and the phone's port must land on the
# same numbers (spec/fixtures/plan.json). Seven IEEE multiplies cannot.
_CTL_WEEK_DECAY = 1.0
for _ in range(7):
    _CTL_WEEK_DECAY *= _CTL_DECAY
_CTL_WEEK_SHARE = 1 - _CTL_WEEK_DECAY   # share of the load–CTL gap a week closes
_RECOVERY_LOAD = 0.75                    # light week: daily load as a share of CTL
_FORM_FLOOR = -30.0                      # zones.yaml high_risk upper edge
_BUILD_PHASE_RAMP = 3.0                  # from here a week gets the build template

# No race: the only builders that read a race distance are race-pace sessions,
# which appear only in peak/taper templates a fitness plan never uses. A value
# is still needed to call them; 10 km is the neutral one.
_NO_RACE_M = 10000.0


def _monday(d: date) -> date:
    return d - timedelta(days=d.weekday())


def fitness_week_targets(ctl: float, atl: float, ramp: float | None,
                         today: date, anchor: date | None) -> list[dict]:
    """The four weeks' load targets, from this week's Monday.

    Each: week_start, recovery (the light week of the cycle), ramp (what the
    week is asked to change CTL by; None for the light week), tss (the week's
    load) and ctl_end (where CTL lands if the week is done as planned).
    """
    r = max(RAMP_MIN, min(RAMP_MAX, float(ramp or 0.0)))
    monday = _monday(today)
    anchor_monday = _monday(anchor or today)
    c = float(ctl or 0.0)
    out = []
    for w in range(FITNESS_WEEKS):
        start = monday + timedelta(weeks=w)
        recovery = ((start - anchor_monday).days // 7) % FITNESS_WEEKS == FITNESS_WEEKS - 1
        week_ramp = r
        if w == 0 and c - float(atl or 0.0) < _FORM_FLOOR:
            week_ramp = min(week_ramp, 0.0)
        daily = c * _RECOVERY_LOAD if recovery else c + week_ramp / _CTL_WEEK_SHARE
        daily = max(daily, 0.0)
        c_end = daily + (c - daily) * _CTL_WEEK_DECAY
        out.append({
            "week_start": start,
            "recovery": recovery,
            "ramp": None if recovery else week_ramp,
            "tss": round(daily * 7, 1),
            "ctl_end": round(c_end, 1),
        })
        c = c_end
    return out


_TRIATHLON_SPORTS = ["swimming", "cycling", "running"]


def goal_sports(goal) -> list[str]:
    """The endurance sports a fitness goal trains, one per sport family.

    ``fitness_sports`` when it names any, else ``event_sport``. Triathlon
    stands for its three sports; strength is left out — it is planned by the
    strength settings (include_strength), not as endurance load.
    """
    raw = getattr(goal, "fitness_sports", None) or []
    picked = [s.lower() for s in raw if isinstance(s, str) and s]
    if not picked:
        picked = [(goal.event_sport or "running").lower()]
    out: list[str] = []
    families: list[str] = []
    for s in picked:
        expanded = _TRIATHLON_SPORTS if _sport_family(s) == "triathlon" else [s]
        for e in expanded:
            fam = _sport_family(e)
            if fam == "strength" or fam in families:
                continue
            families.append(fam)
            out.append(e)
    return out or ["running"]


def generate_fitness_plan(
    goal,
    activity_history: Sequence,
    pace_bests: list[tuple[int, float]],
    today: date,
    ctl: float,
    atl: float,
    anchor: date | None = None,
    ftp: int | None = None,
    days_per_week: int | None = None,
    base_effective_weeks: float | None = None,
    imperial: bool = False,
    threshold_hr: int | None = None,
    activity_frequency: str | None = None,
    activity_frequencies: dict | None = None,
    running_fitness: dict | None = None,
) -> tuple[float | None, list[dict]]:
    """Four weeks of workouts for a fitness goal. Returns (vdot, workouts).

    ``running_fitness`` is today's running-fitness estimate, as the event
    plan reads it (plan.running_vdots).

    Load comes from ``ctl``/``atl``, which already summarise the history;
    ``activity_history`` decides how a multi-sport goal shares it out.

    How often each sport is done (starting.py) is read only with no load
    history: ``activity_frequencies`` is the settings map (sport family →
    level), looked up per sport; without it, ``activity_frequency`` is the
    level of the goal's first sport and the others have none.
    """
    sports = goal_sports(goal)
    dpw = getattr(goal, "days_per_week", None) or days_per_week or 4
    mtb_discipline, cycling_discipline = _goal_disciplines(goal)
    lthr_int = int(threshold_hr) if threshold_hr else None
    families = [_sport_family(s) for s in sports]

    if "running" in families:
        vdot, pace_vdot, capacity_vdot = running_vdots(running_fitness, pace_bests)
        paces = vdot_to_paces(vdot or pace_vdot)
    else:
        vdot, capacity_vdot, paces = None, None, None
    css = _css_pace_sec_per_100m(pace_bests) if "swimming" in families else None
    ew_base = _effective_weeks_base("running", capacity_vdot, base_effective_weeks)
    # What the athlete's load already says they can run without a break: a
    # runner holding CTL C covers about C·7 TSS of easy running a week, and a
    # continuous run of 40% of a week's distance is the long-run rule of thumb.
    # The effective-weeks model alone (built for novices) put a CTL-60 runner's
    # capacity below their long run and broke it into walk intervals.
    load_km = (float(ctl or 0.0) * 7 / tss_per_hour(EASY_IF)
               * _easy_pace_kmh("running", paces)) if paces else 0.0
    # No load history at all (CTL under 1): each sport starts from how often
    # they say they do it (starting.py) — its own answer, so a goal of
    # running and cycling for someone who rides five times a week and has
    # never run shortens and eases the runs and not the rides. Each answer
    # stands for that sport's steady-state CTL, and CTL is a sum over sports,
    # so the load starts at their sum, as fresh (ATL = CTL) as a steady
    # routine leaves you, and is shared out in those proportions — not by
    # the equal split an empty history gives, which put half a regular
    # cyclist's load into a never-runner's first runs (an hour each). A sport
    # answered "never" gets no share: its days stay, at the shortest session.
    starts: list[dict | None] = [None] * len(sports)
    levels: list[str | None] = [None] * len(sports)
    no_history = float(ctl or 0.0) < 1.0
    if no_history:
        for i, fam in enumerate(families):
            if activity_frequencies is not None:
                levels[i] = frequency_for(activity_frequencies, fam)
            else:
                levels[i] = activity_frequency if i == 0 else None
            starts[i] = no_history_start(levels[i])
    start_ctls = [s["ctl"] if s is not None else 0.0 for s in starts]
    start_ctl = 0.0
    for c in start_ctls:
        start_ctl += c
    if any(s is not None for s in starts):
        ctl = max(float(ctl or 0.0), start_ctl)
        atl = max(float(atl or 0.0), start_ctl)
    # Only running reads the capacity model; its answer moves it, as the
    # event plan's does. load_km stays the measured load's, not the answer's.
    run_start = starts[families.index("running")] if "running" in families else None
    if run_start is not None and capacity_vdot is None:
        ew_base = max(ew_base, run_start["effective_weeks"])

    def ctx_for(sport: str, w: int) -> SportCtx:
        fam = _sport_family(sport)
        return SportCtx(
            family=fam, sport=sport,
            paces=paces if fam == "running" else None, ftp=ftp,
            css=css if fam == "swimming" else None, lthr=lthr_int,
            capacity_km=running_capacity_km(ew_base + w * (dpw / 5.0), load_km)
            if fam == "running" else None,
            mtb_discipline=mtb_discipline, cycling_discipline=cycling_discipline,
            imperial=imperial,
        )

    if len(sports) == 1:
        shares = [1.0]
    elif start_ctl > 0:
        shares = [c / start_ctl for c in start_ctls]
    else:
        shares = fitness_shares(families, activity_history, today)
    workouts: list[dict] = []
    counters: dict = {}
    build_idx = 0
    for w, t in enumerate(fitness_week_targets(ctl, atl, goal.ctl_ramp_per_week, today, anchor)):
        building = not t["recovery"] and t["ramp"] >= _BUILD_PHASE_RAMP
        phase = "build" if building else "base"
        if len(sports) > 1:
            workouts.extend(fitness_multisport_week(
                week_start=t["week_start"], phase=phase, week_tss=t["tss"],
                ctxs=[ctx_for(s, w) for s in sports], shares=shares, dpw=dpw,
                week_num=w, build_idx=build_idx, counters=counters, today=today,
                race_m=_NO_RACE_M,
                intensities=[start_intensity(st, w) for st in starts],
                quality=[start_quality(lv, w, no_history) for lv in levels],
            ))
        else:
            workouts.extend(_generate_week(
                week_start=t["week_start"], week_end=t["week_start"] + timedelta(days=6),
                phase=phase, week_tss=t["tss"], ctx=ctx_for(sports[0], w),
                build_idx=build_idx, race_distance_m=_NO_RACE_M, is_race_week=False,
                days_per_week=dpw, occurrence_counters=counters, today=today,
                intensity=start_intensity(starts[0], w), min_long_min=None, week_num=w,
                trim_days=True, quality=start_quality(levels[0], w, no_history),
            ))
        # Interval sets progress across build weeks and restart after a light
        # one, as the event plan's build index does across its 3:1 cycles.
        build_idx = build_idx + 1 if building else 0
    return vdot, workouts


def fitness_horizon_end(today: date) -> date:
    """The last day a fitness plan covers: the Sunday of its fourth week."""
    return _monday(today) + timedelta(weeks=FITNESS_WEEKS, days=-1)
