# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Training-plan generation, regeneration, and workout CRUD endpoints.

Authenticated routes under /coaching:
  POST   /goals/{id}/plan/generate     build (or rebuild) a goal's plan (event or fitness)
  GET    /goals/{id}/plan              fetch the plan (404 if none yet)
  GET    /workouts/upcoming            next-N-days widget feed
  PATCH  /plan/workouts/{id}           edit a planned workout
  DELETE /plan/workouts/{id}           remove a planned workout

Plus the background helpers called from the watcher / goal creation:
  _regenerate_future_workouts, refresh_plans_for_user, refresh_plans_for_user_force

Static `/workouts/...` and `/plan/...` routes register before nothing dynamic
here, but this router owns `/goals/{id}/...` paths so it is included after the
ICS router in __init__ to keep registration order deterministic.
"""

import logging
import time
from datetime import date, datetime, timedelta, timezone

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import and_, or_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.plan.moved import keep_completed, keep_moved
from app.calculators.plan.starting import frequency_for
from app.calculators.training_plan import (
    _sport_family,
    fitness_horizon_end,
    generate_fitness_plan,
    generate_training_plan,
)
from app.database import SessionLocal, get_db
from app.models.activity import User
from app.models.coaching import PLAN_GOAL_TYPES, TrainingGoal
from app.models.training_plan import PlannedWorkout, TrainingPlan
from app.models.user_settings import UserSettings
from app.schemas.training_plan import (
    PlannedWorkoutCreate,
    PlannedWorkoutOut,
    PlannedWorkoutUpdate,
    TrainingPlanOut,
)

from app.sync import store as sync_store
from app.sync.hlc import ClockError, Hlc
from app.sync.uids import uuid7

from .helpers import (
    _effective_ftp,
    _effective_max_hr,
    _effective_threshold_hr,
    _get_activity_history,
    _get_fingerprint_ew,
    _get_goal_or_404,
    _get_pace_bests,
    _get_running_fitness,
    _queue_orphan_deletes,
    _upsert_pending_delete,
)
from .injectors import (
    _inject_field_tests,
    _inject_strength_workouts,
    _inject_stretch_flows,
)

log = logging.getLogger(__name__)

router = APIRouter()


# ─────────────────────────────────────────
# Generate / get plan
# ─────────────────────────────────────────

@router.post("/goals/{goal_id}/plan/generate", response_model=TrainingPlanOut)
def generate_plan(
    goal_id: int,
    schedule_tests: bool | None = None,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Generate (or regenerate) a full training plan for the given goal.

    schedule_tests: optional override of goal.schedule_tests. When omitted,
    the goal's persisted flag is used; when supplied, this value is also
    persisted so future regenerations (e.g. background refresh on activity
    import) keep producing tests. Currently MTB / cycling families only.
    On test completion the matcher auto-updates ftp_auto / threshold_hr_auto.
    """
    goal = _get_goal_or_404(db, goal_id, user)

    if goal.goal_type not in PLAN_GOAL_TYPES:
        raise HTTPException(status_code=400,
                            detail="Training plans are built for Race / Event and Fitness goals")
    today = date.today()
    if goal.goal_type == "event":
        if not goal.event_date:
            raise HTTPException(status_code=400, detail="Goal has no event date")
        if goal.event_date <= today:
            raise HTTPException(status_code=400, detail="Event date is in the past")

    # Persist override if caller supplied one; otherwise read from goal.
    if schedule_tests is not None and bool(goal.schedule_tests) != bool(schedule_tests):
        goal.schedule_tests = bool(schedule_tests)
        db.flush()

    vdot, workout_dicts = _plan_workout_dicts(db, goal, user.id, today)

    # Replace the plan's workouts, not the plan. Its uid is derived from the
    # goal's (spec/sync.yaml: "plan:{goal_uid}"), so deleting and recreating
    # it would tombstone the one uid it can ever have — and a delete wins.
    plan = db.query(TrainingPlan).filter_by(goal_id=goal_id).first()
    if plan:
        plan.sport = goal.event_sport or "running"
        plan.vdot = round(vdot, 1) if vdot else None
        plan.generated_at = datetime.now(timezone.utc)
    else:
        plan = TrainingPlan(
            goal_id=goal_id,
            user_id=user.id,
            sport=goal.event_sport or "running",
            vdot=round(vdot, 1) if vdot else None,
        )
        db.add(plan)
        db.flush()
    _replace_workouts(db, plan, workout_dicts, today)

    db.commit()
    db.refresh(plan)

    workouts = (
        db.query(PlannedWorkout)
        .filter_by(plan_id=plan.id)
        .order_by(PlannedWorkout.scheduled_date)
        .all()
    )

    plan_out = TrainingPlanOut.model_validate(plan)
    plan_out.workouts = [PlannedWorkoutOut.model_validate(w) for w in workouts]
    return plan_out


