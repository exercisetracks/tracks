# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
A running race on race day: the fitness the rest of the training calendar is
expected to add, and the form its taper should leave.

The race plan used to predict from today's VDOT and today's form (TSB). That
answered "what could you run today, as tired as you are today" — for a race
three months out, mid-build, at a fatigue the taper will have cleared. The
training calendar meanwhile built its own race week from today's VDOT plus a
weekly gain, so the two disagreed about the very race they were both for.

Now both read this module:

  race-day VDOT = today's VDOT + the plan's weekly gain × the weeks left × adherence

``vdot_step`` is the plan's own progression (+0.1 VDOT a week, at most +1.5
over the plan — generator/plan.py has always used it), so the race plan's
race-day fitness is exactly the race week the calendar paces. It is
conservative: recreational runners on a supervised ten-week plan took 3.6–5.0%
off their 10 K (Muñoz et al. 2014), about +0.25 VDOT a week, so a plan
followed in full is if anything under-credited — the projection errs slow,
and the measurement corrects it as the weeks pass.

*Adherence* is how much of the plan the last four weeks actually
delivered. The projection is a forecast, and the best forecast of the next
weeks' training is the last weeks': a runner who has done half the planned
running is credited half the gain still to come.

It converges by construction: every import moves today's VDOT (the measured
part) and adherence, every planned day that passes with nothing logged lowers
adherence, and the weeks left shrink to none, so on race day the projection
*is* the measurement. Nothing is stored — it is recomputed from the plan's
own history (past days are kept as they were: done, part-done or missed;
generation.py ``_replace_workouts``) every time it is read.

Adherence
---------
Over the last ``ADHERENCE_DAYS`` (or since the plan began, if later), the
minutes trained against the minutes planned, at most 1 — both counted in
running minutes, each minute at what its sport transfers to running
(``TRANSFER``):

- Planned: the plan's sessions, races aside. Today's counts only once it is
  done — a session is not missed until its day is over. A running plan's
  strength and mobility sessions weigh a tenth of their minutes; a
  triathlon's rides and swims weigh what they do for the run, so a
  triathlete who rides every ride and skips every run is not read as
  having followed the run plan.
- Done: every activity in the window, the same way — so a run on the wrong
  day, or an extra one, still counts. A session ticked off by hand with no
  recording counts ``MANUAL_CREDIT`` of its planned minutes: it was probably
  done, but nothing measured it.
- Both sides carry ``PRIOR_MINUTES`` of the plan done as planned, so the
  first missed run of a new plan is not read as a runner who quit.

Transfer (all heuristics, named here so they can be tuned): a run counts in
full. Leg-dominant aerobic sports — cycling, MTB, cross-country skiing —
count half: collegiate runners who swapped half their running for cycling
for five weeks lost 3.4% over 3,000 m against 1.4% running only (White et
al. 2003) — most of the fitness kept, not all. Swimming counts 0.35: in
trained runners, added swimming bought 37% of the 3.2 km time-trial gain that
added running did (Foster et al. 1995). Rowing sits between, 0.4; hiking —
a family that holds walks too — and paddling 0.3.
Strength, alpine skiing and anything unclassified count a tenth, climbing a
twentieth — they load running's tissues or its aerobic system little, and a
climbing session should not move a 10 K.

Form
----
Freshness is the race plan's own factor (±0.1% per point of TSB, at most
±3%), now read on race morning rather than today: the dashboard's load
series through today, then each remaining planned session's load
(load.workout_tss) through the eve of the race, by the dashboard's own
CTL/ATL recurrence (training_load.calculate_ctl_atl_tsb).
A taper shows up as the freshness it is meant to buy — Bosquet et al.
(2007): a two-week taper cutting volume 41–60% gives the best performance
gain — and today's fatigue mid-build no longer slows a race months away.

Pure; the phone's port is com.tracks.core.plan.RaceDay, held to this by
spec/fixtures/race_day.json.
"""

from __future__ import annotations

import math
from datetime import date, timedelta

from app.calculators.plan.base import _sport_family

# ── The plan's progression ───────────────────────────────────────────────────
# +0.1 VDOT a week (about +1 VDOT a training block, Daniels' pace of re-tests),
# at most +1.5 over a whole plan. What generator/plan.py has always paced the
# weeks with; read from here by both.
WEEKLY_GAIN = 0.10
MAX_GAIN = 1.5

# ── Adherence ────────────────────────────────────────────────────────────────
ADHERENCE_DAYS = 28
# Two sessions' worth, done as planned, on both sides of the ratio (module doc).
PRIOR_MINUTES = 120.0
# A session ticked off with no recording: probably done, nothing measured it.
MANUAL_CREDIT = 0.5
# What a minute of each sport family (base._sport_family) is worth to running.
TRANSFER = {
    "running": 1.0,
    # A multisport file — part of it is running.
    "triathlon": 0.6,
    "cycling": 0.5, "mountain_biking": 0.5, "nordic_skiing": 0.5,
    "rowing": 0.4,
    "swimming": 0.35,
    # Walks are in this family too (base._sport_family), at walking's effort.
    "hiking": 0.3, "paddling": 0.3,
    "alpine_skiing": 0.1, "strength": 0.1, "generic": 0.1,
    "climbing": 0.05,
}
# A race is the event itself, not training toward it.
_NOT_TRAINING = {"race"}

# ── Form ─────────────────────────────────────────────────────────────────────
# race_plan.py's freshness factor, unchanged: 0.1% per point of TSB, ±3%.
FRESHNESS_PER_TSB = 0.001
FRESHNESS_CAP = 0.03


def plan_weeks(today: date, race_date: date) -> int:
    """Weeks from this Monday to the race week, inclusive — the plan's
    ``total_weeks`` (generator/plan.py)."""
    monday = today - timedelta(days=today.weekday())
    return max(1, math.ceil((race_date - monday).days / 7))


def vdot_step(total_weeks: int) -> float:
    """VDOT gained a week over a plan of ``total_weeks``."""
    return min(WEEKLY_GAIN, MAX_GAIN / max(total_weeks, 1))


def race_day_vdot(vdot: float, today: date, race_date: date, adherence: float = 1.0) -> float:
    """Today's VDOT carried to the race week as the plan paces it, the gain
    scaled by ``adherence``. On or after race day, today's VDOT."""
    if race_date <= today:
        return vdot
    weeks = plan_weeks(today, race_date)
    # Grouped as generator/plan.py groups its race week (base + week ×
    # (step × adherence)), so the two agree to the bit.
    return vdot + (weeks - 1) * (vdot_step(weeks) * adherence)


