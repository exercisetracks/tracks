# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Swimming workout builders — pool and open water.

Pool sessions are written the way a pool-swim workout on the watch runs them:
distance-based repeats in multiples of 50 m (so they fit a 25 m or a 50 m
pool), a timed rest after each, and a stroke and equipment on every step
(`stroke`, `equipment`), which the FIT encoder turns into the watch's own
swim-stroke target and equipment field. Pace is prescribed relative to CSS
(Critical Swim Speed) in the note and step label, not as a watch target:
Garmin pool workouts on the fenix 6 generation take no pace target, and a
pool swimmer steers by the pace clock anyway.

Open-water sessions (goal sport `open_water_swimming`) are time-based, since
there are no lengths to count, and carry the skills that decide open-water
races rather than pool fitness: sighting, drafting, pace changes at starts
and buoys, and swimming continuously without a wall every 25 m.

Zones, as offsets from CSS in seconds per 100 m (Swim Smooth's CSS model,
after Wakayoshi 1992):
  easy / aerobic   CSS + 10-15 s
  threshold        CSS + 0-2 s     (the CSS set)
  VO2              CSS - 3-5 s
  sprint           all-out, full recovery

When CSS is unknown the notes prescribe by effort and a default 2:00/100 m is
used only to size a session in metres. (Tracks cannot derive CSS today: it
needs a 200 m and a 400 m best, and pace bests are only kept for running.)

References
----------
- Wakayoshi, K., et al. (1992). Determination and validity of critical
  swimming velocity. *Eur J Appl Physiol*, 64(2), 153-157.
- Pyne, D. B., & Sharp, R. L. (2014). Physical and energy requirements of
  competitive swimming events. *Int J Sport Nutr Exerc Metab*, 24(4), 351-359.
  400 m+ events are >80% aerobic; threshold volume is the core of training.
- Barbosa, T. M., et al. (2010). Energetics and biomechanics as determining
  factors of swimming performance. *J Sci Med Sport*, 13(2), 262-269.
  Stroke economy (distance per stroke) is the main determinant below elite.
- Nugent, F. J., et al. (2017). Ultra-short race-pace training in swimming.
  *Int J Sports Physiol Perform*, 12(9), 1115-1122. Short race-pace repeats.
- Bosquet, L., et al. (2007). Effects of tapering on performance. Keep
  intensity, cut volume — the sharpening set in taper weeks.
- Baldassarre, R., et al. (2017). Characteristics and challenges of open-water
  swimming performance. *Front Physiol*, 8, 1143. Pacing, drafting (−10-20%
  energy cost), sighting and pace changes at starts and buoys.