def _replace_workouts(db: Session, plan: TrainingPlan, workout_dicts: list[dict],
                      today: date) -> None:
    """Write a new generation of ``plan`` from ``workout_dicts``.

    What a rebuild replaces is the unfinished plan from today on. The days
    before today are the plan's history — planned, done or missed — and stay
    exactly as they were; wiping them made every edit after a plan's first
    day erase the record of it so far. From today on, a completed workout
    stays too (it claims its counterpart, calculators/plan/moved.py
    ``keep_completed``), and so does one the user moved (``_carry_moved``);
    everything else is replaced. The phone (LocalPlanning.regenerate) does
    the same, so a plan rebuilt on either ends up the same.

    Shared by the explicit endpoint and the background refresh, which once
    kept different things.
    """
    # Bulk delete: tombstoned row by row by app.sync.store's statement hook.
    _queue_orphan_deletes(db, plan.id, future_only=True, today=today, except_moved=True)
    db.query(PlannedWorkout).filter(
        PlannedWorkout.plan_id == plan.id,
        PlannedWorkout.scheduled_date >= today,
        PlannedWorkout.is_complete.is_(False),
        PlannedWorkout.moved_by_user.is_(False),
    ).delete(synchronize_session=False)
    db.flush()

    # A fresh generation marks every workout of the old one dead on phones
    # that have not heard of this rebuild yet. What survived the delete above
    # is carried into it explicitly; left on the old generation, every phone
    # would reap it as a superseded plan's leftovers.
    plan.generation = uuid7()
    for kept in db.query(PlannedWorkout).filter_by(plan_id=plan.id).all():
        kept.generation = plan.generation
    db.flush()
    workout_dicts = _carry_moved(db, plan, workout_dicts, today)
    workout_dicts = keep_completed(workout_dicts, [
        {"uid": w.uid, "scheduled_date": w.scheduled_date, "sport": w.sport,
         "workout_type": w.workout_type}
        for w in db.query(PlannedWorkout).filter(
            PlannedWorkout.plan_id == plan.id,
            PlannedWorkout.scheduled_date >= today,
            PlannedWorkout.is_complete.is_(True),
            PlannedWorkout.moved_by_user.is_(False),
        ).all()
    ], today)

    for w in workout_dicts:
        db.add(PlannedWorkout(
            plan_id=plan.id,
            generation=plan.generation,
            user_id=plan.user_id,
            scheduled_date=w["scheduled_date"],
            sport=w["sport"],
            workout_type=w["workout_type"],
            title=w["title"],
            description=w.get("description"),
            duration_minutes=w.get("duration_minutes"),
            distance_meters=w.get("distance_meters"),
            steps=w.get("steps", []),
        ))


def _carry_moved(db: Session, plan: TrainingPlan, workout_dicts: list[dict],
                 today: date) -> list[dict]:
    """Keep the workouts the user moved on their days, into this generation.

    Each takes its counterpart's content (calculators/plan/moved.py has the
    matching rule, shared with the phone); the counterparts are returned
    removed, so the caller writes the rest. The moved rows join the new
    generation — left on the old one, every replica would reap them as a
    superseded plan's leftovers.
    """
    moved = (db.query(PlannedWorkout)
             .filter_by(plan_id=plan.id, moved_by_user=True).all())
    if not moved:
        return workout_dicts
    remaining, refresh = keep_moved(workout_dicts, [
        {"uid": w.uid, "scheduled_date": w.scheduled_date, "sport": w.sport,
         "workout_type": w.workout_type, "is_complete": bool(w.is_complete)}
        for w in moved
    ], today)
    for w in moved:
        w.generation = plan.generation
        content = refresh.get(w.uid)
        if content is None:
            continue
        content = {**content, "steps": content.get("steps") or []}
        if all(getattr(w, f) == v for f, v in content.items()):
            continue  # unchanged: stamping it would beat a real edit elsewhere
        for f, v in content.items():
            setattr(w, f, v)
        # The file on the watch describes the old session. Queue it for
        # deletion and mark the workout unsent, as a replaced workout would be,
        # so the next sync carries the refreshed one.
        if w.watch_filename and w.watch_deleted_at is None:
            _upsert_pending_delete(db, w.user_id, w.watch_filename)
            w.watch_filename = None
            w.watch_uploaded_at = None
    db.flush()
    return remaining


