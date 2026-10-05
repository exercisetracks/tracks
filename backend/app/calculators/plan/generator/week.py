# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Single-week workout generation.

Turns one (already-scheduled) week — its phase, its load target in TSS, and
the sport context — into a concrete list of workout dicts. Responsibilities,
in order:

  1. Pick + rotate the day-by-day template (`_rotating_template`).
  2. Enforce polarisation: cap high-intensity sessions at ~20-25% of the week
     and cap the number of quality days per phase, converting the excess to easy
     volume (Seiler 2010; Stöggl & Sperlich 2014).
  3. Size the sessions so the week carries its load (`_fill_sessions`).
  4. Build each day's steps via the dispatch table and assemble titles /
     descriptions / durations.

Cross-week constraints (volume ramp, ACWR, build-index progression) live in the
top-level orchestrators (`plan.py`, `fitness.py`, `multisport.py`), not here.

Sizing a week to its load
-------------------------
The week used to be sized in kilometres: a long session of 35% of the volume,
easy sessions of 65% of an equal share of the rest, and fixed caps (easy ≤ 90
min, long ≤ 150 min running / 210 cycling). Whatever did not fit was dropped,
so past about CTL 50 a runner's plan could not even hold fitness, and a
cyclist got 60–75% of each extra CTL point asked for.

Now the target is TSS and the week is filled to it:

  - Quality sessions are built first, at a length that follows the week's
    average session (30–40 min before their own warm-up offsets), and their
    load (plan/load.py) is taken off the target.
  - The remainder goes to the long and easy sessions in proportion to a
    weight each — long 2, easy 1, a recovery spin or jog less — solved by
    bisection on one shared unit so that the built steps, not an estimate of
    them, carry the load. Walk breaks, variation offsets and builder caps are
    all inside that measurement.
  - Each session is bounded by what a session of its kind can sensibly be
    (``_SESSION_CAPS``): past that, more load in one sitting is injury risk
    (running) or junk time, and a week that cannot hold its target at the
    chosen days-per-week is told so by coming up short, not by a 5-hour run.

Injury guards kept on purpose:
  - The long session is at most 40% of the week's sized time (weight ≤ ⅔ of
    everything else): with few days a week a 2:1 long session would be half
    the training (Pfitzinger's rule of thumb keeps the long run near a
    quarter to a third of the week).
  - Polarised 80/20 and the per-phase quality-day caps are applied before
    sizing, so extra load only ever lands on easy time.
  - The ramp itself (fitness goals) and the 10%/ACWR progression (event
    plans) decide how much the week asks for; sizing only delivers it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

from app.calculators.plan.base import (
    _DEFAULT_RUN_PACES,
    _capacity_km,
    _apply_days_per_week,
    _distance_from_steps,
    _duration_from_steps,
    _workout_description,
    _workout_title,
)
from app.calculators.plan.generator.dispatch import (
    _HIGH_INTENSITY_TYPES,
    _build_steps,
)
from app.calculators.plan.generator.templates import _rotating_template
from app.calculators.plan.load import EASY_IF, tss_per_hour, workout_tss

# The sessions sized to carry the week's load, and their share of it. A
# recovery jog or spin is kept short on purpose (it is there to recover);
# skills rides are mostly technique, so a little shorter than endurance.
# The other sports' steady sessions are sized the same way: rowing's UT2 (and
# the shorter, harder UT1), hiking's vertical and back-to-back days (the
# second of a pair is nearly a long day — Koop 2016), ski-walking with poles,
# and climbing's ARC endurance, which is time on the wall at low intensity.
_SIZED_WEIGHT: dict[str, float] = {
    "long": 2.0,
    "easy": 1.0, "endurance": 1.0, "aerobic": 1.0,
    "skills": 0.8, "easy_spin": 0.7, "easy_recovery": 0.6,
    # back_to_back's builder doubles the minutes it is given (day one of a
    # pair is twice an easy day), so 0.75 here is 1.5 easy days of hiking.
    "ut2": 1.0, "ut1": 0.8, "vert": 1.0, "back_to_back": 0.75,
    "pole_hike": 1.0, "arc": 0.8,
}

