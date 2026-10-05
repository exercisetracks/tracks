# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Exercise library listing.

GET /strength/exercises — the full built-in exercise library plus the user's
custom exercises, each annotated with the user's preference status and a
per-request animation_state resolved from their primary device.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.garmin_animations import (
    build_confirmation_lookup, state_from_lookup,
)
from app.database import get_db
from app.models.activity import User
from app.models.strength import (
    ExerciseLibrary, UserCustomExercise, UserExercisePreference,
)

from .schemas import ExerciseOut

router = APIRouter()


@router.get("/exercises", response_model=list[ExerciseOut])
def list_exercises(db: Session = Depends(get_db),
                   user: User = Depends(require_auth)):
    """Return library exercises + user's custom exercises, each annotated with
    the user's preference status (preferred / excluded / None for neutral)."""
    prefs = {
        p.exercise_name: p.preference
        for p in db.query(UserExercisePreference).filter_by(user_id=user.id).all()
    }
    # One bulk lookup of all confirmations for the user's primary device, then
    # resolve each entry's animation_state from memory (avoids N queries).
    confirm_lookup = build_confirmation_lookup(
        db, user.primary_device_product_id, user.id,
    )

    library = db.query(ExerciseLibrary).order_by(ExerciseLibrary.name).all()
    result: list[ExerciseOut] = []
    for ex in library:
        out = ExerciseOut.model_validate(ex)
        out.preference = prefs.get(ex.name)
        out.animation_state = state_from_lookup(
            ex.garmin_category, ex.garmin_subtype, confirm_lookup,
        )
        result.append(out)

    customs = (
        db.query(UserCustomExercise)
        .filter_by(user_id=user.id)
        .order_by(UserCustomExercise.name)
        .all()
    )
    for ce in customs:
        result.append(ExerciseOut(
            name=ce.name,
            garmin_category=ce.garmin_category,
            garmin_subtype=ce.garmin_subtype,
            has_animation=bool(ce.has_animation),
            animation_state=state_from_lookup(
                ce.garmin_category, ce.garmin_subtype, confirm_lookup,
            ),
            primary_muscles=ce.primary_muscles or [],
            secondary_muscles=ce.secondary_muscles or [],
            equipment=ce.equipment or ["bodyweight"],
            movement_pattern=ce.movement_pattern,
            sport_relevance={},
            difficulty=ce.difficulty,
            is_compound=ce.is_compound,
            description=ce.description,
            instructions=ce.instructions,
            cues=ce.cues or [],
            viewer_slug=ce.viewer_slug,
            preference=prefs.get(ce.name),
            is_custom=True,
            custom_id=ce.id,
            default_sets=ce.default_sets,
            default_reps=ce.default_reps,
        ))

    return result
