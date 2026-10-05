# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-activity sub-resource getters: laps, golf holes, strength sets, climbs.

All are read-only `/activities/{id}/<thing>` endpoints guarded by the
owned-or-404 check. Golf holes are stored as lap rows and 404 for non-golf
activities.
"""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import ClimbSplit, Lap, StrengthSet, User
from app.schemas.activity import ClimbSplitOut, GolfHoleSummary, LapOut, StrengthSetOut

from .helpers import _owned_or_404

router = APIRouter()


@router.get("/{activity_id}/laps", response_model=list[LapOut])
def get_laps(
    activity_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    _owned_or_404(activity_id, user, db)
    return (
        db.query(Lap)
        .filter_by(activity_id=activity_id)
        .order_by(Lap.lap_number)
        .all()
    )


@router.get("/{activity_id}/golf-holes", response_model=list[GolfHoleSummary])
def get_golf_holes(
    activity_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    """Return per-hole scorecard data for a golf activity.

    Each hole is stored as a lap row.  Returns 404 when the activity is not
    found or not a golf activity.
    """
    activity = _owned_or_404(activity_id, user, db)
    if (activity.sport or "").lower() != "golf":
        raise HTTPException(status_code=404, detail="Activity is not a golf round")
    return (
        db.query(Lap)
        .filter_by(activity_id=activity_id)
        .order_by(Lap.lap_number)
        .all()
    )


@router.get("/{activity_id}/sets", response_model=list[StrengthSetOut])
def get_sets(
    activity_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    _owned_or_404(activity_id, user, db)
    return (
        db.query(StrengthSet)
        .filter_by(activity_id=activity_id)
        .order_by(StrengthSet.set_number)
        .all()
    )


@router.get("/{activity_id}/climbs", response_model=list[ClimbSplitOut])
def get_climbs(
    activity_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    _owned_or_404(activity_id, user, db)
    return (
        db.query(ClimbSplit)
        .filter_by(activity_id=activity_id)
        .order_by(ClimbSplit.split_number)
        .all()
    )
