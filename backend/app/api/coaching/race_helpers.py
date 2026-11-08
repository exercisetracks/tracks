# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race-plan support helpers.

Athlete-metric resolution per sport family (running fitness / FTP / CSS / HR), standard
triathlon leg splits, today's and race morning's TSB (form) for the freshness adjustment, race-day
weather (Open-Meteo forecast or historical climate normals), and the RacePlan →
API dict serializer. Consumed by the race-plan endpoint module.
"""

from datetime import date, datetime, timezone

import httpx

from sqlalchemy.orm import Session

from app.calculators.local_day import user_today
from app.calculators.race_predictor import (
    course_totals,
    format_swim_pace,
    format_time,
)
from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.user_settings import UserSettings

from .helpers import _build_tss_by_date, _ctl_atl_today

# The race-intensity slider's ends: a fifth easier or harder than predicted.
# It was ±10 % until 2026-11, on the reasoning that the prediction is already
# the athlete's own fitness. Users asked for more: a prediction from little
# or old data can sit well off what someone can run, and a race run as a
# training day or a time trial is a different effort from the predicted one.
# Beyond a fifth the targets describe a different athlete. The phone's
# SportPlan.INTENSITY_RANGE.
RACE_INTENSITY_MIN = 0.8
RACE_INTENSITY_MAX = 1.2


def race_intensity(rp) -> float:
    """The plan's race intensity, 1.0 when unset, held to the slider's range
    (a synced value from an older or newer client is clamped, not trusted)."""
    k = getattr(rp, "intensity", None)
    return 1.0 if k is None else min(RACE_INTENSITY_MAX, max(RACE_INTENSITY_MIN, float(k)))


def _get_running_metrics(db: Session, user: User, us: UserSettings | None,
                         goal: TrainingGoal) -> dict:
    """Running fitness for a running or triathlon goal, today and on race day.

    ``vdot_today`` is today's running-fitness estimate — the same one the
    training plan is built from (training_plan.helpers._get_running_fitness)
    — and only when it was measured: a race prediction from a profile guess
    would be a number with nothing behind it, so the plan still asks for runs
    first. ``vdot`` is the race-day fitness every prediction reads: today's,
    plus the plan's remaining gain at the rate the last weeks delivered
    (``adherence``; calculators/plan/race_day.py). With no training plan for
    the goal there is no calendar to project, and race day is today.
    ``run_indices`` are the 8-week weekly distance and mean pace the
    marathon's volume correction reads (race_predictor.running.training_indices).
    """
    from app.calculators.plan.race_day import race_day_vdot
    from app.calculators.race_predictor import training_indices
    from app.api.training_plan.helpers import (
        _get_running_evidence, _get_running_fitness, _plan_adherence,
    )
    from app.models.training_plan import TrainingPlan
    today = user_today(db, user.id)
    evidence = _get_running_evidence(db, user.id, today)
    fitness = _get_running_fitness(db, user.id, us, today, evidence)
    vdot_today = fitness["vdot"] if fitness["measured"] else None
    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
    adherence = _plan_adherence(db, user.id, plan.id, today) if plan else None
    vdot = vdot_today
    if vdot_today is not None and adherence is not None and goal.event_date:
        vdot = race_day_vdot(vdot_today, today, goal.event_date, adherence)
    return {"vdot": vdot, "vdot_today": vdot_today, "adherence": adherence,
            "run_indices": training_indices(evidence[1], today)}


# Standard triathlon leg splits by total distance (metres)
_TRI_SPLITS: list[tuple[float, dict]] = [
    (12_950,  {"swim_m":   400, "bike_m":  10_000, "run_m":  2_500}),   # super sprint
    (25_750,  {"swim_m":   750, "bike_m":  20_000, "run_m":  5_000}),   # sprint
    (51_500,  {"swim_m": 1_500, "bike_m":  40_000, "run_m": 10_000}),   # olympic
    (113_000, {"swim_m": 1_900, "bike_m":  90_000, "run_m": 21_097}),   # 70.3
    (226_000, {"swim_m": 3_800, "bike_m": 180_000, "run_m": 42_195}),   # ironman
]


def _infer_triathlon_splits(total_m: float) -> dict:
    """Return per-leg distances for the closest standard triathlon distance."""
    return min(_TRI_SPLITS, key=lambda t: abs(t[0] - total_m))[1]


def _get_athlete_metrics(goal: TrainingGoal, db: Session, user: User,
                         us: UserSettings | None) -> dict:
    """Return sport-specific athlete performance metrics for race prediction.

    Keys: family, vdot (race-day running VDOT), vdot_today, adherence (see
    ``_get_running_metrics``), run_indices (8-week weekly km and mean pace,
    for the marathon), ftp (cycling watts), css (swim sec/100m), max_hr,
    lthr — each None when not applicable / unavailable.
    """
    from app.calculators.training_plan import _sport_family
    family = _sport_family((goal.event_sport or "running").lower())

    running = (_get_running_metrics(db, user, us, goal) if family in ("running", "triathlon")
               else {"vdot": None, "vdot_today": None, "adherence": None, "run_indices": None})
    ftp    = None
    css    = None
    max_hr = None
    lthr   = None

    if us:
        max_hr = us.max_hr_manual if us.max_hr_mode == "manual" else us.max_hr_auto
        lthr   = us.threshold_hr_manual if us.threshold_hr_mode == "manual" else us.threshold_hr_auto
        if family in ("cycling", "triathlon", "mountain_biking"):
            ftp = us.ftp_manual if us.ftp_mode == "manual" else us.ftp_auto
        if family in ("swimming", "triathlon"):
            css = us.css_manual if us.css_mode == "manual" else us.css_auto

    return {"family": family, **running, "ftp": ftp, "css": css, "max_hr": max_hr, "lthr": lthr}


def _race_form(db: Session, user: User, goal: TrainingGoal, metrics: dict) -> dict:
    """``{"today", "race_day"}`` TSB: today's form, and race morning's after the
    plan's remaining sessions (calculators/plan/race_day.race_day_tsb). With
    no plan to project through, race morning is read as today, as it always
    was. Either is None with no load history."""
    from app.api.training_plan.helpers import _planned_load
    from app.calculators.plan.base import vdot_to_paces
    from app.calculators.plan.race_day import race_day_tsb
    from app.models.training_plan import TrainingPlan

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    tss_by_date = _build_tss_by_date(db, user.id, us)
    today = user_today(db, user.id)
    tsb_today = None
    if tss_by_date:
        ctl, atl, _ = _ctl_atl_today(tss_by_date, today)
        tsb_today = round(ctl - atl, 1)
    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
    if plan is None or not goal.event_date or goal.event_date <= today:
        return {"today": tsb_today, "race_day": tsb_today}
    # The sessions' load at today's paces: the steps carry zones, and a
    # distance-set interval lasts as long as its zone's pace says.
    paces = vdot_to_paces(metrics["vdot_today"]) if metrics.get("vdot_today") else None
    planned = _planned_load(db, plan.id, today, goal.event_date, paces)
    return {"today": tsb_today,
            "race_day": race_day_tsb(tss_by_date, planned, today, goal.event_date)}


# ── Weather (Open-Meteo, free / keyless) ──────────────────────────────────────

_WEATHER_FIELDS = "temperature_2m,relative_humidity_2m,wind_speed_10m,wind_direction_10m"
_WEATHER_TIMEOUT = httpx.Timeout(connect=5.0, read=8.0, write=5.0, pool=5.0)
_FORECAST_HORIZON_DAYS = 15  # Open-Meteo forecast covers today + 16 days


def _parse_hourly_midday(data: dict) -> dict | None:
    """Extract hour-12 (midday) values from an Open-Meteo hourly response dict."""
    hourly = data.get("hourly", {})
    temps  = hourly.get("temperature_2m", [])
    hums   = hourly.get("relative_humidity_2m", [])
    winds  = hourly.get("wind_speed_10m", [])
    wdirs  = hourly.get("wind_direction_10m", [])
    if not temps:
        return None
    idx = min(12, len(temps) - 1)
    return {
        "temperature_c":  temps[idx],
        "humidity_pct":   hums[idx]  if idx < len(hums)  else None,
        "wind_mps":       winds[idx] if idx < len(winds) else None,
        "wind_direction": wdirs[idx] if idx < len(wdirs) else None,
    }


# Race-day weather is cached in Redis by place and day, shared by every plan
# that asks: a forecast for an hour (Open-Meteo updates hourly at best), a
# five-year average for a week (it is history; it does not change). Plans now
# regenerate every time their page opens, and the weather was most of a
# generation's time — five HTTPS round trips, one after another — so without
# this every visit would have waited seconds on the network.
_WEATHER_CACHE_PREFIX = "tracks:race_weather:"
_FORECAST_TTL = 3600
_HISTORICAL_TTL = 7 * 24 * 3600


def _fetch_weather(lat: float, lon: float, target_date: date) -> dict | None:
    """Race-day weather, from the shared cache when it has it. Returns a copy
    the caller may annotate. None on any network/parse failure (not cached,
    so the next generation tries again)."""
    import json

    import redis

    from app.services.redis_client import get_redis

    key = f"{_WEATHER_CACHE_PREFIX}{lat:.2f}:{lon:.2f}:{target_date.isoformat()}"
    try:
        cached = get_redis().get(key)
        if cached:
            return json.loads(cached)
    except (redis.RedisError, ValueError):
        pass
    snap = _fetch_weather_uncached(lat, lon, target_date)
    if snap:
        ttl = _FORECAST_TTL if snap.get("source") == "forecast" else _HISTORICAL_TTL
        try:
            get_redis().set(key, json.dumps(snap), ex=ttl)
        except redis.RedisError:
            pass
    return snap


def _fetch_weather_uncached(lat: float, lon: float, target_date: date) -> dict | None:
    """Fetch race-day weather from Open-Meteo (free, no key required).

    Strategy:
    - Within 15 days: use the forecast API (actual predicted conditions).
    - Beyond 15 days: average the archive API over the same calendar date
      for the previous 5 years (historical climate normals), the five
      fetched at once over one connection rather than one after another.

    Returns a dict with temperature_c, humidity_pct, wind_mps, wind_direction,
    source ("forecast" | "historical_avg"), and fetched_at.
    Returns None on any network/parse failure.
    """
    import datetime as _dt
    from concurrent.futures import ThreadPoolExecutor

    # The forecast service's own day, not the account's: this only decides
    # whether target_date is in its 16-day forecast range.
    today = date.today()
    days_out = (target_date - today).days

    def _url(base: str, day) -> str:
        return (
            f"{base}?latitude={lat:.2f}&longitude={lon:.2f}"  # ~1 km; see weather.fetch_point_forecast
            f"&hourly={_WEATHER_FIELDS}"
            f"&start_date={day}&end_date={day}"
            "&timezone=auto&wind_speed_unit=ms"
        )

    try:
        with httpx.Client(timeout=_WEATHER_TIMEOUT, follow_redirects=True) as http:
            if days_out <= _FORECAST_HORIZON_DAYS:
                r = http.get(_url("https://api.open-meteo.com/v1/forecast", target_date))
                if r.is_success:
                    vals = _parse_hourly_midday(r.json())
                    if vals:
                        return {**vals, "source": "forecast",
                                "fetched_at": datetime.now(timezone.utc).isoformat()}

            # Historical average: same calendar date across the previous 5 years.
            days = []
            for yr_offset in range(1, 6):
                hist_year = target_date.year - yr_offset
                # Clamp Feb 29 down to Feb 28 in non-leap years.
                hist_date = _dt.date(hist_year, target_date.month, min(target_date.day, 28
                    if target_date.month == 2 and not _dt.date(hist_year, 1, 1).year % 4 == 0
                    else target_date.day))
                # Archive only goes back to 1940 and up to yesterday.
                if hist_date < today:
                    days.append(hist_date)

            def _one(day):
                try:
                    r = http.get(_url("https://archive-api.open-meteo.com/v1/archive", day))
                    return _parse_hourly_midday(r.json()) if r.is_success else None
                except Exception:
                    return None

            with ThreadPoolExecutor(max_workers=max(1, len(days))) as pool:
                samples = [v for v in pool.map(_one, days) if v]

        if not samples:
            return None

        def _avg(key: str) -> float | None:
            vals = [s[key] for s in samples if s.get(key) is not None]
            return round(sum(vals) / len(vals), 1) if vals else None

        return {
            "temperature_c":  _avg("temperature_c"),
            "humidity_pct":   _avg("humidity_pct"),
            "wind_mps":       _avg("wind_mps"),
            "wind_direction": _avg("wind_direction"),
            "source":         "historical_avg",
            "years_sampled":  len(samples),
            "fetched_at":     datetime.now(timezone.utc).isoformat(),
        }
    except Exception:
        return None


def _triathlon_courses_out(rp: RacePlan, family: str, plan_distance: float | None) -> dict:
    """Each leg's course for the map and its graph, and the T1/T2 in force."""
    if family != "triathlon" or not (rp.course_segments or rp.tri_leg_courses):
        return {"triathlon_courses": None, "triathlon_transitions_m": None}
    from app.calculators.race_predictor.triathlon import leg_courses, total_m
    splits = rp.triathlon_splits_m or _infer_triathlon_splits(plan_distance or 51_500)
    got = leg_courses(rp.course_segments, rp.course_path, rp.tri_leg_courses, rp.tri_transitions_m, splits)
    return {
        "triathlon_courses": {
            leg: {"path": c["path"], "source": c["source"],
                  "distance_m": round(total_m(c["segments"]), 1) if c["segments"] else None}
            for leg, c in got["legs"].items()
        },
        "triathlon_transitions_m": got["transitions_m"],
    }


