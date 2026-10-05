# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for the activities API package.

Query scoping (which devices a user owns), per-user threshold/FTP resolution,
TSS estimation, data-point loading, power/pace "best" recomputation, and the
owned-or-404 ownership guard — all used across the list/detail/heatmap/backfill
sub-modules.
"""

from fastapi import HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.calculators.activity_metrics import (
    _CYCLING_SPORTS,
    _RUNNING_SPORTS,
    compute_pace_curve,
    compute_power_curve,
)
from app.models.activity import (
    Activity, DataPoint, Lap, PaceBest, PowerBest, User, UserDevice,
)
from app.models.user_settings import UserSettings
from app.schemas.activity import ActivityDetail


def _claimed_device_ids(db: Session, user_id: int) -> list[int]:
    """Devices claimed by THIS user. Returns [] if user has no claims so
    queries filter to nothing rather than leaking other users' activities."""
    return [row.device_id for row in db.query(UserDevice.device_id).filter_by(user_id=user_id).all()]


def _effective_ftp(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.ftp_mode == "manual":
        return float(us.ftp_manual) if us.ftp_manual else None
    return float(us.ftp_auto) if us.ftp_auto else None


def _effective_threshold_hr(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.threshold_hr_mode == "manual":
        return float(us.threshold_hr_manual) if us.threshold_hr_manual else None
    return float(us.threshold_hr_auto) if us.threshold_hr_auto else None


def _hr_tss(activity: Activity, threshold_hr: float | None) -> float | None:
    dur = activity.duration_seconds
    avg_hr = activity.avg_heart_rate
    if not dur or not avg_hr:
        return None
    if threshold_hr is None:
        max_hr = activity.max_heart_rate or int(avg_hr * 1.15)
        threshold_hr = max_hr * 0.87
    if threshold_hr <= 0:
        return None
    hr_ratio = min(avg_hr / threshold_hr, 1.5)
    return round((dur / 3600) * (hr_ratio ** 2) * 100, 1)


def _load_dp_dicts(db: Session, activity_id: int) -> list[dict]:
    """Load DataPoints for an activity as dicts compatible with the metric calculators."""
    dps = (
        db.query(DataPoint)
        .filter_by(activity_id=activity_id)
        .order_by(DataPoint.recorded_at)
        .all()
    )
    return [
        {
            "recorded_at": dp.recorded_at,
            "heart_rate":  dp.heart_rate,
            "power":       dp.power,
            "speed":       dp.speed,
        }
        for dp in dps
    ]


def _recompute_bests(db: Session, activity: Activity, dp_dicts: list[dict]) -> None:
    """Delete and reinsert PowerBest / PaceBest rows for an activity."""
    db.query(PowerBest).filter_by(activity_id=activity.id).delete()
    db.query(PaceBest).filter_by(activity_id=activity.id).delete()

    sport_lower = (activity.sport or "").lower()
    if not dp_dicts:
        return

    if sport_lower in _CYCLING_SPORTS:
        curve = compute_power_curve(dp_dicts)
        if curve:
            db.bulk_insert_mappings(PowerBest, [
                {"activity_id": activity.id, "duration_seconds": dur, "avg_watts": w}
                for dur, w in curve.items()
            ])
    elif sport_lower in _RUNNING_SPORTS:
        curve = compute_pace_curve(dp_dicts)
        if curve:
            db.bulk_insert_mappings(PaceBest, [
                {"activity_id": activity.id, "distance_meters": dist, "avg_speed_mps": s}
                for dist, s in curve.items()
            ])


def _owned_or_404(activity_id: int, user: User, db: Session) -> Activity:
    """
    Fetch an activity and assert it belongs to `user`.  Returns 404 (not 403)
    for both missing and foreign activities so attackers can't enumerate IDs.

    Activities with NULL user_id are treated as legacy/unattributed and are
    accessible to any authenticated user — this preserves backwards compat
    with rows imported before user attribution existed.
    """
    activity = db.get(Activity, activity_id)
    if activity is None:
        raise HTTPException(status_code=404, detail="Activity not found")
    if activity.user_id is not None and activity.user_id != user.id:
        raise HTTPException(status_code=404, detail="Activity not found")
    return activity


def _activity_detail(activity: Activity, db: Session) -> dict:
    """Build an ActivityDetail-compatible dict with computed lap_count."""
    lap_count = db.query(func.count(Lap.id)).filter_by(activity_id=activity.id).scalar() or 0
    data = ActivityDetail.model_validate(activity).model_dump()
    data["lap_count"] = lap_count or None
    return data
