# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Weeks that mix sports: a triathlon's swim, bike and run, and a fitness goal
spread over several sports.

Both share one idea: the week has **one** load target (TSS), because fatigue
does not care which sport made it — CTL is the sum over every sport. The
target is divided between the sports, each sport's sessions are decided
(how many, which is long, which carry quality), the sessions are placed on
days, and then each sport's sessions are sized to its share with the same
`_fill_sessions` a single-sport week uses. Workout choice, builders and the
load estimate are therefore the single-sport planner's own.

Placing sessions on days
------------------------
Sessions are placed one at a time — hard ones first, so they get the pick of
the week — on the day with the lowest penalty:

  - two hard sessions (quality, long, brick) on consecutive days: +6, whatever
    the sports. A threshold ride the day after intervals on the track is still
    two hard days in a row (Seiler's hard-easy alternation is about the
    athlete, not the sport);
  - two impact sessions (running, hiking) on consecutive days: +4. Bone and
    tendon load recovers more slowly than the aerobic system, which is the
    reason cross-training exists (Mujika 2000 on cross-training; Nielsen 2014
    on running-related injury and load);
  - the same sport on consecutive days: +3, for variety — the point of
    training several sports is that each rests while the others work;
  - sharing a day: +20 per session already there (+8 for a swim, which
    doubles easily around other training), +15 more if both are hard, and a
    sport never twice in one day.

Ties go to the earlier day. It is greedy, not optimal — a week has at most
ten sessions and the penalties are few, so the difference does not show, and
greedy is what can be held identical on the phone.

Triathlon
---------
Load is split by race distance, following how long each leg takes and what
it costs (Friel, *The Triathlete's Training Bible*, 4th ed.: short course ≈
20/45/35 swim/bike/run by time, long course ≈ 15/55/30; swimming carries
less load per hour and no impact, so its share is at the low end):

  sprint 0.20/0.45/0.35 · Olympic 0.18/0.47/0.35 · 70.3 0.15/0.52/0.33 ·
  full 0.13/0.55/0.32

Sessions per sport follow days per week (``_TRI_COUNTS``): more sessions
than days, because triathletes double up — the extra sessions are swims
where possible. Saturday is the long ride with a **brick** — a run straight
off the bike (workout type ``brick_run``, dated the same day, placed after
the ride) — from the build phase on, and every other week in base (Millet &
Vleck 2000: the run-off-the-bike adaptation is specific and needs rehearsal;
weekly in base is more than the transition needs). Sunday is the long run.
Quality goes to the bike and the swim in base (CSS sets are how triathletes
swim; the run is kept easy while volume builds), and bike, run and swim from
build on. The taper is joint: the whole week's target drops, so every sport
tapers together.

Multi-sport fitness goals
-------------------------
The load is shared by the athlete's own recent training: each sport's share
is half its share of the last eight weeks' hours and half an equal split, so
the plan follows what the person actually does without letting a sport they
picked but have not done yet vanish. Days go to sports by those shares (each
sport at least one day when there are days enough); when there are more
sports than days, the less-done ones take turns week by week. The long
session goes to the sport with the largest share — alternating weekly
between sports within 10% of it — and quality to the largest shares first,
one per sport before any sport gets a second.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta
from typing import Sequence

from app.calculators.plan.base import _TEMPLATES, _apply_days_per_week, _sport_family
from app.calculators.plan.generator.dispatch import _HIGH_INTENSITY_TYPES
from app.calculators.plan.generator.templates import _rotating_template
from app.calculators.plan.generator.week import (
    SportCtx,
    _easy_pace_kmh,
    DOUBLE_PREFIX,
    _fill_sessions,
    easy_session,
    quality_ready,
)

IMPACT_FAMILIES = frozenset({"running", "hiking"})
_HARD_ROLES = frozenset({"long", "quality", "brick"})