# (easy cap, long cap, long floor), minutes. The caps are per-session limits
# on what one sitting should hold, not on the week:
#   running — easy runs past 2 h and long runs past 3 h add impact faster than
#     aerobic benefit (Daniels caps the long run at 150 min or 25–30% of the
#     week; three hours is the marathon-trained ceiling);
#   cycling — 4 h endurance rides and 6 h long rides are ordinary at high CTL
#     (Friel's base-period long ride for a 600 h/yr rider);
#   MTB — terrain makes the same hours costlier (the TSS multiplier), so less;
#   swimming — a trained swimmer's main set session runs to 2 h, a long
#     (often open-water) swim 2.5 h; past that, doubles (below);
#   rowing — UT2 pieces of 70–120 min are the rowing staple (British Rowing's
#     UT2 guidance), a long row on water 3 h;
#   hiking — the long day is the point: 4–8 h under a pack on the objective,
#     so a long hike may run 6 h before the load scaling below;
#   nordic skiing — 2.5 h distance sessions and 5 h long days (Sandbakk &
#     Holmberg 2017: elite XC skiers' long sessions are 2–5 h);
#   alpine skiing — dry-land conditioning, not skiing: 90 min / 3 h;
#   climbing — a gym session of 2.5 h, a crag day of 5 h (time on the wall).
# These used to share a 90-minute long cap with every sport the planner did
# not know, which made a long hike or ski day an hour and a half.
_SESSION_CAPS: dict[str, tuple[int, int, int]] = {
    "running": (120, 180, 45),
    "cycling": (240, 360, 60),
    "mountain_biking": (180, 300, 75),
    "swimming": (120, 150, 35),
    "rowing": (120, 180, 45),
    "hiking": (180, 360, 90),
    "nordic_skiing": (150, 300, 60),
    "alpine_skiing": (90, 180, 45),
    "climbing": (150, 300, 60),
}
_DEFAULT_CAPS = (120, 240, 35)
# The caps above suit an athlete training about CTL 60 (a 420-TSS week). A
# heavier week means a better-conditioned athlete, and session-length rules
# are fractions of the week (Daniels' long run at 25–30% of it), so the caps
# grow with the week's load: easy sessions up to +50%, the long one up to
# +25% (past that the long session's own risk grows faster than the week).
_CAP_REFERENCE_TSS = 420.0
_EASY_CAP_SCALE_MAX = 1.5
_LONG_CAP_SCALE_MAX = 1.25
_EASY_FLOOR = 20
_LONG_SHARE = 2.0 / 3.0    # long weight ≤ ⅔ of the rest ⇒ ≤ 40% of the week
# Quality sessions scale with the week's average session, within bounds: a
# tempo run is this plus its 20-min warm-up/cool-down, so 40 keeps the
# threshold block near Daniels' 20–40 min of continuous T-pace.
_QUALITY_MIN, _QUALITY_MAX = 30, 40
_BISECT_STEPS = 24
_BISECT_HI = 800.0         # minutes per unit weight: past every cap
# Continuous-run capacity implied by a runner's weekly distance (fitness.py,
# plan.py): the long run at 40% of the week is the usual upper bound.
_LOAD_CAPACITY_SHARE = 0.4

# The sports that add a second session on easy days when a week outgrows its
# single sessions (see _fill_sessions): the days that may double, and how the
# second one is titled. Every title starts "Second " — multisport.py relies on it.
DOUBLE_PREFIX = "Second "
_DOUBLES: dict[str, tuple[tuple[str, ...], str]] = {
    "running": (("easy", "easy_recovery"), "Second run · "),
    "rowing": (("ut2", "easy"), "Second row · "),
    "swimming": (("aerobic", "easy"), "Second swim · "),
}


# A runner is ready for quality sessions once they can run about 30 minutes
# without a walk break — 5 km at an easy pace. Until then the capacity model
# itself is prescribing run/walk intervals, and the same week handing them a
# fartlek or tempo run (continuous, with no walk breaks, 45+ minutes long)
# contradicts it. Continuous easy running comes first in every novice
# progression (Daniels' phase I is easy running only; a couch-to-5K programme
# ends at 30 minutes continuous and has no speed work at all), and most
# novice running injuries come from load the tissues have not adapted to yet
# (Nielsen et al. 2014; Videbæk et al. 2015), which intensity adds on top of
# distance. A runner with measured volume passes this at once: 40% of 12.5 km
# a week is already 5 km (running_capacity_km).
RUN_QUALITY_CAPACITY_KM = 5.0


