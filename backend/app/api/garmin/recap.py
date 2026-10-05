# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Post-workout recap endpoints — collect bulk animation feedback after a sync.

When the user syncs activities from their watch, match_activity_to_workout
links each activity to its PlannedWorkout. Completed workouts that contain at
least one (likely-animating) exercise the user hasn't confirmed are surfaced
here, so the user can mark each pair yes / no / I-don't-remember in one pass.

"I don't remember" maps to: don't write a confirmation row, but stamp the
workout's recap_completed_at so we stop asking. The planner's default fallback
for that pair stays `likely` (manifest membership).

  /garmin/recap/pending          GET    workouts awaiting a recap
  /garmin/recap/{id}             GET     animatable steps + current verdicts
  /garmin/recap/{id}             POST    submit verdicts
  /garmin/recap/{id}/dismiss     POST    skip without recording verdicts

Route ordering matters: the static /recap/pending is declared before the
dynamic /recap/{workout_id} so FastAPI doesn't match "pending" as an id.
"""
from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.fit_workout import _CATEGORY_INT
from app.calculators.garmin_animations import (
    build_confirmation_lookup, is_animatable, state_from_lookup,
)
from app.database import get_db
from app.models.activity import User
from app.models.training_plan import PlannedWorkout

from .helpers import upsert_confirmation_row


router = APIRouter()


def _workout_has_animatable_step(workout: PlannedWorkout) -> bool:
    """True iff any step references a (garmin_category, garmin_subtype) pair
    that's in Garmin's animation manifest. Workouts with zero animatable
    steps don't need a recap — auto-mark them done so they never show up."""
    for s in (workout.steps or []):
        cat = s.get("garmin_category")
        sub = s.get("garmin_subtype")
        if cat is None or sub is None:
            continue
        cat_int = _CATEGORY_INT.get(str(cat).lower())
        if is_animatable(cat_int, sub):
            return True
    return False


@router.get("/recap/pending")
def list_pending_recaps(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """List completed workouts (strength/mobility/flexibility) for the user
    that have a matched activity AND at least one animatable step AND
    haven't had the recap submitted/dismissed yet.

    Order: most-recently-completed first. Caller renders a count + maybe a
    teaser; details fetched per-workout via /garmin/recap/{id}."""
    workouts = (
        db.query(PlannedWorkout)
        .filter(
            PlannedWorkout.user_id == user.id,
            PlannedWorkout.completed_activity_id.isnot(None),
            PlannedWorkout.recap_completed_at.is_(None),
            PlannedWorkout.workout_type.in_(("strength", "mobility", "flexibility")),
        )
        .order_by(PlannedWorkout.scheduled_date.desc(), PlannedWorkout.id.desc())
        .all()
    )
    out = []
    for w in workouts:
        if not _workout_has_animatable_step(w):
            # Nothing to ask about — auto-mark done so it never appears again.
            w.recap_completed_at = datetime.now(timezone.utc)
            db.add(w)
            continue
        out.append({
            "workout_id":     w.id,
            "scheduled_date": w.scheduled_date.isoformat(),
            "title":          w.title,
            "workout_type":   w.workout_type,
            "sport":          w.sport,
        })
    if out or any(w.recap_completed_at for w in workouts):
        db.commit()
    return out


@router.get("/recap/{workout_id}")
def get_recap(
    workout_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Return the animatable steps from a single workout, each annotated
    with the user's CURRENT confirmation state so a re-opened recap
    pre-fills correctly."""
    w = (
        db.query(PlannedWorkout)
        .filter(PlannedWorkout.id == workout_id,
                PlannedWorkout.user_id == user.id)
        .first()
    )
    if not w:
        raise HTTPException(status_code=404, detail="Workout not found")

    confirm_lookup = build_confirmation_lookup(
        db, user.primary_device_product_id, user.id,
    )
    items = []
    seen: set[tuple] = set()
    for s in (w.steps or []):
        cat = s.get("garmin_category")
        sub = s.get("garmin_subtype")
        if cat is None or sub is None:
            continue
        cat_int = _CATEGORY_INT.get(str(cat).lower())
        if not is_animatable(cat_int, sub):
            continue
        key = (cat, sub)
        if key in seen:
            continue           # dedup: 'pose 41' shown once per workout
        seen.add(key)
        state = state_from_lookup(cat, sub, confirm_lookup)
        items.append({
            "garmin_category": cat,
            "garmin_subtype":  sub,
            "name":            s.get("name") or "",
            "animation_state": state,
        })
    return {
        "workout_id":              w.id,
        "title":                   w.title,
        "scheduled_date":          w.scheduled_date.isoformat(),
        "workout_type":            w.workout_type,
        "primary_device_set":      user.primary_device_product_id is not None,
        "items":                   items,
    }


class RecapItem(BaseModel):
    garmin_category: str
    garmin_subtype:  int
    # null = "I don't remember" — no confirmation written
    # true / false = thumbs up / down
    animates: bool | None


class RecapSubmitBody(BaseModel):
    items: list[RecapItem]


@router.post("/recap/{workout_id}")
def submit_recap(
    workout_id: int,
    body: RecapSubmitBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Submit the user's verdicts for one workout's recap. Each item with a
    non-null `animates` becomes (or updates) an animation_confirmations
    row; null items are skipped. Marks the workout's recap_completed_at
    regardless so it stops surfacing in the pending list."""
    w = (
        db.query(PlannedWorkout)
        .filter(PlannedWorkout.id == workout_id,
                PlannedWorkout.user_id == user.id)
        .first()
    )
    if not w:
        raise HTTPException(status_code=404, detail="Workout not found")

    product_id = user.primary_device_product_id
    if product_id is None and any(it.animates is not None for it in body.items):
        # User is trying to submit confirmations without a primary device set.
        # Mark the recap done so we stop pestering them, but tell them why
        # nothing got persisted.
        w.recap_completed_at = datetime.now(timezone.utc)
        db.commit()
        raise HTTPException(
            status_code=400,
            detail="No primary device set. Pick one in Settings to record verdicts.",
        )

    saved = 0
    if product_id is not None:
        for it in body.items:
            if it.animates is None:
                continue
            upsert_confirmation_row(
                db,
                product_id=product_id,
                garmin_category=it.garmin_category.lower(),
                garmin_subtype=it.garmin_subtype,
                animates=it.animates,
                user_id=user.id,
            )
            saved += 1

    w.recap_completed_at = datetime.now(timezone.utc)
    db.commit()
    return {"workout_id": w.id, "confirmations_saved": saved}


@router.post("/recap/{workout_id}/dismiss", status_code=204)
def dismiss_recap(
    workout_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Mark a workout's recap as done without recording any confirmations.
    Use when the user clicks 'skip' or closes the modal."""
    w = (
        db.query(PlannedWorkout)
        .filter(PlannedWorkout.id == workout_id,
                PlannedWorkout.user_id == user.id)
        .first()
    )
    if not w:
        raise HTTPException(status_code=404, detail="Workout not found")
    w.recap_completed_at = datetime.now(timezone.utc)
    db.commit()