# (swim, bike, run) share of the week's load, by race distance.
_TRI_SHARES: list[tuple[float, tuple[float, float, float]]] = [
    (40_000.0,  (0.20, 0.45, 0.35)),   # sprint (and super sprint)
    (80_000.0,  (0.18, 0.47, 0.35)),   # Olympic
    (170_000.0, (0.15, 0.52, 0.33)),   # 70.3
]
_TRI_SHARES_FULL = (0.13, 0.55, 0.32)

# Standard leg distances (race_helpers._TRI_SPLITS, the race plan's table).
_TRI_LEGS: list[tuple[float, tuple[float, float, float]]] = [
    (12_950.0,  (400.0, 10_000.0, 2_500.0)),
    (25_750.0,  (750.0, 20_000.0, 5_000.0)),
    (51_500.0,  (1_500.0, 40_000.0, 10_000.0)),
    (113_000.0, (1_900.0, 90_000.0, 21_097.0)),
    (226_000.0, (3_800.0, 180_000.0, 42_195.0)),
]

# Sessions a week (swim, bike, run) by days a week.
_TRI_COUNTS: dict[int, tuple[int, int, int]] = {
    1: (0, 1, 0), 2: (1, 1, 1), 3: (1, 1, 1), 4: (1, 2, 2),
    5: (2, 2, 2), 6: (2, 3, 3), 7: (3, 3, 3),
}
# Training days (Mon = 0) by days a week: Saturday for the long ride and
# brick, Sunday for the long run, the rest spread so no three days run on.
_TRI_DAYS: dict[int, list[int]] = {
    1: [5], 2: [2, 5], 3: [1, 3, 5], 4: [1, 3, 5, 6],
    5: [1, 2, 3, 5, 6], 6: [1, 2, 3, 4, 5, 6], 7: [0, 1, 2, 3, 4, 5, 6],
}


def tri_shares(race_distance_m: float) -> tuple[float, float, float]:
    for limit, shares in _TRI_SHARES:
        if race_distance_m < limit:
            return shares
    return _TRI_SHARES_FULL


def tri_legs(race_distance_m: float) -> tuple[float, float, float]:
    """(swim, bike, run) metres of the standard distance nearest the race."""
    best = _TRI_LEGS[0]
    for entry in _TRI_LEGS:
        if abs(entry[0] - race_distance_m) < abs(best[0] - race_distance_m):
            best = entry
    return best[1]


@dataclass
class Session:
    sport: int          # index into the week's sports
    role: str           # long | quality | easy | brick
    wtype: str
    day: int = -1
    order: int = 0      # within a day: the brick's ride before its run


def easy_type(family: str) -> str:
    """The sport's steady aerobic session."""
    return easy_session(family)


def quality_types(ctx: SportCtx, phase: str, week_num: int) -> list[str]:
    """The sport's own quality sessions for the phase, in template order —
    what its single-sport week would have used. A phase whose template has
    none (swimming's base) borrows the build template's."""
    for ph in (phase, "build"):
        tpl = _rotating_template(ctx.family, ph, week_num,
                                 ctx.mtb_discipline, ctx.cycling_discipline)
        found = [t for t in tpl if t in _HIGH_INTENSITY_TYPES]
        if found:
            return found
    return ["intervals"]


def _penalty(s: Session, day: int, placed: list[Session], families: list[str],
             max_per_day: int) -> int | None:
    fam = families[s.sport]
    hard = s.role in _HARD_ROLES
    on_day = [p for p in placed if p.day == day]
    if len(on_day) >= max_per_day:
        return None
    pen = 0
    for p in on_day:
        if families[p.sport] == fam:
            pen += 100
        if hard and p.role in _HARD_ROLES:
            pen += 15
        pen += 8 if fam == "swimming" else 20
    for p in placed:
        if p.day == day - 1 or p.day == day + 1:
            pfam = families[p.sport]
            if pfam == fam:
                pen += 3
            if pfam in IMPACT_FAMILIES and fam in IMPACT_FAMILIES:
                pen += 4
            if hard and p.role in _HARD_ROLES:
                pen += 6
    return pen


