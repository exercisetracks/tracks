# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A recommended date for a new race / event goal.

The goal form pre-fills its date with this so a plan can be built at once,
and shows the reasons behind the date's "?". The person still picks their real
date; this only answers "when could I be ready for this?".

Two answers, in order of preference:

- **From history.** How much fitness (CTL) the event asks for, against the
  fitness the person has now — discounted by how little of it came from the
  event's sport, since a cyclist's CTL only partly carries to a marathon. The
  gap is built at +3 CTL a week (the top of the fitness slider's "Build"
  band: a ramp people absorb for months), plus a two-week taper, never less
  than half the general span (even a fit athlete wants specific preparation
  for a long event) and never more than 30 weeks.
- **General**, when there is no training in the last six weeks to judge
  from: one to six months by how long the event takes — about four weeks
  for a 5K, about six months for a marathon, an ultra or a full Ironman.

The phone runs the same rules offline (com.tracks.core.plan.EventDate), held
to this file by spec/fixtures/goal_planning.json — the web asks the server,
and the two must agree on the date a person sees. Everything here is plain
arithmetic on floats with no rounding ties left to language defaults
(``floor(x + 0.5)`` rather than ``round``): Python and Kotlin both round half
to even, but JavaScript and Kotlin's roundToInt do not, and the spec README
records how often that has bitten.
"""

from __future__ import annotations

import math
from datetime import date, timedelta

from app.calculators.plan.base import _sport_family

# Rough race speeds (km/h) — only to turn a distance into how long the event
# lasts, which is what preparation scales with. A triathlon's is its whole
# distance over its whole time: 51.5 km in ~2.6 h for an Olympic.
_SPEED_KMH = {
    "running": 10.0,
    "cycling": 25.0,
    "mountain_biking": 15.0,
    "swimming": 3.0,
    "triathlon": 20.0,
    "hiking": 4.0,
    "rowing": 12.0,
    "paddling": 8.0,
}
_DEFAULT_SPEED_KMH = 10.0

# An event with no distance ("Stage Race", "Peak / Summit") is taken as a
# mid-length one, rather than guessing from its name.
_DEFAULT_HOURS = 2.0

# (event hours, weeks of preparation) — the general rule, interpolated. A 5K
# (~0.5 h) near one month, a marathon (~4 h) about four months, anything of
# eight hours or more (ultra, full Ironman, double century) six months.
_GENERAL = ((0.5, 4.0), (1.0, 7.0), (2.0, 11.0), (4.0, 18.0), (8.0, 26.0))

# A strength block (bodybuilding, powerlifting) has no duration to scale by.
_STRENGTH_WEEKS = 12

BUILD_PER_WEEK = 3.0   # CTL a week — the fitness slider's RAMP_RISK
TAPER_WEEKS = 2
MAX_WEEKS = 30
HISTORY_DAYS = 42      # CTL's own time constant: "recent training"


def _half_up(x: float) -> int:
    return math.floor(x + 0.5)


def event_hours(sport: str | None, distance_m: float | None) -> float | None:
    """How long the event lasts, roughly; None when it has no distance."""
    if not distance_m or distance_m <= 0:
        return None
    family = _sport_family((sport or "running").lower())
    return distance_m / 1000.0 / _SPEED_KMH.get(family, _DEFAULT_SPEED_KMH)


def general_weeks(hours: float) -> int:
    """The general rule's weeks of preparation for an event of ``hours``."""
    pts = _GENERAL
    if hours <= pts[0][0]:
        return _half_up(pts[0][1])
    for (h0, w0), (h1, w1) in zip(pts, pts[1:]):
        if hours <= h1:
            return _half_up(w0 + (w1 - w0) * (hours - h0) / (h1 - h0))
    return _half_up(pts[-1][1])


def target_ctl(hours: float) -> float:
    """The fitness an event of ``hours`` asks for to be run well, not survived:
    ~30 for a 5K, ~65 for a marathon, capped at 90 for the longest."""
    return min(90.0, 25.0 + 10.0 * hours)


