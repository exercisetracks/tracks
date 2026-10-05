# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-user stretch preference endpoints.

A preference flags a named stretch as 'preferred', 'excluded', or 'neutral'.
'neutral' is the absence of a row, so setting/deleting to neutral removes the
record. These flags steer the post-activity flow generator (preferred bypass +
excluded drop) and decorate the library listing.
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.flexibility import UserFlexibilityPreference

router = APIRouter()


@router.put("/preferences/{exercise_name}")
def set_stretch_preference(
    exercise_name: str,
    body: dict,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    pref_val = body.get("preference")
    if pref_val not in ("preferred", "excluded", "neutral"):
        raise HTTPException(status_code=400, detail="preference must be 'preferred', 'excluded', or 'neutral'")

    existing = db.query(UserFlexibilityPreference).filter_by(
        user_id=user.id, exercise_name=exercise_name
    ).first()

    if pref_val == "neutral":
        if existing:
            db.delete(existing)
        db.commit()
        return {"exercise_name": exercise_name, "preference": "neutral"}

    if existing:
        existing.preference = pref_val
    else:
        pref = UserFlexibilityPreference(
            user_id=user.id, exercise_name=exercise_name, preference=pref_val
        )
        db.add(pref)
    db.commit()
    return {"exercise_name": exercise_name, "preference": pref_val}


@router.delete("/preferences/{exercise_name}")
def delete_stretch_preference(
    exercise_name: str,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    existing = db.query(UserFlexibilityPreference).filter_by(
        user_id=user.id, exercise_name=exercise_name
    ).first()
    if existing:
        db.delete(existing)
        db.commit()
    return {"exercise_name": exercise_name, "preference": "neutral"}