def quality_ready(ctx: "SportCtx") -> bool:
    """Whether this sport's sessions may include quality this week: always,
    except for a runner who cannot yet run continuously (above)."""
    if ctx.family != "running" or ctx.capacity_km is None:
        return True
    return ctx.capacity_km >= RUN_QUALITY_CAPACITY_KM


def running_capacity_km(effective_weeks: float, weekly_km: float) -> float:
    """How far a runner can go without walk breaks: the effective-weeks
    model (built for novices), or 40% of the weekly distance they already
    run (measured, or implied by their CTL) — whichever is more. The one
    place both meet, so a starting volume from elsewhere (an onboarding
    answer, say) only has to be passed in as ``weekly_km``."""
    return max(_capacity_km(effective_weeks), weekly_km * _LOAD_CAPACITY_SHARE)


def _easy_pace_kmh(family: str, paces: dict | None) -> float:
    """The speed a week's kilometres are turned into minutes at.

    An event plan periodises in kilometres (plan.py); this is the speed those
    kilometres become hours — and so load — at.
    """
    if family == "running":
        return 3600 / (paces or _DEFAULT_RUN_PACES)["easy"]
    if family == "cycling":
        return 18.0
    if family == "mountain_biking":
        return 15.0
    # Swimming ≈ 3 km/h: 2:00/100 m, an age-group aerobic pace. It was 2 km/h,
    # which turned a 20 km swim week into ten hours of swimming.
    if family == "swimming":
        return 3.0
    # Rowing ≈ 2:30/500 m steady, hiking ≈ 4 km/h on trail (Naismith before
    # the climb), classic/skate distance skiing ≈ 9 km/h. Alpine skiing and
    # climbing are planned in time; their kilometres only ever come from a
    # distance someone typed, so the generic 6 stands.
    if family == "rowing":
        return 12.0
    if family == "hiking":
        return 4.0
    if family == "nordic_skiing":
        return 9.0
    return 6.0


def km_to_tss(km: float, family: str, paces: dict | None) -> float:
    """A week of ``km`` at easy pace, as load: the event plan's volume in the
    unit the week is sized in."""
    return km / _easy_pace_kmh(family, paces) * tss_per_hour(EASY_IF)


@dataclass
class SportCtx:
    """What a sport's builders need that does not change within a week."""
    family: str
    sport: str
    paces: dict | None = None
    ftp: int | float | None = None
    css: float | None = None
    lthr: int | None = None
    capacity_km: float | None = None
    mtb_discipline: str = "trail"
    cycling_discipline: str = "road_race"
    imperial: bool = False


def _dur_offset(wtype: str) -> int:
    # Tempo and sweet spot carry a warm-up and cool-down around their block.
    return 20 if wtype in ("tempo", "sweet_spot") else 0


def _workout(day: date, wtype: str, ctx: SportCtx, steps: list[dict]) -> dict:
    duration = _duration_from_steps(steps, ctx.paces)
    distance = _distance_from_steps(steps, ctx.paces) if ctx.family == "running" else None
    return {
        "scheduled_date": day, "sport": ctx.sport, "workout_type": wtype,
        "title": _workout_title(wtype, ctx.family, distance, duration, imperial=ctx.imperial),
        "description": _workout_description(steps),
        "duration_minutes": duration,
        "distance_meters": round(distance) if distance else None,
        "steps": steps,
    }


def _variations(slots: list[tuple[date, str]], counters: dict | None,
                today: date | None, commit: bool) -> list[int]:
    """Each session's builder variation: how many of its type came before it.

    Days already past take no count (they are not written). ``commit=False``
    works on a copy, for trying a week out before it is decided.
    """
    local = dict(counters) if counters is not None else {}
    out: list[int] = []
    for day, wtype in slots:
        if today is not None and day < today:
            out.append(0)
            continue
        occ = local.get(wtype, 0)
        local[wtype] = occ + 1
        out.append(occ % 6)
    if commit and counters is not None:
        counters.update(local)
    return out