def counts_toward(event_sport: str | None, activity_sport: str | None) -> bool:
    """Whether training in ``activity_sport`` is training for this event.

    A triathlon takes all three of its sports; road and mountain bikes carry
    to each other; an event of no known family ("other", skiing) takes
    everything, since nothing narrower can be said about it.
    """
    ef = _sport_family((event_sport or "running").lower())
    af = _sport_family((activity_sport or "").lower())
    if ef == "generic":
        return True
    if ef == "triathlon":
        return af in ("running", "cycling", "swimming", "triathlon")
    if ef in ("cycling", "mountain_biking"):
        return af in ("cycling", "mountain_biking")
    return af == ef


def _fmt_duration(hours: float) -> str:
    minutes = _half_up(hours * 60 / 5) * 5
    if minutes < 60:
        return f"{minutes} min"
    halves = _half_up(hours * 2)
    return f"{halves // 2}{'.5' if halves % 2 else ''} h"


def _sunday_on_or_after(d: date) -> date:
    # Most races are on a weekend; a Sunday is the likelier guess than a Tuesday.
    return d + timedelta(days=(6 - d.weekday()) % 7)


def recommend_event_date(
    sport: str | None,
    distance_m: float | None,
    today: date,
    ctl: float | None,
    sport_tss: float,
    total_tss: float,
) -> dict:
    """The recommended date for an event, and why.

    ``ctl`` is the person's fitness today; ``sport_tss`` and ``total_tss``
    are the training load of the last ``HISTORY_DAYS`` days that counts
    toward this event (``counts_toward``) and in all. Returns ``date`` (ISO),
    ``weeks``, ``basis`` ("history" or "general") and ``reasons``: short
    sentences for the "?" beside the date.
    """
    family = _sport_family((sport or "running").lower())
    if family == "strength":
        weeks = _STRENGTH_WEEKS
        return _result(today, weeks, "general", [
            f"A strength block runs about {weeks} weeks.",
        ])

    hours = event_hours(sport, distance_m)
    known = hours is not None
    if hours is None:
        hours = _DEFAULT_HOURS
    general = general_weeks(hours)
    if known:
        demand = f"An event of about {_fmt_duration(hours)} usually gets about {general} weeks of preparation."
    else:
        demand = f"With no distance set, a mid-length event is assumed: about {general} weeks of preparation."

    if ctl is None or ctl <= 0 or total_tss <= 0:
        return _result(today, general, "general", [
            demand,
            "There is no recent training to judge your fitness from, so this is the general recommendation.",
        ])

    share = min(1.0, max(0.0, sport_tss / total_tss))
    effective = ctl * (0.5 + 0.5 * share)
    target = target_ctl(hours)
    build = math.ceil(max(0.0, target - effective) / BUILD_PER_WEEK)
    floor_weeks = max(4, _half_up(general / 2))
    weeks = min(MAX_WEEKS, max(floor_weeks, build + TAPER_WEEKS))

    if family == "generic":
        fitness = f"Your fitness is CTL {_half_up(ctl)}."
    else:
        fitness = f"Your fitness is CTL {_half_up(ctl)}, {_half_up(share * 100)}% of it from this sport."
    reasons = [fitness]
    if build + TAPER_WEEKS >= floor_weeks:
        reasons.append(
            f"This event wants about CTL {_half_up(target)}: at +{_half_up(BUILD_PER_WEEK)} a week "
            f"that is {build} weeks of building, plus {TAPER_WEEKS} to taper."
        )
    else:
        have = "you already have" if effective >= target else "you are close to"
        reasons.append(
            f"This event wants about CTL {_half_up(target)}, which {have}: "
            f"{weeks} weeks leaves time to sharpen and taper."
        )
    if weeks == MAX_WEEKS and build + TAPER_WEEKS > MAX_WEEKS:
        reasons.append(f"Capped at {MAX_WEEKS} weeks: a shorter event first would get you there sooner.")
    return _result(today, weeks, "history", reasons)


def _result(today: date, weeks: int, basis: str, reasons: list[str]) -> dict:
    return {
        "date": _sunday_on_or_after(today + timedelta(weeks=weeks)).isoformat(),
        "weeks": weeks,
        "basis": basis,
        "reasons": reasons,
    }
