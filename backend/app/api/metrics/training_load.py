# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Training-load endpoint: CTL / ATL / TSB fitness series."""

from datetime import date

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.calculators.mtb import active_mtb_discipline
from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.schemas.metrics import TrainingLoadPoint

from .caching import _build_tload_bytes, get_cached_tload, set_cached_tload
from .helpers import (
    _account_zone,
    _compute_tload_points,
    _effective_threshold_hr,
    _get_user_settings,
    _full_calibration,
    _slim_activity_rows,
)

router = APIRouter()


@router.get("/training-load", response_model=list[TrainingLoadPoint])
def training_load(
    after: date | None = Query(None, description="Start date (inclusive)"),
    before: date | None = Query(None, description="End date (inclusive)"),
    sport: str | None = Query(None, description="One sport's own fitness, fatigue and form"),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    if sport:
        return _sport_load(db, user, sport.strip().lower(), after, before)

    # Unfiltered (all-time) path — serve from per-user cache.
    if after is None and before is None:
        cached = get_cached_tload(user.id)
        if cached is not None:
            return Response(content=cached, media_type="application/json")
        data = _build_tload_bytes(db, user.id)
        set_cached_tload(user.id, data)
        return Response(content=data, media_type="application/json")

    # Date-filtered path — slim query, compute on the fly.
    us = _get_user_settings(db, user.id)
    threshold_hr = _effective_threshold_hr(us)
    discipline = active_mtb_discipline(db, user.id)
    rows = _slim_activity_rows(db, user.id, us, after, before)
    return _compute_tload_points(rows, threshold_hr, discipline,
                                 _full_calibration(db, user.id, us, threshold_hr, discipline),
                                 tz_name=_account_zone(us), before=before)


def _sport_load(db: Session, user: User, sport: str, after, before):
    """Fitness, fatigue and form from one sport's load alone.

    The dashboard draws these beside the overall lines when a sport is picked
    on the pie chart: overall fitness is what the body can absorb, and a
    sport's own is how trained it is for that sport -- the difference between
    a cyclist who is fit and one who is fit to run. Built from the whole
    history (the 42-day average needs its warm-up) and then cut to the window,
    with each activity's load calibrated against everything, as the overall
    series is, so a ride counts the same here as there.
    """
    us = _get_user_settings(db, user.id)
    threshold_hr = _effective_threshold_hr(us)
    discipline = active_mtb_discipline(db, user.id)
    rows = [r for r in _slim_activity_rows(db, user.id, us) if (r.sport or "").lower() == sport]
    if not rows:
        return []
    points = _compute_tload_points(rows, threshold_hr, discipline,
                                   _full_calibration(db, user.id, us, threshold_hr, discipline),
                                   tz_name=_account_zone(us), before=before)
    if after is not None:
        points = [p for p in points if p["date"] >= after]
    return points

