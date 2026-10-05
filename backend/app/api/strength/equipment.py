# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""User equipment list.

GET/PUT /strength/equipment — the set of equipment the user has access to,
used to filter exercise suggestions. Stored on UserSettings.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.user_settings import UserSettings

from .schemas import VALID_EQUIPMENT, EquipmentUpdate

router = APIRouter()

# Shown when the user has no saved equipment yet.
_DEFAULT_EQUIPMENT = ["bodyweight", "dumbbell"]


@router.get("/equipment")
def get_equipment(db: Session = Depends(get_db),
                  user: User = Depends(require_auth)):
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None:
        return {"equipment": list(_DEFAULT_EQUIPMENT)}
    return {"equipment": us.equipment_available or list(_DEFAULT_EQUIPMENT)}


@router.put("/equipment")
def update_equipment(body: EquipmentUpdate,
                     db: Session = Depends(get_db),
                     user: User = Depends(require_auth)):
    invalid = set(body.equipment) - VALID_EQUIPMENT
    if invalid:
        raise HTTPException(status_code=422,
                            detail=f"Unknown equipment: {sorted(invalid)}")

    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None:
        raise HTTPException(status_code=404, detail="User settings not found")

    us.equipment_available = list(set(body.equipment))
    db.commit()
    return {"equipment": us.equipment_available}
