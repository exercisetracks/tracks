# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Strength progress + history analytics.

Read-only trend endpoints over recorded StrengthSets and the per-exercise
UserExerciseStrength state, plus a manual e1RM override:
  GET /strength/progress             — e1RM + volume trends per exercise
  GET /strength/history              — recent strength sets with activity context
  PUT /strength/exercises/{name}/1rm — manual e1RM / calibration override
  GET /strength/weekly-summary       — volume per muscle group this week

NOTE: PUT /exercises/{name}/1rm is a dynamic path under /exercises. The static
GET /exercises (library.py) must register before it — enforced by router
include order in the package __init__.
"""
from __future__ import annotations

from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.local_day import activity_local_date, local_day_start
from app.database import get_db
from app.models.activity import Activity, StrengthSet, User
from app.models.user_settings import UserSettings
from app.models.strength import ExerciseLibrary, UserExerciseStrength

from .schemas import (
    OneRMOverride, ProgressEntry, SetHistoryEntry, WeeklySummaryEntry,
)

router = APIRouter()


@router.get("/progress", response_model=list[ProgressEntry])
def get_progress(db: Session = Depends(get_db),
                 user: User = Depends(require_auth)):
    rows = (
        db.query(UserExerciseStrength)
        .filter_by(user_id=user.id)
        .order_by(UserExerciseStrength.exercise_name)
        .all()
    )
    return [
        ProgressEntry(
            exercise_name=r.exercise_name,
            estimated_1rm_kg=r.estimated_1rm_kg,
            last_weight_kg=r.last_weight_kg,
            last_reps=r.last_reps,
            last_session_volume_kg=r.last_session_volume_kg,
            sessions_completed=r.sessions_completed,
            progression_stage=r.progression_stage,
            last_updated=r.last_updated,
        )
        for r in rows
    ]


@router.get("/history", response_model=list[SetHistoryEntry])
def get_history(days: int = 90,
                db: Session = Depends(get_db),
                user: User = Depends(require_auth)):
    # Each session on its day in the account's zone (calculators/local_day.py),
    # as the phone's LocalLibrary.strengthHistory dates them.
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    tz = us.timezone if us else None
    cutoff = date.today() - timedelta(days=max(1, min(days, 365)))
    rows = (
        db.query(StrengthSet, Activity)
        .join(Activity, Activity.id == StrengthSet.activity_id)
        .filter(
            Activity.user_id == user.id,
            Activity.started_at >= local_day_start(cutoff, tz),
            StrengthSet.set_type == "active",
        )
        .order_by(Activity.started_at.desc(), StrengthSet.set_number)
        .limit(500)
        .all()
    )
    return [
        SetHistoryEntry(
            activity_id=act.id,
            activity_date=activity_local_date(act.started_at, tz) if act.started_at else date.today(),
            sport=act.sport or "strength_training",
            set_number=ss.set_number,
            exercise_name=ss.exercise_name,
            exercise_category=ss.exercise_category,
            weight_kg=ss.weight_kg,
            repetitions=ss.repetitions,
            duration_seconds=ss.duration_seconds,
        )
        for ss, act in rows
    ]


@router.put("/exercises/{exercise_name}/1rm")
def override_1rm(exercise_name: str, body: OneRMOverride,
                 db: Session = Depends(get_db),
                 user: User = Depends(require_auth)):
    if body.estimated_1rm_kg <= 0:
        raise HTTPException(status_code=422, detail="1RM must be positive")

    rec = db.query(UserExerciseStrength).filter_by(
        user_id=user.id, exercise_name=exercise_name
    ).first()

    if rec is None:
        rec = UserExerciseStrength(
            user_id=user.id,
            exercise_name=exercise_name,
            sessions_completed=0,
            progression_stage="linear",
        )
        db.add(rec)

    rec.estimated_1rm_kg = body.estimated_1rm_kg
    if body.last_weight_kg is not None:
        rec.last_weight_kg = body.last_weight_kg
    if body.last_reps is not None:
        rec.last_reps = body.last_reps

    db.commit()
    return {"status": "ok", "exercise_name": exercise_name,
            "estimated_1rm_kg": rec.estimated_1rm_kg}


@router.get("/weekly-summary", response_model=list[WeeklySummaryEntry])
def weekly_summary(db: Session = Depends(get_db),
                   user: User = Depends(require_auth)):
    """Aggregate sets and volume by primary muscle group for the current week."""
    week_start = date.today() - timedelta(days=date.today().weekday())

    rows = (
        db.query(StrengthSet, Activity)
        .join(Activity, Activity.id == StrengthSet.activity_id)
        .filter(
            Activity.user_id == user.id,
            Activity.started_at >= week_start,
            StrengthSet.set_type == "active",
            StrengthSet.repetitions.isnot(None),
        )
        .all()
    )

    # Map exercise name → primary muscles via the library, then tally each set
    # against every muscle that exercise primarily works.
    lib_rows = db.query(ExerciseLibrary.name, ExerciseLibrary.primary_muscles).all()
    ex_muscles: dict[str, list[str]] = {r.name: (r.primary_muscles or []) for r in lib_rows}

    tally: dict[str, dict[str, float]] = {}
    for ss, _act in rows:
        muscles = ex_muscles.get(ss.exercise_name or "", [])
        for muscle in muscles:
            if muscle not in tally:
                tally[muscle] = {"sets": 0, "volume": 0.0}
            tally[muscle]["sets"] += 1
            if ss.weight_kg and ss.repetitions:
                tally[muscle]["volume"] += ss.weight_kg * ss.repetitions

    return [
        WeeklySummaryEntry(
            muscle_group=m,
            sets_this_week=int(v["sets"]),
            volume_kg=round(v["volume"], 1),
        )
        for m, v in sorted(tally.items())
    ]