@dataclass
class _Sized:
    """A session sized by the shared unit: slot index, weight, bounds."""
    slot: int
    weight: float
    floor: int
    cap: int
    double: bool = False


class _Week:
    """One sport's sessions of a week, and the arithmetic to size them."""

    def __init__(self, slots, ctx, phase, week_tss, build_idx, race_distance_m,
                 variations, intensity, min_long_min):
        self.slots = slots
        self.ctx = ctx
        self.phase = phase
        self.week_tss = week_tss
        self.build_idx = build_idx
        self.race_distance_m = race_distance_m
        self.variations = variations
        self.intensity = intensity
        easy_cap, long_cap, long_floor = _SESSION_CAPS.get(ctx.family, _DEFAULT_CAPS)
        scale = week_tss / _CAP_REFERENCE_TSS
        easy_cap = int(easy_cap * min(_EASY_CAP_SCALE_MAX, max(1.0, scale)))
        long_cap = int(long_cap * min(_LONG_CAP_SCALE_MAX, max(1.0, scale)))

        n = len(slots)
        mean_min = week_tss / tss_per_hour(EASY_IF) * 60 / n
        self.q_min = int(min(float(_QUALITY_MAX), max(float(_QUALITY_MIN), mean_min)))

        self.fixed_steps: dict[int, list[dict]] = {}
        self.fixed_tss = 0.0
        self.pool: list[_Sized] = []
        other = 0.0
        for i in range(n):
            wtype = slots[i][1]
            if wtype not in _SIZED_WEIGHT:
                other += 1.0
            elif wtype != "long":
                other += _SIZED_WEIGHT[wtype]
        for i in range(n):
            wtype = slots[i][1]
            if wtype not in _SIZED_WEIGHT:
                off = _dur_offset(wtype)
                st = self.build(i, max(15, int(self.q_min * intensity)) + off, self.q_min + off)
                self.fixed_steps[i] = st
                self.fixed_tss += workout_tss(st, ctx.paces)
            elif wtype == "long":
                floor = long_floor
                if min_long_min is not None and min_long_min > floor:
                    floor = min_long_min
                self.pool.append(_Sized(i, min(_SIZED_WEIGHT["long"], max(1.0, other * _LONG_SHARE)),
                                        min(floor, long_cap), long_cap))
            else:
                self.pool.append(_Sized(i, _SIZED_WEIGHT[wtype], _EASY_FLOOR, easy_cap))

    def build(self, i: int, minutes: int, structural: int, double: bool = False) -> list[dict]:
        wtype = self.slots[i][1]
        var = 0 if wtype == "long" else self.variations[i]
        if double:
            var = (var + 1) % 6
        return _build_steps(
            workout_type=wtype, family=self.ctx.family, sport=self.ctx.sport, phase=self.phase,
            duration_min=minutes, build_idx=self.build_idx, paces=self.ctx.paces,
            ftp=self.ctx.ftp, css=self.ctx.css, race_distance_m=self.race_distance_m,
            capacity_km=self.ctx.capacity_km, variation=var, structural_min=structural,
            imperial=self.ctx.imperial, lthr=self.ctx.lthr,
            mtb_discipline=self.ctx.mtb_discipline, cycling_discipline=self.ctx.cycling_discipline,
        )

    def minutes(self, u: float) -> list[int]:
        out = []
        for e in self.pool:
            out.append(min(e.cap, max(e.floor, int(e.weight * u))))
        return out

    def load(self, u: float) -> float:
        total = 0.0
        ms = self.minutes(u)
        for k, e in enumerate(self.pool):
            total += workout_tss(self.build(e.slot, ms[k], ms[k], e.double), self.ctx.paces)
        return total

    def solve(self) -> float:
        """The unit whose built sessions come closest to the week's load."""
        remaining = self.week_tss - self.fixed_tss
        lo, hi = 0.0, _BISECT_HI
        lo_t, hi_t = self.load(lo), self.load(hi)
        if lo_t >= remaining:
            return lo
        if hi_t <= remaining:
            return hi
        for _ in range(_BISECT_STEPS):
            mid = (lo + hi) / 2
            t = self.load(mid)
            if t < remaining:
                lo, lo_t = mid, t
            else:
                hi, hi_t = mid, t
        return lo if remaining - lo_t <= hi_t - remaining else hi


