# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race fuelling: per-hour targets, a what-to-take-when timeline, and gut training.

Everything here is a pure function of synced rows (the race plan, the user's
fuel products, planned workouts, gut-training logs), and the phone runs a
Kotlin port of it (com.tracks.core.fuel) held to this file by
spec/fixtures/fuel_plan.json. So the rules are deliberately arithmetic a
reader can check by hand, with no clock and no randomness: the same rows must
give the same plan on every device, or a phone and the web would tell the
athlete to eat different things.

Rounding is always floor(x + 0.5) — half up — rather than Python's round(),
which rounds halves to even and would need reproducing on the phone for no
gain in meaning.
"""
from __future__ import annotations

import math

from app.spec.fueling import (
    BANDS, CARB_BREAKPOINTS, DEFAULT_CARBS_PER_HOUR,
    HEAT_THRESHOLD_C, HUMIDITY_MIN_TEMP_C, HUMIDITY_THRESHOLD_PCT, VERY_HOT_THRESHOLD_C,
)

# Fluid and sodium per hour by heat band. Mid-range of the usual sports-
# nutrition guidance (400-800 ml, 300-900 mg per hour), stepped up with heat;
# anyone who knows their sweat rate overrides them.
FLUID_ML_PER_HOUR = {"normal": 500, "hot": 650, "very_hot": 800}
SODIUM_MG_PER_HOUR = {"normal": 400, "hot": 600, "very_hot": 800}
DEFAULT_INTERVAL_MIN = 20

# Gut training. A long session is one at least this long, of a sport that is
# not strength or mobility — the only sessions where eating while moving is
# the point.
LONG_SESSION_MIN = 75
GUT_START_G_PER_H = 40
GUT_STEP_G_PER_H = 10
GUT_FLOOR_G_PER_H = 30
GUT_WINDOW_DAYS = 84          # the progression covers the last twelve weeks
GUT_REHEARSAL_DAYS = 14       # the final fortnight practises race-day fuelling
_NOT_ENDURANCE = ("strength", "flexib", "mobility", "yoga", "pilates")


def _half_up(x: float) -> int:
    return int(math.floor(x + 0.5))


def default_carbs_per_hour(duration_hours: float) -> int:
    for bp in CARB_BREAKPOINTS:
        if duration_hours <= bp["max_hours"]:
            return bp["carbs_per_hour"]
    return DEFAULT_CARBS_PER_HOUR


def heat_band(temperature_c: float | None, humidity_pct: float | None) -> str:
    """The same band spec/fueling.yaml's drink mixing uses (computeFuelingParams
    on the phone, fuelingUtils.js on the web): humid air counts even when the
    temperature is unknown."""
    hot = temperature_c is not None and temperature_c >= HEAT_THRESHOLD_C
    very_hot = temperature_c is not None and temperature_c >= VERY_HOT_THRESHOLD_C
    humid = (humidity_pct is not None and humidity_pct >= HUMIDITY_THRESHOLD_PCT
             and (temperature_c if temperature_c is not None else HUMIDITY_MIN_TEMP_C) >= HUMIDITY_MIN_TEMP_C)
    if very_hot:
        return "very_hot"
    if hot or humid:
        return "hot"
    return "normal"


def targets(duration_min: float, *, temperature_c=None, humidity_pct=None,
            carbs=None, fluid=None, sodium=None, interval=None) -> dict:
    """Per-hour targets: the user's where set, the calculated default where not."""
    band = heat_band(temperature_c, humidity_pct)
    assert band in BANDS
    return {
        "carbs_g_per_h": int(carbs) if carbs is not None else default_carbs_per_hour(duration_min / 60.0),
        "fluid_ml_per_h": int(fluid) if fluid is not None else FLUID_ML_PER_HOUR[band],
        "sodium_mg_per_h": int(sodium) if sodium is not None else SODIUM_MG_PER_HOUR[band],
        "interval_min": int(interval) if interval else DEFAULT_INTERVAL_MIN,
        "band": band,
        "custom": {"carbs": carbs is not None, "fluid": fluid is not None,
                   "sodium": sodium is not None, "interval": bool(interval)},
    }


def _distance_at(minute: float, laps: list[dict] | None) -> int | None:
    """Metres covered at `minute`, from the plan's per-lap target paces."""
    if not laps:
        return None
    t = 0.0
    d = 0.0
    target = minute * 60.0
    for lap in laps:
        dist = float(lap["distance_m"])
        dur = dist / 1000.0 * float(lap["target_sec_per_km"])
        if dur <= 0:
            continue
        if t + dur >= target:
            return _half_up(d + dist * (target - t) / dur)
        t += dur
        d += dist
    return _half_up(d)


