# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
The training load a planned workout will put on the athlete, from its steps.

The plan is asked for load — a fitness goal's week is a TSS figure, and an
event plan's volume is converted to one — so it has to be able to say what
load a session it built carries. It used to assume a flat 55 TSS an hour for
everything, which is right for exactly one mix of easy and hard; a week of
long easy running was over-counted and a week with two interval sessions
under-counted, and the plan stopped delivering the ramp it was asked for.

TSS is ``hours × IF² × 100`` (Coggan): an hour at threshold is 100. Each step
gets an intensity factor from the zone tag the builders already put on it
(``pace`` for running, ``intensity`` everywhere else), so the estimate follows
what the session actually prescribes rather than its type's name.

Why these IFs
-------------
They are hrTSS-shaped, because that is what most of this system's CTL is built
from (training_load.estimate_tss): an easy session sits in HR Zone 2, about
0.75–0.8 of LTHR, and hrTSS squares that ratio. So:

  walk 0.55 · recovery 0.65 · easy/endurance 0.75 · aerobic (Zone 3) 0.80 ·
  marathon/tempo 0.87 · race pace 0.90 · sweet spot 0.92 · threshold 1.00 ·
  VO₂max 1.05 · anaerobic / repetition / neuromuscular 1.10

An activity with no heart rate is estimated on this same scale
(training_load.estimated_tss), so moving these moves what a phone-only
athlete's history is worth against the plan: change the two together.

Coggan's power zones put the same sessions in the same places (endurance
56–75% FTP, sweet spot 88–93%, VO₂ 106–120%); interval work sits a little
below its zone's power ratio because hrTSS lags on short reps, and the
recovery between reps is counted separately at 0.55.

The other sports' tags sit in the same scale: rowing's UT2 0.72 (below the
first threshold by definition), plyometric work 0.80 and heavy strength 0.70
(short efforts with long rests — hrTSS sees little of them), sprints 1.10.

An unknown tag counts as easy — a builder added later (another sport's) is
at worst estimated as steady aerobic work, never as nothing.

The Kotlin port (com.tracks.core.plan.PlanLoad) is held to this by
spec/fixtures/plan.json: the week sizing depends on it, so a different
estimate is a different plan.
"""

from __future__ import annotations

from app.calculators.plan.base import _DEFAULT_RUN_PACES

EASY_IF = 0.75
_REST_IF = 0.55

_IF: dict[str, float] = {
    "walk": 0.55,
    "max_strength": 0.70, "ut2": 0.72, "plyometric": 0.80,
    "recovery": 0.65, "skills": 0.65,
    "easy": 0.75, "endurance": 0.75,
    "aerobic": 0.80,
    "marathon": 0.87, "tempo": 0.87, "descent_repeats": 0.87,
    "race_pace": 0.90,
    "sweet_spot": 0.92,
    "threshold": 1.00, "over_under": 1.00, "test": 1.00,
    "interval": 1.05, "vo2": 1.05, "micro_bursts": 1.05,
    "repetition": 1.10, "anaerobic": 1.10, "neuromuscular": 1.10, "fast": 1.10,
    "sprint": 1.10,
}


def step_if(step: dict) -> float:
    """The intensity factor of one step's work."""
    if step.get("type") == "walk":
        return _IF["walk"]
    tag = step.get("pace") or step.get("intensity")
    return _IF.get(tag, EASY_IF) if isinstance(tag, str) else EASY_IF


def step_tss(step: dict, paces: dict | None) -> float:
    """TSS of one step: work at its IF, recovery between reps at 0.55.

    Durations follow base._duration_from_steps (the minutes a workout shows),
    so the load and the length a person sees describe the same session.
    """
    p = paces or _DEFAULT_RUN_PACES
    t = step.get("type")
    f = step_if(step)
    work_h = 0.0
    rest_h = 0.0
    if t in ("run", "warmup", "cooldown", "walk", "ride", "swim", "activity"):
        work_h = step.get("duration_min", 0) / 60
    elif t in ("interval_set", "effort_set"):
        reps = step.get("reps", 0)
        if "duration_min_each" in step:
            work_h = reps * step["duration_min_each"] / 60
            rest_h = reps * step.get("rest_min", 0) / 60
        elif "duration_sec_each" in step:
            work_h = reps * step["duration_sec_each"] / 3600
            rest_h = reps * step.get("rest_sec", 0) / 3600
        elif "sec_per_km" in step:
            # A swim or erg piece carries its own pace, as in
            # base._duration_from_steps. Timed at running's interval pace
            # instead, a swim set counted about a fifth of its real length,
            # so the week sizer made every swim ~5× longer to reach the
            # week's load: a no-history 1500 m plan opened with a 2 h swim.
            work_h = reps * (step.get("distance_m", 0) / 1000 * step["sec_per_km"]) / 3600
            rest_h = reps * step.get("rest_sec", 0) / 3600
        else:
            zone = step.get("pace", "interval")
            pace_sec_km = p.get(zone, p.get("interval", 300))
            work_h = reps * (step.get("distance_m", 0) / 1000 * pace_sec_km) / 3600
            rest_h = reps * step.get("rest_sec", 0) / 3600
    elif t == "fartlek":
        # hard_min at the step's pace, easy_min at recovery, per round.
        reps = step.get("reps", 0)
        hard_h = reps * step.get("hard_min", 0) / 60
        easy_h = reps * step.get("easy_min", 0) / 60
        return (hard_h * f * f + easy_h * _IF["recovery"] * _IF["recovery"]) * 100
    return (work_h * f * f + rest_h * _REST_IF * _REST_IF) * 100


def workout_tss(steps: list[dict], paces: dict | None) -> float:
    """Estimated TSS of a whole workout."""
    total = 0.0
    for s in steps:
        total += step_tss(s, paces)
    return total


def tss_per_hour(intensity_factor: float) -> float:
    """TSS an hour holds at one IF (``IF² × 100``)."""
    return intensity_factor * intensity_factor * 100
