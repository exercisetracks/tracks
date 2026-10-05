# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Climbing workout builders — one sport, bouldering and routes alike.

Climbing is trained by time and effort, not distance, and what limits it is
local: finger flexor strength, forearm endurance, and the tendons and pulleys
that carry the load. The sessions:

  arc               ARC (aerobic restoration and capillarity): long,
                    continuous easy climbing, well under the pump — forearm
                    aerobic capacity and recovery between hard sections
  technique         movement drills on easy terrain
  hangboard         maximal finger strength: short max hangs with long rest,
                    plus antagonist conditioning
  limit_bouldering  a few moves at your limit, full rest — the strength-power
                    a grade demands
  power_endurance   linked hard climbing with incomplete rest (4×4s,
                    circuits) — the glycolytic capacity for routes
  long              a volume day at the crag or gym

**Frequency and rest.** Finger-intensive sessions are never on consecutive
days (the templates in base.py keep 48 h between them), and hangboard loading
rises slowly — a few percent a week, never with a jump — because tendon and
pulley collagen remodels over weeks and months and recovers more slowly than
the muscle that loads it (Magnusson et al. 2010). Pulley injuries are the
commonest climbing injury, and overload of the fingers the usual cause
(Schöffl et al. 2016). Antagonist and shoulder work rides with the hangboard
session, and heavier general strength comes from the strength planner.

Heart rate is a weak guide for climbing effort (it is driven by small
muscles and isometric grip), so the watch steps carry no targets: they are
timed, and the notes prescribe by feel — pump, attempts, rest.

References
----------
- López-Rivera, E., & González-Badillo, J. J. (2012). The effects of two
  maximum grip strength training methods using the same effort duration and
  different edge depth on grip endurance in elite climbers. *Sports Technol*,
  5(3-4), 100-110. Max hangs (10 s, long rest).
- Medernach, J. P. J., Kleinöder, H., & Lötzerich, H. H. H. (2015). Fingerboard
  in competitive bouldering: training effects on grip strength and endurance.
  *J Strength Cond Res*, 29(8), 2286-2295.
- Magnusson, S. P., Langberg, H., & Kjaer, M. (2010). The pathogenesis of
  tendinopathy: balancing the response to loading. *Nat Rev Rheumatol*, 6(5),
  262-268. Slow collagen turnover; progressive loading.
- Schöffl, V., et al. (2016). Evaluation of injuries and overuse syndromes in
  rock climbing. / Schöffl, V., Hochholzer, T., & Lutter, C. (2016). *One Move
  Too Many*. Pulley injury and finger overload.
- Hörst, E. J. (2016). *Training for Climbing* (3rd ed.). ARC, 4×4s, limit
  bouldering, weekly structure.
