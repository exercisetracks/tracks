# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date, timedelta

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.local_day import activity_local_date, local_day_start, user_today
from app.database import get_db
from app.models.activity import Activity, User, UserDevice
from app.models.health import Injury
from app.models.metrics import DailyMetric
from app.models.user_settings import UserSettings
from app.schemas.health import (
    DailyMetricPatch, InjuryCreate, InjuryOut, InjuryUpdate, SleepNightOut,
    StressDayOut,
)
from app.schemas.metrics import DailyMetricOut
from app.services.crypto_context import require_crypto_session

router = APIRouter(prefix="/health", tags=["health"])

# The re-parse endpoint reads the session id out of the token itself rather than
# taking a User: the worker needs the sid to re-derive key material, and
# require_auth hands back a user with no way to recover it. Same shape as
# /auth/sync-pending-imports, which queues the sibling task.


# Helper kept for backward compatibility; endpoints now resolve the user via
# Depends(require_auth) instead of falling back to "the first user in the DB",
# which would have leaked data between users in a multi-user deployment.
def _get_user(db: Session, user: User) -> User:
    return user


def _claimed_device_ids(db: Session, user_id: int) -> list[int]:
    return [row.device_id for row in db.query(UserDevice.device_id).filter_by(user_id=user_id).all()]


# ─────────────────────────────────────────
# Injuries CRUD
# ─────────────────────────────────────────

