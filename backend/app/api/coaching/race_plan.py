# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race-plan endpoints (all nested under /coaching/goals/{goal_id}).

  GET    .../predicted-time          quick finish-time badge (all sports)
  GET    .../race-plan               fetch/auto-create the plan config
  PATCH  .../race-plan               update config (split, course type, pin…)
  POST   .../race-plan/generate      run the pacing engine, persist + return
  POST   .../race-plan/course        upload a GPX course profile
  DELETE .../race-plan/course        remove the GPX profile
  GET    .../race-plan/fit           download the FIT race workout

The pacing engine (`_do_generate_race_plan`) is kept whole: per-sport-family
branches share the same freshness / weather / course-factor setup and reading
them side-by-side is clearer than slicing the algorithm across files.
"""

import base64
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.race_predictor import (
    compute_cycling_lap_targets,
    compute_cycling_wind_course_exposure,
    compute_lap_paces,
    compute_technicality_factor,
    compute_wind_course_exposure,
    course_totals,
    extract_path_points,
    format_swim_pace,
    format_time,
    generate_race_fit,
    parse_gpx,
    predict_cycling_time_sec,
    predict_race_time_sec,
    predict_running_race_sec,
    predict_swim_time_sec,
    weather_slowdown_factor,
)
from app.database import get_db
from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.user_settings import UserSettings
from app.schemas.coaching import PredictedTimeOut, RacePlanUpdate

from .helpers import _get_user_and_settings
from .race_helpers import (
    _fetch_weather,
    _get_athlete_metrics,
    _get_tsb_today,
    _infer_triathlon_splits,
    _race_plan_out,
)

router = APIRouter()


def _goal_or_404(db: Session, goal_id: int, user: User) -> TrainingGoal:
    goal = db.query(TrainingGoal).filter_by(id=goal_id, user_id=user.id).first()
    if goal is None:
        raise HTTPException(status_code=404, detail="Goal not found")
    return goal


def _get_or_create_plan(db: Session, goal_id: int, user_id: int, *, flush: bool) -> RacePlan:
    """Fetch the goal's RacePlan, creating an empty one if absent.

    `flush` (vs commit) is used by callers that will mutate the plan further
    before committing themselves.
    """
    rp = db.query(RacePlan).filter_by(goal_id=goal_id).first()
    if rp is None:
        rp = RacePlan(goal_id=goal_id, user_id=user_id)
        db.add(rp)
        if flush:
            db.flush()
        else:
            db.commit()
            db.refresh(rp)
    return rp


# ── Predicted finish time (goal card badge) ──────────────────────────────────

@router.get("/goals/{goal_id}/predicted-time", response_model=PredictedTimeOut)
def get_predicted_time(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Quick predicted finish time for the goal card badge — all sports."""
    user, us = _get_user_and_settings(db, user)
    goal = _goal_or_404(db, goal_id, user)
    if goal.goal_type != "event" or not goal.event_distance_meters:
        return PredictedTimeOut()

    metrics = _get_athlete_metrics(goal, db, user, us)
    family  = metrics["family"]
    dist    = goal.event_distance_meters
    sec     = None

    if family == "running" and metrics["vdot"]:
        sec = predict_running_race_sec(metrics["vdot"], dist, metrics["run_indices"])

    elif family == "cycling" and metrics["ftp"]:
        sec = predict_cycling_time_sec(metrics["ftp"], dist)

    elif family == "mountain_biking":
        # Use cycling physics when FTP exists, then apply an MTB terrain factor.
        # Without FTP, fall back to a discipline-typical average speed.
        disc = (goal.mtb_discipline or "trail").lower()
        if metrics["ftp"]:
            road_sec = predict_cycling_time_sec(metrics["ftp"], dist)
            mtb_factor = {"xco": 1.10, "xcm": 1.15, "enduro": 1.25, "trail": 1.15}.get(disc, 1.15)
            sec = road_sec * mtb_factor
        else:
            est_speed_kmh = {"xco": 22.0, "xcm": 18.0, "enduro": 12.0, "trail": 16.0}.get(disc, 16.0)
            sec = dist / (est_speed_kmh * 1000 / 3600)

    elif family == "swimming" and metrics["css"]:
        sec = predict_swim_time_sec(metrics["css"], dist, open_water=False)

    elif family == "triathlon":
        splits = _infer_triathlon_splits(dist)
        sec = 0.0
        if metrics["css"]:
            sec += predict_swim_time_sec(metrics["css"], splits["swim_m"], open_water=True)
        if metrics["ftp"]:
            sec += predict_cycling_time_sec(metrics["ftp"], splits["bike_m"])
        if metrics["vdot"]:
            sec += predict_race_time_sec(metrics["vdot"], splits["run_m"])
        sec += 5 * 60 + 3 * 60   # T1 5 min + T2 3 min transitions
        if sec == 0.0:
            sec = None

    if sec is None:
        return PredictedTimeOut(vdot=metrics.get("vdot"))

    rp = db.query(RacePlan).filter_by(goal_id=goal_id).first()
    return PredictedTimeOut(
        predicted_seconds=round(sec, 1),
        predicted_time=format_time(sec),
        vdot=round(metrics["vdot"], 1) if metrics["vdot"] else None,
        has_race_plan=rp is not None,
    )


