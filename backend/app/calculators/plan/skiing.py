# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Skiing workout builders — cross-country / ski mountaineering, and alpine.

Two families (see base.py `_sport_family`), because they are different sports
to train for.

**Cross-country and skimo** are endurance sports with some of the highest
VO2max values measured in any athletes; races are decided on the climbs,
where skiers work at or above threshold (Sandbakk & Holmberg 2017). Much of
the year's training is dry-land, and the specific forms are the ones used
here: ski-walking and bounding uphill with poles, and roller skiing. The
sessions say "on snow or roller skis" rather than guessing the season. Skimo
(goal sport `backcountry_skiing`) gets the same structure with skinning in
place of skiing, vertical targets, and transitions practice.

**Alpine** is trained almost entirely off the snow before the season, so its
builders are dry-land conditioning: an aerobic base for recovering between
runs and through a long day, eccentric leg work for the loading in a turn,
plyometrics and agility for the reactive side, and lactic intervals the
length of a run. Heavy strength is the strength planner's job — the notes
point there rather than duplicating it.

References
----------
- Sandbakk, Ø., & Holmberg, H.-C. (2017). Physiological capacity and training
  routines of elite cross-country skiers. *Int J Sports Physiol Perform*,
  12(8), 1003-1011. ~90% low intensity; specific dry-land forms.
- Sandbakk, Ø., et al. (2011). The physiology of world-class sprint skiers.
  *Scand J Med Sci Sports*, 21(6), e9-e16. Uphill performance decides.
- Losnegard, T. (2019). Energy system contribution during competitive
  cross-country skiing. *Eur J Appl Physiol*, 119(8), 1675-1690.
- Hintermeister, R. A., et al. (1995). Quadriceps, hamstring and hip muscle
  activity in alpine skiing. *Med Sci Sports Exerc*, 27(3), 315-322.
- Berg, H. E., Eiken, O., & Tesch, P. A. (1995). Involvement of eccentric
  muscle actions in giant slalom racing. *Med Sci Sports Exerc*, 27(12),
  1666-1670.
- Turnbull, J. R., Kilding, A. E., & Keogh, J. W. L. (2009). Physiology of
  alpine skiing. *Scand J Med Sci Sports*, 19(2),
  146-155. Run durations, lactate, the aerobic base for recovery.
- Chu, D. A. (1998). *Jumping into Plyometrics* (2nd ed.). Contacts per
  session: ~80-100 for beginners, rising to 120-140.