def _race_session_uploaded_at(db: Session, goal: TrainingGoal):
    """When the race reached the watch: its race-day calendar session's mark
    (the race rides the calendar — race_plan.py ``_retire_race_file``), or
    None when the goal's plan has no such session or it has not gone yet."""
    from app.models.training_plan import PlannedWorkout, TrainingPlan
    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
    if plan is None or not goal.event_date:
        return None
    session = (db.query(PlannedWorkout)
               .filter_by(plan_id=plan.id, workout_type="race", scheduled_date=goal.event_date)
               .first())
    return session.watch_uploaded_at if session is not None else None


def _race_plan_out(rp: RacePlan, us: UserSettings | None = None,
                   goal: TrainingGoal | None = None, db: Session | None = None) -> dict:
    """Serialize a RacePlan into the API response dict, enriching with the
    athlete's units/thresholds and the goal's discipline. With ``db``, the
    race's watch state is its calendar session's."""
    totals  = course_totals(rp.course_segments or [])
    units   = (us.units if us else None) or "metric"
    max_hr  = None
    ftp     = None
    css     = None
    if us:
        max_hr = us.max_hr_manual if us.max_hr_mode == "manual" else us.max_hr_auto
        ftp    = us.ftp_manual if us.ftp_mode == "manual" else us.ftp_auto
        css    = us.css_manual if us.css_mode == "manual" else us.css_auto

    from app.calculators.training_plan import _sport_family
    event_sport = (goal.event_sport if goal else None) or "running"
    family      = _sport_family(event_sport.lower())

    return {
        "id":                rp.id,
        "goal_id":           rp.goal_id,
        "split_spread":      rp.split_spread if rp.split_spread is not None else 0.0,
        "intensity":         rp.intensity if rp.intensity is not None else 1.0,
        "course_type":       rp.course_type,
        "pace_hr_mode":      rp.pace_hr_mode,
        "pin_lat":           rp.pin_lat,
        "pin_lon":           rp.pin_lon,
        "has_course":          bool(rp.course_segments),
        "use_gpx_distance":    bool(rp.use_gpx_distance),
        "course_distance_m":   totals["distance_m"] if rp.course_segments else None,
        "course_gain_m":       totals["elevation_gain_m"] if rp.course_segments else None,
        "technicality_factor": rp.technicality_factor,
        "technicality_label":  rp.technicality_label,
        "course_path":       rp.course_path,
        "predicted_seconds": rp.predicted_seconds,
        "predicted_time":    format_time(rp.predicted_seconds) if rp.predicted_seconds else None,
        # The blob carries the watch file's fingerprint and the race-day
        # projection too (race_plan.py); the page reads it as weather, so
        # those are taken out of it — the projection into a field of its own.
        "weather_snapshot":  ({k: v for k, v in rp.weather_snapshot.items()
                               if k not in ("fit_sig", "projection")} or None)
                             if rp.weather_snapshot else None,
        "projection":        (rp.weather_snapshot or {}).get("projection"),
        "lap_paces":         rp.lap_paces,
        "watch_uploaded_at": ((_race_session_uploaded_at(db, goal) if db is not None and goal is not None else None)
                              or rp.watch_uploaded_at),
        "generated_at":      rp.generated_at,
        "units":             units,
        "max_hr":            max_hr,
        # Multi-sport fields
        "sport":             family,
        "ftp":               int(ftp) if ftp else None,
        "css_sec_per_100m":  css,
        # The pace the plan asks for: CSS, quickened or eased by the race's
        # intensity (race_plan.sport_plan), as the swim's lap carries it.
        "swim_target_pace":  (format_swim_pace(css, 1.0 / race_intensity(rp))
                              if css and family in ("swimming", "triathlon") else None),
        "triathlon_legs":    rp.weather_snapshot.get("triathlon_legs") if rp.weather_snapshot else None,
        "triathlon_splits_m": rp.triathlon_splits_m,
        **_triathlon_courses_out(rp, family, plan_distance=goal.event_distance_meters if goal else None),
        "tri_transitions_m": rp.tri_transitions_m,
        # Cycling and MTB (None for other sports): the race's HR ceiling, and
        # a hill climb's W/kg with the watts it means at today's weight.
        "target_hr_ceiling": rp.target_hr_ceiling,
        "climb_target_w_per_kg": rp.climb_target_w_per_kg,
        "climb_target_watts": (int(round(rp.climb_target_w_per_kg * us.weight_kg))
                               if rp.climb_target_w_per_kg and us and us.weight_kg else None),
        "fuel_carbs_per_hour":     rp.fuel_carbs_per_hour,
        "fuel_fluid_ml_per_hour":  rp.fuel_fluid_ml_per_hour,
        "fuel_sodium_mg_per_hour": rp.fuel_sodium_mg_per_hour,
        "fuel_potassium_mg_per_hour": rp.fuel_potassium_mg_per_hour,
        "fuel_interval_min":       rp.fuel_interval_min,
        "fuel_product_uids":       rp.fuel_product_uids,
        "fuel_mix_preset":         rp.fuel_mix_preset,
        "fuel_mix_bottle_ml":      rp.fuel_mix_bottle_ml,
        "mtb_discipline":    getattr(goal, "mtb_discipline", None) if goal else None,
        "cycling_discipline": getattr(goal, "cycling_discipline", None) if goal else None,
    }