def _goal_anchor(goal: TrainingGoal) -> date | None:
    """The day the goal was first written: its earliest sync stamp.

    What a fitness plan counts its light weeks from. Not `created_at`: that is
    when *this server* inserted the row, which for a goal made on a phone is
    whenever the phone next synced — and the phone, counting from its own
    stamp (LocalPlanning.planStart), would put the light week somewhere else.
    The stamps travel with the row, so every device reads the same day.
    """
    walls = []
    for stamp in (goal.clock or {}).values():
        try:
            walls.append(Hlc.parse(stamp).wall_ms)
        except ClockError:
            continue
    if not walls:
        return None
    return datetime.fromtimestamp(min(walls) / 1000, tz=timezone.utc).date()


def _plan_workout_dicts(db: Session, goal: TrainingGoal, user_id: int,
                        today: date) -> tuple[float | None, list[dict]]:
    """Everything a goal's plan holds, before it is written: the endurance
    sessions (event or fitness generator), field tests, strength and stretch
    flows. Shared by the explicit endpoint and the background refresh so the
    two cannot drift apart."""
    family = _sport_family((goal.event_sport or "running").lower())

    # ── Strength-only plans (bodybuilding, powerlifting, general strength) ───
    # When the sport is "strength_training" or "strength", skip the endurance
    # calculator entirely and build a pure strength schedule with custom
    # workout integration.
    is_strength_only = family == "strength" or family == "generic" and (
        goal.include_strength and not goal.event_distance_meters
        and goal.goal_type == "event"
    )

    if is_strength_only:
        # Ensure strength is enabled
        goal.include_strength = True
        if not goal.strength_tier or goal.strength_tier < 3:
            goal.strength_tier = 4  # Strength-primary focus
        if not goal.strength_days_per_week:
            goal.strength_days_per_week = 4
        db.flush()
        vdot, workout_dicts = None, []
    else:
        activity_history = _get_activity_history(db, user_id, days=90)
        pace_bests = _get_pace_bests(db, user_id)
        us = db.query(UserSettings).filter_by(user_id=user_id).first()
        common = dict(
            activity_history=activity_history,
            pace_bests=pace_bests,
            today=today,
            ftp=_effective_ftp(us),
            threshold_hr=_effective_threshold_hr(us),
            base_effective_weeks=_get_fingerprint_ew(db, user_id, family),
            imperial=bool(us and us.units == "imperial"),
            # Only read when there is no history of the sport (starting.py).
            activity_frequency=frequency_for(us.activity_frequency if us else None, family),
            # The VDOT every running pace comes from — the same estimate the
            # race plan reads (running_fitness.py).
            running_fitness=_get_running_fitness(db, user_id, us, today),
        )
        if goal.goal_type == "fitness":
            # Load as the dashboard shows it: the same TSS-by-day and CTL/ATL
            # the coaching signal reads, so the plan builds from the fitness
            # number the person sees.
            from app.api.coaching.helpers import _build_tss_by_date, _ctl_atl_today
            ctl, atl, _ = _ctl_atl_today(_build_tss_by_date(db, user_id, us), today)
            # A fitness goal may train several sports; each looks up its own
            # answer in the whole map (fitness.py).
            vdot, workout_dicts = generate_fitness_plan(
                goal=goal, ctl=ctl, atl=atl, anchor=_goal_anchor(goal),
                activity_frequencies=(us.activity_frequency if us else None) or {}, **common)
        else:
            vdot, workout_dicts = generate_training_plan(
                goal=goal, max_hr=_effective_max_hr(us), **common)

    # Honor the goal's persisted schedule_tests flag so the background path
    # produces identical output to the explicit /plan/generate endpoint.
    if goal.schedule_tests:
        workout_dicts = _inject_field_tests(workout_dicts, goal, today)

    # Per-regeneration salt: changes on every plan generate, so the strength
    # exercise picks + stretch picks rotate even when no other inputs change.
    # Format: epoch seconds. Doesn't need to be unique across users — the salt
    # is concatenated with sport/split/muscle/week so collisions are harmless.
    regen_salt = str(int(time.time()))
    workout_dicts = _inject_strength_workouts(db, goal, user_id, workout_dicts,
                                              regen_salt=regen_salt)
    workout_dicts = _inject_stretch_flows(db, workout_dicts, user_id,
                                          regen_salt=regen_salt)

    if goal.goal_type == "fitness":
        # A goal with no date gets twelve weeks of strength (strength_plan's
        # no-race span); a rolling plan is four weeks of everything, or the
        # calendar would show strength running on alone past the endurance.
        end = fitness_horizon_end(today)
        workout_dicts = [w for w in workout_dicts if w["scheduled_date"] <= end]
    return vdot, workout_dicts