"""

from __future__ import annotations

from app.calculators.plan.base import _hr_zone

_SKIMO_SPORTS = frozenset({"backcountry_skiing", "ski_mountaineering", "skimo"})


def _is_skimo(sport: str) -> bool:
    return sport.lower().replace(" ", "_") in _SKIMO_SPORTS


def _mode(skimo: bool) -> str:
    return "skinning uphill" if skimo else "on snow or roller skis"


# ─────────────────────────────────────────
# Cross-country and skimo
# ─────────────────────────────────────────

_XC_FOCUS: tuple[str, ...] = (
    "classic technique",
    "skate technique",
    "double poling on the flats",
    "classic technique on the climbs",
    "skate: V2 on the flats, V1 on the climbs",
    "mixed: whatever the terrain asks",
)


def _ski_endurance(duration_min: int, sport: str, variation: int = 0,
                   lthr: int | None = None) -> list[dict]:
    """
    Low-intensity distance — the ~90% of an elite skier's hours that sit
    below the first threshold (Sandbakk & Holmberg 2017). XC rotates the
    technique of the day; skimo climbs.
    """
    if _is_skimo(sport):
        note = (f"Easy skinning · {_hr_zone(0.75, 0.85, lthr)} · steady rhythm, short"
                " steps on the steep bits · or hike uphill with poles off-season")
    else:
        note = (f"Easy distance {_mode(False)} · {_XC_FOCUS[variation % 6]} ·"
                f" {_hr_zone(0.75, 0.85, lthr)}")
    return [{"type": "activity", "duration_min": duration_min, "intensity": "endurance",
             "note": note}]


_XC_DRILLS: tuple[str, ...] = (
    "no-poles skating: balance over the glide ski",
    "one-skate (V2) on gentle terrain: commit to each ski",
    "double poling only: hinge from the hips, not the arms",
    "diagonal stride without poles: kick and glide timing",
)
_SKIMO_DRILLS: tuple[str, ...] = (
    "transitions: skins off, boots and bindings to ski mode, and back — against the clock",
    "kick turns on a steep slope, both directions",
    "efficient skinning: slide, don't lift; heel risers only when you need them",
    "bootpack sections with skis on the pack",
)


def _ski_technique(duration_min: int, sport: str, variation: int = 0) -> list[dict]:
    """Technique at low intensity — skill before speed, drill rotated weekly.
    Capped at 45 min of drills: past that attention, and so the drill, fades."""
    drills = _SKIMO_DRILLS if _is_skimo(sport) else _XC_DRILLS
    drill = drills[variation % 4]
    main = max(15, min(45, duration_min - 20))
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy skiing"},
        {"type": "activity", "duration_min": main, "intensity": "easy",
         "note": f"Technique · {drill} · easy effort; stop before form fades"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy skiing"},
    ]


def _ski_pole_hike(duration_min: int, build_idx: int, imperial: bool = False,
                   lthr: int | None = None) -> list[dict]:
    """
    Ski-walking uphill with poles — the dry-land session that most resembles
    classic skiing uphill, at an aerobic effort (Sandbakk & Holmberg 2017).
    """
    climb = max(30, duration_min)
    vert = climb * (400 + 25 * min(build_idx, 6)) // 60 // 50 * 50
    shown = f"{int(vert * 3.28084) // 50 * 50} ft" if imperial else f"{vert} m"
    return [{"type": "activity", "duration_min": climb, "intensity": "endurance",
             "note": f"Ski-walking uphill with poles · ~{shown} of climbing · long strides,"
                     f" push through the poles · {_hr_zone(0.75, 0.85, lthr)}"}]


def _ski_bounding(build_idx: int, variation: int = 0, lthr: int | None = None) -> list[dict]:
    """
    Uphill ski-bounding intervals — explosive, ski-specific, and hard enough
    to count as the week's quality session in base. 6 reps rising to 10.
    """
    reps = min(10, 6 + build_idx + variation % 2)
    return [
        {"type": "warmup", "duration_min": 15, "intensity": "easy",
         "note": "Easy jog, then 4× 20 s ski-walking building"},
        {"type": "effort_set", "reps": reps, "duration_min_each": 1, "rest_min": 2,
         "intensity": "vo2", "label": "Bound",
         "note": f"{reps}× 1 min uphill ski-bounding with poles · springy, arms driving ·"
                 f" {_hr_zone(0.97, 1.03, lthr)} by the end · walk down to recover"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy jog"},
    ]


_XC_VO2: tuple[tuple[int, int], ...] = ((5, 3), (5, 4), (6, 4), (5, 5), (4, 6))


def _ski_intervals(build_idx: int, sport: str, lthr: int | None = None) -> list[dict]:
    """
    Uphill VO2 intervals — 3-6 min climbs at 90-95% HRmax, the core high
    intensity session in XC (Sandbakk 2011; Losnegard 2019). Work per
    session rises from 15 to 25-30 min through the block.
    """
    reps, each = _XC_VO2[min(len(_XC_VO2) - 1, build_idx // 2)]
    how = "skinning or bootpacking uphill" if _is_skimo(sport) else "uphill, on snow, roller skis or ski-walking"
    return [
        {"type": "warmup", "duration_min": 15, "intensity": "easy", "note": "Easy, 3× 30 s building"},
        {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": 3,
         "intensity": "vo2", "label": "Uphill",
         "note": f"{reps}× {each} min {how} · {_hr_zone(1.00, 1.05, lthr)} · hard, even pacing"
                 " · 3 min easy down"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy"},
    ]


_XC_THRESHOLD: tuple[tuple[int, int], ...] = ((3, 8), (3, 10), (4, 10), (3, 12), (3, 15))


def _ski_threshold(build_idx: int, sport: str, lthr: int | None = None) -> list[dict]:
    """
    Threshold — sustained climbs at the lactate threshold; skimo's race
    effort is exactly this, a long climb held just under the red line.
    """
    reps, each = _XC_THRESHOLD[min(len(_XC_THRESHOLD) - 1, build_idx // 2)]
    return [
        {"type": "warmup", "duration_min": 15, "intensity": "easy", "note": "Easy"},
        {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": 3,
         "intensity": "threshold", "label": "Threshold",
         "note": f"{reps}× {each} min at threshold {_mode(_is_skimo(sport))} ·"
                 f" {_hr_zone(0.94, 1.00, lthr)} · comfortably hard · 3 min easy"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy"},
    ]


def _ski_race_pace(sport: str, lthr: int | None = None) -> list[dict]:
    """Race rehearsal — race pace with a fast start, or a skimo race climb with transitions."""
    if _is_skimo(sport):
        return [
            {"type": "warmup", "duration_min": 15, "intensity": "easy", "note": "Easy skinning"},
            {"type": "effort_set", "reps": 3, "duration_min_each": 12, "rest_min": 4,
             "intensity": "race_pace", "label": "Race climb",
             "note": f"3× 12 min race-pace climb · {_hr_zone(0.95, 1.02, lthr)} · a full transition"
                     " at the top, ski down as the recovery"},
            {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy"},
        ]
    return [
        {"type": "warmup", "duration_min": 15, "intensity": "easy", "note": "Easy, 3× 20 s fast"},
        {"type": "effort_set", "reps": 5, "duration_min_each": 5, "rest_min": 3,
         "intensity": "race_pace", "label": "Race pace",
         "note": f"5× 5 min at race pace on race-like terrain · the first 30 s of each fast, like a"
                 f" start · {_hr_zone(0.95, 1.02, lthr)} · 3 min easy"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy"},
    ]


def _ski_long(duration_min: int, sport: str, imperial: bool = False) -> list[dict]:
    """Long, easy distance — or for skimo, a long day in the mountains."""
    if _is_skimo(sport):
        vert = duration_min * 400 // 60 // 50 * 50
        shown = f"{int(vert * 3.28084) // 50 * 50} ft" if imperial else f"{vert} m"
        note = (f"Long day skinning (or hiking with poles off-season) · ~{shown} of climbing ·"
                " easy all day: eat and drink every hour")
    else:
        note = "Long easy distance on snow or roller skis · both techniques · fuel every 45 min"
    return [{"type": "activity", "duration_min": duration_min, "intensity": "endurance", "note": note}]


def _ski_short_quality() -> list[dict]:
    """Taper: short sprints and a little race pace — intensity kept, volume cut."""
    return [
        {"type": "warmup", "duration_min": 15, "intensity": "easy", "note": "Easy"},
        {"type": "effort_set", "reps": 6, "duration_sec_each": 15, "rest_sec": 105,
         "intensity": "sprint", "label": "Sprint", "note": "6× 15 s sprint · full recovery"},
        {"type": "effort_set", "reps": 3, "duration_min_each": 3, "rest_min": 2,
         "intensity": "race_pace", "label": "Race pace", "note": "3× 3 min at race pace · 2 min easy"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy", "note": "Easy"},
    ]


# ─────────────────────────────────────────
# Alpine (dry-land)
# ─────────────────────────────────────────

_ALPINE_AEROBIC: tuple[str, ...] = (
    "easy run", "easy ride", "uphill hike", "easy ride", "easy run", "rowing or elliptical",
)

_STRENGTH_NOTE = "Heavy leg strength comes from your strength sessions — turn on Include strength."


def _alp_aerobic(duration_min: int, variation: int = 0, lthr: int | None = None) -> list[dict]:
    """
    Aerobic base. A ski day is hours of intermittent work, and recovery
    between runs is aerobic (Turnbull et al. 2009); the mode rotates to
    spare the joints.
    """
    return [{"type": "activity", "duration_min": duration_min, "intensity": "endurance",
             "note": f"Aerobic base · {_ALPINE_AEROBIC[variation % 6]} · {_hr_zone(0.75, 0.85, lthr)}"
                     " · conversational"}]


def _alp_eccentric(build_idx: int, variation: int = 0) -> list[dict]:
    """
    Eccentric and isometric leg circuit. Skiing loads the quadriceps mostly
    eccentrically and isometrically (Hintermeister 1995; Berg 1995): slow
    lowering and long holds in a skiing stance are the specific strength.
    Rounds rise from 3 to 5.
    """
    rounds = min(5, 3 + max(build_idx // 2, variation // 3))
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy",
         "note": "Easy cardio, leg swings, 10 bodyweight squats"},
        {"type": "effort_set", "reps": rounds, "duration_sec_each": 60, "rest_sec": 30,
         "intensity": "eccentric", "label": "Slow squat",
         "note": f"{rounds}× 60 s squats lowering for 4 s, up in 1 s · goblet weight if easy · 30 s rest"},
        {"type": "effort_set", "reps": rounds, "duration_sec_each": 60, "rest_sec": 30,
         "intensity": "eccentric", "label": "Step-down",
         "note": f"{rounds}× 60 s single-leg step-downs from a box, 3 s lowering, alternating legs"
                 " · 30 s rest"},
        {"type": "effort_set", "reps": rounds, "duration_sec_each": 45 + 15 * min(build_idx, 5),
         "rest_sec": 45, "intensity": "eccentric", "label": "Ski hold",
         "note": f"{rounds}× wall sit or tuck hold in a skiing stance, "
                 f"{45 + 15 * min(build_idx, 5)} s · 45 s rest"},
        {"type": "cooldown", "duration_min": 5, "intensity": "easy", "note": _STRENGTH_NOTE},
    ]


_PLYO: tuple[tuple[str, str], ...] = (
    ("Skater hops", "skater hops: lateral bound, stick the landing on one leg"),
    ("Box jumps", "box jumps: land softly, step down"),
    ("Lateral hops", "lateral hops over a line or low hurdle, both feet"),
    ("Tuck jumps", "tuck jumps: knees up, quiet landing"),
    ("Single-leg hops", "single-leg forward hops, stick each landing"),
    ("Zig-zag bounds", "zig-zag bounds down a line, as through gates"),
)


def _alp_plyometrics(build_idx: int, variation: int = 0) -> list[dict]:
    """
    Plyometrics — the reactive strength of absorbing and redirecting force
    in a turn. Two exercises a session, contacts rising from ~80 to ~140
    (Chu 1998), with full rest: quality of each jump over fatigue.
    """
    a_label, a_note = _PLYO[variation % 6]
    b_label, b_note = _PLYO[(variation + 2) % 6]
    sets = min(7, 4 + build_idx // 2)
    return [
        {"type": "warmup", "duration_min": 12, "intensity": "easy",
         "note": "Easy cardio, dynamic leg swings, 3× 10 low pogo hops"},
        {"type": "effort_set", "reps": sets, "duration_sec_each": 20, "rest_sec": 60,
         "intensity": "plyometric", "label": a_label, "note": f"{sets}× 20 s {a_note} · 60 s rest"},
        {"type": "effort_set", "reps": sets, "duration_sec_each": 20, "rest_sec": 60,
         "intensity": "plyometric", "label": b_label, "note": f"{sets}× 20 s {b_note} · 60 s rest"},
        {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy cardio and stretch"},
    ]


_AGILITY: tuple[tuple[str, str], ...] = (
    ("Ladder", "agility ladder: in-in-out-out, lateral shuffles, crossovers"),
    ("Cones", "cone slalom: quick feet round a tight line of cones"),
    ("Reaction", "reactive shuffles: a partner or app calls the direction"),
    ("Balance", "single-leg balance on a cushion or board, eyes closed for the last 10 s"),
)


def _alp_agility(duration_min: int, variation: int = 0) -> list[dict]:
    """
    Agility and balance — quick feet and edge-to-edge reactions, at low
    metabolic cost. Two drills a session, rotated.
    """
    a_label, a_note = _AGILITY[variation % 4]
    b_label, b_note = _AGILITY[(variation + 3) % 4]
    reps = max(4, (duration_min - 18) // 3)
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy cardio, leg swings"},
        {"type": "effort_set", "reps": reps, "duration_sec_each": 30, "rest_sec": 30,
         "intensity": "agility", "label": a_label, "note": f"{reps}× 30 s {a_note} · 30 s rest"},
        {"type": "effort_set", "reps": reps, "duration_sec_each": 30, "rest_sec": 30,
         "intensity": "agility", "label": b_label, "note": f"{reps}× 30 s {b_note} · 30 s rest"},
        {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy cardio"},
    ]


def _alp_ski_intervals(build_idx: int, lthr: int | None = None) -> list[dict]:
    """
    Ski-run intervals — lateral work the length of a run (60-90 s) with
    rest about twice as long, as between runs. Blood lactate after a giant
    slalom run is high (Turnbull 2009); this trains tolerating and clearing
    it. 6 reps rising to 10, the reps lengthening from 60 to 90 s.
    """
    reps = min(10, 6 + build_idx // 2)
    each = min(90, 60 + 10 * (build_idx // 2))
    return [
        {"type": "warmup", "duration_min": 12, "intensity": "easy", "note": "Easy cardio, 3× 15 s skater hops"},
        {"type": "effort_set", "reps": reps, "duration_sec_each": each, "rest_sec": each * 2,
         "intensity": "vo2", "label": "Ski run",
         "note": f"{reps}× {each} s ski-run circuit: skater hops, lateral box step-overs, tuck"
                 f" hold · {_hr_zone(0.95, 1.05, lthr)} by the end · {each * 2} s rest"},
        {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy cardio"},
    ]


def _alp_long(duration_min: int) -> list[dict]:
    """A long aerobic day — hike or ride — for the stamina of a full ski day."""
    return [{"type": "activity", "duration_min": duration_min, "intensity": "endurance",
             "note": "Long aerobic day · hike or ride · steady, the length of a ski day's skiing"}]