@router.get("/injuries", response_model=list[InjuryOut])
def list_injuries(
    db: Session = Depends(get_db), user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    # user injected via Depends
    return (
        db.query(Injury)
        .filter_by(user_id=user.id)
        .order_by(Injury.start_date.desc())
        .all()
    )


@router.post("/injuries", response_model=InjuryOut, status_code=201)
def create_injury(
    body: InjuryCreate, db: Session = Depends(get_db), user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    # user injected via Depends
    inj = Injury(user_id=user.id, **body.model_dump())
    db.add(inj)
    db.commit()
    db.refresh(inj)
    return inj


@router.patch("/injuries/{injury_id}", response_model=InjuryOut)
def update_injury(
    injury_id: int, body: InjuryUpdate, db: Session = Depends(get_db), user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    # user injected via Depends
    inj = db.query(Injury).filter_by(id=injury_id, user_id=user.id).first()
    if inj is None:
        raise HTTPException(status_code=404, detail="Injury not found")
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(inj, field, value)
    db.commit()
    db.refresh(inj)
    return inj


@router.delete("/injuries/{injury_id}", status_code=204)
def delete_injury(
    injury_id: int, db: Session = Depends(get_db), user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    # user injected via Depends
    inj = db.query(Injury).filter_by(id=injury_id, user_id=user.id).first()
    if inj is None:
        raise HTTPException(status_code=404, detail="Injury not found")
    db.delete(inj)
    db.commit()


# ─────────────────────────────────────────
# Activities surrounding an injury
# ─────────────────────────────────────────

@router.get("/injuries/{injury_id}/activities")
def injury_activities(
    injury_id: int, window_days: int = 7, db: Session = Depends(get_db), user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    """
    Return activities in the window:
      [start_date - window_days, end_date + window_days]
    Falls back to start_date when no end_date is set.

    Requires a crypto session even though the response never surfaces
    body_part/injury_type/notes — SQLAlchemy hydrates every mapped column
    when the Injury row loads, so decrypting is unavoidable at load time.
    """
    # user injected via Depends
    inj = db.query(Injury).filter_by(id=injury_id, user_id=user.id).first()
    if inj is None:
        raise HTTPException(status_code=404, detail="Injury not found")

    lo = inj.start_date - timedelta(days=window_days)
    # Use end_date if set, else fall back to start_date
    injury_end = inj.end_date or inj.start_date
    # Add +1 to hi so an activity on the last day is included (date vs timestamptz boundary)
    hi = injury_end + timedelta(days=window_days + 1)
    # Days are the account's local days (calculators/local_day.py): an injury
    # dated the 1st is an evening run's day, not the UTC day after it.
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    tz = us.timezone if us else None

    device_ids = _claimed_device_ids(db, user.id)
    if not device_ids:
        return []

    acts = (
        db.query(Activity)
        .filter(
            Activity.device_id.in_(device_ids),
            Activity.started_at >= local_day_start(lo, tz),
            Activity.started_at < local_day_start(hi, tz),
        )
        .order_by(Activity.started_at)
        .all()
    )

    return [
        {
            "id":               a.id,
            "started_at":       a.started_at.isoformat() if a.started_at else None,
            "sport":            a.sport,
            "title":            a.name,
            "distance_meters":  a.distance_meters,
            "duration_seconds": a.duration_seconds,
            "days_from_injury": ((activity_local_date(a.started_at, tz) - inj.start_date).days
                                 if a.started_at else None),
            "injury_end_date":  injury_end.isoformat(),
        }
        for a in acts
    ]


# ─────────────────────────────────────────
# Daily metric manual entry (weight, hydration, calories)
# ─────────────────────────────────────────

@router.patch("/daily/{metric_date}", response_model=DailyMetricOut)
def patch_daily(metric_date: date, body: DailyMetricPatch, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    """Upsert manually-entered daily metrics (weight, hydration, calories_in)."""
    data = body.model_dump(exclude_unset=True)
    existing = db.query(DailyMetric).filter_by(date=metric_date, user_id=user.id).first()
    if existing:
        for field, value in data.items():
            if value is not None:
                setattr(existing, field, value)
        db.commit()
        db.refresh(existing)
        result = existing
    else:
        m = DailyMetric(
            date=metric_date,
            user_id=user.id,
            **{k: v for k, v in data.items() if v is not None},
        )
        db.add(m)
        db.commit()
        db.refresh(m)
        result = m

    # Two-way weight sync: keep UserSettings.weight_kg in step with today's metric
    if "weight_kg" in data and data["weight_kg"] is not None and metric_date == user_today(db, user.id):
        us = db.query(UserSettings).filter_by(user_id=user.id).first()
        if us is not None:
            us.weight_kg = data["weight_kg"]
            db.commit()

    return result


# ─────────────────────────────────────────
# Sleep detail (one night's stage timeline)
# ─────────────────────────────────────────

@router.get("/sleep/{metric_date}", response_model=SleepNightOut)
def sleep_night(
    metric_date: date,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    """
    One night's sleep stages, in order.

    Returns an empty list rather than 404 for a night with no timeline: a night
    whose totals exist but whose stages were never stored — every night imported
    before the importer began keeping them — is a normal, expected answer, and a
    client should draw the summary rather than an error.
    """
    row = db.query(DailyMetric).filter_by(date=metric_date, user_id=user.id).first()
    stages = ((row.extra or {}).get("sleep_stages") or []) if row else []
    return {"date": metric_date, "stages": stages}


# ─────────────────────────────────────────
# Stress detail (the curve behind the daily average)
# ─────────────────────────────────────────

@router.get("/stress", response_model=list[StressDayOut])
def stress_detail(
    after: date | None = None,
    before: date | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    """Stress reading by reading, for every day in the window that has any.

    ## Why this is not on the daily metric

    Because a day is roughly five hundred readings and a month of daily
    metrics is thirty rows. Folding the series in would multiply the size of
    the one request the health page makes on every load by two orders of
    magnitude, to serve a chart nobody has opened yet.

    ## Why the window is capped

    The curve is only worth drawing while a day is still a distinguishable
    part of the picture — over a year it is a solid block of ink, and the
    daily averages already on the metric rows are the readable answer at that
    scale. So the client asks for this on short windows only, and the cap is
    what stops a hand-written request for a lifetime from serving a hundred
    megabytes of three-minute samples.

    Days with no series come back not at all rather than as an empty list:
    absence of a curve and a flat curve are different things, and every night
    imported before the parser kept the series is the former.
    """
    end = before or user_today(db, user.id)
    start = after or (end - timedelta(days=_STRESS_DEFAULT_DAYS))
    start = max(start, end - timedelta(days=_STRESS_MAX_DAYS))

    rows = (
        db.query(DailyMetric)
        .filter(
            DailyMetric.user_id == user.id,
            DailyMetric.date >= start,
            DailyMetric.date <= end,
        )
        .order_by(DailyMetric.date)
        .all()
    )
    out = []
    for row in rows:
        points = (row.extra or {}).get("stress_series") or []
        if points:
            out.append({"date": row.date, "points": points})
    return out


# A month either way of the ask, and no more. See [stress_detail].
_STRESS_DEFAULT_DAYS = 30
_STRESS_MAX_DAYS = 62


# ─────────────────────────────────────────
# Health summary (recent daily metrics)
# ─────────────────────────────────────────

@router.get("/summary", response_model=list[DailyMetricOut])
def health_summary(
    days: int = 30,
    db: Session = Depends(get_db), user: User = Depends(require_auth),
):
    """Recent daily metrics for the health page overview."""
    # user injected via Depends
    since = user_today(db, user.id) - timedelta(days=days - 1)
    return (
        db.query(DailyMetric)
        .filter(DailyMetric.user_id == user.id, DailyMetric.date >= since)
        .order_by(DailyMetric.date)
        .all()
    )
