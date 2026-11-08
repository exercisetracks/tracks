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

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, File, HTTPException, Query, UploadFile
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.plan.race_day import freshness_factor as race_freshness
from app.calculators.race_predictor import (
    compute_cycling_lap_targets,
    compute_cycling_wind_course_exposure,
    compute_lap_paces,
    compute_technicality_factor,
    compute_wind_course_exposure,
    course_grade_factor,
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
    _infer_triathlon_splits,
    _race_form,
    _race_plan_out,
    race_intensity,
)

router = APIRouter()

# ── Course types: a course chosen without a course file ──────────────────────
# The time a terrain type adds, used in place of a course's own hills. Each
# is close to what the grade model (race_predictor/grade.py) makes of a
# course with the climbing the picker gives that type (metres per 100 km,
# frontend/components/raceplan/constants.js): about +1 % for Rolling's 3 m a
# kilometre, +3.5 % for Hilly's 10 m and +6–10 % for Mountainous's 15–25 m,
# so choosing "Hilly" and loading a hilly course's file land within half a
# point. Checked when the grade model was corrected (2026-11) and left alone.
# Cycling is less affected by terrain labels (grade is already in physics).
COURSE_FACTORS    = {"flat": 1.0, "rolling": 1.015, "hilly": 1.04, "mountainous": 1.08}
CY_COURSE_FACTORS = {"flat": 1.0, "rolling": 1.008, "hilly": 1.02, "mountainous": 1.04}


def _plan_distance(rp: RacePlan | None, goal: TrainingGoal) -> float | None:
    """The distance the race is planned at: the course's own when the plan
    says to race it, else the event's."""
    if rp is not None and rp.use_gpx_distance and rp.course_segments:
        return course_totals(rp.course_segments)["distance_m"]
    return goal.event_distance_meters


def running_course_factor(rp, distance_m: float) -> float:
    """What the course does to a running finish on a neutral day, as
    sport_plan's running branch prices it: with a course, its technicality
    and its ground (race_predictor.course_grade_factor — the finish
    compute_lap_paces gives its laps); without, its terrain type. The goal
    card's badge uses it, so the card and the plan price the same hills."""
    if rp is None:
        return 1.0
    if rp.course_segments:
        return (compute_technicality_factor(rp.course_segments)[0]
                * course_grade_factor(rp.course_segments, distance_m))
    return COURSE_FACTORS.get(rp.course_type, 1.0)


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
    rp      = db.query(RacePlan).filter_by(goal_id=goal_id).first()
    # The badge follows the race plan's intensity, so the goal card and the
    # plan agree on how hard the race is run (see sport_plan).
    k       = race_intensity(rp)
    if k != 1.0:
        metrics = {**metrics,
                   "ftp": metrics["ftp"] * k if metrics["ftp"] else None,
                   "css": metrics["css"] / k if metrics["css"] else None}

    if family == "running" and metrics["vdot"]:
        # The plan's course too: the badge used to price every race flat,
        # so a hilly course's card and plan disagreed by its hills.
        run_m = _plan_distance(rp, goal)
        sec = (predict_running_race_sec(metrics["vdot"], run_m, metrics["run_indices"]) / k
               * running_course_factor(rp, run_m))

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
            sec = dist / (est_speed_kmh * 1000 / 3600) / k

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
            sec += predict_race_time_sec(metrics["vdot"], splits["run_m"]) / k
        sec += 5 * 60 + 3 * 60   # T1 5 min + T2 3 min transitions
        if sec == 0.0:
            sec = None

    if sec is None:
        return PredictedTimeOut(vdot=metrics.get("vdot"))

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
    return _race_plan_out(rp, us, goal, db)


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
    return _race_plan_out(rp, us, goal, db)


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
    rp = plan_race(db, user, us, goal)
    return _race_plan_out(rp, us, goal, db)


