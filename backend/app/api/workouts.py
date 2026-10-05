# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Workout builder API — CRUD, progression calculation, session logging."""
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select, delete, func
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.workout import UserWorkout, UserWorkoutExercise, UserWorkoutSession
from app.models.strength import UserExerciseStrength
from app.models.training_plan import PlannedWorkout
from app.sync.lists import write_list
from app.schemas.workout import (
    WorkoutIn, WorkoutUpdate, WorkoutOut, WorkoutExerciseOut,
    ProgressionRequest, ProgressionResponse,
    WorkoutSessionIn, WorkoutSessionOut,
)

router = APIRouter(prefix="/workouts", tags=["workouts"])

VALID_TAGS = {
    "upper_body", "lower_body", "core", "push", "pull",
    "aerobic", "full_body", "mobility",
}


_EXERCISE_FIELDS = ("exercise_name", "exercise_source", "target_reps",
                    "target_sets", "rir_target", "rest_seconds", "weight_method",
                    "weight_value", "notes",
                    # Blocks — see spec/sync.yaml, workout_exercise.
                    "item_kind", "group_uid", "group_kind", "group_rounds",
                    "group_rest_seconds")


def _exercise_out(e: UserWorkoutExercise, position: int) -> WorkoutExerciseOut:
    return WorkoutExerciseOut(
        id=e.id, workout_id=e.workout_id, created_at=e.created_at, order_index=position,
        **{f: getattr(e, f) for f in _EXERCISE_FIELDS},
    )


def _exercise_items(exercises) -> list[dict]:
    """Submitted exercises in list order, positions honoured when given."""
    ordered = sorted(enumerate(exercises), key=lambda p: (p[1].order_index or p[0], p[0]))
    items = []
    for _, ex in ordered:
        item = {f: getattr(ex, f) for f in _EXERCISE_FIELDS}
        item["weight_value"] = ex.weight_value or 0.75
        items.append(item)
    return items


def _workout_to_out(workout: UserWorkout, db: Session) -> WorkoutOut:
    ex_rows = db.execute(
        select(UserWorkoutExercise)
        .where(UserWorkoutExercise.workout_id == workout.id)
        .order_by(UserWorkoutExercise.order_index)
    ).scalars().all()
    return WorkoutOut(
        id=workout.id,
        user_id=workout.user_id,
        name=workout.name,
        description=workout.description,
        tags=workout.tags or [],
        include_in_plan=workout.include_in_plan if workout.include_in_plan is not None else True,
        sync_to_watch=workout.sync_to_watch if workout.sync_to_watch is not None else False,
        exercises=[_exercise_out(e, i) for i, e in enumerate(ex_rows)],
        created_at=workout.created_at,
        updated_at=workout.updated_at,
    )


