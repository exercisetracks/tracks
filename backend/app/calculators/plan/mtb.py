# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Mountain-biking workout builders.

HR-first prescriptions per Friel (2018) — Coggan power layered on when FTP
is set. Zone boundaries: Z1 <81%LTHR, Z2 81-88, Z3 89-93, Z4 94-99,
Z5a 100-102, Z5b 103-106, Z5c >106. Sub-2-min efforts use power/RPE only
since HR cannot respond fast enough on technical MTB terrain.

References
----------
- Impellizzeri, F. M., & Marcora, S. M. (2007). The physiology of mountain
  biking. *Sports Med*, 37(1), 59-71. — MTB requires high aerobic power +
  upper-body strength for technical handling.
- Macdermid, P. W., & Stannard, S. (2012). Mechanical work and physiological
  responses during simulated cross-country mountain biking. *J Sports Sci*,
  30(14), 1515-1523. — XC MTB demands larger strength component vs road cycling.
- Skiba, P. F., et al. (2015). Modeling the expenditure and reconstitution of
  anaerobic work capacity above critical power. *Med Sci Sports Exerc*, 47(8),
  1695-1705. — W' (W prime) model for anaerobic capacity; matchbook sessions
  built around W' reconstitution kinetics.
- Rønnestad, B. R., et al. (2015). 30/15 micro-bursts produce superior VO₂max
  stimulus at lower RPE. *Scand J Med Sci Sports*, 25(1), e89-98.
- McCormack, L. (2018). MTB skills are trainable and detrain faster than
  aerobic fitness — must be scheduled, not optional.
- Arriel, R. A., et al. (2022). Contemporary review of MTB physiology.
  *Int J Environ Res Public Health*, 19(19), 12552.
  Vibration-induced muscle fatigue decreases strength output on technical trails.
