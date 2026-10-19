# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The plan answering how tired you are now.

A plan is generated week by week from fitness, but until this it took no
notice of fatigue: a 100 km ride the day before a planned run left the run as
it was, and a week of riding instead of running changed nothing about the next
run. This runs on every rebuild -- and the plan rebuilds after every import --
over the next few days of endurance sessions, and adjusts each one to the form
it will be done in.

Fatigue is the dashboard's own ATL, nothing new is stored. Form is CTL - ATL
(TSB), both decayed with no added load to the session's day: what the plan is
answering is "if you rest until then".

## The rules (docs/adaptive-plan-research.md has the reasoning)

- **TSB below -20: shortened and made easy.** About the edge of Coggan's
  "optimal training" band; past it a quality session is likely to be done
  badly and to deepen the hole. The session keeps its day at 70% of its
  length, at easy effort.
- **TSB below -30, or a big unplanned session in the last 48 h: moved.** -30
  is where the high-risk band starts. A "big" session is one of another sport
  (the plan did not schedule it) with more than twice the athlete's daily
  fitness in load -- the 100 km ride. The session moves one or two days later
  within its week, onto a day with no other endurance session, and is checked
  again there; with nowhere to go it is dropped.
- **Low-impact load counts a little less toward a run.** Cycling, swimming,
  rowing and skiing load the heart and lungs as running does but the legs'
  tendons and bones far less, and running injury risk follows the latter. So
  for an impact session (run, hike) the last three days of low-impact load
  count at 70% toward the fatigue it is judged on. Only at decision time; the
  ATL everyone sees is unchanged.

