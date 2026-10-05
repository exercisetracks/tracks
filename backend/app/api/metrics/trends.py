# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Trends endpoint: activity volume + TSS aggregated by week / month / year.

Each bucket also carries Foster's Training Monotony and Strain, computed from
the per-day TSS distribution within the bucket. The arithmetic lives in
app.calculators.dashboard_stats, where the phone's port is checked against it.
"""

from datetime import date

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.calculators.mtb import active_mtb_discipline
from app.auth import require_auth
from app.calculators import dashboard_stats
from app.database import get_db
from app.models.activity import Activity, User
from app.schemas.metrics import TrendBucketOut

from .helpers import (
    _account_zone,
    _activity_query,
    _effective_threshold_hr,
    _full_calibration,
    _get_user_settings,
)

router = APIRouter()

_TREND_BUCKETS = dashboard_stats._TREND_BUCKETS


@router.get("/trends", response_model=list[TrendBucketOut])
def trends(
    bucket: str = Query("week", description="Time bucket: 'week', 'month', or 'year'"),
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Training trends aggregated by week, month, or year.
    Each bucket contains activity count, total distance, total duration, and
    total TSS (device-provided where available, else hrTSS, else estimated
    from sport, duration, distance and climb).
    """
    if bucket not in _TREND_BUCKETS:
        raise HTTPException(status_code=422, detail=f"bucket must be one of: {', '.join(sorted(_TREND_BUCKETS))}")

    us = _get_user_settings(db, user.id)
    threshold_hr = _effective_threshold_hr(us)
    discipline = active_mtb_discipline(db, user.id)
    activities = _activity_query(db, user.id, us, after, before).order_by(Activity.started_at).all()
    return [TrendBucketOut(**b) for b in dashboard_stats.trends(
        activities, threshold_hr, bucket, discipline,
        _full_calibration(db, user.id, us, threshold_hr, discipline), _account_zone(us))]