def timeline(duration_min: float, tg: dict, products: list[dict], laps: list[dict] | None = None) -> dict:
    """What to take when.

    A slot every `interval_min`, never at the start or the finish. At each
    slot the carbs owed so far (rate x elapsed - taken) decide: the product
    whose carbs are closest to what is owed is taken, provided at least half
    of it is owed — so a 25 g gel against a 30 g/h target is taken roughly
    every fifty minutes rather than every twenty. Ties go to the earlier
    product in the user's order. With no products, each slot names the grams
    to take instead.
    """
    rate = float(tg["carbs_g_per_h"])
    interval = int(tg["interval_min"])
    usable = [p for p in products if float(p.get("carbs_g") or 0) > 0]
    items: list[dict] = []
    taken = 0.0
    totals = {"carbs_g": 0.0, "sodium_mg": 0, "fluid_ml": 0, "caffeine_mg": 0}
    minute = interval
    while minute < duration_min:
        owed = rate * minute / 60.0 - taken
        if not usable:
            grams = _half_up(rate * interval / 60.0)
            if grams > 0:
                items.append({"minute": minute, "distance_m": _distance_at(minute, laps),
                              "product_uid": None, "name": None, "carbs_g": grams,
                              "sodium_mg": 0, "fluid_ml": 0, "caffeine_mg": 0})
                taken += grams
                totals["carbs_g"] += grams
        else:
            best = None
            for p in usable:
                gap = abs(owed - float(p["carbs_g"]))
                if best is None or gap < best[0]:
                    best = (gap, p)
            p = best[1]
            carbs = float(p["carbs_g"])
            if owed >= carbs / 2.0:
                items.append({
                    "minute": minute, "distance_m": _distance_at(minute, laps),
                    "product_uid": p.get("uid"), "name": p.get("name"),
                    "carbs_g": carbs, "sodium_mg": int(p.get("sodium_mg") or 0),
                    "fluid_ml": int(p.get("fluid_ml") or 0),
                    "caffeine_mg": int(p.get("caffeine_mg") or 0),
                })
                taken += carbs
                totals["carbs_g"] += carbs
                totals["sodium_mg"] += int(p.get("sodium_mg") or 0)
                totals["fluid_ml"] += int(p.get("fluid_ml") or 0)
                totals["caffeine_mg"] += int(p.get("caffeine_mg") or 0)
        minute += interval
    hours = duration_min / 60.0 if duration_min > 0 else 0.0
    per_hour = {k: (_half_up(v / hours) if hours else 0) for k, v in totals.items()}
    return {"items": items, "totals": {k: _half_up(v) for k, v in totals.items()},
            "per_hour": per_hour}


def _is_long(w: dict) -> bool:
    sport = (w.get("sport") or "").lower()
    kind = (w.get("workout_type") or "").lower()
    if any(x in sport or x in kind for x in _NOT_ENDURANCE):
        return False
    return (w.get("duration_minutes") or 0) >= LONG_SESSION_MIN


def _days_between(a: str, b: str) -> int:
    from datetime import date
    return (date.fromisoformat(b) - date.fromisoformat(a)).days


def gut_training(race_carbs_g_per_h: int, race_date: str, workouts: list[dict], logs: list[dict]) -> dict:
    """Carbs-per-hour target for each long session before the race.

    Starts at 40 g/h and steps 10 g/h per long session toward the race
    target. How each session went moves the ramp: a logged comfort of 1-2
    steps back 10 g/h, 3 holds, 4-5 with at least 90% of the target actually
    taken steps up; an unlogged session is projected as a success, so the
    schedule ahead is visible before anything is logged. The final fortnight
    rehearses the race target itself. A hand-set target on a session is used
    as-is, and the ramp continues from it.

    Returns {planned_workout uid: g/h}. Sessions outside the twelve weeks
    before the race get none.
    """
    by_workout: dict[str, dict] = {}
    for log in sorted(logs, key=lambda l: (l.get("date") or "", l.get("uid") or "")):
        if log.get("planned_workout_uid"):
            by_workout[log["planned_workout_uid"]] = log      # latest log wins
    sessions = sorted(
        (w for w in workouts if _is_long(w) and w.get("scheduled_date")
         and 0 < _days_between(w["scheduled_date"], race_date) <= GUT_WINDOW_DAYS),
        key=lambda w: (w["scheduled_date"], w.get("uid") or ""),
    )
    out: dict[str, int] = {}
    level = GUT_START_G_PER_H
    race = int(race_carbs_g_per_h)
    for w in sessions:
        if w.get("fuel_carbs_per_hour") is not None:
            target = int(w["fuel_carbs_per_hour"])
        elif _days_between(w["scheduled_date"], race_date) <= GUT_REHEARSAL_DAYS:
            target = race
        else:
            target = min(level, race)
        out[w["uid"]] = target
        log = by_workout.get(w["uid"])
        if log is None:
            level = min(race, target + GUT_STEP_G_PER_H)
            continue
        comfort = int(log.get("comfort") or 3)
        minutes = log.get("duration_min") or w.get("duration_minutes") or 0
        taken_rate = float(log.get("carbs_g") or 0) * 60.0 / minutes if minutes else 0.0
        if comfort <= 2:
            level = max(GUT_FLOOR_G_PER_H, target - GUT_STEP_G_PER_H)
        elif comfort >= 4 and taken_rate >= 0.9 * target:
            level = min(race, target + GUT_STEP_G_PER_H)
        else:
            level = target
    return out