def _fill_sessions(
    slots: list[tuple[date, str]],
    ctx: SportCtx,
    phase: str,
    week_tss: float,
    build_idx: int,
    race_distance_m: float,
    occurrence_counters: dict | None,
    today: date | None,
    intensity: float = 1.0,
    min_long_min: int | None = None,
    trim_days: bool = False,
    allow_doubles: bool = True,
) -> list[dict]:
    """Build one sport's sessions of a week so they carry ``week_tss``.

    ``slots`` are (day, workout type) in date order — every session of the
    week, including days already past: the week is sized whole, so rebuilding
    it on a Thursday does not cram Monday's load into the weekend. Only the
    days from ``today`` on are returned.

    ``trim_days``: when even the shortest sessions would overshoot the load by
    more than 10%, rest easy days (never the long session or quality) until
    they do not. A fitness goal at CTL 20 on seven days a week asks for 20
    minutes a day; a 45-minute floor ride every day is twice that, and more
    load than a low-fitness athlete asked for. Event plans do not trim: their
    days are part of what the athlete chose to prepare with.

    Running, rowing and swimming: when the week cannot hold its load in
    single sessions even at the session caps, easy days get a second, shorter
    session (a "double", 30–60 min) — how high-volume runners add time on
    feet without lengthening any one run past what the legs tolerate
    (Pfitzinger & Douglas, *Advanced Marathoning*: doubles once weekly volume
    outgrows the week's days), and how rowers and swimmers train as a matter
    of course (two sessions a day is the norm in both from club level up). A
    ride or a hike just gets longer instead — the caps there are higher.
    """
    slots = list(slots)
    if not slots:
        return []
    intensity = max(0.5, min(1.5, intensity))

    if trim_days:
        while len(slots) > 2:
            wk = _Week(slots, ctx, phase, week_tss, build_idx, race_distance_m,
                       _variations(slots, occurrence_counters, today, False), 1.0, min_long_min)
            if wk.fixed_tss + wk.load(0.0) <= week_tss * 1.1:
                break
            drop = -1
            for e in wk.pool:
                t = slots[e.slot][1]
                if t == "long":
                    continue
                if drop < 0 or _SIZED_WEIGHT[t] <= _SIZED_WEIGHT[slots[drop][1]]:
                    drop = e.slot
            if drop < 0:
                break
            slots.pop(drop)

    wk = _Week(slots, ctx, phase, week_tss, build_idx, race_distance_m,
               _variations(slots, occurrence_counters, today, True), intensity, min_long_min)
    doubles = _DOUBLES.get(ctx.family) if allow_doubles else None
    if doubles is not None and wk.pool:
        remaining = week_tss - wk.fixed_tss
        if wk.load(_BISECT_HI) < remaining * 0.95:
            for e in list(wk.pool):
                if slots[e.slot][1] in doubles[0]:
                    wk.pool.append(_Sized(e.slot, 0.5, 30, 60, double=True))
    u = wk.solve()

    steps: dict[tuple[int, bool], list[dict]] = {}
    for i, st in wk.fixed_steps.items():
        steps[(i, False)] = st
    chosen = wk.minutes(u)
    for k, e in enumerate(wk.pool):
        base = chosen[k]
        least = 20 if slots[e.slot][1] == "long" else 15
        steps[(e.slot, e.double)] = wk.build(e.slot, max(least, int(base * intensity)), base,
                                             e.double)

    out = []
    for i in range(len(slots)):
        day, wtype = slots[i]
        if today is not None and day < today:
            continue
        out.append(_workout(day, wtype, ctx, steps[(i, False)]))
        if (i, True) in steps:
            w = _workout(day, wtype, ctx, steps[(i, True)])
            w["title"] = _DOUBLES[ctx.family][1] + w["title"]
            out.append(w)
    return out


def easy_session(family: str) -> str:
    """The sport's steady aerobic session: what excess quality becomes, and
    what a multi-sport week gives a sport's easy days."""
    return {
        "running": "easy", "cycling": "endurance", "mountain_biking": "endurance",
        "swimming": "aerobic", "rowing": "ut2", "hiking": "easy",
        "nordic_skiing": "endurance", "alpine_skiing": "aerobic", "climbing": "arc",
    }.get(family, "easy")