def adherence(planned: list[dict], done: list[dict], today: date) -> float:
    """How much of the plan the last ``ADHERENCE_DAYS`` delivered, 0–1.

    ``planned``: the plan's sessions, ``{"date", "sport", "workout_type",
    "minutes", "complete", "recorded"}`` — ``recorded`` when a completed
    session has the activity that completed it. ``done``: every activity,
    ``{"date", "sport", "minutes"}``.
    """
    start = today - timedelta(days=ADHERENCE_DAYS - 1)
    begun = min((p["date"] for p in planned), default=None)
    if begun is not None and begun > start:
        start = begun

    planned_min: list[float] = []
    done_min: list[float] = []
    for p in planned:
        if not start <= p["date"] <= today or p.get("workout_type") in _NOT_TRAINING:
            continue
        if p["date"] == today and not p.get("complete"):
            continue
        minutes = float(p.get("minutes") or 0) * transfer(p.get("sport"))
        planned_min.append(minutes)
        if p.get("complete") and not p.get("recorded"):
            done_min.append(minutes * MANUAL_CREDIT)
    for a in done:
        if start <= a["date"] <= today:
            done_min.append(float(a.get("minutes") or 0) * transfer(a.get("sport")))
    return min(1.0, (_sum(done_min) + PRIOR_MINUTES) / (_sum(planned_min) + PRIOR_MINUTES))


def transfer(sport: str | None) -> float:
    """What a minute of ``sport`` is worth to running (``TRANSFER``)."""
    return TRANSFER.get(_sport_family((sport or "").lower()), TRANSFER["generic"])


def _sum(values: list[float]) -> float:
    """A sum independent of the order the rows arrived in, so the phone —
    whose replica returns them in another order — adds the same floats in
    the same order and lands on the same bits."""
    total = 0.0
    for v in sorted(values):
        total += v
    return total


def planned_load_by_day(sessions: list[tuple[date, float]]) -> dict[date, float]:
    """Each day's planned load from its sessions' ``(date, tss)``, summed in
    the same order whatever order the sessions came in (``_sum``)."""
    by_day: dict[date, list[float]] = {}
    for d, tss in sessions:
        by_day.setdefault(d, []).append(tss)
    return {d: _sum(v) for d, v in by_day.items()}


def freshness_factor(tsb: float | None) -> float:
    """The race plan's time multiplier for a TSB: below 1 when fresh."""
    if tsb is None:
        return 1.0
    return 1.0 - max(-FRESHNESS_CAP, min(FRESHNESS_CAP, tsb * FRESHNESS_PER_TSB))


def race_day_tsb(tss_by_date: dict[date, float], planned_tss: dict[date, float],
                 today: date, race_date: date) -> float | None:
    """TSB on race morning: the load so far, then the plan's remaining load
    as written through the eve of the race. None with no load history, as the
    dashboard has no form to show then. On race day, today's TSB.

    The remaining load is not scaled by adherence: adherence is counted in
    running minutes, which says nothing of a ride's taper, and race-morning
    form is set by the taper far more than by the weeks before it."""
    from app.calculators.training_load import calculate_ctl_atl_tsb

    if not tss_by_date:
        return None
    series = {d: t for d, t in tss_by_date.items() if d <= today}
    series.setdefault(today, 0.0)
    # Race morning's form is the eve's; a race today or past reads today's.
    eve = max(today, race_date - timedelta(days=1))
    for d, tss in planned_tss.items():
        if today < d <= eve:
            series[d] = series.get(d, 0.0) + tss
    series.setdefault(eve, 0.0)
    points = calculate_ctl_atl_tsb([{"date": d, "tss": t} for d, t in sorted(series.items())])
    # The eve is the series' last day. Rounded as race_helpers._race_form
    # rounds today's, from the chart's own one-decimal CTL and ATL.
    return round(points[-1]["ctl"] - points[-1]["atl"], 1)
