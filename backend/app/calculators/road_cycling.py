# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Road-cycling specific helpers.

Drives discipline-aware periodization, race-plan strategy, and the indoor
TSS multiplier applied at activity import.

Science basis:
- Coggan & Allen power zones and FTP test protocols (Training and Racing
  with a Power Meter, 3rd ed., 2019).
- Friel periodisation (Cyclist's Training Bible, 5th ed., 2018).
- Carmichael Time-Crunched (3rd ed., 2017) for compressed-block builds.
- Indoor TSS adjustment: no coasting + thermal load typically yields IF
  5-15% higher indoors at equivalent perceived effort.
"""
from __future__ import annotations

import math
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from sqlalchemy.orm import Session


# ── Sport classification ─────────────────────────────────────────────────────

ROAD_CYCLING_SPORTS: frozenset[str] = frozenset({
    "cycling", "road_biking", "gravel_cycling",
    "virtual_cycling", "indoor_cycling", "e_biking",
})

# Indoor / smart-trainer rides — get the no-coasting TSS bump.
INDOOR_CYCLING_SPORTS: frozenset[str] = frozenset({
    "indoor_cycling", "virtual_cycling",
})


def _normalize(sport: str | None) -> str:
    return (sport or "").lower().replace(" ", "_")


def is_road_cycling(sport: str | None) -> bool:
    return _normalize(sport) in ROAD_CYCLING_SPORTS


def is_indoor_cycling(sport: str | None) -> bool:
    return _normalize(sport) in INDOOR_CYCLING_SPORTS


# ── Discipline taxonomy ──────────────────────────────────────────────────────

CYCLING_DISCIPLINES: tuple[str, ...] = ("road_race", "time_trial", "hill_climb", "criterium")
DEFAULT_DISCIPLINE: str = "road_race"


def infer_discipline_from_distance(distance_m: float | None) -> str:
    """Best-guess discipline from event distance. Used as a fallback only."""
    if not distance_m or distance_m <= 0:
        return DEFAULT_DISCIPLINE
    km = distance_m / 1000
    # <15 km → criterium / hill climb territory; we can't disambiguate
    # without more context, so default to road_race which is the safest
    # general-purpose plan.
    if km < 8:    return "criterium"
    if km < 25:   return "time_trial"
    return "road_race"   # everything 25 km+ defaults to road race / fondo


# ── Indoor TSS multiplier ────────────────────────────────────────────────────

# Indoor rides typically run +5-15% higher IF than outdoors at equivalent
# perceived effort because there's no coasting on descents, no air cooling,
# and ERG mode locks the power. 1.10× is the convention Allen-Coggan
# acknowledge in the 3rd ed.
_INDOOR_TSS_MULTIPLIER: float = 1.10


def indoor_tss_multiplier() -> float:
    return _INDOOR_TSS_MULTIPLIER


def apply_indoor_tss_multiplier(sport: str | None, tss: float | None) -> float | None:
    """Multiply TSS by 1.10× when the activity is an indoor cycling sport."""
    if tss is None or not is_indoor_cycling(sport):
        return tss
    return round(tss * _INDOOR_TSS_MULTIPLIER, 1)


# ── Active goal lookup ───────────────────────────────────────────────────────

def active_cycling_discipline(db: "Session", user_id: int) -> str | None:
    """
    Return the discipline of the user's active road-cycling event goal, or
    None if no such goal exists. Used at race-plan generation time.
    """
    from app.models.coaching import TrainingGoal   # local import: avoid cycle

    goal = (
        db.query(TrainingGoal)
        .filter(
            TrainingGoal.user_id == user_id,
            TrainingGoal.is_active.is_(True),
            TrainingGoal.goal_type == "event",
            TrainingGoal.event_sport.in_(list(ROAD_CYCLING_SPORTS)),
        )
        .order_by(TrainingGoal.event_date.asc().nulls_last())
        .first()
    )
    if goal is None:
        return None
    if getattr(goal, "cycling_discipline", None):
        return goal.cycling_discipline.lower()
    return infer_discipline_from_distance(goal.event_distance_meters)


# ── Race-plan helpers ────────────────────────────────────────────────────────

# Pugh 1971 / McCole 1990: drafting saves ~30% at race pace, ~22% behind a
# single rider, ~37% deep in a pack. We use 0.75 (25% saving) as a reasonable
# default for a mid-pack road race position. TT is solo (1.0). Hill climbs
# are typically solo at pace (1.0). Criteriums get heavier drafting (0.70)
# because riders stay in the pack except during attacks/sprints.
_DRAFTING_FACTORS: dict[str, float] = {
    "road_race":   0.75,
    "criterium":   0.70,
    "time_trial":  1.00,
    "hill_climb":  1.00,
}


def drafting_factor(discipline: str | None) -> float:
    """
    Multiplicative factor applied to predicted solo-power so the race-time
    prediction reflects pack-riding savings. Values < 1.0 mean the rider
    needs less power for the same speed.
    """
    return _DRAFTING_FACTORS.get(
        (discipline or DEFAULT_DISCIPLINE).lower(),
        _DRAFTING_FACTORS[DEFAULT_DISCIPLINE],
    )


# HR ceiling — race-day cap on average HR. Mirrors MTB structure.
# Sources: Friel 2018 for sub-LT pacing; gran-fondo data from Padilla 2000.
_DISCIPLINE_HR_CEILING_PCT: dict[str, float] = {
    "road_race":   0.92,   # pack-paced; surges OK above
    "criterium":   1.00,   # high anaerobic — LTHR cap is the realistic upper
    "time_trial":  1.02,   # 1-hour effort sits right at LTHR
    "hill_climb":  1.00,   # long climbs sit at LTHR
}


def race_hr_ceiling(discipline: str | None, lthr: int | None) -> int | None:
    """Suggested average-HR ceiling during the race."""
    if not lthr or lthr <= 0:
        return None
    d   = (discipline or DEFAULT_DISCIPLINE).lower()
    pct = _DISCIPLINE_HR_CEILING_PCT.get(d, _DISCIPLINE_HR_CEILING_PCT[DEFAULT_DISCIPLINE])
    return int(round(lthr * pct))


# Carb fueling target g/h by duration (Jeukendrup 2014, Sports Med).
def fueling_plan(predicted_seconds: float | None, discipline: str | None = None) -> dict:
    """
    Carbohydrate fueling guidance by predicted race duration.
    Glucose:fructose 2:1 to clear the 60 g/h single-transporter limit.
    """
    hours = (predicted_seconds or 0) / 3600
    if hours <= 1.0:
        return {"carbs_g_per_h": 30, "reminder_min": 30,
                "notes": "Short race — top up before the start; one mid-race feed is plenty."}
    if hours <= 2.0:
        return {"carbs_g_per_h": 60, "reminder_min": 25,
                "notes": "Aim for 60 g/h from minute 30 — one gel/chew every ~20–25 min."}
    if hours <= 4.0:
        return {"carbs_g_per_h": 80, "reminder_min": 20,
                "notes": "80 g/h mixed carbs (glucose + fructose 2:1). Practice in training."}
    return {"carbs_g_per_h": 90, "reminder_min": 20,
            "notes": "Ultra fueling: 90 g/h, mixed sources, include real food after hour 3."}


# ── Grade adjustment (same multiplicative model as MTB but tuned for road) ──

def road_grade_multiplier(grad: float) -> float:
    """
    Pace multiplier on a road bike for a given gradient (rise/run fraction).

    Road bikes climb more efficiently than MTBs (no rolling-resistance
    penalty from knobbies) but lose the technical-descent equation —
    descents save more time. Coefficients calibrated against the cycling
    physics model used in race_predictor._cycling_speed_at_power.

    +5% grade  → ~1.30× (steeper drop-off than MTB because climbing slows
                          a road bike to 8-12 km/h where wind resistance
                          becomes negligible and gravity dominates)
    +10% grade → ~1.75×
    -5% grade  → ~0.78× (free speed on tarmac, more time saved than MTB)
    -10% grade → ~0.65× (terminal velocity territory)
    """
    if grad >= 0:
        pct = grad * 100
        return 1.0 + 0.055 * pct + 0.0015 * pct * pct
    pct = -grad * 100
    # Asymptote at 0.55× (45% pace gain max) — tarmac descents are limited
    # by safety + cornering, not bike capability.
    return 1.0 - 0.45 * (1.0 - math.exp(-pct / 6.0))


# ── W/kg target (hill-climb specific) ────────────────────────────────────────

def hill_climb_target_w_per_kg(predicted_seconds: float, lthr: int | None = None) -> float:
    """
    Sustainable W/kg target for a hill climb of the given duration.

    Maps duration to Coggan's "Good" amateur category (Allen-Coggan 2019).
    Pro climbers exceed these by ~30%; world-class by ~50%. Use this as the
    *display target* — the actual race plan still uses the rider's measured
    FTP for the absolute watts target.

    Duration → W/kg ('Good' amateur category):
      3 min   → 5.0
      5 min   → 4.3
      20 min  → 3.7
      60 min  → 3.1
      4 h     → 2.4
    """
    minutes = max(1.0, (predicted_seconds or 0) / 60)
    if minutes <= 3:    return 5.0
    if minutes <= 5:    return 4.3
    if minutes <= 12:   return 4.0
    if minutes <= 20:   return 3.7
    if minutes <= 40:   return 3.4
    if minutes <= 60:   return 3.1
    if minutes <= 120:  return 2.8
    if minutes <= 240:  return 2.5
    return 2.2