def _polarise(template: list[str], family: str, phase: str) -> list[str]:
    """The template with its quality capped for the phase (in place)."""
    easy_fill = easy_session(family)

    # If high intensity >25% of sessions, convert excess quality to easy
    # (enforce ≤20% ceiling, with +5% tolerance for short-term peaking weeks).
    # Rounded to the nearest session, not down: truncated, a 5–7 session build
    # week could never hold the two quality days the build phase allows, and
    # two hard sessions in six or seven is the polarised norm (Seiler 2010:
    # ~2 per week in the well-trained), not a breach of it.
    total_sessions = sum(1 for t in template if t != "rest")
    high_sessions = sum(1 for t in template if t in _HIGH_INTENSITY_TYPES)
    max_high = max(1, int(total_sessions * 0.25 + 0.5))
    if high_sessions > max_high and phase in ("base", "build"):
        excess = high_sessions - max_high
        for i in range(len(template)):
            if excess <= 0:
                break
            if template[i] in _HIGH_INTENSITY_TYPES and template[i] != "race_pace":
                template[i] = easy_fill
                excess -= 1

    # ── Volume-intensity coupling — prevent simultaneous climb ───────────────
    # During base phase, only 1 quality session allowed; constraints increase
    # through build/peak as volume plateaus.
    quality_slots = sum(1 for t in template if t in _HIGH_INTENSITY_TYPES)
    max_quality = {
        "base": 1, "build": 2, "peak": 2, "taper": 1,
    }.get(phase, 1)
    if quality_slots > max_quality:
        excess = quality_slots - max_quality
        for i in range(len(template)):
            if excess <= 0:
                break
            if template[i] in _HIGH_INTENSITY_TYPES and template[i] != "race_pace":
                template[i] = easy_fill
                excess -= 1
    return template


def _week_template(family: str, phase: str, week_num: int, days_per_week: int | None,
                   mtb_discipline: str, cycling_discipline: str,
                   quality: bool = True) -> list[str]:
    """The week's day plan: rotated, trimmed to the days, polarised.

    ``quality=False`` turns every quality session into the sport's easy one —
    the first weeks of someone new to the sport (plan/starting.py).
    """
    template = _rotating_template(family, phase, week_num, mtb_discipline, cycling_discipline)
    if days_per_week is not None:
        template = _apply_days_per_week(template, days_per_week, family)
    template = _polarise(template, family, phase)
    if not quality:
        easy_fill = easy_session(family)
        template = [easy_fill if t in _HIGH_INTENSITY_TYPES else t for t in template]
    return template


def _generate_week(
    week_start: date,
    week_end: date,
    phase: str,
    week_tss: float,
    ctx: SportCtx,
    build_idx: int,
    race_distance_m: float,
    is_race_week: bool,
    days_per_week: int | None = None,
    occurrence_counters: dict | None = None,
    today: date | None = None,
    intensity: float = 1.0,
    min_long_min: int | None = None,
    week_num: int = 0,
    trim_days: bool = False,
    quality: bool = True,
) -> list[dict]:
    template = _week_template(ctx.family, phase, week_num, days_per_week,
                              ctx.mtb_discipline, ctx.cycling_discipline,
                              quality and quality_ready(ctx))

    days_in_week = (week_end - week_start).days + 1
    slots: list[tuple[date, str]] = []
    race: dict | None = None
    for day_idx in range(days_in_week):
        day = week_start + timedelta(days=day_idx)
        if is_race_week and day == week_end:
            race = {
                "scheduled_date": day, "sport": ctx.sport, "workout_type": "race",
                "title": "Race Day", "description": "Race day. Trust your training.",
                "duration_minutes": 0, "distance_meters": race_distance_m, "steps": [],
            }
            continue
        wtype = template[day_idx] if day_idx < len(template) else "easy"
        if wtype != "rest":
            slots.append((day, wtype))

    workouts = _fill_sessions(slots, ctx, phase, week_tss, build_idx, race_distance_m,
                              occurrence_counters, today, intensity, min_long_min, trim_days)
    if race is not None and (today is None or race["scheduled_date"] >= today):
        workouts.append(race)
    return workouts