def plan_race(db: Session, user: User, us: UserSettings | None, goal: TrainingGoal, *,
              create: bool = True) -> RacePlan:
    """Run the pacing engine for ``goal`` and store what it says: the race-day
    prediction, its laps, the weather and the projection behind it.

    Shared by the race plan's page (``_do_generate_race_plan``) and the
    training calendar, whose race day is this plan's race
    (training_plan/sync.py ``_race_day_fit``) — so the watch runs the laps
    the page shows. The race is no longer a file of its own: see
    ``_retire_race_file``. Raises the 422 the page shows when the sport's
    threshold (VDOT, FTP, CSS) is not known.

    With ``create`` false — the calendar's call — a goal that has no race
    plan yet is planned on the plan's defaults and none is written: the row
    syncs, ``goal_id`` is unique, and one made here beside the one a phone
    makes for the same goal on its first edit would collide. The phone plans
    such a goal the same way (LocalRacePlans.strategy's defaults).
    """
    if goal.goal_type != "event" or not goal.event_distance_meters:
        raise HTTPException(status_code=422, detail="Goal must be an event goal with a distance")

    rp = db.query(RacePlan).filter_by(goal_id=goal.id).first()
    stored = rp is not None or create
    if rp is None:
        rp = _get_or_create_plan(db, goal.id, user.id, flush=True) if create \
            else RacePlan(goal_id=goal.id, user_id=user.id)

    metrics = _get_athlete_metrics(goal, db, user, us)
    family  = metrics["family"]

    # ── Decide distance ──────────────────────────────────────────────────────
    plan_distance_m = _plan_distance(rp, goal)

    if not plan_distance_m:
        raise HTTPException(status_code=422, detail="Goal must have a distance set")

    # ── Freshness adjustment (applies to all sports equally) ─────────────────
    # Race morning's form, after the plan's remaining sessions — not today's,
    # which mid-build is a fatigue the taper will have cleared
    # (calculators/plan/race_day.py).
    form = _race_form(db, user, goal, metrics)
    freshness_factor = race_freshness(form["race_day"])

    # ── Weather (cached by place and day — see race_helpers._fetch_weather) ──
    lat       = rp.pin_lat
    lon       = rp.pin_lon

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

    laps, actual_sec, weather_snap = sport_plan(
        family, metrics, rp, goal, plan_distance_m,
        freshness_factor=freshness_factor, weather_snap=weather_snap, weather_factor=weather_factor,
        net_wind_mps=net_wind_mps, annotate=_annotate_weather, imperial=bool(us and us.units == "imperial"),
    )
    projection = race_projection(family, metrics, form, actual_sec, plan_distance_m)

    _retire_race_file(db, rp)
    if weather_snap is not None or projection is not None:
        # Carried in the snapshot blob, which already holds non-weather
        # extras (triathlon_legs), rather than a column of its own. The
        # projection is derived — rebuilt on every generation, which every
        # visit to the page runs — so it needs no column or sync either.
        weather_snap = {**(weather_snap or {}), "projection": projection}

    rp.predicted_seconds  = actual_sec
    rp.weather_snapshot   = weather_snap
    rp.lap_paces          = laps
    rp.generated_at       = datetime.now(timezone.utc)
    if stored:
        db.commit()
        db.refresh(rp)
    return rp


def _retire_race_file(db: Session, rp: RacePlan) -> None:
    """Forget the race's file of its own, and take it off the watch.

    A race plan used to be a ``RACE_*.fit`` stored here and offered on the
    upload list, pushed by a route of its own (FileSyncService) that only a
    visit to this page ever filled — while the training calendar's race day
    went out as an empty workout. The race is now that calendar entry,
    encoded from these laps (training_plan/sync.py ``_race_day_fit``), so a
    file still on the watch is a second copy of the race: queued for
    deletion (USB removes it; Bluetooth cannot delete a workout file), and
    the plan stops naming it.
    """
    if rp.watch_filename and rp.watch_uploaded_at:
        from app.models.training_plan import WatchPendingDelete
        if not db.query(WatchPendingDelete).filter_by(
                user_id=rp.user_id, filename=rp.watch_filename).first():
            db.add(WatchPendingDelete(user_id=rp.user_id, filename=rp.watch_filename))
    rp.fit_b64 = None
    rp.watch_filename = None
    rp.watch_uploaded_at = None


# ── GPX course upload / delete ───────────────────────────────────────────────