"""

from __future__ import annotations

from app.calculators.plan.base import _pwr_zone, _target_note

# ─────────────────────────────────────────
# Variation tables — 6 variants per type
# ─────────────────────────────────────────

_MTB_INTERVAL_VAR: tuple[tuple[int, int], ...] = (
    ( 0,   0), (+1, -30), ( 0, +30), (-1,   0), (+1, +15), (-1, -15),
)

_MTB_MICRO_VAR: tuple[tuple[int, int], ...] = (
    (13, 3), (10, 4), (15, 3), (13, 2), (12, 3), (14, 3),
)

_MTB_MATCH_VAR: tuple[tuple[int, int, int], ...] = (
    (8, 60, 180), (10, 45, 150), (6, 90, 240), (8, 30, 120),
    (6, 60, 240), (10, 30, 120),
)

_MTB_ENDURANCE_VAR: tuple[int, ...] = (0, +10, -10, +5, +15, -5)


# ─────────────────────────────────────────
# MTB skills curriculum
# ─────────────────────────────────────────

_MTB_SKILLS_DRILLS: tuple[tuple[str, str], ...] = (
    ("Cornering",          "Outside foot down, eyes on exit, lean bike not body. 8–12 reps each direction."),
    ("Descending",         "Heels down, hips back over rear wheel, light grip, look 5 m ahead. Repeat a descent 4–6 times, varying lines."),
    ("Climbing technique", "Seat-stay climbs — slide forward, drive heels, hold a steady cadence (75–85 rpm). 4–6 short climbs."),
    ("Line choice",        "Pick three lines on the same trail section, ride each 3×, compare flow and exit speed."),
    ("Drops",              "Start small (20–30 cm), preload, lift front wheel, soft landing. 8–10 reps in progression."),
    ("Jumps & pumps",      "Pump track or rolling bumps — generate speed without pedaling. Focus on the push-pull rhythm."),
    ("Braking control",    "Threshold braking — slow to walking pace using mostly the front, no skid. 6–8 reps."),
    ("Track stands",       "Balance drill at speed = 0. Builds slow-speed control. 30 s on, 30 s off, 10 sets."),
    ("Ratchet pedaling",   "Quarter-pedal strokes through rocks. Pick a technical 50 m and ratchet repeatedly."),
    ("Switchbacks",        "Tight uphill + downhill switchback turns. Late apex, weight outside pedal. 6–8 each way."),
)

_MTB_SKILLS_WEIGHTS: dict[str, dict[int, int]] = {
    "xco":    {0: 25, 1: 10, 2: 40, 3: 10, 4: 5,  5: 0,  6: 5,  7: 0,  8: 5,  9: 0},
    "xcm":    {0: 20, 1: 15, 2: 40, 3: 15, 4: 0,  5: 0,  6: 10, 7: 0,  8: 0,  9: 0},
    "enduro": {0: 25, 1: 25, 2: 5,  3: 5,  4: 15, 5: 10, 6: 10, 7: 0,  8: 0,  9: 5},
    "trail":  {0: 15, 1: 15, 2: 15, 3: 10, 4: 10, 5: 10, 6: 10, 7: 5,  8: 5,  9: 5},
}


def _mtb_pick_skills(discipline: str, k: int, seed: int) -> list[tuple[str, str]]:
    import random
    rng = random.Random(seed)
    weights = _MTB_SKILLS_WEIGHTS.get(discipline, _MTB_SKILLS_WEIGHTS["trail"])
    indices, ws = zip(*[(i, w) for i, w in weights.items() if w > 0])
    picked: list[int] = []
    pool_idx = list(indices)
    pool_w   = list(ws)
    for _ in range(min(k, len(pool_idx))):
        choice = rng.choices(pool_idx, weights=pool_w, k=1)[0]
        picked.append(choice)
        ci = pool_idx.index(choice)
        pool_idx.pop(ci); pool_w.pop(ci)
    return [_MTB_SKILLS_DRILLS[i] for i in picked]


# ─────────────────────────────────────────
# MTB workout builders
# ─────────────────────────────────────────

def _mtb_endurance(duration_min: int, lthr: int | None = None, ftp: int | None = None,
                    variation: int = 0) -> list[dict]:
    dur = max(45, duration_min + _MTB_ENDURANCE_VAR[variation % 6])
    note = _target_note(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (easy, conversational)")
    return [{
        "type": "ride", "duration_min": dur, "intensity": "endurance",
        "note": f"Z2 aerobic ride · {note}. Stay seated on climbs, smooth pedal stroke.",
    }]


def _mtb_tempo(duration_min: int, lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    warmup, cooldown = 15, 10
    tempo = max(20, duration_min - warmup - cooldown)
    note = _target_note(0.89, 0.93, lthr, 0.76, 0.88, ftp, "5/10 (moderate-hard)")
    return [
        {"type": "warmup",   "duration_min": warmup,   "intensity": "endurance",
         "note": "Easy spin + 2× 30 s spin-ups to open the legs"},
        {"type": "ride",     "duration_min": tempo,    "intensity": "tempo",
         "note": f"Z3 tempo · {note}. Sustainable but you can't sing."},
        {"type": "cooldown", "duration_min": cooldown, "intensity": "endurance",
         "note": "Easy spin back home"},
    ]


def _mtb_sweet_spot(duration_min: int, lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    warmup, cooldown = 15, 10
    ss = max(15, duration_min - warmup - cooldown)
    note = _target_note(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10 (comfortably hard)")
    return [
        {"type": "warmup",   "duration_min": warmup,   "intensity": "endurance",
         "note": "Easy spin + 3× 1 min build to threshold"},
        {"type": "ride",     "duration_min": ss,        "intensity": "sweet_spot",
         "note": f"Sweet spot · {note}. Pick a steady climb if possible."},
        {"type": "cooldown", "duration_min": cooldown,  "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_threshold(build_idx: int, lthr: int | None = None, ftp: int | None = None,
                    variation: int = 0) -> list[dict]:
    if build_idx <= 2:   base_reps, dur, rest = 2, 15, 5
    elif build_idx <= 4: base_reps, dur, rest = 2, 20, 5
    else:                base_reps, dur, rest = 3, 15, 4
    d_reps, d_rest_sec = _MTB_INTERVAL_VAR[variation % 6]
    reps     = max(2, base_reps + d_reps)
    rest_min = max(3, rest + (d_rest_sec // 60))
    note = _target_note(1.00, 1.02, lthr, 0.95, 1.00, ftp, "7/10 (1-hour race effort)")
    return [
        {"type": "warmup",     "duration_min": 15,        "intensity": "endurance",
         "note": "Easy spin + 3× 1 min ramping to threshold"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": rest_min,
         "intensity": "threshold",
         "note": f"{reps}× {dur} min at LT · {note} · {rest_min} min easy between"},
        {"type": "cooldown",   "duration_min": 10,        "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_intervals(build_idx: int, lthr: int | None = None, ftp: int | None = None,
                    variation: int = 0) -> list[dict]:
    if build_idx <= 1:   base_reps, dur = 4, 3
    elif build_idx <= 3: base_reps, dur = 4, 4
    elif build_idx <= 5: base_reps, dur = 5, 4
    else:                base_reps, dur = 5, 5
    d_reps, _ = _MTB_INTERVAL_VAR[variation % 6]
    reps = max(3, base_reps + d_reps)
    rest = dur
    note = _target_note(1.03, 1.06, lthr, 1.05, 1.20, ftp, "8/10 (VO2max, hard)")
    return [
        {"type": "warmup",     "duration_min": 20,        "intensity": "endurance",
         "note": "Easy spin + 4× 30 s spin-ups + 1× 2 min build to threshold"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": rest,
         "intensity": "vo2",
         "note": f"{reps}× {dur} min at VO2max · {note} · {rest} min easy between"},
        {"type": "cooldown",   "duration_min": 10,        "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_micro_bursts(lthr: int | None = None, ftp: int | None = None,
                      variation: int = 0) -> list[dict]:
    reps_per_set, sets = _MTB_MICRO_VAR[variation % 6]
    pw = _pwr_zone(1.05, 1.30, ftp)
    target = pw or "all-out aerobic effort"
    return [
        {"type": "warmup", "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min build to threshold"},
        {"type": "effort_set", "reps": sets,
         "duration_sec_each": reps_per_set * 45, "rest_sec": 180,
         "intensity": "micro_bursts",
         "note": (
            f"{sets}× ({reps_per_set}× 30 s on / 15 s off) · target {target} on the 'on' segments. "
            f"3 min easy spin between sets. (Rønnestad 30/15s protocol — VO2max & MTB surge specificity.)"
         )},
        {"type": "cooldown", "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_over_unders(lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    over_note  = _target_note(1.03, 1.05, lthr, 1.02, 1.08, ftp, "8/10")
    under_note = _target_note(0.93, 0.96, lthr, 0.88, 0.92, ftp, "6/10")
    return [
        {"type": "warmup",     "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min ramping to threshold"},
        {"type": "effort_set", "reps": 3, "duration_min_each": 8, "rest_min": 5,
         "intensity": "over_under",
         "note": (
            f"3× 8 min over-unders · alternate 30 s OVER ({over_note}) / 30 s UNDER ({under_note}). "
            f"5 min easy between sets."
         )},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_matchbook(lthr: int | None = None, ftp: int | None = None,
                    variation: int = 0) -> list[dict]:
    """
    'Matchbook' anaerobic sets — full-recovery W' reconstitution work (Skiba 2015).
    30–90 s all-out efforts with long rest. Builds the anaerobic capacity
    demanded by XCO surges, Enduro attacks, race starts.
    """
    reps, work_sec, rest_sec = _MTB_MATCH_VAR[variation % 6]
    pw = _pwr_zone(1.20, 1.50, ftp)
    target = pw or "all-out — Z6 anaerobic"
    return [
        {"type": "warmup", "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 5× 30 s spin-ups + 1× 2 min build to VO2"},
        {"type": "effort_set", "reps": reps, "duration_sec_each": work_sec, "rest_sec": rest_sec,
         "intensity": "anaerobic",
         "note": (
            f"{reps}× {work_sec} s all-out · target {target} · {rest_sec // 60}:{rest_sec % 60:02d} full recovery. "
            f"These are matchbook efforts — go to depletion, then refill."
         )},
        {"type": "cooldown", "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin until HR settles"},
    ]


def _mtb_standing_starts(lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    """
    Standing-start sprints — neuromuscular + race-start specificity.
    10× 10 s from 10 km/h, full recovery. Power-only target.
    """
    pw = _pwr_zone(1.50, 3.00, ftp)
    target = pw or "absolute maximum power · 9.5/10 RPE"
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 3× 20 s spin-ups + 2× 5 s seated sprints"},
        {"type": "effort_set", "reps": 10, "duration_sec_each": 10, "rest_sec": 110,
         "intensity": "neuromuscular",
         "note": (
            f"10× 10 s standing-start sprints · target {target}. "
            f"Roll at 10 km/h, attack hard, stand for first 5 s. ~2 min full recovery between."
         )},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_descent_repeats(duration_min: int, lthr: int | None = None) -> list[dict]:
    return [
        {"type": "warmup", "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin + 3× 30 s spin-ups + 1× 2 min sweet spot to open the legs"},
        {"type": "ride",   "duration_min": max(40, duration_min - 25),
         "intensity": "descent_repeats",
         "note": (
            "Pick a descent of 2–5 min and ride it 4–8 times. Race-pace on the way down — "
            "focus on line choice, brake control, body position. Climb back at Z2 transfer effort "
            "(stay below LT — these are not climb intervals)."
         )},
        {"type": "cooldown", "duration_min": 10, "intensity": "endurance", "note": "Easy spin"},
    ]


def _mtb_long(total_min: int, phase: str, lthr: int | None = None,
              ftp: int | None = None) -> list[dict]:
    """XCM-style long ride. Build/peak inserts sweet-spot blocks for race specificity."""
    z2_note = _target_note(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (conversational)")
    if phase in ("build", "peak") and total_min >= 120:
        ss_min   = min(45, int(total_min * 0.25))
        warmup   = int((total_min - ss_min) * 0.6)
        cooldown = total_min - ss_min - warmup
        ss_note  = _target_note(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10")
        return [
            {"type": "ride", "duration_min": warmup,   "intensity": "endurance",
             "note": f"Easy aerobic warm-up · {z2_note}. Fuel from minute 30."},
            {"type": "ride", "duration_min": ss_min,   "intensity": "sweet_spot",
             "note": f"Sweet-spot block · {ss_note}. Pick a sustained climb if possible."},
            {"type": "ride", "duration_min": cooldown, "intensity": "endurance",
             "note": f"Aerobic return to easy spin · {z2_note}"},
        ]
    return [{
        "type": "ride", "duration_min": total_min, "intensity": "endurance",
        "note": (
            f"Long aerobic ride · {z2_note}. Fuel 60 g/h carbs from minute 45. "
            f"Practice race-day equipment and nutrition strategy."
        ),
    }]


def _mtb_race_pace(lthr: int | None = None, ftp: int | None = None,
                    discipline: str = "xcm") -> list[dict]:
    if discipline == "xco":
        return [
            {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
             "note": "Easy spin + 2× 1 min build"},
            {"type": "effort_set", "reps": 4, "duration_min_each": 6, "rest_min": 3,
             "intensity": "race_pace",
             "note": f"4× 6 min at XCO race intensity · {_target_note(0.98, 1.04, lthr, 0.95, 1.05, ftp, '8/10')}"},
            {"type": "cooldown",   "duration_min": 10, "intensity": "endurance", "note": "Easy spin"},
        ]
    if discipline == "enduro":
        return _mtb_descent_repeats(75, lthr)
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance", "note": "Easy spin"},
        {"type": "effort_set", "reps": 3, "duration_min_each": 12, "rest_min": 5,
         "intensity": "race_pace",
         "note": f"3× 12 min at XCM race effort · {_target_note(0.92, 0.97, lthr, 0.85, 0.95, ftp, '7/10')}"},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance", "note": "Easy spin"},
    ]


def _mtb_skills(duration_min: int, discipline: str = "trail", variation: int = 0) -> list[dict]:
    # A stable seed. This was `hash(discipline)`, which Python randomises per
    # process, so the same plan picked different drills after every restart
    # and no other device could ever reproduce it.
    drills = _mtb_pick_skills(discipline, k=3, seed=variation + sum(map(ord, discipline)) % 1000)
    drill_notes = "; ".join(f"({label}) {desc}" for label, desc in drills)
    return [
        {"type": "warmup", "duration_min": 10, "intensity": "easy",
         "note": "Easy spin + body check (saddle height, tire pressure, drivetrain)"},
        {"type": "activity", "duration_min": max(20, duration_min - 15),
         "intensity": "skills",
         "note": f"Skills focus — {drill_notes}"},
        {"type": "cooldown", "duration_min": 5, "intensity": "easy", "note": "Easy spin"},
    ]


def _mtb_field_test(test_type: str, lthr: int | None = None) -> list[dict]:
    """
    Field-test workouts emitted when a goal opts into test scheduling.

    On completion, match_activity_to_workout calls _apply_field_test_result
    which auto-updates ftp_auto / threshold_hr_auto.

    FTP 20-min test: best 20-min rolling power × 0.95 (Coggan & Allen 2019).
    LTHR estimate: max HR during 20-min all-out test × 0.93 (Friel 2018).
    """
    if test_type == "ftp20":
        return [
            {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
             "note": "Easy spin + 3× 1 min ramping + 1× 5 min at threshold to open"},
            {"type": "ride",       "duration_min": 5,  "intensity": "easy",
             "note": "5 min easy spin between primer and test"},
            {"type": "ride",       "duration_min": 20, "intensity": "test",
             "note": "20 MIN ALL-OUT TIME TRIAL · pace evenly · FTP = 95% of average power."},
            {"type": "cooldown",   "duration_min": 15, "intensity": "endurance",
             "note": "Easy spin until HR settles"},
        ]
    if test_type == "pmax5":
        return [
            {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
             "note": "Easy spin + 3× 1 min ramping + 1× 2 min at threshold"},
            {"type": "effort_set", "reps": 2, "duration_min_each": 5, "rest_min": 10,
             "intensity": "test",
             "note": "2× 5 MIN ALL-OUT · pace evenly · best average ≈ MAP / 5-min power."},
            {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
             "note": "Easy spin"},
        ]
    if test_type == "rsa":
        return [
            {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
             "note": "Easy spin + 5× 30 s spin-ups + 2× 10 s sprints"},
            {"type": "effort_set", "reps": 10, "duration_sec_each": 6, "rest_sec": 30,
             "intensity": "test",
             "note": "10× 6 s ALL-OUT sprints · 30 s easy between · RSA test (power decrement)."},
            {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
             "note": "Easy spin"},
        ]
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 3× 30 s spin-ups + 1× 2 min at threshold"},
        {"type": "effort_set", "reps": 1, "duration_min_each": 1, "rest_min": 0,
         "intensity": "test",
         "note": "1 MIN ALL-OUT TIME TRIAL · estimates W' / anaerobic capacity."},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _mtb_recovery(duration_min: int) -> list[dict]:
    dur = max(20, min(45, duration_min))
    return [{
        "type": "ride", "duration_min": dur, "intensity": "recovery",
        "note": "Recovery spin · Z1 only · light gear, fast cadence, legs stay loose. Pick the smoothest trail available.",
    }]
