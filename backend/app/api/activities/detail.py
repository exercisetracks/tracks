# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-activity detail + mutation endpoints.

GET `/activities/{id}` (summary + lap_count), GET `/{id}/track` (LTTB-downsampled
data points), PATCH `/{id}` (rename / re-notes / re-sport with metric recompute),
and DELETE `/{id}`. These own the dynamic `/{activity_id}` path, so this router
is included LAST in the package so static paths (/sports, /heatmap, …) win.
"""

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.activity_metrics import (
    compute_aerobic_decoupling,
    compute_efficiency_factor,
)
from app.database import get_db
from app.models.activity import DataPoint, Lap, User
from app.models.imports import Import
from app.sync import store as sync_store
from app.schemas.activity import ActivityDetail, TrackPoint
from app.services.crypto_context import require_crypto_session

from .helpers import _activity_detail, _load_dp_dicts, _owned_or_404, _recompute_bests

router = APIRouter()

# Bounds for the per-request `?max_points=` override on /{id}/track. The floor
# keeps a downsampled track recognisable; the ceiling is well above the largest
# RESOLUTION_CAPS entry ("high" = 3000) so a caller can ask for more detail than
# the user's setting, but still can't request an unbounded payload the way
# chart_resolution="raw" does.
_MIN_TRACK_POINTS = 100
_MAX_TRACK_POINTS = 50_000


@router.get("/{activity_id}", response_model=ActivityDetail)
def get_activity(
    activity_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    activity = _owned_or_404(activity_id, user, db)
    return _activity_detail(activity, db)


@router.get("/{activity_id}/track", response_model=list[TrackPoint])
def get_track(
    activity_id: int,
    max_points: int | None = Query(
        None, ge=_MIN_TRACK_POINTS, le=_MAX_TRACK_POINTS,
        description="Cap the returned sample count, overriding the user's "
                    "chart_resolution setting for this request only.",
    ),
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    """
    Return the activity's data points, downsampled via LTTB to the cap
    defined by the user's chart_resolution setting.  Long activities can
    have 25K+ samples — LTTB preserves the visual shape of all metrics
    while cutting the payload to a configurable limit.

    `?max_points=` overrides that setting per-request. Clients that can't
    afford the user's configured resolution need this: `chart_resolution`
    is a *server-side* preference, and `"raw"` means no cap at all, so a
    12-hour ride would otherwise send 40K+ objects to a phone with no way
    for the caller to ask for less.
    """
    from app.models.user_settings import UserSettings
    from app.parsers.smoother import lttb, RESOLUTION_CAPS

    _owned_or_404(activity_id, user, db)

    if max_points is None:
        us         = db.query(UserSettings).filter_by(user_id=user.id).first()
        resolution = (us.chart_resolution if us else None) or "high"
        max_points = RESOLUTION_CAPS.get(resolution, 3000)

    points = (
        db.query(DataPoint)
        .filter(DataPoint.activity_id == activity_id)
        .order_by(DataPoint.recorded_at)
        .all()
    )
    if not points:
        return []

    if max_points is not None and len(points) > max_points:
        dicts     = [{"recorded_at": p.recorded_at, "speed": p.speed,
                      "heart_rate": p.heart_rate, "altitude": p.altitude,
                      "cadence": p.cadence, "power": p.power, "_obj": p}
                     for p in points]
        selected  = lttb(dicts, max_points)
        points    = [d["_obj"] for d in selected]

    return points


class ActivityUpdate(BaseModel):
    name: str | None = None
    notes: str | None = None
    sport: str | None = None


@router.patch("/{activity_id}", response_model=ActivityDetail)
def update_activity(
    activity_id: int,
    update: ActivityUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    activity = _owned_or_404(activity_id, user, db)

    if update.name is not None:
        activity.name = update.name
    if update.notes is not None:
        activity.notes = update.notes

    if update.sport is not None:
        new_sport = update.sport.strip().lower()
        if new_sport != activity.sport:
            activity.sport = new_sport
            # Recompute metrics that depend on sport from stored session-level values
            activity.efficiency_factor = compute_efficiency_factor(
                sport=new_sport,
                avg_hr=activity.avg_heart_rate,
                normalized_power=activity.normalized_power,
                avg_speed=activity.avg_speed,
            )
            dp_dicts = _load_dp_dicts(db, activity.id)
            activity.aerobic_decoupling = compute_aerobic_decoupling(
                sport=new_sport, data_points=dp_dicts
            )
            _recompute_bests(db, activity, dp_dicts)

    db.commit()
    db.refresh(activity)
    return activity


@router.delete("/{activity_id}", status_code=204)
def delete_activity(
    activity_id: int,
    allow_reimport: bool = False,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    activity = _owned_or_404(activity_id, user, db)

    if allow_reimport:
        # Delete the import record entirely so the file hash is forgotten and
        # the file will be accepted again on the next scan.
        db.query(Import).filter(Import.activity_id == activity_id).delete()
        # And leave no tombstone. A re-import brings back the *same* activity —
        # its uid is derived from the watch and start time — and a delete wins,
        # so a tombstone would hide it on every phone forever. Phones parse the
        # file themselves and never lost it; this is a server-side re-parse.
        with sync_store.no_tombstones(db):
            db.query(DataPoint).filter(DataPoint.activity_id == activity_id).delete()
            db.query(Lap).filter(Lap.activity_id == activity_id).delete()
            db.delete(activity)
            db.commit()
        return
    else:
        # Null out import record's activity_id — preserves the SHA-256 dedup
        # record so the file isn't re-imported on next scan.
        db.query(Import).filter(Import.activity_id == activity_id).update(
            {Import.activity_id: None}, synchronize_session=False
        )

    db.query(DataPoint).filter(DataPoint.activity_id == activity_id).delete()
    db.query(Lap).filter(Lap.activity_id == activity_id).delete()
    # The tombstone is written by app.sync.store as the row goes, in the same
    # flush — so phones stop showing it, and it stays gone even though the
    # file it came from is kept.
    db.delete(activity)
    db.commit()
