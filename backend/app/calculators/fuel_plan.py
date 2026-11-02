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

# Potassium follows the fluid drunk, at mid-range sweat potassium (~5 mmol/L,
# 0.2 mg per ml): no position stand sets an hourly dose, and sweat potassium
# varies far less between people than sodium does. docs/race-fuelling-research.md.
POTASSIUM_MG_PER_ML = 0.2

# The fuel gauges' full scale: what an elite ultra-endurance athlete takes per
# hour (docs/race-fuelling-research.md has the sources). Display only — no
# target is ever clamped to these.
CEILINGS = {"carbs_g_per_h": 120, "sodium_mg_per_h": 1500, "potassium_mg_per_h": 375, "fluid_ml_per_h": 1200}

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
            carbs=None, fluid=None, sodium=None, interval=None, potassium=None) -> dict:
    """Per-hour targets: the user's where set, the calculated default where not."""
    band = heat_band(temperature_c, humidity_pct)
    assert band in BANDS
    fluid_ml = int(fluid) if fluid is not None else FLUID_ML_PER_HOUR[band]
    return {
        "carbs_g_per_h": int(carbs) if carbs is not None else default_carbs_per_hour(duration_min / 60.0),
        "fluid_ml_per_h": fluid_ml,
        "sodium_mg_per_h": int(sodium) if sodium is not None else SODIUM_MG_PER_HOUR[band],
        # From the fluid in force, the user's own included.
        "potassium_mg_per_h": int(potassium) if potassium is not None else _half_up(fluid_ml * POTASSIUM_MG_PER_ML),
        "interval_min": int(interval) if interval else DEFAULT_INTERVAL_MIN,
        "band": band,
        "custom": {"carbs": carbs is not None, "fluid": fluid is not None,
                   "sodium": sodium is not None, "potassium": potassium is not None,
                   "interval": bool(interval)},
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


def timeline(duration_min: float, tg: dict, products: list[dict], laps: list[dict] | None = None,
             mix: dict | None = None) -> dict:
    """What to take when.

    A slot every `interval_min`, never at the start or the finish. At each
    slot the carbs owed so far (rate x elapsed - taken) decide: the product
    whose carbs are closest to what is owed is taken, provided at least half
    of it is owed — so a 25 g gel against a 30 g/h target is taken roughly
    every fifty minutes rather than every twenty. Ties go to the earlier
    product in the user's order. With no products, each slot names the grams
    to take instead.

    With the homemade mix (`mix`, from homemade_serving) the slots are its
    sips: every slot drinks one serving first, and the products then make up
    whatever carbs the drink leaves owed, by the same rule. The drink is
    capped at its heat band's strength, so on a hot day the gels carry more.
    """
    rate = float(tg["carbs_g_per_h"])
    interval = int(mix["interval_min"]) if mix else int(tg["interval_min"])
    usable = [p for p in products if float(p.get("carbs_g") or 0) > 0]
    items: list[dict] = []
    taken = 0.0
    totals = {"carbs_g": 0.0, "sodium_mg": 0, "potassium_mg": 0, "fluid_ml": 0, "caffeine_mg": 0}

    def take(minute: int, p: dict) -> None:
        nonlocal taken
        carbs = float(p["carbs_g"])
        items.append({
            "minute": minute, "distance_m": _distance_at(minute, laps),
            "product_uid": p.get("uid"), "name": p.get("name"),
            "carbs_g": carbs, "sodium_mg": int(p.get("sodium_mg") or 0),
            "potassium_mg": int(p.get("potassium_mg") or 0),
            "fluid_ml": int(p.get("fluid_ml") or 0),
            "caffeine_mg": int(p.get("caffeine_mg") or 0),
        })
        taken += carbs
        totals["carbs_g"] += carbs
        totals["sodium_mg"] += int(p.get("sodium_mg") or 0)
        totals["potassium_mg"] += int(p.get("potassium_mg") or 0)
        totals["fluid_ml"] += int(p.get("fluid_ml") or 0)
        totals["caffeine_mg"] += int(p.get("caffeine_mg") or 0)

    minute = interval
    while minute < duration_min:
        if mix:
            take(minute, mix)
        owed = rate * minute / 60.0 - taken
        if not usable:
            if not mix:
                grams = _half_up(rate * interval / 60.0)
                if grams > 0:
                    take(minute, {"uid": None, "name": None, "carbs_g": grams})
        else:
            best = None
            for p in usable:
                gap = abs(owed - float(p["carbs_g"]))
                if best is None or gap < best[0]:
                    best = (gap, p)
            p = best[1]
            if owed >= float(p["carbs_g"]) / 2.0:
                take(minute, p)
        minute += interval
    hours = duration_min / 60.0 if duration_min > 0 else 0.0
    per_hour = {k: (_half_up(v / hours) if hours else 0) for k, v in totals.items()}
    return {"items": items, "totals": {k: _half_up(v) for k, v in totals.items()},
            "per_hour": per_hour}


# ── The homemade mix ─────────────────────────────────────────────────────────
# A drink made from household ingredients, offered beside the user's own
# products as a built-in one (its uid is HOMEMADE_UID in fuel_product_uids).
# docs/race-fuelling-research.md has the reasoning and every source.

HOMEMADE_UID = "homemade"
HOMEMADE_NAME = "Homemade mix"
DEFAULT_BOTTLE_ML = 750
MIX_PRESETS = ("basic", "high_carb", "natural")

# Per gram (per ml for coconut water), and grams per teaspoon.
SUGAR = {"carbs_g": 1.0, "g_per_tsp": 4.2}
MALTODEXTRIN = {"carbs_g": 0.95, "g_per_tsp": 2.5}
HONEY = {"carbs_g": 0.82, "g_per_tsp": 7.0}
TABLE_SALT = {"sodium_mg": 393.0, "g_per_tsp": 6.0}
LITE_SALT = {"sodium_mg": 207.0, "potassium_mg": 250.0, "g_per_tsp": 5.6}
COCONUT_WATER = {"potassium_mg": 2.5, "sodium_mg": 1.05, "carbs_g": 0.037, "ml_per_cup": 240}


def _tenth(x: float) -> float:
    return math.floor(x * 10 + 0.5) / 10


def mix_preset(carbs_g_per_h: int, chosen: str | None) -> str:
    """The user's recipe, or the default for the carb rate: sugar alone is a
    fine 1:1 glucose:fructose below 60 g/h, where neither transporter is
    saturated; above it the high-carb recipe sets the ratio."""
    if chosen in MIX_PRESETS:
        return chosen
    return "basic" if carbs_g_per_h <= 60 else "high_carb"


def homemade_serving(tg: dict) -> dict:
    """One sip of the mix, as a product the timeline can take.

    Sipped at the heat band's interval (the user's own interval if they set
    one), each sip the fluid target's share of the hour, carrying that share
    of the sodium and potassium in full and carbs up to the band's strength
    or the carb target, whichever is less.
    """
    band = BANDS[tg["band"]]
    interval = int(tg["interval_min"]) if tg["custom"]["interval"] else int(band["sip_interval_min"])
    share = interval / 60.0
    fluid = _half_up(tg["fluid_ml_per_h"] * share)
    carbs = _tenth(min(fluid * band["concentration_pct"] / 100.0, tg["carbs_g_per_h"] * share))
    return {"uid": HOMEMADE_UID, "name": HOMEMADE_NAME, "carbs_g": carbs,
            "sodium_mg": _half_up(tg["sodium_mg_per_h"] * share),
            "potassium_mg": _half_up(tg["potassium_mg_per_h"] * share),
            "fluid_ml": fluid, "caffeine_mg": 0, "interval_min": interval,
            "concentration_pct": band["concentration_pct"]}


_FRACTIONS = {0: "", 1: "⅛", 2: "¼", 3: "⅜", 4: "½", 5: "⅝", 6: "¾", 7: "⅞"}


def _eighths(x: float) -> str:
    """1.375 -> "1⅜": whole and eighths, as spoons are marked."""
    n = _half_up(x * 8)
    whole, part = divmod(n, 8)
    return (str(whole) if whole else "") + _FRACTIONS[part] or "0"


def spoon_measure(grams: float, g_per_tsp: float, step_eighths: int) -> str:
    """Grams as spoons: teaspoons to the nearest `step_eighths`/8, and
    tablespoons (three teaspoons) once there are three or more. Salt is
    measured in eighths of a teaspoon, sugar in halves."""
    tsp = grams / g_per_tsp
    if tsp >= 3:
        tbsp = _half_up(tsp / 3 * 2) / 2
        return f"{_eighths(tbsp)} tbsp"
    step = step_eighths / 8
    q = _half_up(tsp / step) * step
    if q == 0:
        return "a pinch"
    return f"{_eighths(q)} tsp"


def _ingredient(key: str, name: str, grams: float, g_per_tsp: float, step_eighths: int, salt: bool) -> dict:
    g = _tenth(grams) if salt else float(_half_up(grams))
    return {"key": key, "name": name, "grams": g, "ml": None,
            "measure": spoon_measure(grams, g_per_tsp, step_eighths)}


def homemade_recipe(preset: str, bottle_ml: int, serving: dict, carbs_g_per_h: int, race_fluid_ml: int) -> dict:
    """One bottle of the mix, from household ingredients.

    Per bottle: the serving's carbs, sodium and potassium per ml, times the
    bottle. Potassium comes from lite salt (Basic, High-carb) or coconut
    water (Natural), and table salt makes up the sodium. Lite salt carries
    sodium too, so it is capped at the sodium budget; a potassium shortfall
    then shows on the gauge rather than being hidden. `race_fluid_ml` is the
    mix the whole race drinks, which sets how many bottles to make.
    """
    per_ml = 1.0 / serving["fluid_ml"] if serving["fluid_ml"] else 0.0
    carbs = serving["carbs_g"] * per_ml * bottle_ml
    sodium = serving["sodium_mg"] * per_ml * bottle_ml
    potassium = serving["potassium_mg"] * per_ml * bottle_ml
    out: list[dict] = []
    got = {"carbs_g": 0.0, "sodium_mg": 0.0, "potassium_mg": 0.0}
    if preset == "natural":
        cw = min(float(bottle_ml), potassium / COCONUT_WATER["potassium_mg"])
        cw = float(_half_up(cw / 10) * 10)
        got["potassium_mg"] += cw * COCONUT_WATER["potassium_mg"]
        got["sodium_mg"] += cw * COCONUT_WATER["sodium_mg"]
        got["carbs_g"] += cw * COCONUT_WATER["carbs_g"]
        honey = max(0.0, carbs - got["carbs_g"]) / HONEY["carbs_g"]
        salt = max(0.0, sodium - got["sodium_mg"]) / TABLE_SALT["sodium_mg"]
        if honey > 0:
            out.append(_ingredient("honey", "Honey", honey, HONEY["g_per_tsp"], 4, False))
            got["carbs_g"] += out[-1]["grams"] * HONEY["carbs_g"]
        if cw > 0:
            cups = cw / COCONUT_WATER["ml_per_cup"]
            out.append({"key": "coconut_water", "name": "Coconut water", "grams": None, "ml": cw,
                        "measure": f"{_eighths(_half_up(cups * 4) / 4) or '0'} cup"})
        if salt > 0:
            out.append(_ingredient("salt", "Table salt", salt, TABLE_SALT["g_per_tsp"], 1, True))
            got["sodium_mg"] += out[-1]["grams"] * TABLE_SALT["sodium_mg"]
    else:
        lite = min(potassium / LITE_SALT["potassium_mg"], sodium / LITE_SALT["sodium_mg"])
        salt = max(0.0, sodium - lite * LITE_SALT["sodium_mg"]) / TABLE_SALT["sodium_mg"]
        if preset == "high_carb":
            # Maltodextrin is glucose and sugar half glucose: 1 part to 2 gives
            # 2:1 glucose:fructose; 1 part to 8 gives 1:0.8, for above 90 g/h.
            share = 1 / 3 if carbs_g_per_h <= 90 else 1 / 9
            malto = carbs * share / MALTODEXTRIN["carbs_g"]
            sugar = carbs * (1 - share) / SUGAR["carbs_g"]
            out.append(_ingredient("maltodextrin", "Maltodextrin", malto, MALTODEXTRIN["g_per_tsp"], 4, False))
            got["carbs_g"] += out[-1]["grams"] * MALTODEXTRIN["carbs_g"]
        else:
            sugar = carbs / SUGAR["carbs_g"]
        if sugar > 0:
            out.append(_ingredient("sugar", "Table sugar", sugar, SUGAR["g_per_tsp"], 4, False))
            got["carbs_g"] += out[-1]["grams"] * SUGAR["carbs_g"]
        if salt > 0:
            out.append(_ingredient("salt", "Table salt", salt, TABLE_SALT["g_per_tsp"], 1, True))
            got["sodium_mg"] += out[-1]["grams"] * TABLE_SALT["sodium_mg"]
        if lite > 0:
            out.append(_ingredient("lite_salt", "Lite salt", lite, LITE_SALT["g_per_tsp"], 1, True))
            got["sodium_mg"] += out[-1]["grams"] * LITE_SALT["sodium_mg"]
            got["potassium_mg"] += out[-1]["grams"] * LITE_SALT["potassium_mg"]
    return {
        "preset": preset, "bottle_ml": bottle_ml,
        "bottles": math.ceil(race_fluid_ml / bottle_ml) if bottle_ml else 0,
        "concentration_pct": serving["concentration_pct"],
        "sip_ml": serving["fluid_ml"], "interval_min": serving["interval_min"],
        "ingredients": out,
        "per_bottle": {k: _half_up(v) for k, v in got.items()},
    }


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