"""

from __future__ import annotations


def _climb_warmup(minutes: int = 15) -> dict:
    return {"type": "warmup", "duration_min": minutes, "intensity": "easy",
            "note": "Easy traversing and big-hold climbing, then 3-4 problems building"
                    " toward the day's effort"}


def _climb_cooldown(minutes: int = 10) -> dict:
    return {"type": "cooldown", "duration_min": minutes, "intensity": "easy",
            "note": "Easy climbing or traversing, then forearm and shoulder stretches"}


def _climb_arc(duration_min: int, build_idx: int, variation: int = 0) -> list[dict]:
    """
    ARC — continuous easy climbing for 15-30 min at a time, a light pump at
    most (Hörst 2016). Blocks lengthen with the build.
    """
    each = min(30, 15 + 5 * min(build_idx, 3) + 5 * (variation % 2))
    reps = max(2, min(3, (duration_min - 5) // (each + 5)))
    return [
        {"type": "warmup", "duration_min": 5, "intensity": "easy", "note": "Easy traverse"},
        {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": each // 2,
         "intensity": "endurance", "label": "ARC",
         "note": f"{reps}× {each} min continuous easy climbing (traverse or up-and-down laps) ·"
                 f" never more than a light pump · shake out on good holds · {each // 2} min rest"},
    ]


_TECHNIQUE_DRILLS: tuple[str, ...] = (
    "silent feet: place every foot without a sound",
    "straight arms: move from the hips, arms as ropes",
    "flagging and drop-knees on every move that allows one",
    "downclimb every problem you climb",
    "hover hands: pause over each hold before taking it",
    "slab and smearing: trust the feet",
)


def _climb_technique(duration_min: int, variation: int = 0) -> list[dict]:
    """Movement practice on terrain well below the limit, two drills a session."""
    a = _TECHNIQUE_DRILLS[variation % 6]
    b = _TECHNIQUE_DRILLS[(variation + 3) % 6]
    main = max(20, min(60, duration_min - 15))
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy traverse"},
        {"type": "activity", "duration_min": main, "intensity": "easy",
         "note": f"Technique on easy problems or routes · {a} · then {b} · stop before tiredness"
                 " makes the movement sloppy"},
        {"type": "cooldown", "duration_min": 5, "intensity": "easy", "note": "Forearm stretches"},
    ]


def _climb_hangboard(build_idx: int, variation: int = 0) -> list[dict]:
    """
    Max hangs — 10 s on a 20 mm edge at a load you could hold ~13 s, 3 min
    rest (López-Rivera 2012; Medernach 2015). Sets rise from 5 to 8 over the
    plan; the load rises only by a kilo or two a week. Antagonist and
    shoulder work follows, for the pushing muscles climbing neglects.
    """
    sets = min(8, 5 + build_idx // 2 + variation % 2)
    return [
        _climb_warmup(15),
        {"type": "effort_set", "reps": sets, "duration_sec_each": 10, "rest_sec": 180,
         "intensity": "max_strength", "label": "Max hang",
         "note": f"{sets}× 10 s max hang, 20 mm edge, half-crimp or open hand · add or take off"
                 " weight so 10 s is hard but clean · 3 min rest · stop at any finger pain"},
        {"type": "effort_set", "reps": 3, "duration_sec_each": 45, "rest_sec": 45,
         "intensity": "easy", "label": "Antagonists",
         "note": "3 rounds: 10 push-ups, 15 band external rotations, 15 reverse wrist curls"},
        _climb_cooldown(5),
    ]


def _climb_limit_bouldering(build_idx: int) -> list[dict]:
    """
    Limit bouldering — 4-6 problems at or just above your best, about three
    attempts each, full rest between (Hörst 2016). Short, maximal and hard on
    the fingers, which is why it never follows another finger session.
    """
    problems = min(6, 4 + build_idx // 3)
    attempts = problems * 3
    return [
        _climb_warmup(20),
        {"type": "effort_set", "reps": attempts, "duration_sec_each": 30, "rest_sec": 180,
         "intensity": "max_strength", "label": "Limit attempt",
         "note": f"{problems} limit problems × ~3 attempts ({attempts} goes) · full effort on"
                 " every go · 3 min rest, longer if you need it · stop when attempts get worse"},
        _climb_cooldown(),
    ]


_PE_SHAPES: tuple[tuple[str, str, int, int, int], ...] = (
    # label, what, sets, seconds on, seconds rest
    ("4x4", "four problems 3-4 grades below your limit, back to back", 4, 240, 240),
    ("Circuit", "a 30-50 move circuit that pumps you out near the end", 3, 180, 180),
    ("Linked", "two hard problems linked without stepping off", 4, 120, 120),
)


def _climb_power_endurance(build_idx: int, variation: int = 0) -> list[dict]:
    """
    Power endurance — hard climbing with incomplete rest, a deep pump by the
    end of each set: 4×4s, circuits of 30-50 moves, or linked problems
    (Hörst 2016). Sets grow by up to two with the build.
    """
    label, what, sets, on, off = _PE_SHAPES[variation % 3]
    sets += min(build_idx // 2, 2)
    return [
        _climb_warmup(20),
        {"type": "effort_set", "reps": sets, "duration_sec_each": on, "rest_sec": off,
         "intensity": "vo2", "label": label,
         "note": f"{sets}× {what} · ~{on // 60} min on, {off // 60} min rest · deep pump, clean"
                 " movement"},
        _climb_cooldown(),
    ]


def _climb_long(duration_min: int) -> list[dict]:
    """A volume day: many routes or problems well within your level, long rests."""
    return [{"type": "activity", "duration_min": duration_min, "intensity": "endurance",
             "note": "Volume day at the crag or gym · lots of climbing 2-3 grades below your"
                     " max · rest as long as you climb · a project attempt or two if fresh"}]


def _climb_short_quality() -> list[dict]:
    """Taper: a handful of near-limit problems, then stop fresh."""
    return [
        _climb_warmup(20),
        {"type": "effort_set", "reps": 6, "duration_sec_each": 30, "rest_sec": 240,
         "intensity": "max_strength", "label": "Near limit",
         "note": "6 goes on problems just below your limit · full rest · leave wanting more"},
        _climb_cooldown(),
    ]
