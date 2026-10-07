# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race-plan support helpers.

Athlete-metric resolution per sport family (running fitness / FTP / CSS / HR), standard
triathlon leg splits, today's TSB (form) for the freshness adjustment, race-day
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


def _get_running_metrics(db: Session, user: User,
                         us: UserSettings | None) -> tuple[float | None, tuple | None]:
    """(VDOT, marathon training indices) for a running or triathlon goal.

    The VDOT is today's running-fitness estimate — the same one the training
    plan is built from (training_plan.helpers._get_running_fitness) — and only
    when it was measured: a race prediction from a profile guess would be a
    number with nothing behind it, so the plan still asks for runs first. The
    indices are the 8-week weekly distance and mean pace the marathon's volume
    correction reads (race_predictor.running.training_indices).
    """
    from app.calculators.race_predictor import training_indices
    from app.api.training_plan.helpers import _get_running_evidence, _get_running_fitness
    today = user_today(db, user.id)
    evidence = _get_running_evidence(db, user.id, today)
    fitness = _get_running_fitness(db, user.id, us, today, evidence)
    vdot = fitness["vdot"] if fitness["measured"] else None
    return vdot, training_indices(evidence[1], today)


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

    Keys: family, vdot (running VDOT), run_indices (8-week weekly km and mean
    pace, for the marathon), ftp (cycling watts), css (swim sec/100m), max_hr,
    lthr — each None when not applicable / unavailable.
    """
    from app.calculators.training_plan import _sport_family
    family = _sport_family((goal.event_sport or "running").lower())

    vdot, run_indices = (_get_running_metrics(db, user, us)
                         if family in ("running", "triathlon") else (None, None))
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

    return {"family": family, "vdot": vdot, "run_indices": run_indices, "ftp": ftp,
            "css": css, "max_hr": max_hr, "lthr": lthr}


def _get_tsb_today(db: Session, user: User) -> float | None:
    """Return today's TSB (form) to reflect current freshness."""
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    tss_by_date = _build_tss_by_date(db, user.id, us)
    if not tss_by_date:
        return None
    ctl, atl, _ = _ctl_atl_today(tss_by_date, user_today(db, user.id))
    return round(ctl - atl, 1)


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


def _fetch_weather(lat: float, lon: float, target_date: date) -> dict | None:
    """Fetch race-day weather from Open-Meteo (free, no key required).

    Strategy:
    - Within 15 days: use the forecast API (actual predicted conditions).
    - Beyond 15 days: average the archive API over the same calendar date
      for the previous 5 years (historical climate normals).

    Returns a dict with temperature_c, humidity_pct, wind_mps, wind_direction,
    source ("forecast" | "historical_avg"), and fetched_at.
    Returns None on any network/parse failure.
    """
    # The forecast service's own day, not the account's: this only decides
    # whether target_date is in its 16-day forecast range.
    today = date.today()
    days_out = (target_date - today).days

    try:
        if days_out <= _FORECAST_HORIZON_DAYS:
            url = (
                "https://api.open-meteo.com/v1/forecast"
                f"?latitude={lat:.2f}&longitude={lon:.2f}"  # ~1 km; see weather.fetch_point_forecast
                f"&hourly={_WEATHER_FIELDS}"
                f"&start_date={target_date}&end_date={target_date}"
                "&timezone=auto&wind_speed_unit=ms"
            )
            r = httpx.get(url, timeout=_WEATHER_TIMEOUT, follow_redirects=True)
            if r.is_success:
                vals = _parse_hourly_midday(r.json())
                if vals:
                    return {**vals, "source": "forecast",
                            "fetched_at": datetime.now(timezone.utc).isoformat()}

        # Historical average: same calendar date across the previous 5 years.
        import datetime as _dt
        samples: list[dict] = []
        for yr_offset in range(1, 6):
            try:
                hist_year = target_date.year - yr_offset
                # Clamp Feb 29 down to Feb 28 in non-leap years.
                hist_date = _dt.date(hist_year, target_date.month, min(target_date.day, 28
                    if target_date.month == 2 and not _dt.date(hist_year, 1, 1).year % 4 == 0
                    else target_date.day))
                # Archive only goes back to 1940 and up to yesterday.
                if hist_date >= today:
                    continue
                url = (
                    "https://archive-api.open-meteo.com/v1/archive"
                    f"?latitude={lat:.2f}&longitude={lon:.2f}"  # ~1 km; see weather.fetch_point_forecast
                    f"&hourly={_WEATHER_FIELDS}"
                    f"&start_date={hist_date}&end_date={hist_date}"
                    "&timezone=auto&wind_speed_unit=ms"
                )
                r = httpx.get(url, timeout=_WEATHER_TIMEOUT, follow_redirects=True)
                if r.is_success:
                    vals = _parse_hourly_midday(r.json())
                    if vals:
                        samples.append(vals)
            except Exception:
                continue

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


def _race_plan_out(rp: RacePlan, us: UserSettings | None = None,
                   goal: TrainingGoal | None = None) -> dict:
    """Serialize a RacePlan into the API response dict, enriching with the
    athlete's units/thresholds and the goal's discipline."""
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
        "weather_snapshot":  rp.weather_snapshot,
        "lap_paces":         rp.lap_paces,
        "watch_uploaded_at": rp.watch_uploaded_at,
        "generated_at":      rp.generated_at,
        "units":             units,
        "max_hr":            max_hr,
        # Multi-sport fields
        "sport":             family,
        "ftp":               int(ftp) if ftp else None,
        "css_sec_per_100m":  css,
        "swim_target_pace":  format_swim_pace(css, 1.0) if css and family in ("swimming", "triathlon") else None,
        "triathlon_legs":    rp.weather_snapshot.get("triathlon_legs") if rp.weather_snapshot else None,
        "triathlon_splits_m": rp.triathlon_splits_m,
        # MTB-specific (None for other sports)
        "target_hr_ceiling": rp.target_hr_ceiling,
        "fueling_plan":      rp.fueling_plan,
        "fuel_carbs_per_hour":     rp.fuel_carbs_per_hour,
        "fuel_fluid_ml_per_hour":  rp.fuel_fluid_ml_per_hour,
        "fuel_sodium_mg_per_hour": rp.fuel_sodium_mg_per_hour,
        "fuel_interval_min":       rp.fuel_interval_min,
        "fuel_product_uids":       rp.fuel_product_uids,
        "mtb_discipline":    getattr(goal, "mtb_discipline", None) if goal else None,
        "cycling_discipline": getattr(goal, "cycling_discipline", None) if goal else None,
    }