def race_projection(family: str, metrics: dict, form: dict, actual_sec: float,
                    distance_m: float) -> dict | None:
    """What a running race's race-day prediction is made of, for the page to
    say: fitness today and on race day, the share of the plan the last four
    weeks delivered, today's and race morning's form, and the finish at
    today's fitness and form.

    That finish is ``actual_sec`` rescaled by the flat prediction's ratio:
    the intensity, the weather and the course multiply both alike
    (sport_plan), so the ratio is exactly the fitness and the form. None
    for a sport VDOT does not predict; ``today_seconds`` only for a running
    race, the one whose whole time follows VDOT.
    """
    if metrics.get("vdot") is None or family not in ("running", "triathlon"):
        return None
    vdot, vdot_today = metrics["vdot"], metrics.get("vdot_today") or metrics["vdot"]
    today_sec = None
    if family == "running":
        def flat(v: float, ff: float) -> float:
            return predict_running_race_sec(v * (2.0 - ff), distance_m, metrics["run_indices"], ff)
        today_sec = round(actual_sec * flat(vdot_today, race_freshness(form["today"]))
                          / flat(vdot, race_freshness(form["race_day"])), 1)
    adherence = metrics.get("adherence")
    return {
        "vdot_today": round(vdot_today, 1),
        "vdot_race_day": round(vdot, 1),
        "adherence": round(adherence, 2) if adherence is not None else None,
        "tsb_today": form["today"],
        "tsb_race_day": form["race_day"],
        "today_seconds": today_sec,
        # Formatted here, as predicted_time is: Python rounds ties to even and
        # the browser would not, and the two times sit side by side.
        "today_time": format_time(today_sec) if today_sec is not None else None,
    }


