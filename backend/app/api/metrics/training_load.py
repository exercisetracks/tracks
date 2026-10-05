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
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
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
                                 tz_name=_account_zone(us))
