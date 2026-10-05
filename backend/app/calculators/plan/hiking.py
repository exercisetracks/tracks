# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Hiking workout builders — vertical, load and time on feet.

A hard mountain day is limited by three things a flat walking plan does not
train: climbing (vertical metres per hour at a sustainable heart rate),
carrying a pack, and descending — the eccentric quadriceps work that leaves
legs shaking on the way down and sore for days after. The sessions here train
each directly:

  vert               sustained uphill hiking at an aerobic effort, with a
                     vertical target that rises across the plan
  incline_intervals  uphill intervals — treadmill incline, stairs, hill repeats,
                     weighted step-ups — for climbing power
  descent            controlled downhill repeats and step-downs, started small
  long               the loaded long hike, pack weight progressing
  back_to_back       the first of two consecutive long days

Pack weight is prescribed in kilograms and rises by about a kilogram a week
of build, reaching 15 kg at most. Load carriage research puts the injury
risk in sudden jumps rather than in the load itself, and finds that
progressive loaded marching improves loaded performance more than unloaded
training does (Knapik et al. 2004).

References
----------
- Knapik, J. J., Reynolds, K. L., & Harman, E. (2004). Soldier load carriage:
  historical, physiological, biomechanical, and medical aspects. *Mil Med*,
  169(1), 45-56.
  Progressive load; loaded training is specific.
- Nosaka, K., & Clarkson, P. M. (1995). Muscle damage following repeated
  bouts of high force eccentric exercise. *Med Sci Sports Exerc*, 27(9),
  1263-1269. The repeated-bout effect: one light exposure protects.
- Koop, J. (2016). *Training Essentials for Ultrarunning*. Back-to-back long
  days for multi-day and very long events.
- House, S., & Johnston, S. (2014). *Training for the New Alpinism*.
  Patagonia Books. Vertical-gain progression and loaded step-ups for mountain days.
"""

from __future__ import annotations

from app.calculators.plan.base import _hr_zone


def _fmt_vert(metres: int, imperial: bool) -> str:
    if imperial:
        return f"{int(metres * 3.28084) // 50 * 50} ft"
    return f"{metres} m"


def _fmt_pack(kg: int, imperial: bool) -> str:
    if imperial:
        return f"{kg * 22 // 10} lb"
    return f"{kg} kg"


def _hike_easy(duration_min: int) -> list[dict]:
    return [{"type": "activity", "duration_min": duration_min, "intensity": "easy",
             "note": "Easy hike or brisk walk · rolling terrain · conversational"}]


def _hike_vert(duration_min: int, build_idx: int, imperial: bool = False) -> list[dict]:
    """
    Sustained climbing at an aerobic effort. The vertical target is set from
    the time available at a climbing rate that improves across the plan —
    350 m/h at the start, up to 550 m/h — which is how a hiker's weekly
    vertical rises without any one session jumping.
    """
    climb = max(30, duration_min)
    rate = 350 + 25 * min(build_idx, 8)
    vert = climb * rate // 60 // 50 * 50
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy flat walking"},
        {"type": "activity", "duration_min": climb, "intensity": "endurance",
         "note": f"Climb steadily · aim for ~{_fmt_vert(vert, imperial)} of ascent"
                 " · aerobic: breathing hard enough to notice, not to stop talking"},
        {"type": "cooldown", "duration_min": 10, "intensity": "easy",
         "note": "Easy descent · short steps, poles if you use them"},
    ]


_INCLINE_MODES: tuple[tuple[str, str, int, int, int], ...] = (
    # label, what, base reps, minutes each, rest minutes
    ("Incline", "treadmill at 12-15% incline, brisk walk, no handrails", 5, 4, 2),
    ("Stairs", "stair climbing, every step, steady rhythm; walk down to recover", 6, 3, 2),
    ("Hill", "outdoor hill repeats at a hard hiking pace; walk down to recover", 4, 6, 4),
    ("Step-ups", "step-ups onto a knee-high box with your pack, alternating legs", 4, 5, 2),
)


def _hike_incline_intervals(build_idx: int, variation: int = 0,
                            lthr: int | None = None) -> list[dict]:
    """
    Uphill intervals near threshold — climbing power for the steep parts,
    where a hiker's heart rate is highest. Mode rotates by occurrence; reps
    grow by one every two build weeks, capped at three extra.
    """
    label, what, reps, each, rest = _INCLINE_MODES[variation % 4]
    reps += min(build_idx // 2, 3)
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy",
         "note": "Easy walking, the last 3 min on a gentle incline"},
        {"type": "effort_set", "reps": reps, "duration_min_each": each, "rest_min": rest,
         "intensity": "threshold", "label": label,
         "note": f"{reps}× {each} min {what} · {_hr_zone(0.94, 1.00, lthr)} · hard but steady"
                 f" · {rest} min easy"},
        {"type": "cooldown", "duration_min": 8, "intensity": "easy", "note": "Easy walking"},
    ]


def _hike_descent(duration_min: int, build_idx: int, variation: int = 0) -> list[dict]:
    """
    Descent conditioning. Controlled downhill repeats with the easy climb
    back up as the recovery, then slow step-downs. Deliberately small at
    first: a single light eccentric bout gives most of the protection
    against later soreness (Nosaka & Clarkson 1995), and a big first dose is
    days of it. Repeats rise with each occurrence and with the build.
    """
    reps = min(8, 2 + max(variation, build_idx))
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy", "note": "Easy walking to the hill"},
        {"type": "effort_set", "reps": reps, "duration_min_each": 4, "rest_min": 4,
         "intensity": "endurance", "label": "Descend",
         "note": f"{reps}× 4 min controlled descent on a steep path or stairs · soft knees, short"
                 " quick steps, don't brake with straight legs · walk back up easy"},
        {"type": "activity", "duration_min": 8, "intensity": "easy",
         "note": "Step-downs: 3× 10 slow (3 s) lowerings each leg from a 20-30 cm step"},
    ]


def _pack_kg(phase: str, build_idx: int) -> int:
    if phase in ("base", "taper"):
        return 5
    return min(15, 7 + build_idx)


def _hike_long(duration_min: int, phase: str, build_idx: int, imperial: bool = False) -> list[dict]:
    """
    The loaded long hike — time on feet with the pack, vertical included.
    Pack weight is light in base and taper and rises about a kilogram a week
    through build and peak (Knapik 2004: progress load gradually).
    """
    kg = _pack_kg(phase, build_idx)
    vert = duration_min * 250 // 60 // 50 * 50
    return [{"type": "activity", "duration_min": duration_min, "intensity": "endurance",
             "note": f"Long hike with a {_fmt_pack(kg, imperial)} pack · ~{_fmt_vert(vert, imperial)}"
                     " of climbing · steady all day: eat and drink every hour"}]


def _hike_back_to_back(duration_min: int, phase: str, build_idx: int,
                       imperial: bool = False) -> list[dict]:
    """
    Day one of a back-to-back: a solid hike the day before the long one, so
    the long hike starts on tired legs, as the second day of a trip does
    (Koop 2016). Twice an easy day's length, with a lighter pack.
    """
    minutes = max(90, duration_min * 2)
    kg = max(5, _pack_kg(phase, build_idx) - 3)
    return [{"type": "activity", "duration_min": minutes, "intensity": "endurance",
             "note": f"Day 1 of 2 · {minutes} min hike with a {_fmt_pack(kg, imperial)} pack"
                     " · keep it easy: tomorrow's long hike is the point"}]
