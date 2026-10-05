# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Road-cycling workout builders.

HR-anchored Friel cycling zones with Coggan power overlay when FTP is set.
All workouts emit intensity tags (`endurance`, `tempo`, `sweet_spot`,
`threshold`, `vo2`, `over_under`, `anaerobic`, `neuromuscular`, `race_pace`)
so the FIT encoder can resolve HR / power target ranges for Garmin coaching.

References
----------
- Friel, J. (2018). *The Cyclist's Training Bible* (5th ed.). VeloPress.
  LTHR zones, periodisation phases, cycling workout structures.
- Coggan, A., & Allen, H. (2019). *Training and Racing with a Power Meter*
  (3rd ed.). VeloPress. FTP-based power zones (Z1-Z7).
- Rønnestad, B. R., et al. (2015). Heavy strength training improves cycling
  performance in elite cyclists. *Scand J Med Sci Sports*, 25(1), e89-98.
  Half squats + leg press → improved 40-min TT power, no VO₂max change.
- Seiler, S. (2010). What is best practice for training intensity and duration
  distribution in endurance athletes? *Int J Sports Physiol Perform*, 5(3), 276-291.
  80/20 polarised: ~80% Z1-2, ~20% Z4-7; very little Z3 moderate.
- Carmichael, C. (2016). *The Time-Crunched Cyclist* (3rd ed.). VeloPress.
  Sweet-spot training for time-constrained amateur cyclists.