def sport_plan(family: str, metrics: dict, rp, goal, plan_distance_m: float, *,
               freshness_factor: float = 1.0, weather_snap: dict | None = None,
               weather_factor: float = 1.0, net_wind_mps: float = 0.0, annotate=None,
               imperial: bool = False) -> tuple[list[dict], float, dict | None]:
    """The race's laps and finish time for its sport family: the pacing engine.

    Pure apart from what it writes back onto `rp` — technicality, the HR
    ceiling, the climb target, the triathlon splits — so it runs on a plain
    namespace as well as a RacePlan row. That is what lets
    spec/make_race_plan_fixtures.py call it with no database, and the phone's
    port (LocalRacePlans, per sport) be held to it. `annotate` folds race-day
    weather into the time; it is only called when there is weather, which the
    phone, planning a neutral day, never has.

    The plan's intensity (`race_intensity`, 0.80–1.20) scales what every
    target asks for: speed on foot and in the water, power on a bike. Running
    divides the finish time rather than raising VDOT, because a marathon's time
    is half VDOT and half the training-volume model, and a VDOT change would
    reach only the first half; 1.05 is 5 % faster whatever the distance. The
    bike scales the FTP its physics and targets are worked from, so 1.05 is
    5 % more power and the speed that buys; target_watts_pct_ftp stays a
    share of the real FTP, which is what the watch compares it to.
    """
    course_factors, cy_course_factors = COURSE_FACTORS, CY_COURSE_FACTORS

    lap_km       = 1.60934 if imperial else 1.0
    split_spread = rp.split_spread if rp.split_spread is not None else 0.0
    max_hr_val   = metrics["max_hr"]
    k            = race_intensity(rp)

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
                                                 metrics["run_indices"], freshness_factor) / k

        if weather_snap and "heat_penalty_pct" not in weather_snap:
            weather_factor, net_wind_mps = annotate(weather_snap, base_sec)

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
            # A course gets terrain splits, not kilometres — race_predictor/terrain.py.
            terrain         = True,
        )

    # ══════════════════════════════════════════════════════════════════════════
    # MOUNTAIN BIKING
    # ══════════════════════════════════════════════════════════════════════════
    elif family == "mountain_biking":
        from app.calculators.mtb import (
            race_hr_ceiling,
            mtb_hr_only_laps as _mtb_hr_only_laps,
            mtb_grade_multiplier,
        )

        ftp        = metrics["ftp"]
        lthr       = metrics["lthr"]
        discipline = (goal.mtb_discipline or "trail").lower()

        # Time prediction: prefer cycling physics when FTP is available;
        # otherwise estimate from typical MTB speed by discipline.
        if ftp:
            ftp_adjusted = ftp * (2.0 - freshness_factor) * k
            base_sec = predict_cycling_time_sec(
                ftp_adjusted, plan_distance_m, rp.course_segments or None,
            )
        else:
            # XCO ~22 km/h, XCM ~18 km/h, Enduro ~12 km/h transfers, Trail ~16.
            est_speed_kmh = {"xco": 22.0, "xcm": 18.0, "enduro": 12.0, "trail": 16.0}.get(discipline, 16.0)
            base_sec = plan_distance_m / (est_speed_kmh * 1000 / 3600) / k
            ftp_adjusted = None

        if weather_snap and "heat_penalty_pct" not in weather_snap:
            weather_factor, net_wind_mps = annotate(
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

        # The race's HR ceiling, shown in Pacing. Fuelling is the fuel plan's
        # (calculators/fuel_plan.py), for every sport alike.
        rp.target_hr_ceiling = race_hr_ceiling(discipline, int(lthr) if lthr else None)

    # ══════════════════════════════════════════════════════════════════════════
    # CYCLING
    # ══════════════════════════════════════════════════════════════════════════
    elif family == "cycling":
        from app.calculators.road_cycling import (
            drafting_factor as cy_drafting_factor,
            race_hr_ceiling as cy_race_hr_ceiling,
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
        ftp_effective = (ftp / df) * (2.0 - freshness_factor) * k

        base_sec = predict_cycling_time_sec(ftp_effective, plan_distance_m,
                                             rp.course_segments or None)
        if weather_snap and "heat_penalty_pct" not in weather_snap:
            weather_factor, net_wind_mps = annotate(
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
        # Hill climb: a W/kg to aim for, shown in Pacing beside the HR
        # ceiling; the watts it means are worked out from the rider's weight
        # when the plan is read (_race_plan_out), so a new weight needs no
        # regeneration.
        rp.climb_target_w_per_kg = (
            hill_climb_target_w_per_kg(actual_sec) if cycling_discipline == "hill_climb" else None
        )

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
        css = css / k   # the pace raced: CSS, quickened or eased by the intensity
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
        # Each leg's course — its own file, else its stretch of the whole
        # course split at T1/T2 (race_predictor/triathlon.py). A leg with a
        # course is raced at the course's length, and its hills shape its
        # targets as a single-sport course does.
        from app.calculators.race_predictor.triathlon import leg_courses, total_m
        courses = leg_courses(rp.course_segments, rp.course_path, getattr(rp, "tri_leg_courses", None),
                              getattr(rp, "tri_transitions_m", None), splits)["legs"]
        leg_segs = {k: (v["segments"] or None) for k, v in courses.items()}
        swim_m = total_m(leg_segs["swim"]) if leg_segs["swim"] else splits.get("swim_m", 1500)
        bike_m = total_m(leg_segs["bike"]) if leg_segs["bike"] else splits.get("bike_m", 40000)
        run_m  = total_m(leg_segs["run"]) if leg_segs["run"] else splits.get("run_m",  10000)

        T1_SEC, T2_SEC = 5 * 60, 3 * 60   # standard transition estimates

        tri_legs = []
        total_sec_acc = 0.0

        # Swim leg
        css = metrics["css"] / k if metrics["css"] else None
        if css:
            swim_sec = predict_swim_time_sec(css * freshness_factor, swim_m, open_water=True)
            tri_legs.append({
                "leg":            "swim",
                "distance_m":     swim_m,
                "predicted_seconds": swim_sec,
                "predicted_time": format_time(swim_sec),
                "target_pace":    format_swim_pace(css),
                # The pace the swim's graph draws (per km, as every lap's is).
                "target_sec_per_km": round(css * 10, 1),
                "target_watts":   None,
                "target_watts_pct_ftp": None,
                "lap_paces":      None,
            })
            total_sec_acc += swim_sec + T1_SEC

        # Bike leg
        ftp = metrics["ftp"]
        if ftp:
            from app.calculators.road_cycling import road_grade_multiplier
            ftp_adjusted = ftp * (2.0 - freshness_factor) * k
            bike_sec     = predict_cycling_time_sec(ftp_adjusted, bike_m, leg_segs["bike"])
            if not leg_segs["bike"]:
                # Its terrain type, as a ride's, where the leg has no course.
                bike_sec *= cy_course_factors.get(rp.course_type, 1.0)
            avg_speed_kph = bike_m / bike_sec * 3.6
            bike_laps, _ = compute_cycling_lap_targets(
                ftp           = ftp_adjusted,
                distance_m    = bike_m,
                split_spread  = split_spread,
                lap_km        = lap_km,
                course_segments = leg_segs["bike"],
                max_hr        = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
                predicted_sec = bike_sec,
            )
            if leg_segs["bike"]:
                for lap in bike_laps:
                    lap["grade_multiplier"] = round(road_grade_multiplier(lap.get("gradient", 0.0) or 0.0), 4)
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
            run_sec  = predict_race_time_sec(adj_vdot, run_m) / k
            if leg_segs["run"]:
                run_sec *= compute_technicality_factor(leg_segs["run"])[0]
            else:
                run_sec *= course_factors.get(rp.course_type, 1.0)
            run_laps, run_course_sec = compute_lap_paces(
                predicted_sec = run_sec,
                distance_m    = run_m,
                split_spread  = split_spread,
                lap_km        = lap_km,
                course_segments = leg_segs["run"],
                max_hr        = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
                terrain       = bool(leg_segs["run"]),
            )
            if leg_segs["run"]:
                # The leg's time is the one its laps are paced to, hills in:
                # it was the flat time, so the laps and the leg disagreed.
                run_sec = run_course_sec
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
        base_sec = predict_race_time_sec(adjusted_vdot, plan_distance_m) / k
        flat_sec = base_sec * weather_factor * course_factors.get(rp.course_type, 1.0)
        laps, actual_sec = compute_lap_paces(
            predicted_sec = flat_sec,
            distance_m    = plan_distance_m,
            split_spread  = split_spread,
            lap_km        = lap_km,
            max_hr        = max_hr_val if rp.pace_hr_mode == "pace_hr" else None,
        )

    return laps, actual_sec, weather_snap


_TRI_LEGS = ("swim", "bike", "run")


def _check_leg(leg: str | None) -> str | None:
    if leg is not None and leg not in _TRI_LEGS:
        raise HTTPException(status_code=422, detail="leg must be swim, bike or run")
    return leg


def _store_course(rp: RacePlan, segments: list[dict], path: list[list], leg: str | None) -> None:
    """A parsed course as the race's whole course, or as one triathlon leg's
    own (race_predictor/triathlon.py), and the stale plan cleared."""
    if leg:
        rp.tri_leg_courses = {**(rp.tri_leg_courses or {}), leg: {"segments": segments, "path": path}}
    else:
        rp.technicality_factor, rp.technicality_label = compute_technicality_factor(segments)
        rp.course_segments, rp.course_path = segments, path
        # A new whole course is split afresh: the old T1/T2 were along another file.
        rp.tri_transitions_m = None
    # The weather pin follows the course start — the whole course's, or the
    # first leg file's when there is no whole course.
    if path and (not leg or not rp.course_segments):
        rp.pin_lat, rp.pin_lon = path[0][0], path[0][1]
    rp.lap_paces = rp.generated_at = rp.fit_b64 = rp.watch_filename = rp.watch_uploaded_at = None


@router.post("/goals/{goal_id}/race-plan/course")
def upload_course_gpx(
    goal_id: int,
    file: UploadFile = File(...),
    leg: str | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Parse an uploaded GPX file and store the elevation profile — as the
    whole course, or with `leg` as one triathlon leg's own."""
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
    _store_course(rp, segments, path, _check_leg(leg))
    db.commit()
    db.refresh(rp)
    return _race_plan_out(rp, us, goal, db)


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
def course_from_track(goal_id: int, track_id: int, leg: str | None = Query(None),
                      user: User = Depends(require_auth), db: Session = Depends(get_db)):
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
    _store_course(rp, segments, path, _check_leg(leg))
    db.commit()
    db.refresh(rp)
    return _race_plan_out(rp, us, goal, db)


@router.delete("/goals/{goal_id}/race-plan/course", status_code=204)
def delete_course_gpx(goal_id: int, leg: str | None = Query(None), user: User = Depends(require_auth),
                      db: Session = Depends(get_db)):
    """Remove the uploaded GPX course profile — the whole course, or with
    `leg` one triathlon leg's own file (the leg falls back to its stretch of
    the whole course)."""
    _check_leg(leg)
    rp = (
        db.query(RacePlan)
        .join(TrainingGoal, RacePlan.goal_id == TrainingGoal.id)
        .filter(TrainingGoal.user_id == user.id, RacePlan.goal_id == goal_id)
        .first()
    )
    if rp and leg:
        rp.tri_leg_courses = {k: v for k, v in (rp.tri_leg_courses or {}).items() if k != leg} or None
        rp.lap_paces = rp.generated_at = rp.fit_b64 = rp.watch_filename = rp.watch_uploaded_at = None
        db.commit()
    elif rp:
        rp.course_segments   = None
        rp.course_path       = None
        rp.tri_transitions_m = None
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
