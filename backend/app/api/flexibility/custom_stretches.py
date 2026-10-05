# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""User-defined custom stretch CRUD.

Custom stretches extend the built-in StretchLibrary with the user's own poses.
has_animation is derived (never client-supplied) from the garmin_category /
garmin_subtype mapping via resolve_animation_flag, and is recomputed whenever
that mapping changes on update.
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.flexibility import UserCustomStretch

from .animation import resolve_animation_flag

router = APIRouter()


@router.get("/custom-stretches")
def get_custom_stretches(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    return db.query(UserCustomStretch).filter_by(user_id=user.id).all()


@router.post("/custom-stretches")
def create_custom_stretch(
    body: dict,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    existing = db.query(UserCustomStretch).filter_by(
        user_id=user.id, name=body["name"]
    ).first()
    if existing:
        raise HTTPException(status_code=409, detail="A custom stretch with this name already exists")

    cs = UserCustomStretch(
        user_id=user.id,
        name=body["name"],
        primary_muscles=body.get("primary_muscles", []),
        secondary_muscles=body.get("secondary_muscles", []),
        equipment=body.get("equipment", ["bodyweight"]),
        movement_pattern=body.get("movement_pattern", "static_stretch"),
        is_compound=body.get("is_compound", True),
        difficulty=body.get("difficulty", 1),
        duration_per_side_sec=body.get("duration_per_side_sec", 60),
        each_side=body.get("each_side", False),
        sets=body.get("sets", 1),
        description=body.get("description"),
        instructions=body.get("instructions"),
        cautions=body.get("cautions"),
        viewer_slug=body.get("viewer_slug"),
        garmin_category=body.get("garmin_category"),
        garmin_subtype=body.get("garmin_subtype"),
        has_animation=resolve_animation_flag(
            body.get("garmin_category"), body.get("garmin_subtype")
        ),
    )
    db.add(cs)
    db.commit()
    db.refresh(cs)
    return cs


@router.put("/custom-stretches/{custom_id}")
def update_custom_stretch(custom_id: int, body: dict, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    cs = db.query(UserCustomStretch).filter_by(id=custom_id, user_id=user.id).first()
    if not cs:
        raise HTTPException(status_code=404, detail="Not found")
    for key in ("name", "primary_muscles", "secondary_muscles", "equipment",
                "movement_pattern", "is_compound", "difficulty",
                "duration_per_side_sec", "each_side", "sets",
                "description", "instructions", "cautions", "viewer_slug",
                "garmin_category", "garmin_subtype"):
        if key in body:
            setattr(cs, key, body[key])
    # Recompute has_animation whenever the garmin mapping changes.
    if "garmin_category" in body or "garmin_subtype" in body:
        cs.has_animation = resolve_animation_flag(cs.garmin_category, cs.garmin_subtype)
    db.commit()
    db.refresh(cs)
    return cs


@router.delete("/custom-stretches/{custom_id}", status_code=204)
def delete_custom_stretch(custom_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    cs = db.query(UserCustomStretch).filter_by(id=custom_id, user_id=user.id).first()
    if not cs:
        raise HTTPException(status_code=404, detail="Not found")
    db.delete(cs)
    db.commit()
