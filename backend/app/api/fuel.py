# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race fuelling for the web: the product library, gut-training logs, and a
goal's fuel plan (targets, timeline, gut-training schedule).

The web is a live client, so these are plain REST writes; the ORM stamps
every field as it lands (app.sync.store) and phones merge them like any other
edit. The phone computes the same plan itself from synced rows
(com.tracks.core.fuel.FuelPlan) — calculators/fuel_plan.py is the one rule
book both follow.
"""
from __future__ import annotations

from datetime import date
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators import fuel_plan as fp
from app.database import get_db
from app.models.activity import User
from app.models.coaching import RacePlan, TrainingGoal
from app.models.fuel import FuelProduct, GutTrainingLog
from app.models.training_plan import PlannedWorkout, TrainingPlan

router = APIRouter(prefix="/fuel", tags=["fuel"])


class ProductIn(BaseModel):
    name: str
    kind: str = "gel"
    carbs_g: float = 0
    sodium_mg: int = 0
    caffeine_mg: int = 0
    fluid_ml: int = 0
    serving: Optional[str] = None
    notes: Optional[str] = None


class ProductOut(ProductIn):
    id: int
    uid: str
    model_config = {"from_attributes": True}


class GutLogIn(BaseModel):
    planned_workout_id: Optional[int] = None
    activity_id: Optional[int] = None
    date: date
    duration_min: Optional[int] = None
    carbs_g: float = 0
    comfort: int
    notes: Optional[str] = None


class GutLogOut(GutLogIn):
    id: int
    model_config = {"from_attributes": True}


@router.get("/products", response_model=list[ProductOut])
def list_products(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    return db.query(FuelProduct).filter_by(user_id=user.id).order_by(FuelProduct.name).all()


@router.post("/products", response_model=ProductOut, status_code=201)
def create_product(body: ProductIn, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    p = FuelProduct(user_id=user.id, **body.model_dump())
    db.add(p)
    db.commit()
    db.refresh(p)
    return p


@router.put("/products/{product_id}", response_model=ProductOut)
def update_product(product_id: int, body: ProductIn, user: User = Depends(require_auth),
                   db: Session = Depends(get_db)):
    p = db.query(FuelProduct).filter_by(id=product_id, user_id=user.id).first()
    if p is None:
        raise HTTPException(404, "Product not found")
    for k, v in body.model_dump().items():
        setattr(p, k, v)
    db.commit()
    db.refresh(p)
    return p


@router.delete("/products/{product_id}", status_code=204)
def delete_product(product_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    p = db.query(FuelProduct).filter_by(id=product_id, user_id=user.id).first()
    if p is not None:
        db.delete(p)
        db.commit()


@router.get("/gut-logs", response_model=list[GutLogOut])
def list_gut_logs(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    return (db.query(GutTrainingLog).filter_by(user_id=user.id)
            .order_by(GutTrainingLog.date.desc()).all())


@router.post("/gut-logs", response_model=GutLogOut, status_code=201)
def create_gut_log(body: GutLogIn, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    if not 1 <= body.comfort <= 5:
        raise HTTPException(422, "comfort is 1 (bad) to 5 (no issues)")
    log = GutTrainingLog(user_id=user.id, **body.model_dump())
    db.add(log)
    db.commit()
    db.refresh(log)
    return log


@router.delete("/gut-logs/{log_id}", status_code=204)
def delete_gut_log(log_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    log = db.query(GutTrainingLog).filter_by(id=log_id, user_id=user.id).first()
    if log is not None:
        db.delete(log)
        db.commit()


def fuel_plan_for(db: Session, user_id: int, goal: TrainingGoal, rp: RacePlan | None) -> dict:
    """Targets, timeline and gut-training schedule for one race goal."""
    laps = (rp.lap_paces if rp else None) or []
    duration_min = (rp.predicted_seconds / 60.0) if rp and rp.predicted_seconds else 0.0
    weather = (rp.weather_snapshot or {}) if rp else {}
    tg = fp.targets(
        duration_min,
        temperature_c=weather.get("temperature_c"), humidity_pct=weather.get("humidity_pct"),
        carbs=rp.fuel_carbs_per_hour if rp else None,
        fluid=rp.fuel_fluid_ml_per_hour if rp else None,
        sodium=rp.fuel_sodium_mg_per_hour if rp else None,
        interval=rp.fuel_interval_min if rp else None,
    )
    all_products = db.query(FuelProduct).filter_by(user_id=user_id).all()
    by_uid = {p.uid: p for p in all_products}
    chosen = [by_uid[u] for u in ((rp.fuel_product_uids if rp else None) or []) if u in by_uid]
    products = [{"uid": p.uid, "name": p.name, "carbs_g": float(p.carbs_g or 0),
                 "sodium_mg": p.sodium_mg or 0, "fluid_ml": p.fluid_ml or 0,
                 "caffeine_mg": p.caffeine_mg or 0} for p in chosen]
    timeline = fp.timeline(duration_min, tg, products,
                           [{"distance_m": l["distance_m"], "target_sec_per_km": l["target_sec_per_km"]}
                            for l in laps]) if duration_min else {"items": [], "totals": {}, "per_hour": {}}

    gut: dict[str, int] = {}
    if goal.event_date:
        plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
        rows = db.query(PlannedWorkout).filter(PlannedWorkout.user_id == user_id).all()
        workouts = [{"uid": w.uid, "scheduled_date": w.scheduled_date.isoformat() if w.scheduled_date else None,
                     "sport": w.sport, "workout_type": w.workout_type,
                     "duration_minutes": w.duration_minutes, "fuel_carbs_per_hour": w.fuel_carbs_per_hour}
                    for w in rows if plan is None or w.plan_id in (plan.id, None)]
        uid_of = {w.id: w.uid for w in rows}
        logs = [{"uid": l.uid, "planned_workout_uid": uid_of.get(l.planned_workout_id),
                 "date": l.date.isoformat(), "comfort": l.comfort, "carbs_g": float(l.carbs_g or 0),
                 "duration_min": l.duration_min}
                for l in db.query(GutTrainingLog).filter_by(user_id=user_id).all()]
        gut = fp.gut_training(tg["carbs_g_per_h"], goal.event_date.isoformat(), workouts, logs)
    return {"targets": tg, "timeline": timeline, "gut_training": gut,
            "product_uids": [p["uid"] for p in products]}


@router.get("/goals/{goal_id}/plan")
def goal_fuel_plan(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    goal = db.query(TrainingGoal).filter_by(id=goal_id, user_id=user.id).first()
    if goal is None:
        raise HTTPException(404, "Goal not found")
    rp = db.query(RacePlan).filter_by(goal_id=goal_id).first()
    return fuel_plan_for(db, user.id, goal, rp)