A race is never touched, nor anything that is not an endurance session
(strength and stretch flows are attached after this runs, and follow their
sessions). An adjusted session says so in its description.
"""
from __future__ import annotations

from datetime import date, timedelta
from typing import Iterable

from app.calculators.plan.base import (
    _sport_family, _workout_description, _workout_title,
)
from app.calculators.plan.generator.week import easy_session

# The EMA constants the dashboard's CTL/ATL use (training_load.py).
_CTL_DECAY = 41.0 / 42.0
_ATL_DECAY = 6.0 / 7.0

SHORTEN_TSB = -20.0
MOVE_TSB = -30.0
#: An unplanned session this many times the daily CTL moves the next one.
BIG_SESSION_RATIO = 2.0
#: How long ago a big session still counts.
BIG_SESSION_HOURS = 48
#: Low-impact load's weight toward an impact session's fatigue.
LOW_IMPACT_WEIGHT = 0.7
#: How many days of sessions are adjusted, starting today.
WINDOW_DAYS = 3
#: How far back low-impact load is discounted.
LOW_IMPACT_DAYS = 3
SHORTEN_FACTOR = 0.7
MIN_SHORTENED_MIN = 20
#: How far a session may move.
MAX_MOVE_DAYS = 2

_IMPACT = {"running", "hiking", "trail_running"}
_LOW_IMPACT = {"cycling", "mountain_biking", "swimming", "rowing", "nordic_skiing"}
#: Not endurance sessions: left alone here.
_NOT_ENDURANCE = {"strength_training", "strength", "flexibility_training", "flexibility",
                  "mobility", "field_test", "race"}


def adapt_to_fatigue(
    workouts: list[dict],
    *,
    today: date,
    ctl: float,
    atl: float,
    recent: Iterable[tuple[date, str | None, float]],
    imperial: bool = False,
) -> list[dict]:
    """``workouts`` adjusted to the form each of the next days' sessions will
    be done in. ``recent`` is (local day, sport, TSS) per activity, the last
    few days' worth; days after ``today`` are ignored. Pure: returns a new
    list and leaves the input alone."""
    recent = [(d, (s or "").lower(), float(t or 0.0)) for d, s, t in recent if d <= today]
    out = [dict(w) for w in workouts]
    if ctl <= 0:
        return out

    window_end = today + timedelta(days=WINDOW_DAYS - 1)
    candidates = sorted(
        (w for w in out if _adjustable(w) and today <= w["scheduled_date"] <= window_end),
        key=lambda w: (w["scheduled_date"], w.get("workout_type") or ""),
    )
    dropped: list[dict] = []
    # A big session moves the first session after it, not every one: the
    # ones beyond answer to form alone, or one ride would push the whole
    # week back a day at a time.
    big_answered = False
    for w in candidates:
        day = w["scheduled_date"]
        family = _sport_family((w.get("sport") or "").lower())
        tsb = _form_on(day, today=today, ctl=ctl, atl=atl, recent=recent, family=family)
        big = None if big_answered else _big_unplanned(day, recent=recent, ctl=ctl, family=family)
        big_answered = big_answered or big is not None
        if tsb < MOVE_TSB or big:
            target = _free_day(out, w, family)
            if target is None:
                dropped.append(w)
                continue
            w["scheduled_date"] = target
            why = (f"after {big} in the last two days" if big else
                   f"you are carrying a lot of fatigue (form {round(tsb)})")
            _note(w, f"Moved to {target.strftime('%A')}: {why}.")
            tsb = _form_on(target, today=today, ctl=ctl, atl=atl, recent=recent, family=family)
            if tsb < SHORTEN_TSB:
                _shorten(w, out, family, imperial, f"form {round(tsb)}")
        elif tsb < SHORTEN_TSB:
            _shorten(w, out, family, imperial, f"form {round(tsb)}")
    return [w for w in out if not any(w is d for d in dropped)]


def _adjustable(w: dict) -> bool:
    wtype = (w.get("workout_type") or "").lower()
    sport = (w.get("sport") or "").lower()
    if wtype in _NOT_ENDURANCE or sport in _NOT_ENDURANCE:
        return False
    return not w.get("is_complete")


def _form_on(day: date, *, today: date, ctl: float, atl: float,
             recent: list, family: str) -> float:
    """TSB on ``day`` resting until then, with low-impact load discounted
    for an impact session."""
    ahead = (day - today).days
    ctl_d = ctl * _CTL_DECAY ** ahead
    atl_d = atl * _ATL_DECAY ** ahead
    if family in _IMPACT:
        since = today - timedelta(days=LOW_IMPACT_DAYS - 1)
        for d, sport, tss in recent:
            if d < since or _sport_family(sport) not in _LOW_IMPACT:
                continue
            share = tss * (1 - _ATL_DECAY) * _ATL_DECAY ** (day - d).days
            atl_d -= (1 - LOW_IMPACT_WEIGHT) * share
    return ctl_d - atl_d


def _big_unplanned(day: date, *, recent: list, ctl: float, family: str) -> str | None:
    """A description of a big session of another sport in the 48 h before
    ``day``, or None. Another sport is the plan's own word for "unplanned":
    a plan for this sport did not schedule it."""
    since = day - timedelta(days=BIG_SESSION_HOURS // 24)
    for d, sport, tss in recent:
        # Earlier the same day counts: a ride this morning, a run this evening.
        if not since <= d <= day:
            continue
        if _sport_family(sport) == family:
            continue
        if tss > BIG_SESSION_RATIO * ctl:
            return f"a big {sport.replace('_', ' ')} session"
    return None


def _free_day(workouts: list[dict], w: dict, family: str) -> date | None:
    """The first of the next ``MAX_MOVE_DAYS`` days in the session's week
    with no other endurance session on it."""
    day = w["scheduled_date"]
    week_end = day + timedelta(days=6 - day.weekday())
    for step in range(1, MAX_MOVE_DAYS + 1):
        target = day + timedelta(days=step)
        if target > week_end:
            break
        busy = any(o is not w and o["scheduled_date"] == target and _adjustable(o) for o in workouts)
        if not busy:
            return target
    return None


def _shorten(w: dict, workouts: list[dict], family: str, imperial: bool, why: str) -> None:
    """The session at 70% of its length, at easy effort."""
    minutes = int(w.get("duration_minutes") or 0)
    if minutes <= 0:
        return
    target = max(MIN_SHORTENED_MIN, int(round(minutes * SHORTEN_FACTOR / 5.0)) * 5)
    if target >= minutes:
        return
    easy = easy_session(family)
    steps = w.get("steps") or []
    warm = [s for s in steps if s.get("type") == "walk"]
    walk_min = sum(int(s.get("duration_min") or 0) for s in warm)
    main_min = max(5, target - walk_min)
    template = _easy_step(workouts, w, family)
    main = dict(template)
    main["duration_min"] = main_min
    new_steps = ([warm[0]] if warm else []) + [main] + ([warm[-1]] if len(warm) > 1 else [])
    speed = _easy_speed(workouts, family)
    distance = round(speed * (main_min / 60.0) * 1000) if speed else None
    w["workout_type"] = easy
    w["steps"] = new_steps
    w["duration_minutes"] = sum(int(s.get("duration_min") or 0) for s in new_steps)
    w["distance_meters"] = distance
    w["title"] = _workout_title(easy, family, distance, w["duration_minutes"], imperial=imperial)
    w["description"] = _workout_description(new_steps)
    _note(w, f"Shortened and made easy: you are carrying fatigue ({why}).")


def _easy_step(workouts: list[dict], w: dict, family: str) -> dict:
    """The plan's own easy step for this sport, so the pace note matches."""
    easy = easy_session(family)
    for o in workouts:
        if o.get("workout_type") == easy and _sport_family((o.get("sport") or "").lower()) == family:
            for s in o.get("steps") or []:
                if s.get("type") != "walk":
                    return {k: v for k, v in s.items() if k != "duration_min"}
    kind = "run" if family == "running" else "steady"
    return {"type": kind, "pace": "easy", "note": "Easy, conversational effort"}


def _easy_speed(workouts: list[dict], family: str) -> float | None:
    """Easy pace, km/h, read off the plan's own easy sessions (running only:
    other sports' sessions carry no distance)."""
    if family != "running":
        return None
    easy = easy_session(family)
    for o in workouts:
        if o.get("workout_type") == easy and o.get("distance_meters") and o.get("duration_minutes"):
            walk = sum(int(s.get("duration_min") or 0) for s in o.get("steps") or [] if s.get("type") == "walk")
            moving = int(o["duration_minutes"]) - walk
            if moving > 0:
                return o["distance_meters"] / 1000.0 / (moving / 60.0)
    return None


def _note(w: dict, text: str) -> None:
    w["description"] = f"{text}\n{w.get('description') or ''}".strip()