# ── Plan config get / update ─────────────────────────────────────────────────

@router.get("/goals/{goal_id}/race-plan")
def get_race_plan(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Return the saved race plan configuration and generated lap paces."""
    user, us = _get_user_and_settings(db, user)
    goal = _goal_or_404(db, goal_id, user)
    rp = _get_or_create_plan(db, goal_id, user.id, flush=False)
    return _race_plan_out(rp, us, goal)


@router.patch("/goals/{goal_id}/race-plan")
def update_race_plan(goal_id: int, body: RacePlanUpdate, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Update race plan configuration (split type, course type, pin, etc.)."""
    user, us = _get_user_and_settings(db, user)
    goal = _goal_or_404(db, goal_id, user)
    rp = _get_or_create_plan(db, goal_id, user.id, flush=True)
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(rp, field, value)
    db.commit()
    db.refresh(rp)
    return _race_plan_out(rp, us, goal)


# ── Generate (the pacing engine) ─────────────────────────────────────────────

@router.post("/goals/{goal_id}/race-plan/generate")
def generate_race_plan(goal_id: int, user: User = Depends(require_auth),
                        db: Session = Depends(get_db)):
    """Fetch weather (if pinned), compute predicted time, and generate lap
    paces. Saves the result and returns the updated plan."""
    try:
        return _do_generate_race_plan(goal_id, db, user)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


def _do_generate_race_plan(goal_id: int, db: Session, user: User):
    _, us = _get_user_and_settings(db, user)
    goal = _goal_or_404(db, goal_id, user)
    if goal.goal_type != "event" or not goal.event_distance_meters:
        raise HTTPException(status_code=422, detail="Goal must be an event goal with a distance")

    rp = _get_or_create_plan(db, goal_id, user.id, flush=True)

    metrics = _get_athlete_metrics(goal, db, user, us)
    family  = metrics["family"]

    # ── Decide distance ──────────────────────────────────────────────────────
    if rp.use_gpx_distance and rp.course_segments:
        plan_distance_m = course_totals(rp.course_segments)["distance_m"]
    else:
        plan_distance_m = goal.event_distance_meters

    if not plan_distance_m:
        raise HTTPException(status_code=422, detail="Goal must have a distance set")

    # ── Freshness adjustment (applies to all sports equally) ─────────────────
    tsb = _get_tsb_today(db, user)
    freshness_factor = 1.0
    if tsb is not None:
        freshness_factor = 1.0 - max(-0.03, min(0.03, tsb * 0.001))

    # ── Weather (15-minute cache) ─────────────────────────────────────────────
    now       = datetime.now(timezone.utc)
    cache_age = (now - rp.generated_at).total_seconds() / 60 if rp.generated_at else 999
    lat       = rp.pin_lat
    lon       = rp.pin_lon

    def _weather_factor_from_snap(snap: dict) -> float:
        if "heat_penalty_pct" in snap and "wind_penalty_pct" in snap:
            return (1.0 + snap["heat_penalty_pct"] / 100) * (1.0 + snap["wind_penalty_pct"] / 100)
        return weather_slowdown_factor(
            temp_c         = snap.get("temperature_c") or 15.0,
            humidity_pct   = snap.get("humidity_pct")  or 50.0,
            wind_mps       = snap.get("wind_mps")       or 0.0,
            wind_angle_deg = snap.get("wind_direction") or 0.0,
        )

    def _annotate_weather(snap: dict, base_seconds: float, is_cycling: bool = False) -> tuple[float, float]:
        """Decompose snap into heat and wind penalties; annotate snap in-place.

        Returns (total_factor, net_wind_mps_headwind). Cycling uses the
        aerodynamic v² wind model; running uses the Pugh linear model.
        """
        temp_c       = snap.get("temperature_c") or 15.0
        humidity_pct = snap.get("humidity_pct")  or 50.0
        wind_mps     = snap.get("wind_mps")       or 0.0
        wind_dir     = snap.get("wind_direction") or 0.0

        # Heat penalty (same for all sports — metabolic stress model).
        wbt          = temp_c - (1 - humidity_pct / 100) * 5.0
        effective    = max(wbt, temp_c)
        heat_penalty = max(0.0, (effective - 10.0) * 0.003)
        heat_factor  = 1.0 + heat_penalty

        net_wind_mps = 0.0
        if rp.course_path and len(rp.course_path) >= 2 and wind_mps > 0:
            if is_cycling:
                rider_speed = plan_distance_m / max(base_seconds, 1) if base_seconds > 0 else 10.0
                wind_exp = compute_cycling_wind_course_exposure(
                    rp.course_path, wind_dir, wind_mps, rider_speed_mps=rider_speed
                )
            else:
                wind_exp = compute_wind_course_exposure(rp.course_path, wind_dir, wind_mps)
            wind_factor              = wind_exp["wind_factor"]
            snap["wind_course_note"] = wind_exp["course_note"]
            snap["net_headwind_mps"] = wind_exp["net_headwind_mps"]
            net_wind_mps             = wind_exp["net_headwind_mps"]
        else:
            loop_penalty = wind_mps * (0.006 if is_cycling else 0.004) * 0.3
            wind_factor  = 1.0 + loop_penalty
            snap.pop("wind_course_note", None)
            snap.pop("net_headwind_mps", None)

        total_factor = heat_factor * wind_factor
        snap["heat_penalty_pct"] = round(heat_penalty * 100, 1)
        snap["wind_penalty_pct"] = round((wind_factor - 1.0) * 100, 1)
        snap["heat_added_sec"]   = round((heat_factor - 1.0) * base_seconds)
        snap["wind_added_sec"]   = round((wind_factor - 1.0) * base_seconds)
        return total_factor, net_wind_mps

    if us is not None and not us.weather_enabled:
        weather_snap   = None
        weather_factor = 1.0
        net_wind_mps   = 0.0
    elif rp.weather_snapshot and cache_age < 15 and lat is not None:
        weather_snap   = rp.weather_snapshot
        weather_factor = _weather_factor_from_snap(weather_snap)
        net_wind_mps   = weather_snap.get("net_headwind_mps", 0.0)
    elif lat is not None and lon is not None and goal.event_date:
        weather_snap = _fetch_weather(lat, lon, goal.event_date)
        if weather_snap:
            rough_base_sec  = plan_distance_m / 3.5 if family == "running" else plan_distance_m / 10.0
            weather_factor, net_wind_mps = _annotate_weather(
                weather_snap, rough_base_sec, is_cycling=(family == "cycling")
            )
        else:
            weather_factor = 1.0
            net_wind_mps   = 0.0
    else:
        weather_snap   = None
        weather_factor = 1.0
        net_wind_mps   = 0.0

    # ── Course factors (terrain type — no GPX) ───────────────────────────────
    course_factors = {"flat": 1.0, "rolling": 1.015, "hilly": 1.04, "mountainous": 1.08}
    # Cycling is less affected by terrain labels (grade is already in physics).
    cy_course_factors = {"flat": 1.0, "rolling": 1.008, "hilly": 1.02, "mountainous": 1.04}

    imperial     = (us and us.units == "imperial")
    lap_km       = 1.60934 if imperial else 1.0
    split_spread = rp.split_spread if rp.split_spread is not None else 0.0
    max_hr_val   = metrics["max_hr"]

    # ══════════════════════════════════════════════════════════════════════════
    # RUNNING
    # ══════════════════════════════════════════════════════════════════════════
    if family == "running":
        vdot = metrics["vdot"]
        if vdot is None:
            raise HTTPException(
                status_code=422,
                detail="No running fitness measured yet — record a few runs first",
            )
        adjusted_vdot = vdot * (2.0 - freshness_factor)
        base_sec      = predict_running_race_sec(adjusted_vdot, plan_distance_m,
                                                 metrics["run_indices"], freshness_factor)

        if weather_snap and "heat_penalty_pct" not in weather_snap:
            weather_factor, net_wind_mps = _annotate_weather(weather_snap, base_sec)

        flat_sec = base_sec * weather_factor
        if rp.course_segments:
            tech_factor, tech_label = compute_technicality_factor(rp.course_segments)
            rp.technicality_factor  = tech_factor
            rp.technicality_label   = tech_label
            flat_sec *= tech_factor
        else:
            flat_sec *= course_factors.get(rp.course_type, 1.0)

        laps, actual_sec = compute_lap_paces(
            predicted_sec   = flat_sec,
            distance_m      = plan_distance_m,
            split_spread    = split_spread,
            lap_km          = lap_km,
            course_segments = rp.course_segments,
            max_hr          = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
        )

    # ══════════════════════════════════════════════════════════════════════════
    # MOUNTAIN BIKING
    # ══════════════════════════════════════════════════════════════════════════
    elif family == "mountain_biking":
        from app.calculators.mtb import (
            race_hr_ceiling,
            fueling_plan as mtb_fueling_plan,
            mtb_hr_only_laps as _mtb_hr_only_laps,
            mtb_grade_multiplier,
        )

        ftp        = metrics["ftp"]
        lthr       = metrics["lthr"]
        discipline = (goal.mtb_discipline or "trail").lower()

        # Time prediction: prefer cycling physics when FTP is available;
        # otherwise estimate from typical MTB speed by discipline.
        if ftp:
            ftp_adjusted = ftp * (2.0 - freshness_factor)
            base_sec = predict_cycling_time_sec(
                ftp_adjusted, plan_distance_m, rp.course_segments or None,
            )
        else:
            # XCO ~22 km/h, XCM ~18 km/h, Enduro ~12 km/h transfers, Trail ~16.
            est_speed_kmh = {"xco": 22.0, "xcm": 18.0, "enduro": 12.0, "trail": 16.0}.get(discipline, 16.0)
            base_sec = plan_distance_m / (est_speed_kmh * 1000 / 3600)
            ftp_adjusted = None

        if weather_snap and "heat_penalty_pct" not in weather_snap:
            weather_factor, net_wind_mps = _annotate_weather(
                weather_snap, base_sec, is_cycling=True,
            )

        # MTB terrain factor: ~1.5× the road factor (climbs + technical descents
        # both cost more than equivalent road kilometers).
        mtb_course_factors = {"flat": 1.0, "rolling": 1.04, "hilly": 1.10, "mountainous": 1.20}
        if not rp.course_segments:
            base_sec *= mtb_course_factors.get(rp.course_type, 1.10)
        else:
            tech_factor, tech_label = compute_technicality_factor(rp.course_segments)
            rp.technicality_factor  = tech_factor
            rp.technicality_label   = tech_label

        heat_factor = 1.0 + (weather_snap.get("heat_penalty_pct", 0) / 100 if weather_snap else 0)
        adj_sec     = base_sec * heat_factor

        # Lap pacing: when FTP exists reuse cycling lap targets; otherwise emit
        # per-km HR-only laps so the watch still gets coaching at every split.
        if ftp_adjusted:
            laps, actual_sec = compute_cycling_lap_targets(
                ftp             = ftp_adjusted,
                distance_m      = plan_distance_m,
                split_spread    = split_spread,
                lap_km          = lap_km,
                course_segments = rp.course_segments,
                max_hr          = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
                predicted_sec   = adj_sec,
                wind_mps        = max(0.0, net_wind_mps),
            )
            # cycling lap targets hard-code grade_multiplier=1.0 — overwrite with
            # the MTB grade model so the frontend's recomputeLaps preserves
            # per-lap pace variation (otherwise every lap shows identical pace).
            hr_ceiling = race_hr_ceiling(discipline, int(lthr) if lthr else None)
            for lap in laps:
                if hr_ceiling:
                    lap["hr_ceiling"] = hr_ceiling
                lap["grade_multiplier"] = round(
                    mtb_grade_multiplier(lap.get("gradient", 0.0) or 0.0), 4,
                )
        else:
            laps, actual_sec = _mtb_hr_only_laps(
                distance_m   = plan_distance_m,
                total_sec    = adj_sec,
                lap_km       = lap_km,
                split_spread = split_spread,
                discipline   = discipline,
                lthr         = lthr,
                course_segments = rp.course_segments,
                course_type     = rp.course_type,
            )

        # Race-plan extras: HR ceiling field + fueling plan.
        rp.target_hr_ceiling = race_hr_ceiling(discipline, int(lthr) if lthr else None)
        rp.fueling_plan      = mtb_fueling_plan(actual_sec, discipline)

    # ══════════════════════════════════════════════════════════════════════════
    # CYCLING
    # ══════════════════════════════════════════════════════════════════════════
    elif family == "cycling":
        from app.calculators.road_cycling import (
            drafting_factor as cy_drafting_factor,
            race_hr_ceiling as cy_race_hr_ceiling,
            fueling_plan as cy_fueling_plan,
            road_grade_multiplier,
            hill_climb_target_w_per_kg,
        )

        ftp = metrics["ftp"]
        if ftp is None:
            raise HTTPException(
                status_code=422,
                detail="No FTP data available — complete some cycling efforts or set FTP manually in settings",
            )
        cycling_discipline = (goal.cycling_discipline or "road_race").lower()
        lthr = metrics["lthr"]

        # Drafting savings: solo (TT, hill climb) = 1.0 ; road race = 0.75 ;
        # crit = 0.70. Treated as a *power multiplier* — the rider effectively
        # produces watts_effective = ftp / drafting_factor for the same speed,
        # so the physics model uses the inflated power for the finish time.
        df = cy_drafting_factor(cycling_discipline)
        ftp_effective = (ftp / df) * (2.0 - freshness_factor)

        base_sec = predict_cycling_time_sec(ftp_effective, plan_distance_m,
                                             rp.course_segments or None)
        if weather_snap and "heat_penalty_pct" not in weather_snap:
            weather_factor, net_wind_mps = _annotate_weather(
                weather_snap, base_sec, is_cycling=True
            )

        # Course factor for no-GPX plans.
        if not rp.course_segments:
            base_sec *= cy_course_factors.get(rp.course_type, 1.0)
        else:
            tech_factor, tech_label = compute_technicality_factor(rp.course_segments)
            rp.technicality_factor  = tech_factor
            rp.technicality_label   = tech_label

        # Heat adjusts time directly; wind is handled inside the physics model
        # via net_wind_mps.
        heat_factor = 1.0 + (weather_snap.get("heat_penalty_pct", 0) / 100 if weather_snap else 0)
        adj_sec     = base_sec * heat_factor

        laps, actual_sec = compute_cycling_lap_targets(
            ftp            = ftp_effective,
            distance_m     = plan_distance_m,
            split_spread   = split_spread,
            lap_km         = lap_km,
            course_segments = rp.course_segments,
            max_hr         = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
            predicted_sec  = adj_sec,
            wind_mps       = max(0.0, net_wind_mps),   # headwind component for aero model
        )

        # Stamp each lap with the road grade multiplier (so the frontend's
        # recomputeLaps reproduces per-lap variation) and the HR ceiling, and
        # scale target_watts back down to the rider's actual output (divide out
        # the drafting inflation added earlier).
        hr_ceiling = cy_race_hr_ceiling(cycling_discipline, int(lthr) if lthr else None)
        for lap in laps:
            if lap.get("target_watts"):
                lap["target_watts"] = int(round(lap["target_watts"] * df))
                lap["target_watts_pct_ftp"] = int(round(lap["target_watts"] / ftp * 100))
            lap["grade_multiplier"] = round(
                road_grade_multiplier(lap.get("gradient", 0.0) or 0.0), 4,
            )
            if hr_ceiling:
                lap["hr_ceiling"] = hr_ceiling

        rp.target_hr_ceiling = hr_ceiling
        rp.fueling_plan      = cy_fueling_plan(actual_sec, cycling_discipline)

        # Hill climb: surface a target W/kg alongside watts. Stored inside the
        # fueling_plan dict so we don't need a new column.
        if cycling_discipline == "hill_climb" and rp.fueling_plan is not None:
            target_w_kg = hill_climb_target_w_per_kg(actual_sec)
            rp.fueling_plan = dict(rp.fueling_plan)   # detach from default literal
            rp.fueling_plan["target_w_per_kg"] = target_w_kg
            if us and us.weight_kg:
                rp.fueling_plan["target_watts"] = int(round(target_w_kg * us.weight_kg))

    # ══════════════════════════════════════════════════════════════════════════
    # SWIMMING
    # ══════════════════════════════════════════════════════════════════════════
    elif family == "swimming":
        css = metrics["css"]
        if css is None:
            raise HTTPException(
                status_code=422,
                detail="No CSS data available — set Critical Swim Speed in settings",
            )
        # Heuristic: > 1000 m events are usually open water.
        open_water = plan_distance_m > 1000
        actual_sec = predict_swim_time_sec(css * freshness_factor, plan_distance_m, open_water)

        # No lap table — emit a single "lap" carrying the target pace.
        target_pace_str = format_swim_pace(css)
        laps = [{
            "lap":               1,
            "distance_m":        round(plan_distance_m),
            "target_sec_per_km": round(css * 10),   # sec/km equivalent for schema compat
            "target_pace":       target_pace_str,
            "gradient":          0.0,
            "grade_multiplier":  1.0,
            "grade_adj_sec":     round(css * 10),
            "grade_adj_pace":    target_pace_str,
            "cumulative_km":     round(plan_distance_m / 1000, 2),
            "hr_ceiling":        None,
            "target_watts":      None,
            "target_watts_pct_ftp": None,
        }]

    # ══════════════════════════════════════════════════════════════════════════
    # TRIATHLON
    # ══════════════════════════════════════════════════════════════════════════
    elif family == "triathlon":
        splits = rp.triathlon_splits_m or _infer_triathlon_splits(plan_distance_m)
        rp.triathlon_splits_m = splits
        swim_m = splits.get("swim_m", 1500)
        bike_m = splits.get("bike_m", 40000)
        run_m  = splits.get("run_m",  10000)

        T1_SEC, T2_SEC = 5 * 60, 3 * 60   # standard transition estimates

        tri_legs = []
        total_sec_acc = 0.0

        # Swim leg
        css = metrics["css"]
        if css:
            swim_sec = predict_swim_time_sec(css * freshness_factor, swim_m, open_water=True)
            tri_legs.append({
                "leg":            "swim",
                "distance_m":     swim_m,
                "predicted_seconds": swim_sec,
                "predicted_time": format_time(swim_sec),
                "target_pace":    format_swim_pace(css),
                "target_watts":   None,
                "target_watts_pct_ftp": None,
                "lap_paces":      None,
            })
            total_sec_acc += swim_sec + T1_SEC

        # Bike leg
        ftp = metrics["ftp"]
        if ftp:
            ftp_adjusted = ftp * (2.0 - freshness_factor)
            bike_sec     = predict_cycling_time_sec(ftp_adjusted, bike_m)
            avg_speed_kph = bike_m / bike_sec * 3.6
            bike_laps, _ = compute_cycling_lap_targets(
                ftp           = ftp_adjusted,
                distance_m    = bike_m,
                split_spread  = split_spread,
                lap_km        = lap_km,
                max_hr        = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
                predicted_sec = bike_sec,
            )
            base_watts_tri = round(ftp_adjusted * 0.85)   # ~85% FTP for IM bike leg
            tri_legs.append({
                "leg":            "bike",
                "distance_m":     bike_m,
                "predicted_seconds": bike_sec,
                "predicted_time": format_time(bike_sec),
                "target_pace":    f"{round(avg_speed_kph, 1)} km/h avg",
                "target_watts":   base_watts_tri,
                "target_watts_pct_ftp": round(base_watts_tri / ftp * 100),
                "lap_paces":      bike_laps,
            })
            total_sec_acc += bike_sec + T2_SEC

        # Run leg
        vdot = metrics["vdot"]
        if vdot:
            adj_vdot = vdot * (2.0 - freshness_factor) * 0.95   # ~5% fatigue from swim+bike
            run_sec  = predict_race_time_sec(adj_vdot, run_m)
            run_laps, _ = compute_lap_paces(
                predicted_sec = run_sec,
                distance_m    = run_m,
                split_spread  = split_spread,
                lap_km        = lap_km,
                max_hr        = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
            )
            tri_legs.append({
                "leg":            "run",
                "distance_m":     run_m,
                "predicted_seconds": run_sec,
                "predicted_time": format_time(run_sec),
                "target_pace":    run_laps[0]["target_pace"] if run_laps else None,
                "target_watts":   None,
                "target_watts_pct_ftp": None,
                "lap_paces":      run_laps,
            })
            total_sec_acc += run_sec

        if not tri_legs:
            raise HTTPException(
                status_code=422,
                detail="No FTP, CSS, or VDOT data available for any triathlon leg",
            )

        actual_sec = total_sec_acc
        # Store legs in weather_snapshot for retrieval.
        if weather_snap is None:
            weather_snap = {}
        weather_snap["triathlon_legs"] = tri_legs
        # Primary lap_paces = bike laps if available, else run.
        laps = next((l["lap_paces"] for l in tri_legs if l["lap_paces"]), [])

    else:
        # Generic fallback — running-style plan with VDOT if available.
        vdot = metrics["vdot"]
        if vdot is None:
            raise HTTPException(
                status_code=422,
                detail=f"Race plans for {family} require performance data — "
                        "please set relevant thresholds in settings",
            )
        adjusted_vdot = vdot * (2.0 - freshness_factor)
        base_sec = predict_race_time_sec(adjusted_vdot, plan_distance_m)
        flat_sec = base_sec * weather_factor * course_factors.get(rp.course_type, 1.0)
        laps, actual_sec = compute_lap_paces(
            predicted_sec = flat_sec,
            distance_m    = plan_distance_m,
            split_spread  = split_spread,
            lap_km        = lap_km,
            max_hr        = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
        )

    # ── Queue old watch file for deletion on regeneration ─────────────────────
    if rp.watch_filename and rp.watch_uploaded_at:
        from app.models.training_plan import WatchPendingDelete
        if not db.query(WatchPendingDelete).filter_by(
                user_id=rp.user_id, filename=rp.watch_filename).first():
            db.add(WatchPendingDelete(user_id=rp.user_id, filename=rp.watch_filename))
        rp.watch_filename    = None
        rp.watch_uploaded_at = None

    # ── Generate FIT (running + cycling only — swim/tri use per-leg legs) ──────
    if family not in ("swimming", "triathlon") and laps:
        try:
            fit_bytes = generate_race_fit(
                name          = goal.event_name or "Race",
                sport         = (goal.event_sport or "running").lower(),
                lap_paces     = laps,
                pace_coaching = (family != "cycling"),   # cycling: no pace alert, use HR
                hr_coaching   = (rp.pace_hr_mode == "pace_hr"),
                max_hr        = max_hr_val,
                fuel_items    = _fuel_items(db, rp, goal, laps, actual_sec),
            )
            rp.fit_b64        = base64.b64encode(fit_bytes).decode()
            rp.watch_filename = f"RACE_{goal.id}_{goal.event_date or 'undated'}.fit"
        except Exception:
            rp.fit_b64        = None
            rp.watch_filename = None
    else:
        rp.fit_b64        = None
        rp.watch_filename = None

    rp.predicted_seconds  = actual_sec
    rp.weather_snapshot   = weather_snap
    rp.lap_paces          = laps
    rp.generated_at       = now
    db.commit()
    db.refresh(rp)
    return _race_plan_out(rp, us, goal)


# ── GPX course upload / delete ───────────────────────────────────────────────

@router.post("/goals/{goal_id}/race-plan/course")
def upload_course_gpx(
    goal_id: int,
    file: UploadFile = File(...),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Parse an uploaded GPX file and store the elevation profile."""
    user, us = _get_user_and_settings(db, user)
    goal = _goal_or_404(db, goal_id, user)
    if not file.filename.lower().endswith(".gpx"):
        raise HTTPException(status_code=422, detail="Must be a .gpx file")

    content  = file.file.read()
    segments = parse_gpx(content)
    if not segments:
        raise HTTPException(status_code=422, detail="Could not parse GPX — no track points found")

    path = extract_path_points(content, max_points=300)

    rp = _get_or_create_plan(db, goal_id, user.id, flush=True)

    tech_factor, tech_label    = compute_technicality_factor(segments)
    rp.course_segments         = segments
    rp.course_path             = path
    rp.technicality_factor     = tech_factor
    rp.technicality_label      = tech_label

    # Auto-set the weather pin to the course start (first trackpoint).
    if path:
        rp.pin_lat = path[0][0]
        rp.pin_lon = path[0][1]

    # Clear stale generated results — the user must regenerate.
    rp.lap_paces         = None
    rp.generated_at      = None
    rp.fit_b64           = None
    rp.watch_filename    = None
    rp.watch_uploaded_at = None
    db.commit()
    db.refresh(rp)
    return _race_plan_out(rp, us, goal)


def _fuel_items(db, rp, goal, laps, predicted_sec) -> list[dict]:
    """The fuelling timeline's items, for the race FIT's lap names."""
    if not predicted_sec:
        return []
    from app.api.fuel import fuel_plan_for
    saved = rp.lap_paces, rp.predicted_seconds
    rp.lap_paces, rp.predicted_seconds = laps, predicted_sec
    try:
        return fuel_plan_for(db, goal.user_id, goal, rp)["timeline"]["items"]
    finally:
        rp.lap_paces, rp.predicted_seconds = saved


def _track_gpx(geometry: list) -> bytes:
    """A saved track's [[lng, lat, ele], ...] as GPX, so it goes through the
    same parse_gpx as an uploaded file and yields the same segments."""
    pts = "".join(
        f'<trkpt lat="{p[1]}" lon="{p[0]}">' + (f"<ele>{p[2]}</ele>" if len(p) > 2 and p[2] is not None else "")
        + "</trkpt>"
        for p in geometry
    )
    return (f'<?xml version="1.0"?><gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">'
            f"<trk><trkseg>{pts}</trkseg></trk></gpx>").encode()


@router.post("/goals/{goal_id}/race-plan/course/from-track/{track_id}")
def course_from_track(goal_id: int, track_id: int, user: User = Depends(require_auth),
                      db: Session = Depends(get_db)):
    """Use a saved (or just-drawn) map track as the race course — the same
    result as uploading it as GPX."""
    from app.models.custom_track import CustomTrack
    user, us = _get_user_and_settings(db, user)
    goal = _goal_or_404(db, goal_id, user)
    track = db.query(CustomTrack).filter_by(id=track_id, user_id=user.id).first()
    if track is None or not track.geometry:
        raise HTTPException(status_code=404, detail="Track not found")
    content = _track_gpx(track.geometry)
    segments = parse_gpx(content)
    if not segments:
        raise HTTPException(status_code=422, detail="That track has no usable points")
    path = extract_path_points(content, max_points=300)
    rp = _get_or_create_plan(db, goal_id, user.id, flush=True)
    rp.technicality_factor, rp.technicality_label = compute_technicality_factor(segments)
    rp.course_segments, rp.course_path = segments, path
    if path:
        rp.pin_lat, rp.pin_lon = path[0][0], path[0][1]
    rp.lap_paces = rp.generated_at = rp.fit_b64 = rp.watch_filename = rp.watch_uploaded_at = None
    db.commit()
    db.refresh(rp)
    return _race_plan_out(rp, us, goal)


@router.delete("/goals/{goal_id}/race-plan/course", status_code=204)
def delete_course_gpx(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Remove the uploaded GPX course profile."""
    rp = (
        db.query(RacePlan)
        .join(TrainingGoal, RacePlan.goal_id == TrainingGoal.id)
        .filter(TrainingGoal.user_id == user.id, RacePlan.goal_id == goal_id)
        .first()
    )
    if rp:
        rp.course_segments   = None
        rp.course_path       = None
        rp.lap_paces         = None
        rp.generated_at      = None
        rp.fit_b64           = None
        rp.watch_filename    = None
        rp.watch_uploaded_at = None
        db.commit()


# ── FIT download ─────────────────────────────────────────────────────────────

@router.get("/goals/{goal_id}/race-plan/fit")
def download_race_fit(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Generate and download a FIT race workout file with per-km pace targets."""
    goal = _goal_or_404(db, goal_id, user)
    rp = db.query(RacePlan).filter_by(goal_id=goal_id).first()
    if rp is None or not rp.lap_paces:
        raise HTTPException(status_code=422, detail="Generate the race plan first")

    us      = db.query(UserSettings).filter_by(user_id=user.id).first()
    max_hr  = int(us.max_hr) if us and us.max_hr else None
    sport   = (goal.event_sport or "running").lower()
    name    = goal.event_name or f"{sport.title()} Race"
    hr_mode = rp.pace_hr_mode == "pace_hr"

    fit_bytes = generate_race_fit(
        name         = name[:31],
        sport        = sport,
        lap_paces    = rp.lap_paces,
        pace_coaching = True,
        hr_coaching  = hr_mode,
        max_hr       = max_hr,
        fuel_items   = _fuel_items(db, rp, goal, rp.lap_paces, rp.predicted_seconds),
    )
    filename = f"race_{goal_id}_{goal.event_date or 'plan'}.fit"
    return Response(
        content=fit_bytes,
        media_type="application/octet-stream",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
