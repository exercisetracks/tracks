# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Training-goal CRUD endpoints.

  GET    /coaching/goals            list goals (newest first)
  POST   /coaching/goals            create (deactivates other active goals)
  GET    /coaching/goals/{id}       fetch one
  PATCH  /coaching/goals/{id}       partial update / toggle active
  DELETE /coaching/goals/{id}       remove

  GET    /coaching/goals/recommended-date   a suggested date for a new event

Static `/goals` routes are registered before the dynamic `/goals/{id}` ones.
Creating/updating/deleting a goal invalidates today's recommendation cache so
it regenerates against the new goal; event and fitness goals also
(re)generate the plan — on every edit that leaves it stale
(calculators/plan/staleness.py), since there is no Regenerate button.
"""

import threading
from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.calculators.local_day import user_today
from app.api.coaching.helpers import _event_load
from app.auth import require_auth
from app.calculators.event_date import counts_toward, recommend_event_date
from app.calculators.plan.staleness import goal_edit_stales_plan
from app.database import get_db
from app.models.activity import User
from app.models.coaching import CoachingRecommendation, TrainingGoal
from app.schemas.coaching import (
    TrainingGoalCreate,
    TrainingGoalOut,
    TrainingGoalUpdate,
)

router = APIRouter()


def _invalidate_today_cache(db: Session, user_id: int) -> None:
    """Drop today's cached recommendation so it regenerates against the
    current goal set."""
    db.query(CoachingRecommendation).filter_by(user_id=user_id, date=user_today(db, user_id)).delete()
    db.commit()


@router.get("/goals", response_model=list[TrainingGoalOut])
def list_goals(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    return (
        db.query(TrainingGoal)
        .filter_by(user_id=user.id)
        .order_by(TrainingGoal.created_at.desc())
        .all()
    )


@router.post("/goals", response_model=TrainingGoalOut, status_code=201)
def create_goal(body: TrainingGoalCreate, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Create a new training goal. Only one goal can be active at a time, so any
    currently-active goals are deactivated first."""
    for other in db.query(TrainingGoal).filter_by(user_id=user.id, is_active=True).all():
        other.is_active = False

    goal = TrainingGoal(user_id=user.id, **body.model_dump())
    db.add(goal)
    db.commit()
    db.refresh(goal)

    _invalidate_today_cache(db, user.id)

    # Auto-generate a training plan for future-dated event goals and for
    # fitness goals (off-thread so the request returns immediately).
    if (goal.goal_type == "fitness"
            or goal.goal_type == "event" and goal.event_date and goal.event_date > user_today(db, user.id)):
        from app.api.training_plan import refresh_plans_for_user
        threading.Thread(target=refresh_plans_for_user, args=(user.id,), daemon=True).start()

    return goal


@router.get("/goals/recommended-date")
def recommended_event_date(
    sport: str = Query("running"),
    distance_m: float | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """When this person could be ready for an event of this sport and
    distance — the date the new-goal form starts from, with its reasons.
    The phone computes the same offline (calculators/event_date.py)."""
    today = user_today(db, user.id)
    ctl, sport_tss, total_tss = _event_load(db, user.id, today, lambda s: counts_toward(sport, s))
    return recommend_event_date(sport, distance_m, today, ctl, sport_tss, total_tss)


def _plannable(goal: TrainingGoal, today: date) -> bool:
    """A goal whose plan is live: fitness (rolling), or a race still ahead."""
    if goal.goal_type == "fitness":
        return True
    return goal.goal_type == "event" and goal.event_date is not None and goal.event_date > today


@router.get("/goals/{goal_id}", response_model=TrainingGoalOut)
def get_goal(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    goal = db.query(TrainingGoal).filter_by(id=goal_id, user_id=user.id).first()
    if goal is None:
        raise HTTPException(status_code=404, detail="Goal not found")
    return goal


@router.patch("/goals/{goal_id}", response_model=TrainingGoalOut)
def update_goal(goal_id: int, body: TrainingGoalUpdate, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Partial update of a goal. Use is_active=true/false to toggle focus."""
    goal = db.query(TrainingGoal).filter_by(id=goal_id, user_id=user.id).first()
    if goal is None:
        raise HTTPException(status_code=404, detail="Goal not found")

    data = body.model_dump(exclude_unset=True)

    # Activating this goal deactivates any other active goal first.
    if data.get("is_active") is True:
        for other in db.query(TrainingGoal).filter(
            TrainingGoal.user_id == user.id,
            TrainingGoal.id != goal_id,
            TrainingGoal.is_active.is_(True),
        ).all():
            other.is_active = False

    for field, value in data.items():
        setattr(goal, field, value)

    db.commit()
    db.refresh(goal)

    _invalidate_today_cache(db, user.id)

    # Any edit the plan reads — the days, the intensity, strength, the date
    # (moved in the form or by dragging the race), the sport — rebuilds it,
    # and so does activating it: its plan was built for the fitness of
    # whenever it was last active. Workouts the user moved stay put.
    if goal.is_active and _plannable(goal, user_today(db, user.id)) and goal_edit_stales_plan(data):
        from app.api.training_plan import _regenerate_future_workouts
        _regenerate_future_workouts(db, goal, user.id)

    return goal


@router.delete("/goals/{goal_id}", status_code=204)
def delete_goal(goal_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    goal = db.query(TrainingGoal).filter_by(id=goal_id, user_id=user.id).first()
    if goal is None:
        raise HTTPException(status_code=404, detail="Goal not found")
    # The goal cascade wipes the plan, its workouts, and the race plan at the
    # DB level; queue their watch files for deletion first, or the sync
    # delete-list can never see them and the FIT files stay on the watch.
    from app.api.training_plan.helpers import _queue_orphan_deletes, _upsert_pending_delete
    from app.models.coaching import RacePlan
    from app.models.training_plan import TrainingPlan

    plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
    if plan is not None:
        _queue_orphan_deletes(db, plan.id)
    rp = db.query(RacePlan).filter_by(goal_id=goal.id).first()
    if rp is not None and rp.watch_filename and rp.watch_uploaded_at:
        _upsert_pending_delete(db, user.id, rp.watch_filename)
    db.delete(goal)
    db.commit()
    _invalidate_today_cache(db, user.id)
