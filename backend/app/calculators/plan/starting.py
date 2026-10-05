# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Where a plan starts for someone with no history of its sport.

With no recorded activities the generators used to assume one athlete for
everybody: the sport's default weekly volume (20 km of running) and the
bottom of the walk-break capacity model. That put a regular runner who had
just installed the app on the same first week as someone who had never run —
too little for one, and for the other a 3 km first run is already too much.

So setup asks how often each endurance sport is done
(``user_settings.activity_frequency``) and, *only while there is no history of
that sport*, the answer sets four starting points:

  - ``weekly_km_share``: the event plan's starting weekly volume, as a share of
    the sport's default (``_SPORT_DEFAULT_WEEKLY_KM``). 1–2× a week is the old
    default (share 1.0), so an unanswered question and that answer agree.
  - ``effective_weeks``: where the running capacity model starts
    (``_capacity_km`` = 1.5 × 1.15^weeks km of continuous running). 0 keeps a
    newcomer on run/walk intervals; 16 is ~14 km, i.e. no walk breaks at all.
  - ``ctl``: the fitness plan's starting CTL (it has no weekly volume; it
    builds load from CTL). Roughly the steady-state CTL of that many easy
    sessions a week. 0 keeps today's behaviour for a newcomer.
  - ``intensity``: a multiplier on session length (the week generator's
    ``intensity``) for the first weeks, easing to 1 over ``RAMP_WEEKS``. The
    week generator floors an easy session at 20 minutes and a long run at 45,
    which no weekly-volume number can get under; for someone who has never
    done the sport those floors *are* the problem, so the first weeks are
    shortened instead (0.6 gives ~15-minute run/walk sessions).

The first weeks of a plan with no history also hold no quality sessions
(``start_quality``): they become the sport's easy session. Shortening alone
could not fix them — a fartlek is 10 minutes of warm-up, at least 20 of
hard/easy blocks and a cool-down whatever length it is asked for, and none of
it has walk breaks, so a never-runner's first week held a 45-minute session of
threshold surges. How many weeks (``_INTRO_WEEKS``):

  - never / occasional: until the shortened sessions are back to full length
    (``RAMP_WEEKS``). They build the habit and the tissue tolerance first;
    bone and tendon adapt over weeks, far slower than the cardiovascular
    system does (Magnusson et al. 2010), which is why novice running
    injuries cluster in the first weeks (Nielsen et al. 2012, 2014).
  - 1–2× a week: three weeks. The plan already asks them for more days a
    week than they do; raising frequency and intensity together is the
    simultaneous climb the generator avoids everywhere else (Foster 2001;
    Gabbett 2016), so the frequency goes first.
  - 3–4× / 5+ a week: none. They are training now, as history would show.
  - No answer: three weeks too. It used to mean "the behaviour before this
    existed", which was one default athlete with a fartlek in week one — for
    someone the app knows nothing about, which is most people on their first
    plan. Not knowing is not evidence of training, and three easy weeks cost a
    trained athlete very little (Daniels' first phase is easy running for
    everyone), where a first-week fartlek can cost a newcomer the plan.

History always wins: the moment there are activities of the sport (event plan:
≥3 km a week over the last 8 weeks; fitness plan: CTL ≥ 1), they are what the
plan reads, and this answer is ignored. No answer, or an unknown one, leaves
the starting volume where it was before this existed; only the intro weeks
apply to it.

Ported to the phone as com.tracks.core.plan.PlanStart and held to it by
spec/fixtures/plan_start.json.
"""

from __future__ import annotations

LEVELS = ("never", "occasional", "1_2", "3_4", "5_plus")

# level -> (weekly_km_share, effective_weeks, ctl, intensity)
_START: dict[str, tuple[float, float, float, float]] = {
    "never":      (0.35, 0.0, 0.0, 0.6),
    "occasional": (0.6, 2.0, 8.0, 0.8),
    "1_2":        (1.0, 5.0, 15.0, 1.0),
    "3_4":        (1.5, 10.0, 30.0, 1.0),
    "5_plus":     (2.0, 16.0, 45.0, 1.0),
}

# Weeks over which the shortened first sessions grow back to full length.
RAMP_WEEKS = 6

# Weeks of no quality sessions for someone with no history, by answer (see
# the module docstring); an unanswered or unknown level gets _UNANSWERED_INTRO.
_INTRO_WEEKS: dict[str, int] = {
    "never": RAMP_WEEKS, "occasional": RAMP_WEEKS, "1_2": 3, "3_4": 0, "5_plus": 0,
}
_UNANSWERED_INTRO = 3

# A family nobody was asked about borrows the nearest one that was: a mountain
# biker who said how often they ride said it about bikes.
_BORROW = {"mountain_biking": "cycling"}


def frequency_for(frequencies, family: str) -> str | None:
    """The answer for ``family`` from the settings map, or None if not given."""
    if not isinstance(frequencies, dict):
        return None
    level = frequencies.get(family)
    if level not in _START and family in _BORROW:
        level = frequencies.get(_BORROW[family])
    return level if level in _START else None


def no_history_start(level: str | None) -> dict | None:
    """The starting points for a frequency answer; None when there is none."""
    start = _START.get(level) if level else None
    if start is None:
        return None
    share, weeks, ctl, intensity = start
    return {"weekly_km_share": share, "effective_weeks": weeks, "ctl": ctl,
            "intensity": intensity}


def start_intensity(start: dict | None, week_num: int) -> float:
    """The session-length multiplier for plan week ``week_num`` (0-based)."""
    if start is None:
        return 1.0
    s = start["intensity"]
    return s + (1.0 - s) * min(1.0, week_num / RAMP_WEEKS)


def intro_weeks(level: str | None) -> int:
    """How many weeks a plan with no history of the sport opens without
    quality sessions, for a frequency answer (or none)."""
    if level is None:
        return _UNANSWERED_INTRO
    return _INTRO_WEEKS.get(level, _UNANSWERED_INTRO)


def start_quality(level: str | None, week_num: int, no_history: bool) -> bool:
    """Whether plan week ``week_num`` may hold quality sessions: always with
    history of the sport; without, not in the intro weeks (module docstring)."""
    if not no_history:
        return True
    return week_num >= intro_weeks(level)
