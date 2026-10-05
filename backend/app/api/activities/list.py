# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Activity list + sport-filter endpoints.

`/activities/sports` (distinct sports by frequency) and the paginated, sortable,
filterable `/activities/` listing. Both are static paths and must be registered
before the dynamic `/{activity_id}` routes (see the package __init__).
"""

from datetime import date, timedelta

from fastapi import APIRouter, Depends, Query
from sqlalchemy import and_, func, or_
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, User
from app.schemas.activity import ActivitySummary, Page

from .helpers import _claimed_device_ids

router = APIRouter()

_MAX_PAGE_SIZE = 100
_DEFAULT_PAGE_SIZE = 20

_SORT_COLUMNS = {
    "date":      Activity.started_at,
    "duration":  Activity.duration_seconds,
    "distance":  Activity.distance_meters,
    "speed":     Activity.avg_speed,        # higher avg_speed = faster pace
    "heartrate": Activity.avg_heart_rate,
    "elevation": Activity.total_ascent,
    "calories":  Activity.total_calories,
    "sport":     Activity.sport,
}


@router.get("/sports", response_model=list[str])
def list_sports(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Return distinct sports sorted by activity count descending."""
    rows = (
        db.query(Activity.sport, func.count(Activity.id).label("n"))
        .filter(Activity.sport.isnot(None))
        .filter(Activity.device_id.in_(_claimed_device_ids(db, user.id)))
        .group_by(Activity.sport)
        .order_by(func.count(Activity.id).desc())
        .all()
    )
    return [row.sport for row in rows]


@router.get("/", response_model=Page[ActivitySummary])
def list_activities(
    sport: str | None = Query(None, description="Filter by sport, e.g. 'running'"),
    after: date | None = Query(None, description="Only activities on or after this date"),
    before: date | None = Query(None, description="Only activities on or before this date"),
    search: str | None = Query(None, description="Filter by activity name (case-insensitive)"),
    sort: str = Query("date", description="Sort column: date | duration | distance"),
    order: str = Query("desc", description="Sort direction: asc | desc"),
    page: int = Query(1, ge=1, description="Page number, starting at 1"),
    page_size: int = Query(_DEFAULT_PAGE_SIZE, ge=1, le=_MAX_PAGE_SIZE),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    # Real activities are scoped by claimed device; merged-trip summaries have no
    # device (device_id=NULL keeps them out of every metric query) so include them
    # here explicitly by user so they still appear in the list.
    q = db.query(Activity).filter(or_(
        Activity.device_id.in_(_claimed_device_ids(db, user.id)),
        and_(Activity.is_merged.is_(True), Activity.user_id == user.id),
    ))

    if sport:
        q = q.filter(Activity.sport == sport.strip().lower())
    if after:
        q = q.filter(Activity.started_at >= after)
    if before:
        q = q.filter(Activity.started_at < before + timedelta(days=1))
    if search:
        q = q.filter(Activity.name.ilike(f"%{search.strip()}%"))

    col = _SORT_COLUMNS.get(sort, Activity.started_at)
    direction = col.desc() if order != "asc" else col.asc()
    # NULLs are pushed to the end either way so an unsorted column (e.g.
    # distance for an indoor workout with no GPS) doesn't dominate the top.
    direction = direction.nullslast()
    # Tie-break by started_at desc so equal distances/durations come out in a
    # stable, intuitive order.
    secondary = Activity.started_at.desc()

    total = q.count()
    items = (
        q.order_by(direction, secondary)
        .offset((page - 1) * page_size)
        .limit(page_size)
        .all()
    )

    return Page(total=total, page=page, page_size=page_size, items=items)