# ─────────────────────────────────────────
# Background refresh (called from watcher / goal creation)
# ─────────────────────────────────────────

def _regenerate_future_workouts(db: Session, goal: TrainingGoal, user_id: int) -> None:
    """Replace all non-completed future workouts with a freshly generated plan."""
    today = date.today()
    vdot, workout_dicts = _plan_workout_dicts(db, goal, user_id, today)

    # Concurrency: training_plans.goal_id has a UNIQUE constraint, but two
    # callers can race on the SELECT-then-INSERT branch — the explicit
    # POST /plan/generate endpoint runs synchronously while
    # refresh_plans_for_user fires from a background thread on the same
    # goal. If both see "no plan exists" and INSERT, the second one violates
    # the unique key.
    #
    # Resolve by catching IntegrityError → rolling back the partial flush →
    # re-querying for the now-existing plan (committed by the winner) and
    # mutating it instead of inserting.
    existing_plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()

    if not existing_plan:
        try:
            plan = TrainingPlan(
                goal_id=goal.id,
                user_id=user_id,
                sport=goal.event_sport or "running",
                vdot=round(vdot, 1) if vdot else None,
            )
            db.add(plan)
            db.flush()
        except IntegrityError:
            db.rollback()
            existing_plan = (
                db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
            )
            if existing_plan is None:
                # Re-raise: something else (not a concurrent insert) is wrong.
                raise

    if existing_plan:
        existing_plan.vdot = round(vdot, 1) if vdot else None
        existing_plan.generated_at = datetime.now(timezone.utc)
        plan = existing_plan

    _replace_workouts(db, plan, workout_dicts, today)
    db.commit()


def _planned_now(today: date):
    """Goals whose plan is live: future events, and fitness goals (rolling,
    so always)."""
    return or_(
        and_(TrainingGoal.goal_type == "event", TrainingGoal.event_date > today),
        TrainingGoal.goal_type == "fitness",
    )


def refresh_plans_for_user(user_id: int) -> None:
    """
    Lazily regenerate future workouts for all active planned goals.
    Skips goals whose plan was generated within the last 6 hours.
    Designed to be called from background threads — never raises.
    """
    db = SessionLocal()
    try:
        today = date.today()
        goals = (
            db.query(TrainingGoal)
            .filter(
                TrainingGoal.user_id == user_id,
                TrainingGoal.is_active.is_(True),
                _planned_now(today),
            )
            .all()
        )
        for goal in goals:
            plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
            if plan and plan.generated_at:
                gen_at = plan.generated_at
                if gen_at.tzinfo is None:
                    gen_at = gen_at.replace(tzinfo=timezone.utc)
                if datetime.now(timezone.utc) - gen_at < timedelta(hours=6):
                    continue
            _regenerate_future_workouts(db, goal, user_id)
            log.info("Refreshed training plan for goal %d (user %d)", goal.id, user_id)
    except Exception:
        log.exception("refresh_plans_for_user failed for user %d", user_id)
    finally:
        db.close()


def refresh_plans_for_user_force(user_id: int) -> None:
    """
    Same as refresh_plans_for_user but bypasses the 6-hour skip — used when
    the user changes settings (e.g. units) so workout notes regenerate
    immediately rather than waiting for the next lazy refresh window.
    """
    db = SessionLocal()
    try:
        today = date.today()
        goals = (
            db.query(TrainingGoal)
            .filter(
                TrainingGoal.user_id == user_id,
                TrainingGoal.is_active.is_(True),
                _planned_now(today),
            )
            .all()
        )
        for goal in goals:
            _regenerate_future_workouts(db, goal, user_id)
            log.info("Force-refreshed training plan for goal %d (user %d)", goal.id, user_id)
    except Exception:
        log.exception("refresh_plans_for_user_force failed for user %d", user_id)
    finally:
        db.close()