def place(sessions: list[Session], days: list[int], families: list[str],
          max_per_day: int) -> None:
    """Give every unplaced session a day (see the module docstring)."""
    placed = [s for s in sessions if s.day >= 0]
    todo = [s for s in sessions if s.day < 0]
    ordered = [s for s in todo if s.role in _HARD_ROLES]
    # Easy sessions round-robin across sports, so that when a week has more
    # sessions than room (race week) every sport keeps one before any keeps two.
    easy = [s for s in todo if s.role not in _HARD_ROLES]
    rnd = 0
    while len(ordered) < len(todo):
        for sport in sorted({s.sport for s in easy}):
            mine = [s for s in easy if s.sport == sport]
            if rnd < len(mine):
                ordered.append(mine[rnd])
        rnd += 1
    for s in ordered:
        best_day, best_pen = -1, 0
        for d in days:
            pen = _penalty(s, d, placed, families, max_per_day)
            if pen is None:
                continue
            if best_day < 0 or pen < best_pen:
                best_day, best_pen = d, pen
        if best_day < 0:
            # Every day full: the week has more sessions than room. Drop it
            # rather than stack a third session on a day.
            continue
        s.day = best_day
        placed.append(s)


def _build(week_start: date, sessions: list[Session], ctxs: list[SportCtx],
           budgets: list[float], phase: str, build_idx: int, race_m: list[float],
           counters: dict[str, dict], today: date | None, intensities: list[float],
           trim_days: bool) -> list[dict]:
    """Size and build each sport's placed sessions against its budget, each
    sport's sessions lengthened or shortened by its own ``intensities``."""
    keyed: list[tuple[int, int, int, int, dict]] = []
    for i, ctx in enumerate(ctxs):
        mine = sorted([s for s in sessions if s.sport == i and s.day >= 0],
                      key=lambda s: (s.day, s.order))
        if not mine:
            continue
        slots = [(week_start + timedelta(days=s.day), s.wtype) for s in mine]
        fam_counters = counters.setdefault(ctx.family, {})
        built = _fill_sessions(slots, ctx, phase, budgets[i], build_idx, race_m[i],
                               fam_counters, today, intensities[i], None, trim_days,
                               # A triathlon week already doubles days (swim +
                               # bike or run); a third session is not the answer.
                               allow_doubles=trim_days)
        # Line each built workout back up with its session: by day and type,
        # in order. A run's double (week.py) follows the run it doubles.
        used = [False] * len(mine)
        order, role = 0, "easy"
        for seq, w in enumerate(built):
            day = (w["scheduled_date"] - week_start).days
            match = -1
            if not w["title"].startswith(DOUBLE_PREFIX):
                for k, s in enumerate(mine):
                    if not used[k] and s.day == day and s.wtype == w["workout_type"]:
                        match = k
                        break
            if match >= 0:
                used[match] = True
                order, role = mine[match].order, mine[match].role
            else:
                role = "easy"
            if role == "brick" and w["workout_type"] != "brick_run":
                w["title"] = "Brick · " + w["title"]
                w["description"] = (w["description"] + "\n" if w["description"] else "") + \
                    "Brick: run straight off this ride — shoes ready, change in under five minutes."
            keyed.append((day, order, i, seq, w))
    keyed.sort(key=lambda k: (k[0], k[1], k[2], k[3]))
    return [k[4] for k in keyed]


def _budgets(week_tss: float, shares: list[float], sessions: list[Session]) -> list[float]:
    """Each sport's share of the week, renormalised over the sports that have
    a session this week (a sport resting this week hands its share on)."""
    active = [any(s.sport == i and s.day >= 0 for s in sessions) for i in range(len(shares))]
    total = 0.0
    for i, a in enumerate(active):
        if a:
            total += shares[i]
    return [week_tss * shares[i] / total if active[i] and total > 0 else 0.0
            for i in range(len(shares))]


# ─────────────────────────────────────────
# Multi-sport fitness goals
# ─────────────────────────────────────────

_HISTORY_WEEKS = 8


