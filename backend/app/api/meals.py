# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime, timezone, timedelta
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.meals import Meal, MealLog

router = APIRouter(prefix="/meals", tags=["meals"])


# ── Schemas ───────────────────────────────────────────────────────────────────

class MealIn(BaseModel):
    name: str
    calories: int = 0
    protein_g: Optional[float] = None
    carbs_g:   Optional[float] = None
    fat_g:     Optional[float] = None
    notes:     Optional[str]   = None


class MealOut(BaseModel):
    id:        int
    name:      str
    calories:  int
    protein_g: Optional[float]
    carbs_g:   Optional[float]
    fat_g:     Optional[float]
    notes:     Optional[str]

    model_config = {"from_attributes": True}


class MealLogIn(BaseModel):
    meal_id:   Optional[int]   = None
    name:      str
    calories:  int             = 0
    protein_g: Optional[float] = None
    carbs_g:   Optional[float] = None
    fat_g:     Optional[float] = None
    logged_at: Optional[datetime] = None
    notes:     Optional[str]   = None


class MealLogOut(BaseModel):
    id:        int
    meal_id:   Optional[int]
    name:      str
    calories:  int
    protein_g: Optional[float]
    carbs_g:   Optional[float]
    fat_g:     Optional[float]
    logged_at: datetime
    notes:     Optional[str]

    model_config = {"from_attributes": True}


# ── Meal templates ────────────────────────────────────────────────────────────

@router.get("", response_model=list[MealOut])
def list_meals(db: Session = Depends(get_db), user: User = Depends(require_auth)):
    return db.query(Meal).filter_by(user_id=user.id).order_by(Meal.name).all()


@router.post("", response_model=MealOut, status_code=201)
def create_meal(body: MealIn, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    m = Meal(user_id=user.id, **body.model_dump())
    db.add(m)
    db.commit()
    db.refresh(m)
    return m


@router.patch("/{meal_id}", response_model=MealOut)
def update_meal(meal_id: int, body: MealIn, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    m = db.query(Meal).filter_by(id=meal_id, user_id=user.id).first()
    if m is None:
        raise HTTPException(status_code=404, detail="Meal not found")
    for k, v in body.model_dump(exclude_unset=True).items():
        setattr(m, k, v)
    db.commit()
    db.refresh(m)
    return m


@router.delete("/{meal_id}", status_code=204)
def delete_meal(meal_id: int, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    m = db.query(Meal).filter_by(id=meal_id, user_id=user.id).first()
    if m is None:
        raise HTTPException(status_code=404, detail="Meal not found")
    db.delete(m)
    db.commit()


# ── Meal log ──────────────────────────────────────────────────────────────────

@router.get("/log", response_model=list[MealLogOut])
def list_meal_log(
    days: int = 7,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    return (
        db.query(MealLog)
        .filter(MealLog.user_id == user.id, MealLog.logged_at >= since)
        .order_by(MealLog.logged_at.desc())
        .all()
    )


@router.post("/log", response_model=MealLogOut, status_code=201)
def log_meal(body: MealLogIn, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    data = body.model_dump()
    if data.get("logged_at") is None:
        data["logged_at"] = datetime.now(timezone.utc)
    entry = MealLog(user_id=user.id, **data)
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


@router.delete("/log/{entry_id}", status_code=204)
def delete_log_entry(entry_id: int, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    e = db.query(MealLog).filter_by(id=entry_id, user_id=user.id).first()
    if e is None:
        raise HTTPException(status_code=404, detail="Log entry not found")
    db.delete(e)
    db.commit()