@router.get("/goals/{goal_id}/plan", response_model=TrainingPlanOut)
def get_plan(
    goal_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Return the training plan for a goal (404 if none generated yet)."""
    _get_goal_or_404(db, goal_id, user)

    plan = db.query(TrainingPlan).filter_by(goal_id=goal_id, user_id=user.id).first()
    if plan is None:
        raise HTTPException(status_code=404, detail="No plan generated for this goal")

    workouts = (
        db.query(PlannedWorkout)
        .filter_by(plan_id=plan.id)
        .order_by(PlannedWorkout.scheduled_date)
        .all()
    )

    plan_out = TrainingPlanOut.model_validate(plan)
    plan_out.workouts = [PlannedWorkoutOut.model_validate(w) for w in workouts]
    return plan_out


# ─────────────────────────────────────────
# Upcoming workouts (for dashboard widget)
# ─────────────────────────────────────────

@router.get("/workouts/upcoming", response_model=list[PlannedWorkoutOut])
def get_upcoming_workouts(
    days: int = 14,
    limit: int = Query(
        10,
        ge=1,
        le=500,
        description="Most workouts to return. The default suits the dashboard "
                    "widget this was written for; a caller mirroring the plan "
                    "locally wants every workout inside `days` and should say so.",
    ),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Return planned workouts for the next N days across all active event goals.

    `limit` defaults to 10 rather than to `days`-worth because that is what this
    endpoint has always returned, and the two browser callers are widgets that
    show a handful. It used to be a bare `.limit(10)` that silently contradicted
    `days`: asking for a fortnight and being handed ten workouts looks like a
    plan with ten workouts in it, which is exactly how the phone's offline
    calendar came to be built from a truncated copy of the plan.
    """
    today = date.today()
    cutoff = today + timedelta(days=days)

    workouts = (
        db.query(PlannedWorkout)
        .filter(
            PlannedWorkout.user_id == user.id,
            PlannedWorkout.scheduled_date >= today,
            PlannedWorkout.scheduled_date <= cutoff,
        )
        .order_by(PlannedWorkout.scheduled_date)
        .limit(limit)
        .all()
    )
    return [PlannedWorkoutOut.model_validate(w) for w in workouts]


# ─────────────────────────────────────────
# Workout CRUD
# ─────────────────────────────────────────

@router.post("/plan/workouts", response_model=PlannedWorkoutOut, status_code=201)
def create_planned_workout(
    body: PlannedWorkoutCreate,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Add a workout to the calendar that no plan prescribed.

    Deliberately not attached to a plan. Both paths `generate_plan` deletes
    through filter on `plan_id`, so a null one is what stops a regeneration
    throwing away the session somebody added by hand — no special case, and
    nothing to remember when that code next changes.

    Every field is stamped by the server as it is written (app.sync.store), so
    a later edit from anywhere merges against it field by field.
    """
    workout = PlannedWorkout(
        plan_id=None,
        user_id=user.id,
        scheduled_date=body.scheduled_date,
        sport=body.sport,
        workout_type=body.workout_type,
        title=body.title,
        description=body.description,
        duration_minutes=body.duration_minutes,
        distance_meters=body.distance_meters,
        steps=body.steps or [],
        origin="user",
    )
    db.add(workout)
    db.commit()
    db.refresh(workout)
    return PlannedWorkoutOut.model_validate(workout)


@router.patch("/plan/workouts/{workout_id}", response_model=PlannedWorkoutOut)
def update_workout(
    workout_id: int,
    body: PlannedWorkoutUpdate,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Patch a planned workout.

    A live edit always lands: the server stamps each written field strictly
    after whatever stamp it already carries (app.sync.hlc.Clock.stamp_after),
    because somebody looking at the current value and changing it now is the
    newest thing there is. Phones merge against those stamps field by field.
    """
    workout = db.query(PlannedWorkout).filter_by(id=workout_id, user_id=user.id).first()
    if workout is None:
        raise HTTPException(status_code=404, detail="Workout not found")

    # exclude_unset, not exclude_none: the two differ exactly when a caller
    # wants to clear a field, and with exclude_none a description can be set
    # but never removed.
    updates = body.model_dump(exclude_unset=True)
    # A date changed by hand is a move, whichever form made it (the web's
    # editor, today). Recorded here rather than trusted from the client, so a
    # regeneration keeps the workout on the day it was moved to.
    if "scheduled_date" in updates and updates["scheduled_date"] != workout.scheduled_date:
        workout.moved_by_user = True
    for field, value in updates.items():
        setattr(workout, field, value)

    db.commit()
    db.refresh(workout)
    return PlannedWorkoutOut.model_validate(workout)


@router.delete("/plan/workouts/{workout_id}", status_code=204)
def delete_workout(
    workout_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    workout = db.query(PlannedWorkout).filter_by(id=workout_id, user_id=user.id).first()
    if workout is None:
        raise HTTPException(status_code=404, detail="Workout not found")
    if workout.watch_filename and workout.watch_deleted_at is None:
        _upsert_pending_delete(db, user.id, workout.watch_filename)
    db.delete(workout)
    db.commit()