"""

from __future__ import annotations

from app.calculators.plan.base import _target_note

# ─────────────────────────────────────────
# Variation tables — 6 variants per type
# ─────────────────────────────────────────

_CY_SS_VAR: tuple[tuple[int, int], ...] = (
    (2, 20), (3, 15), (4, 12), (2, 25), (3, 20), (4, 10),
)

_CY_THR_VAR: tuple[tuple[int, int, int], ...] = (
    (2, 20, 5), (3, 15, 5), (4, 10, 3), (5,  8, 2), (3, 20, 4), (2, 25, 6),
)

_CY_VO2_VAR: tuple[tuple[int, int], ...] = (
    (5, 5), (8, 3), (4, 6), (6, 4), (5, 4), (7, 3),
)

_CY_ANAEROBIC_VAR: tuple[tuple[int, int, int], ...] = (
    (6,  180, 180), (10,  30,  30), (8,   20,  10), (8,   60, 240),
    (6,  120, 180), (12,  30,  30),
)

_CY_SPRINT_VAR: tuple[tuple[int, int, int], ...] = (
    (5,  10, 290), (6,  30, 270), (8,  15, 180), (4,  60, 360),
    (10, 10, 120), (6,  20, 240),
)

_CY_ENDURANCE_VAR: tuple[int, ...] = (0, +10, -10, +5, +15, -5)

_CY_MICRO_VAR: tuple[tuple[int, int], ...] = (
    (13, 3), (10, 4), (15, 3), (13, 2), (12, 3), (14, 3),
)


# ─────────────────────────────────────────
# Cycling workout builders
# ─────────────────────────────────────────

def _cy_endurance(duration_min: int, lthr: int | None = None, ftp: int | None = None,
                   variation: int = 0) -> list[dict]:
    """
    Z2 aerobic ride — foundation volume.

    San Millán (2018): low-Z2 (60-75% FTP) drives mitochondrial biogenesis
    and fat oxidation via PGC-1α pathway activation. Conversational pace.
    6 variation schemes cycle session duration to reduce monotony.
    """
    dur = max(45, duration_min + _CY_ENDURANCE_VAR[variation % 6])
    note = _target_note(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (conversational)")
    return [{
        "type": "ride", "duration_min": dur, "intensity": "endurance",
        "note": f"Z2 endurance · {note}. Smooth pedalling, hold a conversation.",
    }]


def _cy_easy_spin(duration_min: int, lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    """Active recovery spin — true Z1, used the day after hard sessions. Capped at 60 min."""
    dur = max(20, min(60, duration_min))
    note = _target_note(0.50, 0.81, lthr, 0.40, 0.55, ftp, "2/10 (legs only)")
    return [{
        "type": "ride", "duration_min": dur, "intensity": "recovery",
        "note": f"Recovery spin · {note}. Light gear, fast cadence, no surges.",
    }]


def _cy_tempo(duration_min: int, lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    """Z3 tempo block — sustainable but challenging."""
    warmup, cooldown = 15, 10
    tempo = max(20, duration_min - warmup - cooldown)
    note = _target_note(0.89, 0.93, lthr, 0.76, 0.88, ftp, "5/10 (moderate-hard)")
    return [
        {"type": "warmup",   "duration_min": warmup,   "intensity": "endurance",
         "note": "Easy spin + 2× 30 s spin-ups to open the legs"},
        {"type": "ride",     "duration_min": tempo,    "intensity": "tempo",
         "note": f"Z3 tempo · {note}. Sustainable; you can't sing."},
        {"type": "cooldown", "duration_min": cooldown, "intensity": "endurance",
         "note": "Easy spin back home"},
    ]


def _cy_sweet_spot(duration_min: int, lthr: int | None = None, ftp: int | None = None,
                    variation: int = 0) -> list[dict]:
    """
    Sweet-spot intervals — best fitness-to-fatigue ratio for amateur-
    time-constrained cyclists (Carmichael 2016). 6-variant ladder.
    """
    reps, dur = _CY_SS_VAR[variation % 6]
    rest = 5
    note = _target_note(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10 (comfortably hard)")
    return [
        {"type": "warmup",     "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min build to threshold"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": rest,
         "intensity": "sweet_spot",
         "note": f"{reps}× {dur} min sweet spot · {note} · {rest} min easy between"},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_threshold(build_idx: int, lthr: int | None = None, ftp: int | None = None,
                   variation: int = 0) -> list[dict]:
    """
    Lactate-threshold intervals — Friel's mainstay for road race + TT prep.
    6-variant ladder: 2-5 reps × 8-25 min.
    """
    reps, dur, rest = _CY_THR_VAR[variation % 6]
    if build_idx >= 4 and reps < 4:
        reps += 1
    note = _target_note(1.00, 1.02, lthr, 0.95, 1.00, ftp, "7/10 (1-hour race effort)")
    return [
        {"type": "warmup",     "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min ramping to threshold"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": rest,
         "intensity": "threshold",
         "note": f"{reps}× {dur} min at LT · {note} · {rest} min easy between"},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_vo2(build_idx: int, lthr: int | None = None, ftp: int | None = None,
             variation: int = 0) -> list[dict]:
    """
    VO₂max intervals — Coggan ladder with equal work-to-rest ratio.
    6 variants: 3-8 min reps.
    """
    reps, dur = _CY_VO2_VAR[variation % 6]
    if build_idx >= 5 and reps < 6:
        reps += 1
    note = _target_note(1.03, 1.06, lthr, 1.05, 1.20, ftp, "8/10 (VO2max, hard)")
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 4× 30 s spin-ups + 1× 2 min build to threshold"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": dur,
         "intensity": "vo2",
         "note": f"{reps}× {dur} min at VO2max · {note} · {dur} min easy between"},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_micro_bursts(lthr: int | None = None, ftp: int | None = None,
                      variation: int = 0) -> list[dict]:
    """
    Rønnestad 30/15 micro-bursts (Rønnestad et al. 2015).
    3-4 sets of 10-15 × (30 s @ 115% FTP / 15 s @ 50%), 3 min between sets.
    Superior VO₂max stimulus at lower RPE than traditional 5×5.
    """
    reps_per_set, sets = _CY_MICRO_VAR[variation % 6]
    from app.calculators.plan.base import _pwr_zone
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
            f"3 min easy spin between sets. (Rønnestad 30/15 — superior VO2max stimulus.)"
         )},
        {"type": "cooldown", "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_over_unders(lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    """
    Over/under intervals — alternate just above and below threshold.
    Builds lactate clearance and mirrors crit / road race surge patterns.
    """
    over_note  = _target_note(1.03, 1.05, lthr, 1.02, 1.08, ftp, "8/10")
    under_note = _target_note(0.93, 0.96, lthr, 0.88, 0.92, ftp, "6/10")
    return [
        {"type": "warmup",     "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min ramping to threshold"},
        {"type": "effort_set", "reps": 3, "duration_min_each": 10, "rest_min": 5,
         "intensity": "over_under",
         "note": (
            f"3× 10 min over-unders · alternate 2 min OVER ({over_note}) / 1 min UNDER ({under_note}). "
            f"5 min easy between sets."
         )},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_anaerobic(lthr: int | None = None, ftp: int | None = None,
                   variation: int = 0) -> list[dict]:
    """
    Anaerobic capacity work — crit-specific.
    6 variants: 6×3min @ Z6, 30/30, Tabata 8×(20/10), 8×60s, 6×2min, 12×30s.
    """
    reps, work_sec, rest_sec = _CY_ANAEROBIC_VAR[variation % 6]
    from app.calculators.plan.base import _pwr_zone
    pw = _pwr_zone(1.20, 1.50, ftp)
    target = pw or "Z6 anaerobic — all out"
    label = (
        "Tabata 20/10" if work_sec == 20 else
        f"30/30 × {reps}" if work_sec == 30 else
        f"{reps}× {work_sec}s"
    )
    return [
        {"type": "warmup", "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 5× 30 s spin-ups + 1× 2 min build to VO2"},
        {"type": "effort_set", "reps": reps, "duration_sec_each": work_sec, "rest_sec": rest_sec,
         "intensity": "anaerobic",
         "note": (
            f"{label} · target {target} on each rep · "
            f"{rest_sec // 60}:{rest_sec % 60:02d} between. Race-specific anaerobic capacity."
         )},
        {"type": "cooldown", "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin until HR settles"},
    ]


def _cy_sprint(lthr: int | None = None, ftp: int | None = None,
                variation: int = 0) -> list[dict]:
    """
    Sprint / neuromuscular power — full recovery between efforts.
    6 variants: 4-10 reps × 10-60s. Critical for criterium finishes.
    """
    reps, work_sec, rest_sec = _CY_SPRINT_VAR[variation % 6]
    from app.calculators.plan.base import _pwr_zone
    pw = _pwr_zone(1.50, 3.00, ftp)
    target = pw or "absolute maximum power · 9.5/10 RPE"
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 3× 20 s spin-ups + 2× 5 s seated sprints to prime"},
        {"type": "effort_set", "reps": reps, "duration_sec_each": work_sec, "rest_sec": rest_sec,
         "intensity": "neuromuscular",
         "note": (
            f"{reps}× {work_sec} s sprints · target {target}. "
            f"Full recovery ({rest_sec // 60}:{rest_sec % 60:02d}) — every rep should be maximal."
         )},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_sustained_climb(duration_min: int, lthr: int | None = None,
                         ftp: int | None = None) -> list[dict]:
    """Sustained climbing intervals — 2×15 or 3×10 at threshold on steady climbs."""
    reps, dur, rest = (2, 15, 5) if duration_min < 70 else (3, 10, 4)
    note = _target_note(0.97, 1.00, lthr, 0.93, 0.98, ftp, "7.5/10 (steady climbing)")
    return [
        {"type": "warmup",     "duration_min": 15, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min progressive build"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": rest,
         "intensity": "threshold",
         "note": (
            f"{reps}× {dur} min sustained climb · {note}. Seated, smooth cadence "
            f"70-85 rpm. Find a 5%+ climb or simulate on the trainer."
         )},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_tt_pace(duration_min: int, lthr: int | None = None,
                 ftp: int | None = None) -> list[dict]:
    """
    Time-trial-specific block — race-pace work in aero position.
    Single sustained effort or 2 long efforts; mirrors race demand.
    """
    reps, dur, rest = (1, 20, 0) if duration_min < 70 else (2, 20, 6)
    note = _target_note(1.00, 1.04, lthr, 0.95, 1.05, ftp, "8/10 (TT effort)")
    intro = "Single 20-min TT-pace effort" if reps == 1 else f"{reps}× {dur} min at TT pace"
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 3× 1 min build + 1× 5 min at threshold to open"},
        {"type": "effort_set", "reps": reps, "duration_min_each": dur, "rest_min": rest,
         "intensity": "race_pace",
         "note": (
            f"{intro} · {note}. Stay in aero position the entire effort — "
            f"position cost is part of the workout."
         )},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance",
         "note": "Easy spin"},
    ]


def _cy_long(duration_min: int, phase: str, lthr: int | None = None,
              ftp: int | None = None) -> list[dict]:
    """
    Long endurance ride. Phase-aware: build/peak inserts sweet-spot blocks
    for race specificity (mirrors how road races sit on top of a Z2 base).
    """
    z2_note = _target_note(0.81, 0.88, lthr, 0.65, 0.75, ftp, "3/10 (conversational)")
    if phase in ("build", "peak") and duration_min >= 120:
        ss_min   = min(45, int(duration_min * 0.25))
        warmup   = int((duration_min - ss_min) * 0.6)
        cooldown = duration_min - ss_min - warmup
        ss_note  = _target_note(0.94, 0.99, lthr, 0.88, 0.93, ftp, "6/10")
        return [
            {"type": "ride", "duration_min": warmup,   "intensity": "endurance",
             "note": f"Z2 warm-up · {z2_note}. Fuel from minute 30."},
            {"type": "ride", "duration_min": ss_min,   "intensity": "sweet_spot",
             "note": f"Sweet-spot block · {ss_note}. Pick a sustained climb if possible."},
            {"type": "ride", "duration_min": cooldown, "intensity": "endurance",
             "note": f"Z2 return · {z2_note}"},
        ]
    return [{
        "type": "ride", "duration_min": duration_min, "intensity": "endurance",
        "note": (
            f"Long Z2 ride · {z2_note}. Fuel 60 g/h carbs from minute 45. "
            f"Sustained ride > 2 h drives mitochondrial adaptation (San Millán 2018)."
        ),
    }]


def _cy_race_pace(lthr: int | None = None, ftp: int | None = None,
                   discipline: str = "road_race") -> list[dict]:
    """Race-pace block — discipline-specific."""
    if discipline == "criterium":
        return [
            {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
             "note": "Easy spin + 3× 1 min build"},
            {"type": "effort_set", "reps": 8, "duration_sec_each": 30, "rest_sec": 90,
             "intensity": "anaerobic",
             "note": f"8× 30 s race-pace surges · {_target_note(1.03, 1.06, lthr, 1.10, 1.30, ftp, '8.5/10')}"},
            {"type": "cooldown",   "duration_min": 10, "intensity": "endurance", "note": "Easy spin"},
        ]
    if discipline == "time_trial":
        return _cy_tt_pace(70, lthr, ftp)
    if discipline == "hill_climb":
        return _cy_sustained_climb(70, lthr, ftp)
    return [
        {"type": "warmup",     "duration_min": 20, "intensity": "endurance",
         "note": "Easy spin + 2× 1 min build"},
        {"type": "effort_set", "reps": 3, "duration_min_each": 12, "rest_min": 5,
         "intensity": "race_pace",
         "note": f"3× 12 min at road-race effort · {_target_note(0.96, 1.02, lthr, 0.90, 1.00, ftp, '7.5/10')}"},
        {"type": "cooldown",   "duration_min": 10, "intensity": "endurance", "note": "Easy spin"},
    ]


def _cy_short_quality(lthr: int | None = None, ftp: int | None = None) -> list[dict]:
    """Short race-day-style quality (taper opener)."""
    return _cy_sprint(lthr, ftp, variation=0)