@router.get("", response_model=list[WorkoutOut])
def list_workouts(
    tag: Optional[str] = None,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    q = select(UserWorkout).where(UserWorkout.user_id == user.id).order_by(UserWorkout.updated_at.desc())
    rows = db.execute(q).scalars().all()
    result = [_workout_to_out(w, db) for w in rows]
    if tag and tag in VALID_TAGS:
        result = [w for w in result if tag in w.tags]
    return result


@router.get("/{workout_id}", response_model=WorkoutOut)
def get_workout(
    workout_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    w = db.execute(
        select(UserWorkout).where(UserWorkout.id == workout_id, UserWorkout.user_id == user.id)
    ).scalar_one_or_none()
    if not w:
        raise HTTPException(404, "Workout not found")
    return _workout_to_out(w, db)


@router.post("", response_model=WorkoutOut, status_code=201)
def create_workout(
    body: WorkoutIn,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    for tag in body.tags:
        if tag not in VALID_TAGS:
            raise HTTPException(400, f"Invalid tag: {tag}")

    existing = db.execute(
        select(UserWorkout).where(UserWorkout.user_id == user.id, UserWorkout.name == body.name)
    ).scalar_one_or_none()
    if existing:
        raise HTTPException(409, "Workout with this name already exists")

    w = UserWorkout(user_id=user.id, name=body.name, description=body.description,
                    tags=body.tags, include_in_plan=body.include_in_plan,
                    sync_to_watch=body.sync_to_watch)
    db.add(w)
    db.flush()

    write_list(db, [], _exercise_items(body.exercises),
               lambda: UserWorkoutExercise(workout_id=w.id, user_id=user.id))

    db.commit()
    db.refresh(w)
    return _workout_to_out(w, db)


@router.put("/{workout_id}", response_model=WorkoutOut)
def update_workout(
    workout_id: int,
    body: WorkoutUpdate,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    w = db.execute(
        select(UserWorkout).where(UserWorkout.id == workout_id, UserWorkout.user_id == user.id)
    ).scalar_one_or_none()
    if not w:
        raise HTTPException(404, "Workout not found")

    if body.name is not None:
        w.name = body.name
    if body.description is not None:
        w.description = body.description
    if body.tags is not None:
        for tag in body.tags:
            if tag not in VALID_TAGS:
                raise HTTPException(400, f"Invalid tag: {tag}")
        w.tags = body.tags
    if body.include_in_plan is not None:
        w.include_in_plan = body.include_in_plan
    if body.sync_to_watch is not None:
        # Enforce Garmin's 25-workout limit
        if body.sync_to_watch:
            current_sync_count = db.execute(
                select(func.count()).select_from(UserWorkout).where(
                    UserWorkout.user_id == user.id,
                    UserWorkout.sync_to_watch == True,
                    UserWorkout.id != workout_id,
                )
            ).scalar() or 0
            if current_sync_count >= 25:
                raise HTTPException(400, "Maximum 25 workouts can be synced to Garmin watch at once")
        w.sync_to_watch = body.sync_to_watch

    if body.exercises is not None:
        existing = db.execute(
            select(UserWorkoutExercise).where(UserWorkoutExercise.workout_id == w.id)
            .order_by(UserWorkoutExercise.order_index)
        ).scalars().all()
        write_list(db, list(existing), _exercise_items(body.exercises),
                   lambda: UserWorkoutExercise(workout_id=w.id, user_id=user.id))

    db.commit()
    db.refresh(w)
    return _workout_to_out(w, db)


@router.delete("/{workout_id}", status_code=204)
def delete_workout(
    workout_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    w = db.execute(
        select(UserWorkout).where(UserWorkout.id == workout_id, UserWorkout.user_id == user.id)
    ).scalar_one_or_none()
    if not w:
        raise HTTPException(404, "Workout not found")
    db.delete(w)
    db.commit()


# ═══════════════════════════════════════════════════════════════════════════════
#  PROGRESSIVE OVERLOAD CALCULATION
# ═══════════════════════════════════════════════════════════════════════════════

def _epley_1rm(weight_kg: float, reps: int) -> float:
    if reps <= 1:
        return weight_kg
    return weight_kg * (1 + reps / 30)


def _inverse_epley(e1rm: float, target_reps: int) -> float:
    if target_reps <= 0:
        return e1rm
    return e1rm / (1 + target_reps / 30)


_RPE_TO_PCT = {10: 1.0, 9.5: 0.97, 9: 0.95, 8.5: 0.92, 8: 0.90,
                7.5: 0.87, 7: 0.83, 6.5: 0.80, 6: 0.77, 5: 0.70}

_PROGRESSION_INCREMENT = {"novice": 2.5, "intermediate": 1.25, "advanced": 0.5}

_STAGE_TO_LEVEL = {"linear": "novice", "weekly_undulating": "intermediate", "dup": "advanced"}


def _get_training_level(sessions_completed: int) -> str:
    if sessions_completed < 20:
        return "novice"
    elif sessions_completed < 100:
        return "intermediate"
    return "advanced"


@router.post("/progression", response_model=ProgressionResponse)
def calculate_progression(
    body: ProgressionRequest,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    row = db.execute(
        select(UserExerciseStrength).where(
            UserExerciseStrength.user_id == user.id,
            UserExerciseStrength.exercise_name == body.exercise_name,
        )
    ).scalar_one_or_none()

    e1rm = row.estimated_1rm_kg if row else None
    stage = row.progression_stage if row else "linear"
    sessions = row.sessions_completed if row else 0
    level = _STAGE_TO_LEVEL.get(stage, _get_training_level(sessions))

    suggested_kg = None
    ready = False
    increment = 0.0

    if body.weight_method == "fixed" and body.weight_value:
        suggested_kg = body.weight_value
    elif body.weight_method == "rpe" and body.weight_value and e1rm:
        pct = _RPE_TO_PCT.get(round(body.weight_value), 0.80)
        suggested_kg = e1rm * pct
    elif e1rm:
        actual_reps = body.target_reps + body.rir_target
        pct = body.weight_value if body.weight_value else 0.75
        working_1rm = e1rm * pct
        suggested_kg = _inverse_epley(working_1rm, actual_reps)
        suggested_kg = round(suggested_kg, 1)

        if sessions > 0:
            increment = _PROGRESSION_INCREMENT.get(level, 1.25)
            if level == "novice":
                ready = sessions % 2 == 0
            elif level == "intermediate":
                ready = sessions % 4 == 0
            else:
                ready = sessions % 8 == 0

    warmups = []
    if suggested_kg:
        for pct_label, pct_val in [("40%", 0.40), ("60%", 0.60), ("80%", 0.80)]:
            wu_kg = round(suggested_kg * pct_val, 1)
            if wu_kg > 0:
                warmups.append({"label": pct_label, "weight_kg": wu_kg, "reps": 5})

    return ProgressionResponse(
        exercise_name=body.exercise_name,
        suggested_weight_kg=suggested_kg,
        suggested_weight_lb=round(suggested_kg * 2.20462, 1) if suggested_kg else None,
        estimated_1rm_kg=e1rm,
        progression_stage=stage,
        sessions_completed=sessions,
        ready_to_progress=ready,
        recommended_increment_kg=increment if ready else 0.0,
        warmup_sets=warmups,
    )


# ═══════════════════════════════════════════════════════════════════════════════
#  WORKOUT SESSION LOGGING
# ═══════════════════════════════════════════════════════════════════════════════

@router.post("/sessions", response_model=WorkoutSessionOut, status_code=201)
def log_workout_session(
    body: WorkoutSessionIn,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    # When completing a generated plan session (guided runner), validate the
    # planned workout and reject a duplicate log.
    planned = None
    if body.planned_workout_id is not None:
        planned = db.get(PlannedWorkout, body.planned_workout_id)
        if planned is None or planned.user_id != user.id:
            raise HTTPException(404, "Planned workout not found")
        existing = db.execute(
            select(UserWorkoutSession.id).where(
                UserWorkoutSession.planned_workout_id == body.planned_workout_id,
            )
        ).first()
        if existing:
            raise HTTPException(409, "This workout has already been logged")

    total_vol = 0.0
    session_data = []

    for ex in body.exercises:
        ex_data = {"exercise_name": ex.exercise_name, "sets": []}
        # Aggregate the exercise's sets FIRST, then update progression state once
        # per exercise per session. (Previously the update ran inside the set
        # loop, inflating sessions_completed by the number of sets logged.)
        best_1rm = None
        ex_volume = 0.0
        last_set = None
        for s in ex.sets:
            vol = s.weight_kg * s.reps
            total_vol += vol
            ex_volume += vol
            ex_data["sets"].append({
                "weight_kg": s.weight_kg, "reps": s.reps, "rpe": s.rpe,
                "volume": round(vol, 1),
            })
            last_set = s
            if 1 <= s.reps <= 10:
                e1rm = _epley_1rm(s.weight_kg, s.reps)
                if best_1rm is None or e1rm > best_1rm:
                    best_1rm = e1rm
        session_data.append(ex_data)

        if last_set is None:
            continue  # exercise logged with no sets — nothing to progress

        row = db.execute(
            select(UserExerciseStrength).where(
                UserExerciseStrength.user_id == user.id,
                UserExerciseStrength.exercise_name == ex.exercise_name,
            )
        ).scalar_one_or_none()
        if row:
            if best_1rm is not None:
                base = row.estimated_1rm_kg or best_1rm
                row.estimated_1rm_kg = round(base * 0.7 + best_1rm * 0.3, 1)
            row.last_weight_kg = last_set.weight_kg
            row.last_reps = last_set.reps
            row.sessions_completed = (row.sessions_completed or 0) + 1
            row.last_session_volume_kg = round(ex_volume, 1)
        else:
            row = UserExerciseStrength(
                user_id=user.id, exercise_name=ex.exercise_name,
                estimated_1rm_kg=round(best_1rm, 1) if best_1rm else None,
                last_weight_kg=last_set.weight_kg,
                last_reps=last_set.reps, sessions_completed=1,
                last_session_volume_kg=round(ex_volume, 1),
            )
            db.add(row)

        # Advance progression stage on the same 20/100 thresholds as the
        # FIT-import path (matching._update_strength_fingerprint).
        total = row.sessions_completed or 0
        row.progression_stage = (
            "dup" if total >= 100 else
            "weekly_undulating" if total >= 20 else
            "linear"
        )

    sess = UserWorkoutSession(
        workout_id=body.workout_id, user_id=user.id,
        planned_workout_id=body.planned_workout_id,
        session_data=session_data, total_volume_kg=round(total_vol, 1),
        session_rpe=body.session_rpe, notes=body.notes,
    )
    db.add(sess)

    # Mark the generated plan workout complete so the calendar reflects it.
    if planned is not None:
        planned.is_complete = True

    db.commit()
    db.refresh(sess)
    return WorkoutSessionOut.model_validate(sess)


@router.get("/sessions", response_model=list[WorkoutSessionOut])
def list_sessions(
    workout_id: Optional[int] = None,
    limit: int = 20,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    q = select(UserWorkoutSession).where(UserWorkoutSession.user_id == user.id)
    if workout_id:
        q = q.where(UserWorkoutSession.workout_id == workout_id)
    q = q.order_by(UserWorkoutSession.completed_at.desc()).limit(limit)
    return [WorkoutSessionOut.model_validate(s) for s in db.execute(q).scalars().all()]
