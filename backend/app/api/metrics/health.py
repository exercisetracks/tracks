# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Health & per-activity load endpoints.

/daily               raw daily health metrics (sleep, HRV, resting HR)
/activity-load       per-activity TSS dots for the fitness-chart overlay
/readiness-history   daily readiness scores blended with the TSB timeline
"""

from datetime import date, timedelta

from fastapi import APIRouter, Depends, Query
from sqlalchemy.orm import Session

from app.calculators.mtb import active_mtb_discipline
from app.auth import require_auth
from app.calculators import dashboard_stats
from app.calculators.readiness import readiness_history as compute_readiness_history
from app.database import get_db
from app.models.activity import Activity, User
from app.models.metrics import DailyMetric
from app.schemas.metrics import (
    ActivityLoadPoint,
    DailyMetricOut,
    ReadinessHistoryPoint,
)

from .helpers import (
    _account_zone,
    _activity_query,
    _effective_threshold_hr,
    _full_calibration,
    _get_user_settings,
)

router = APIRouter()


@router.get("/daily", response_model=list[DailyMetricOut])
def daily_metrics(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Daily health metrics: sleep, HRV, resting HR."""
    q = db.query(DailyMetric).filter(DailyMetric.user_id == user.id)
    if after:
        q = q.filter(DailyMetric.date >= after)
    if before:
        q = q.filter(DailyMetric.date <= before)
    return q.order_by(DailyMetric.date).all()


@router.get("/activity-load", response_model=list[ActivityLoadPoint])
def activity_load(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Per-activity TSS values for overlaying as dots on the fitness chart.
    Uses the best available TSS: device-provided → effective_tss (power/hr) → estimated.
    """
    us = _get_user_settings(db, user.id)
    threshold_hr = _effective_threshold_hr(us)
    discipline = active_mtb_discipline(db, user.id)
    activities = _activity_query(db, user.id, us, after, before).order_by(Activity.started_at).all()
    return [ActivityLoadPoint(**p) for p in dashboard_stats.activity_load(
        activities, threshold_hr, discipline, _full_calibration(db, user.id, us, threshold_hr, discipline),
        _account_zone(us))]


@router.get("/readiness-history", response_model=list[ReadinessHistoryPoint])
def readiness_history(
    days: int = Query(30, ge=1, le=365, description="How many days back to return"),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Daily readiness scores for the last N days.
    Useful for charting recovery trends alongside CTL/ATL.
    """
    today = date.today()
    start = today - timedelta(days=days - 1)
    load_from = start - timedelta(days=7)
    us = _get_user_settings(db, user.id)

    all_metrics = (
        db.query(DailyMetric)
        .filter(DailyMetric.user_id == user.id, DailyMetric.date >= load_from)
        .order_by(DailyMetric.date)
        .all()
    )
    # The live score's selection — claimed devices, hidden sports removed, the
    # whole history — so each point is what the gauge showed that day. Claimed
    # devices already exclude merged-trip summaries (their device_id is NULL).
    activities = _activity_query(db, user.id, us, None, today).order_by(Activity.started_at, Activity.id).all()
    return [
        ReadinessHistoryPoint(**p)
        for p in compute_readiness_history(all_metrics, activities, today, days,
                                          _effective_threshold_hr(us),
                                          active_mtb_discipline(db, user.id),
                                          _account_zone(us))
    ]
