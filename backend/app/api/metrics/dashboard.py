# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Dashboard endpoints backed by the per-user dashboard cache.

Each endpoint serves its unfiltered all-time request straight from the cache
(via ``_serve_dash_slice``) and recomputes only when a date/sport filter is
supplied. The cached slices are built together in :mod:`.caching`; both paths
select rows here and leave the arithmetic to app.calculators.dashboard_stats.
"""

from datetime import date

from fastapi import APIRouter, Depends, Query
from fastapi.responses import Response
from sqlalchemy import func, or_
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators import dashboard_stats
from app.database import get_db
from app.models.activity import Activity, User, UserDevice
from app.schemas.metrics import (
    ActivityCalendarPoint,
    MetricsSummaryOut,
    SportBreakdownOut,
    Vo2MaxPoint,
    WeeklyVolumePoint,
)

from .caching import _serve_dash_slice
from .helpers import _account_zone, _activity_query, _get_user_settings, _started_in


def _dash_rows(db: Session, user_id: int, us, after: date | None, before: date | None):
    """The in-range rows the dashboard arithmetic reads, oldest first."""
    return (
        _activity_query(db, user_id, us, after, before)
        .with_entities(
            Activity.started_at,
            Activity.sport,
            Activity.distance_meters,
            Activity.duration_seconds,
        )
        .order_by(Activity.started_at, Activity.id)
        .all()
    )


router = APIRouter()


@router.get("/summary", response_model=MetricsSummaryOut)
def summary(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Aggregate headline stats for the dashboard."""
    if after is None and before is None:
        return Response(content=_serve_dash_slice(db, user, "summary"), media_type="application/json")

    us = _get_user_settings(db, user.id)
    rows = _dash_rows(db, user.id, us, after, before)

    # Device count: devices linked to THIS user (not date-filtered — lifetime stat)
    device_count = (
        db.query(func.count(UserDevice.device_id))
        .filter(UserDevice.user_id == user.id)
        .scalar() or 0
    )
    return MetricsSummaryOut(**dashboard_stats.summary(rows, device_count))


@router.get("/by-sport", response_model=list[SportBreakdownOut])
def by_sport(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Per-sport totals for pie/bar charts, ordered by activity count descending."""
    if after is None and before is None:
        return Response(content=_serve_dash_slice(db, user, "by_sport"), media_type="application/json")

    us = _get_user_settings(db, user.id)
    rows = _dash_rows(db, user.id, us, after, before)
    return [SportBreakdownOut(**s) for s in dashboard_stats.by_sport(rows)]


@router.get("/activity-calendar", response_model=list[ActivityCalendarPoint])
def activity_calendar(
    after: date | None = Query(None),
    before: date | None = Query(None),
    sport: str | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Daily activity counts for the contribution heatmap calendar."""
    if after is None and before is None and not sport:
        return Response(content=_serve_dash_slice(db, user, "activity_calendar"), media_type="application/json")

    us = _get_user_settings(db, user.id)
    q = _activity_query(db, user.id, us, after, before)
    if sport:
        q = q.filter(Activity.sport == sport.strip().lower())
    rows = q.with_entities(Activity.started_at).order_by(Activity.started_at).all()
    return [ActivityCalendarPoint(**p) for p in dashboard_stats.activity_calendar(rows, _account_zone(us))]


@router.get("/vo2max-history", response_model=list[Vo2MaxPoint])
def vo2max_history(
    after: date | None = Query(None),
    before: date | None = Query(None),
    db: Session = Depends(get_db),
    user=Depends(require_auth),
):
    """One VO2Max data point per calendar day (latest activity that day)."""
    if after is None and before is None:
        return Response(content=_serve_dash_slice(db, user, "vo2max_history"), media_type="application/json")

    q = (
        db.query(Activity.started_at, Activity.vo2max_estimate, Activity.sport,
                 Activity.distance_meters, Activity.duration_seconds)
        # Runs too, for the pace-based estimate when no device reports one
        # (dashboard_stats.vo2max_history).
        .filter(Activity.user_id == user.id,
                or_(Activity.vo2max_estimate.isnot(None), Activity.sport.ilike("%run%")))
    )
    us = _get_user_settings(db, user.id)
    rows = _started_in(q, us, after, before).order_by(Activity.started_at).all()
    return [Vo2MaxPoint(**p) for p in dashboard_stats.vo2max_history(rows, _account_zone(us))]


@router.get("/weekly-volume", response_model=list[WeeklyVolumePoint])
def weekly_volume(
    after: date | None = Query(None),
    before: date | None = Query(None),
    sport: str | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Weekly distance and duration bucketed by ISO week (Monday start).
    Honors the date range and optional sport filter.
    Unfiltered all-time requests are served from the dashboard cache.
    """
    if after is None and before is None and sport is None:
        return Response(content=_serve_dash_slice(db, user, "weekly_volume"), media_type="application/json")

    us = _get_user_settings(db, user.id)
    q = _activity_query(db, user.id, us, after, before)
    if sport:
        q = q.filter(Activity.sport == sport.strip().lower())

    rows = q.with_entities(
        Activity.started_at,
        Activity.distance_meters,
        Activity.duration_seconds,
    ).order_by(Activity.started_at).all()
    return [WeeklyVolumePoint(**p) for p in dashboard_stats.weekly_volume(rows, _account_zone(us))]
