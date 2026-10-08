# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-exercise preferences.

PUT/DELETE /strength/preferences/{name} — mark an exercise as "preferred" or
"excluded", or reset it to neutral. Preferences bias which exercises the
planner suggests and are surfaced on GET /exercises.

Neutral is a row whose preference is null, never a deleted row. A preference's
sync uid is derived from the exercise's name (spec/sync.yaml, uid_key), and a
deleted synced row is a tombstone that wins over every later edit — so
deleting one froze that exercise's preference everywhere: the phone's next
change to it was refused as "deleted", on every retry. The phone clears the
same way (LocalTraining.setPreference).
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.strength import UserExercisePreference

router = APIRouter()


class PreferenceBody(BaseModel):
    preference: str  # "preferred" | "excluded"


@router.put("/preferences/{exercise_name}")
def set_preference(exercise_name: str,
                   body: PreferenceBody,
                   db: Session = Depends(get_db),
                   user: User = Depends(require_auth)):
    if body.preference not in ("preferred", "excluded"):
        raise HTTPException(status_code=422, detail="preference must be 'preferred' or 'excluded'")
    row = db.query(UserExercisePreference).filter_by(
        user_id=user.id, exercise_name=exercise_name
    ).first()
    if row:
        row.preference = body.preference
    else:
        db.add(UserExercisePreference(
            user_id=user.id,
            exercise_name=exercise_name,
            preference=body.preference,
        ))
    db.commit()
    return {"exercise_name": exercise_name, "preference": body.preference}


@router.delete("/preferences/{exercise_name}", status_code=204)
def delete_preference(exercise_name: str,
                      db: Session = Depends(get_db),
                      user: User = Depends(require_auth)):
    row = db.query(UserExercisePreference).filter_by(
        user_id=user.id, exercise_name=exercise_name
    ).first()
    if row is not None and row.preference is not None:
        row.preference = None       # cleared, not deleted — see the module docstring
        db.commit()