def recent_hours(history: Sequence, family: str, today: date) -> float:
    """Hours of ``family`` over the last eight weeks (distance at easy speed
    where an activity has no duration)."""
    cutoff = today - timedelta(weeks=_HISTORY_WEEKS)
    total = 0.0
    for act in history:
        if not act.started_at or _sport_family(act.sport or "") != family:
            continue
        d = act.started_at.date() if hasattr(act.started_at, "date") else act.started_at
        if d < cutoff or d > today:
            continue
        secs = getattr(act, "duration_seconds", None)
        if secs:
            total += secs / 3600
        elif act.distance_meters:
            total += act.distance_meters / 1000 / _easy_pace_kmh(family, None)
    return total


def fitness_shares(families: list[str], history: Sequence, today: date) -> list[float]:
    """Half recent history, half an equal split (see module docstring)."""
    n = len(families)
    hours = [recent_hours(history, f, today) for f in families]
    total = 0.0
    for h in hours:
        total += h
    if total <= 0:
        return [1.0 / n] * n
    return [0.5 * hours[i] / total + 0.5 / n for i in range(n)]


def _fitness_days(dpw: int) -> list[int]:
    tpl = _apply_days_per_week(list(_TEMPLATES["generic"]["base"]), dpw, "generic")
    return [i for i, t in enumerate(tpl) if t != "rest"]


def fitness_multisport_week(
    week_start: date, phase: str, week_tss: float, ctxs: list[SportCtx],
    shares: list[float], dpw: int, week_num: int, build_idx: int,
    counters: dict[str, dict], today: date | None, race_m: float,
    intensities: list[float] | None = None, quality: list[bool] | None = None,
) -> list[dict]:
    """One week of a fitness goal over several sports.

    ``intensities`` and ``quality`` are per sport, for a sport someone is new
    to (plan/starting.py): its sessions shortened, and no quality for it —
    the week's quality goes to the other sports instead.
    """
    if intensities is None:
        intensities = [1.0] * len(ctxs)
    if quality is None:
        quality = [True] * len(ctxs)
    n = len(ctxs)
    families = [c.family for c in ctxs]
    days = _fitness_days(dpw)
    nd = len(days)

    # ── Days per sport ───────────────────────────────────────────────────────
    counts = [0] * n
    if nd >= n:
        counts = [1] * n
        for _ in range(nd - n):
            best = 0
            for i in range(1, n):
                if shares[i] * nd - counts[i] > shares[best] * nd - counts[best]:
                    best = i
            counts[best] += 1
    else:
        order = sorted(range(n), key=lambda i: (-shares[i], i))
        keep = order[:nd - 1]
        rest = order[nd - 1:]
        keep.append(rest[week_num % len(rest)])
        for i in keep:
            counts[i] = 1

    # ── The long session ─────────────────────────────────────────────────────
    top = max(shares[i] for i in range(n) if counts[i] > 0)
    candidates = [i for i in range(n) if counts[i] > 0 and shares[i] >= top - 0.1]
    long_sport = candidates[week_num % len(candidates)]
    long_day = 6 if 6 in days else days[-1]

    # ── Quality ──────────────────────────────────────────────────────────────
    q_total = 0 if nd <= 1 else min(2 if phase in ("build", "peak") else 1,
                                    max(1, int(nd * 0.25 + 0.5)))
    q = [0] * n
    by_share = sorted(range(n), key=lambda i: (-shares[i], i))
    # Round by round: every sport with room gets its first before any its second.
    for rnd in range(7):
        for i in by_share:
            if q_total <= 0:
                break
            if (quality[i] and quality_ready(ctxs[i]) and q[i] == rnd
                    and counts[i] - q[i] - (1 if i == long_sport else 0) > 0):
                q[i] += 1
                q_total -= 1

    sessions: list[Session] = []
    for i, ctx in enumerate(ctxs):
        k = counts[i]
        if i == long_sport:
            sessions.append(Session(i, "long", "long", day=long_day, order=i))
            k -= 1
        qt = quality_types(ctx, phase, week_num)
        for j in range(q[i]):
            sessions.append(Session(i, "quality", qt[j % len(qt)], order=i))
        for _ in range(k - q[i]):
            sessions.append(Session(i, "easy", easy_type(ctx.family), order=i))

    place(sessions, [d for d in days], families, max_per_day=1)
    return _build(week_start, sessions, ctxs, _budgets(week_tss, shares, sessions), phase,
                  build_idx, [race_m] * n, counters, today, intensities, True)


