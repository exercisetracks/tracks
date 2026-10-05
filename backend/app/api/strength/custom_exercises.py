# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Custom-exercise CRUD.

List / create / update / delete user-defined exercises:
  GET    /strength/custom-exercises
  POST   /strength/custom-exercises
  PUT    /strength/custom-exercises/{id}
  DELETE /strength/custom-exercises/{id}

On create/update, has_animation is derived from the chosen Garmin category +
subtype so the flag stays in sync with the watch animation manifest without a
separate UI control.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.garmin_animations import is_animatable
from app.database import get_db
from app.models.activity import User
from app.models.strength import UserCustomExercise

from .schemas import VALID_EQUIPMENT

router = APIRouter()

# Movement patterns and muscle names accepted for a custom exercise. Kept here
# (not in schemas) because only custom-exercise validation uses them.
_VALID_PATTERNS = {
    "push", "pull", "hinge", "squat", "carry", "rotation",
    "isometric", "plyometric", "isolation",
}
_VALID_MUSCLES = {
    "quads", "hamstrings", "glutes", "calves", "hip_flexors", "lower_back",
    "upper_back", "lats", "chest", "shoulders", "front_delts", "side_delts",
    "rear_delts", "biceps", "triceps", "core", "forearms", "obliques",
    "hip_abductors", "hip_adductors", "hip_external_rotators",
}


class CustomExerciseBody(BaseModel):
    name: str
    primary_muscles: list[str] = []
    secondary_muscles: list[str] = []
    equipment: list[str] = ["bodyweight"]
    movement_pattern: str | None = None
    is_compound: bool = True
    difficulty: int = 1
    description: str | None = None
    instructions: str | None = None
    default_sets: int = 3
    default_reps: int = 10
    garmin_category: str | None = None
    garmin_subtype: int | None = None
    viewer_slug: str | None = None


def _resolve_animation_flag(cat: str | None, sub: int | None) -> bool:
    """True iff (cat, sub) is in Garmin's animation manifest. Auto-populates
    has_animation on custom exercises as they're created or edited, keeping the
    flag in sync with the user's chosen garmin_category + garmin_subtype."""
    if not cat or sub is None:
        return False
    # Map category string → int via the FIT enum table embedded in the encoder
    # module (avoids a circular dep on a separate lookup table).
    from app.calculators.fit_workout import _CATEGORY_INT
    cat_int = _CATEGORY_INT.get(cat.lower())
    return is_animatable(cat_int, sub)


def _validate_custom(body: CustomExerciseBody) -> None:
    bad_muscles = set(body.primary_muscles + body.secondary_muscles) - _VALID_MUSCLES
    if bad_muscles:
        raise HTTPException(status_code=422, detail=f"Unknown muscles: {sorted(bad_muscles)}")
    bad_equip = set(body.equipment) - VALID_EQUIPMENT
    if bad_equip:
        raise HTTPException(status_code=422, detail=f"Unknown equipment: {sorted(bad_equip)}")
    if body.movement_pattern and body.movement_pattern not in _VALID_PATTERNS:
        raise HTTPException(status_code=422,
                            detail=f"Unknown movement_pattern: {body.movement_pattern}")
    if not (1 <= body.difficulty <= 3):
        raise HTTPException(status_code=422, detail="difficulty must be 1–3")


@router.get("/custom-exercises")
def list_custom_exercises(db: Session = Depends(get_db),
                          user: User = Depends(require_auth)):
    rows = (
        db.query(UserCustomExercise)
        .filter_by(user_id=user.id)
        .order_by(UserCustomExercise.name)
        .all()
    )
    return [
        {
            "id": r.id, "name": r.name,
            "primary_muscles": r.primary_muscles, "secondary_muscles": r.secondary_muscles,
            "equipment": r.equipment, "movement_pattern": r.movement_pattern,
            "is_compound": r.is_compound, "difficulty": r.difficulty,
            "description": r.description, "instructions": r.instructions,
            "default_sets": r.default_sets, "default_reps": r.default_reps,
            "garmin_category": r.garmin_category, "garmin_subtype": r.garmin_subtype,
            "viewer_slug": r.viewer_slug,
            "has_animation": bool(r.has_animation),
        }
        for r in rows
    ]


@router.post("/custom-exercises", status_code=201)
def create_custom_exercise(body: CustomExerciseBody,
                           db: Session = Depends(get_db),
                           user: User = Depends(require_auth)):
    _validate_custom(body)
    existing = db.query(UserCustomExercise).filter_by(
        user_id=user.id, name=body.name
    ).first()
    if existing:
        raise HTTPException(status_code=409, detail="Exercise with this name already exists")
    row = UserCustomExercise(
        user_id=user.id,
        name=body.name,
        primary_muscles=body.primary_muscles,
        secondary_muscles=body.secondary_muscles,
        equipment=body.equipment,
        movement_pattern=body.movement_pattern,
        is_compound=body.is_compound,
        difficulty=body.difficulty,
        description=body.description,
        instructions=body.instructions,
        default_sets=body.default_sets,
        default_reps=body.default_reps,
        garmin_category=body.garmin_category,
        garmin_subtype=body.garmin_subtype,
        viewer_slug=body.viewer_slug,
        has_animation=_resolve_animation_flag(body.garmin_category, body.garmin_subtype),
    )
    db.add(row)
    db.commit()
    db.refresh(row)
    return {"id": row.id, "name": row.name, "has_animation": row.has_animation}


@router.put("/custom-exercises/{exercise_id}")
def update_custom_exercise(exercise_id: int,
                           body: CustomExerciseBody,
                           db: Session = Depends(get_db),
                           user: User = Depends(require_auth)):
    row = db.query(UserCustomExercise).filter_by(
        id=exercise_id, user_id=user.id
    ).first()
    if not row:
        raise HTTPException(status_code=404, detail="Custom exercise not found")
    _validate_custom(body)
    row.name              = body.name
    row.primary_muscles   = body.primary_muscles
    row.secondary_muscles = body.secondary_muscles
    row.equipment         = body.equipment
    row.movement_pattern  = body.movement_pattern
    row.is_compound       = body.is_compound
    row.difficulty        = body.difficulty
    row.description        = body.description
    row.instructions      = body.instructions
    row.default_sets      = body.default_sets
    row.default_reps      = body.default_reps
    row.garmin_category   = body.garmin_category
    row.garmin_subtype    = body.garmin_subtype
    row.viewer_slug       = body.viewer_slug
    row.has_animation     = _resolve_animation_flag(body.garmin_category, body.garmin_subtype)
    db.commit()
    return {"id": row.id, "name": row.name, "has_animation": row.has_animation}


@router.delete("/custom-exercises/{exercise_id}", status_code=204)
def delete_custom_exercise(exercise_id: int,
                           db: Session = Depends(get_db),
                           user: User = Depends(require_auth)):
    row = db.query(UserCustomExercise).filter_by(
        id=exercise_id, user_id=user.id
    ).first()
    if not row:
        raise HTTPException(status_code=404, detail="Custom exercise not found")
    db.delete(row)
    db.commit()