- Seiler, S. (2010). ~80% low intensity holds for swimmers too.
"""

from __future__ import annotations

_DEFAULT_CSS = 120.0   # s/100 m; sizes sessions only, never shown as a target

_DRILLS: tuple[tuple[str, str], ...] = (
    ("Catch-up", "catch-up: one arm waits in front until the other arrives — long, patient stroke"),
    ("Fingertip drag", "fingertip drag: high relaxed elbow on the recovery"),
    ("6-kick switch", "6-kick switch: six kicks on the side, then one stroke — rotation and balance"),
    ("Single arm", "single arm, other arm at your side: breathe to the non-working side"),
    ("Fists", "closed fists: feel the forearm do the catch"),
    ("Sculling", "front sculling: feel the water with the hands before the pull"),
)

_OW_SKILLS: tuple[tuple[str, str], ...] = (
    ("Sighting", "sight every 6 strokes: eyes forward just above the water, then breathe to the side"),
    ("Drafting", "sit on a partner's feet or hip — drafting saves 10-20% of the effort"),
    ("Buoy turns", "tight turns at a buoy: a short burst in, roll round it, settle out"),
    ("Bilateral", "breathe every 3 strokes so either side works when the chop or sun decides"),
    ("Straight line", "10 strokes eyes closed, then sight: find out which way you drift"),
    ("Start sprint", "fast start from standing in the water, settle to race pace after 100 strokes"),
)


def _css_or_default(css: float | None) -> float:
    return css if css else _DEFAULT_CSS


def _mss(sec: float) -> str:
    s = int(sec)
    return f"{s // 60}:{s % 60:02d}"


def _pace_note(css: float | None, delta: int, effort: str) -> str:
    """'@ 1:52/100m' from CSS, or the effort in words when CSS is unknown."""
    if css:
        return f"@ {_mss(css + delta)}/100m"
    return effort


def _race_m(race_distance_m: float) -> int:
    """The swim's race distance. A goal with no distance reaches the planner
    as the marathon default (42 195 m), which no swim is; 1500 m stands in."""
    if not race_distance_m or race_distance_m > 25000:
        return 1500
    return int(race_distance_m)


def _minutes_for(dist_m: int, sec_per_100: float) -> int:
    return max(1, int(dist_m / 100 * sec_per_100 / 60))


def _warmup(dist_m: int, css: float | None, note: str = "Easy mixed strokes") -> dict:
    return {"type": "warmup", "distance_m": dist_m,
            "duration_min": _minutes_for(dist_m, _css_or_default(css) + 15),
            "intensity": "easy", "note": f"{dist_m} m warm-up · {note}"}


def _cooldown(dist_m: int, css: float | None) -> dict:
    return {"type": "cooldown", "distance_m": dist_m,
            "duration_min": _minutes_for(dist_m, _css_or_default(css) + 15),
            "intensity": "easy", "note": f"{dist_m} m easy cool-down"}


def _pool_set(reps: int, dist: int, rest: int, intensity: str, sec_per_100: float,
              label: str, note: str, stroke: str = "freestyle",
              equipment: str | None = None) -> dict:
    s = {"type": "interval_set", "reps": reps, "distance_m": dist, "rest_sec": rest,
         "intensity": intensity, "sec_per_km": int(sec_per_100 * 10),
         "stroke": stroke, "label": label, "note": note}
    if equipment:
        s["equipment"] = equipment
    return s


def _fill_reps(budget_m: int, dist: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, budget_m // dist))


def _budget_m(duration_min: int, css: float | None, used_m: int) -> int:
    """Metres left in the session at an aerobic pace, after `used_m`."""
    total = int(duration_min * 60 / (_css_or_default(css) + 12) * 100)
    return max(0, total - used_m)


# ─────────────────────────────────────────
# Pool and open-water builders
# ─────────────────────────────────────────

def _swim_technique(duration_min: int, css: float | None = None, variation: int = 0,
                    open_water: bool = False) -> list[dict]:
    """
    Technique session — drills, kick and pull at easy effort.

    Economy is trained at low intensity so the new movement is rehearsed, not
    fought (Barbosa 2010). Drills rotate by `variation` so a month of Mondays
    covers the whole stroke. Open water swaps drills for the skills of the
    event: sighting, drafting, buoy turns.
    """
    if open_water:
        a_label, a_note = _OW_SKILLS[variation % 6]
        b_label, b_note = _OW_SKILLS[(variation + 3) % 6]
        main = max(10, duration_min - 20)
        each = 4
        reps = max(2, main // (each + 1) // 2)
        return [
            {"type": "warmup", "duration_min": 10, "intensity": "easy",
             "note": "Easy swim · settle breathing, let the goggles and wetsuit settle"},
            {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": 1,
             "intensity": "easy", "label": a_label,
             "note": f"{reps}× {each} min {a_note} · 1 min easy"},
            {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": 1,
             "intensity": "easy", "label": b_label,
             "note": f"{reps}× {each} min {b_note} · 1 min easy"},
            {"type": "cooldown", "duration_min": 10, "intensity": "easy",
             "note": "Easy swim back to the exit"},
        ]
    c = _css_or_default(css)
    d_label, d_note = _DRILLS[variation % 6]
    k_reps = 6
    steps = [
        _warmup(300, css),
        _pool_set(8, 50, 15, "easy", c + 20, d_label, f"8×50 m drill · {d_note} · 15 s rest",
                  stroke="drill"),
        _pool_set(k_reps, 50, 20, "easy", c + 30, "Kick",
                  f"{k_reps}×50 m kick with a board · from the hips, small fast kick · 20 s rest",
                  stroke="drill", equipment="swim_kickboard"),
    ]
    budget = _budget_m(duration_min, css, 300 + 400 + 300 + 200)
    reps = _fill_reps(budget, 100, 2, 10)
    steps.append(_pool_set(reps, 100, 15, "aerobic", c + 12, "Pull",
                           f"{reps}×100 m pull buoy {_pace_note(css, 12, 'at an easy aerobic effort')}"
                           " · long strokes, count them per length · 15 s rest",
                           equipment="swim_pull_buoy"))
    steps.append(_cooldown(200, css))
    return steps


# Aerobic repeat shapes: (distance, rest s). Longer repeats, short rests —
# aerobic volume without the monotony of one long swim.
_AEROBIC_SHAPES: tuple[tuple[int, int], ...] = (
    (400, 30), (200, 20), (300, 25), (500, 30), (100, 10), (250, 20),
)


def _swim_aerobic(duration_min: int, css: float | None = None, variation: int = 0,
                  open_water: bool = False) -> list[dict]:
    """
    Aerobic endurance at CSS + 10-15 s/100 m — the volume that makes up most
    of the week (Seiler 2010; Pyne & Sharp 2014).
    """
    if open_water:
        main = max(15, duration_min - 10)
        return [
            {"type": "warmup", "duration_min": 5, "intensity": "easy", "note": "Easy swim"},
            {"type": "swim", "duration_min": main, "intensity": "aerobic",
             "note": "Continuous aerobic swim · comfortable, could keep going · sight every 8-10 strokes"},
            {"type": "cooldown", "duration_min": 5, "intensity": "easy", "note": "Easy swim"},
        ]
    c = _css_or_default(css)
    dist, rest = _AEROBIC_SHAPES[variation % 6]
    budget = _budget_m(duration_min, css, 300 + 200)
    reps = _fill_reps(budget, dist, 2, 30)
    return [
        _warmup(300, css),
        _pool_set(reps, dist, rest, "aerobic", c + 12, f"Aerobic {dist}",
                  f"{reps}×{dist} m {_pace_note(css, 12, 'at a steady aerobic effort')}"
                  f" · even splits · {rest} s rest"),
        _cooldown(200, css),
    ]


def _swim_css(build_idx: int, css: float | None = None, variation: int = 0,
              open_water: bool = False) -> list[dict]:
    """
    CSS threshold set — repeats at CSS with short rest.

    The ladder lengthens the repeats as the block goes on (100s → 200s → 300s
    → 400s) while the total at CSS grows from 1000 m to 1600 m: the same
    stimulus held for longer, which is how threshold work progresses (Pyne &
    Sharp 2014). Short rests (10-30 s) keep lactate near the steady state
    rather than letting it clear.
    """
    if open_water:
        reps = min(5, 3 + build_idx // 2)
        each = min(10, 6 + build_idx // 2)
        return [
            {"type": "warmup", "duration_min": 10, "intensity": "easy",
             "note": "Easy swim, 4× 20 strokes building"},
            {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": 1,
             "intensity": "threshold", "label": "CSS effort",
             "note": f"{reps}× {each} min at CSS effort (hard but even) · sight every 6 strokes"
                     " · 1 min easy"},
            {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy swim"},
        ]
    c = _css_or_default(css)
    ladder = ((10, 100, 15), (6, 200, 20), (5, 300, 25), (4, 400, 30))
    step = min(len(ladder) - 1, build_idx // 2)
    reps, dist, rest = ladder[step]
    if variation % 2 == 1 and step > 0:
        # Every other occurrence, the same metres as twice as many half-length
        # repeats: variety without changing the dose.
        reps, dist, rest = reps * 2, dist // 2, max(10, rest - 5)
    return [
        _warmup(400, css, "200 easy, 4×50 build, 100 easy"),
        _pool_set(reps, dist, rest, "threshold", c, f"CSS {dist}",
                  f"{reps}×{dist} m {_pace_note(css, 0, 'at CSS (threshold) effort')}"
                  f" · hold every repeat within 2 s · {rest} s rest"),
        _cooldown(200, css),
    ]


def _swim_vo2(build_idx: int, css: float | None = None, variation: int = 0,
              open_water: bool = False) -> list[dict]:
    """
    VO2 set — repeats 3-5 s/100 m faster than CSS with rest of about half the
    work. Shorter reps early (50s), longer as the block builds (100s, 200s).
    """
    if open_water:
        reps = min(10, 6 + build_idx // 2)
        return [
            {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy swim"},
            {"type": "effort_set", "reps": reps, "duration_min_each": 2, "rest_min": 1,
             "intensity": "vo2", "label": "Surge",
             "note": f"{reps}× 2 min hard (as at a race start or buoy) · 1 min easy but keep swimming"},
            {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy swim"},
        ]
    c = _css_or_default(css)
    ladder = ((16, 50, 20, 5), (10, 100, 30, 4), (8, 100, 25, 4), (5, 200, 45, 3))
    reps, dist, rest, delta = ladder[min(len(ladder) - 1, build_idx // 2)]
    return [
        _warmup(400, css, "200 easy, 4×50 build, 100 easy"),
        _pool_set(reps, dist, rest, "vo2", c - delta, f"VO2 {dist}",
                  f"{reps}×{dist} m {_pace_note(css, -delta, 'fast — faster than CSS, not a sprint')}"
                  f" · {rest} s rest"),
        _cooldown(300, css),
    ]


def _swim_race_pace(race_distance_m: float, css: float | None = None,
                    open_water: bool = False) -> list[dict]:
    """
    Race-specific set. Pool: the race distance broken into short repeats at
    goal pace with 10-15 s rest (race-pace work, Nugent 2017), so the pace is
    rehearsed at full volume. Open water: continuous race pace with the pace
    changes a race brings — a fast start, surges round buoys (Baldassarre 2017).
    """
    race = _race_m(race_distance_m)
    if open_water:
        main = min(30, max(12, race // 100))
        return [
            {"type": "warmup", "duration_min": 10, "intensity": "easy",
             "note": "Easy swim, 3× 20 strokes fast"},
            {"type": "effort_set", "reps": 1, "duration_min_each": 2, "intensity": "vo2",
             "label": "Start", "note": "2 min start sprint · then settle without stopping"},
            {"type": "swim", "duration_min": main, "intensity": "race_pace",
             "note": f"{main} min at race pace · surge for 20 strokes every 5 min as at a buoy"
                     " · sight every 6 strokes"},
            {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy swim"},
        ]
    c = _css_or_default(css)
    dist = 100 if race <= 800 else 200 if race <= 2000 else 400
    total = min(race, 3000)
    reps = max(3, total // dist)
    return [
        _warmup(400, css, "200 easy, 4×50 build, 100 easy"),
        _pool_set(reps, dist, 15, "race_pace", c - 2, f"Race {dist}",
                  f"{reps}×{dist} m at goal race pace {_pace_note(css, -2, '')}".rstrip()
                  + f" · the race distance broken up · 15 s rest"),
        _cooldown(200, css),
    ]


def _swim_long(duration_min: int, phase: str, css: float | None = None,
               open_water: bool = False) -> list[dict]:
    """
    Long swim, by distance: continuous in build and peak for the event, broken
    into four with 20 s rests in base while form still holds better in pieces.
    """
    if open_water:
        return [{"type": "swim", "duration_min": duration_min, "intensity": "aerobic",
                 "note": "Long continuous open-water swim · steady aerobic effort · sight every 8"
                         " strokes · practise feeding if the race is over an hour"}]
    c = _css_or_default(css)
    total = _budget_m(duration_min, css, 400) // 100 * 100
    total = max(800, total)
    if phase == "base":
        dist = total // 4 // 50 * 50
        body = _pool_set(4, dist, 20, "aerobic", c + 12, f"Long {dist}",
                         f"4×{dist} m {_pace_note(css, 12, 'at a steady aerobic effort')}"
                         " · form over speed · 20 s rest")
    else:
        body = _pool_set(1, total, 0, "aerobic", c + 10, f"Long {total}",
                         f"{total} m continuous {_pace_note(css, 10, 'at a steady aerobic effort')}"
                         " · no stopping at the walls")
    return [_warmup(200, css), body, _cooldown(200, css)]


def _swim_short_quality(css: float | None = None, open_water: bool = False) -> list[dict]:
    """
    Taper sharpener: little volume, full intensity (Bosquet 2007) — fast 50s
    with full rest, then a few 100s at race pace.
    """
    if open_water:
        return [
            {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy swim"},
            {"type": "effort_set", "reps": 6, "duration_sec_each": 30, "rest_sec": 60,
             "intensity": "fast", "label": "Fast", "note": "6× 30 s fast · 1 min easy"},
            {"type": "effort_set", "reps": 3, "duration_min_each": 3, "rest_min": 1,
             "intensity": "race_pace", "label": "Race pace", "note": "3× 3 min at race pace · 1 min easy"},
            {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy swim"},
        ]
    c = _css_or_default(css)
    return [
        _warmup(400, css, "200 easy, 4×50 build, 100 easy"),
        _pool_set(8, 50, 40, "fast", c - 12, "Fast 50",
                  "8×50 m fast · smooth, not thrashing · 40 s rest"),
        _pool_set(4, 100, 20, "race_pace", c - 2, "Race 100",
                  f"4×100 m at race pace {_pace_note(css, -2, '')}".rstrip() + " · 20 s rest"),
        _cooldown(300, css),
    ]