# ─────────────────────────────────────────
# Triathlon
# ─────────────────────────────────────────

def triathlon_week(
    week_start: date, week_end: date, phase: str, week_tss: float,
    ctxs: list[SportCtx], race_distance_m: float, is_race_week: bool,
    dpw: int, week_num: int, build_idx: int, counters: dict[str, dict],
    today: date | None, intensity: float, quality: bool = True,
) -> list[dict]:
    """One triathlon week. ``ctxs`` are (swim, bike, run).

    ``quality=False`` holds no quality sessions in any sport — the intro weeks
    of someone with no swim/bike/run history (plan/starting.py). A run leg
    whose runner cannot yet run continuously holds none either
    (week.quality_ready).
    """
    shares = list(tri_shares(race_distance_m))
    legs = list(tri_legs(race_distance_m))
    families = [c.family for c in ctxs]
    dpw = max(1, min(7, dpw))
    n_swim, n_bike, n_run = _TRI_COUNTS[dpw]
    last = (week_end - week_start).days - (1 if is_race_week else 0)
    days = [d for d in _TRI_DAYS[dpw] if d <= last]
    if not days:
        days = [d for d in range(last + 1)]

    sessions: list[Session] = []
    # Race week: the taper's last days — short and easy around the race.
    racing = is_race_week
    long_ride_day = 5 if 5 in days else days[-1]
    brick = (not racing and dpw >= 2 and
             (phase in ("build", "peak", "taper") or week_num % 2 == 1))
    counts = [n_swim, n_bike, n_run]

    if counts[1] > 0:
        sessions.append(Session(1, "brick" if brick else ("easy" if racing else "long"),
                                "long" if not racing else easy_type("cycling"),
                                day=long_ride_day, order=10))
        counts[1] -= 1
    if brick and n_run > 0:
        # On top of the run sessions, not one of them: it is the ride's
        # second half, 10–30 minutes, not a run day.
        sessions.append(Session(2, "brick", "brick_run", day=long_ride_day, order=11))
    if counts[2] > 0 and not racing:
        long_run_day = 6 if 6 in days else -1
        sessions.append(Session(2, "long", "long", day=long_run_day, order=2))
        counts[2] -= 1
    if counts[0] >= 3 and race_distance_m >= 80_000 and not racing:
        # Long course: one swim a week goes long, for the 1.9–3.8 km swim.
        sessions.append(Session(0, "long", "long", order=0))
        counts[0] -= 1

    # Quality per sport: bike and swim in base, all three from build.
    want = {
        "base": (1, 1, 0), "build": (1, 1, 1), "peak": (1, 1, 1), "taper": (0, 1, 1),
    }.get(phase, (1, 1, 0))
    if racing:
        want = (0, 0, 0)
    for i in range(3):
        qt = quality_types(ctxs[i], phase, week_num)
        k = min(want[i], counts[i]) if quality and quality_ready(ctxs[i]) else 0
        for j in range(k):
            sessions.append(Session(i, "quality", qt[j % len(qt)], order=i))
        counts[i] -= k
        for _ in range(counts[i]):
            sessions.append(Session(i, "easy", easy_type(families[i]), order=i))

    # Race week holds one session a day: the taper keeps frequency (Bosquet
    # 2007), not doubles.
    place(sessions, days, families, max_per_day=1 if racing else 2)
    workouts = _build(week_start, sessions, ctxs, _budgets(week_tss, shares, sessions), phase,
                      build_idx, legs, counters, today, [intensity] * len(ctxs), False)
    if is_race_week and (today is None or week_end >= today):
        workouts.append({
            "scheduled_date": week_end, "sport": "triathlon", "workout_type": "race",
            "title": "Race Day", "description": "Race day. Trust your training.",
            "duration_minutes": 0, "distance_meters": race_distance_m, "steps": [],
        })
    return workouts
