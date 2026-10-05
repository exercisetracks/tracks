# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Performance endpoints derived from the PowerBest / PaceBest tables.

/power-curve       best avg power at each standard duration
/pace-curve        best pace at each standard distance
/race-predictions  finishing times at standard race distances (Riegel)

Each endpoint selects the in-range activity ids as a correlated subquery
(no Postgres round-trip, no oversized IN list) and aggregates the bests.
"""

from datetime import date

from fastapi import APIRouter, Depends, Query
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, PaceBest, PowerBest, User
from app.schemas.metrics import PaceCurvePoint, PowerCurvePoint, RacePredictionOut

from .helpers import _activity_query, _get_user_settings

router = APIRouter()


def _in_range_activity_ids(db: Session, user_id: int, us, after, before):
    """Correlated subquery of the in-range activity ids for joining to *Best tables."""
    return (
        _activity_query(db, user_id, us, after, before)
        .with_entities(Activity.id)
        .subquery()
    )


@router.get("/power-curve", response_model=list[PowerCurvePoint])
def power_curve(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    All-time (or date-ranged) best power at each standard duration.
    Returns the highest avg_watts across all matching activities for each duration.
    """
    us = _get_user_settings(db, user.id)
    activity_subq = _in_range_activity_ids(db, user.id, us, after, before)
    rows = (
        db.query(PowerBest.duration_seconds, func.max(PowerBest.avg_watts))
        .filter(PowerBest.activity_id.in_(activity_subq.select()))
        .group_by(PowerBest.duration_seconds)
        .order_by(PowerBest.duration_seconds)
        .all()
    )

    return [PowerCurvePoint(**p) for p in power_curve_points(rows)]


def power_curve_points(rows) -> list[dict]:
    """(duration, best watts) pairs, already maxed per duration, as curve points.

    Pure so the phone's port can be checked against it; the phone does the
    per-duration max itself over its own rows.
    """
    return [{"duration_seconds": dur, "avg_watts": round(watts, 1)} for dur, watts in rows]


def _format_pace(avg_speed_mps: float) -> str:
    """Convert m/s to mm:ss per km string."""
    if avg_speed_mps <= 0:
        return "--:--"
    pace_sec_per_km = 1000 / avg_speed_mps
    mins = int(pace_sec_per_km // 60)
    secs = int(pace_sec_per_km % 60)
    return f"{mins}:{secs:02d}"


@router.get("/pace-curve", response_model=list[PaceCurvePoint])
def pace_curve(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    All-time (or date-ranged) best pace at each standard distance.
    Returns the highest avg_speed_mps across all matching activities for each distance.
    """
    us = _get_user_settings(db, user.id)
    activity_subq = _in_range_activity_ids(db, user.id, us, after, before)
    rows = (
        db.query(PaceBest.distance_meters, func.max(PaceBest.avg_speed_mps))
        .filter(PaceBest.activity_id.in_(activity_subq.select()))
        .group_by(PaceBest.distance_meters)
        .order_by(PaceBest.distance_meters)
        .all()
    )

    return [PaceCurvePoint(**p) for p in pace_curve_points(rows)]


def pace_curve_points(rows) -> list[dict]:
    """(distance, best speed) pairs, already maxed per distance, as curve points."""
    return [
        {
            "distance_meters": dist,
            "avg_speed_mps":   round(speed, 4),
            "pace_per_km":     _format_pace(speed),
        }
        for dist, speed in rows
    ]


_RACE_DISTANCES = [
    (1000,  "1 km"),
    (1609,  "1 mile"),
    (5000,  "5 km"),
    (10000, "10 km"),
    (21097, "Half Marathon"),
    (42195, "Marathon"),
]


def _riegel_predict(ref_dist: int, ref_speed: float, target_dist: int) -> int:
    """Riegel: T2 = T1 × (D2/D1)^1.06"""
    t1 = ref_dist / ref_speed
    return round(t1 * (target_dist / ref_dist) ** 1.06)


def _format_race_time(seconds: int) -> str:
    h = seconds // 3600
    m = (seconds % 3600) // 60
    s = seconds % 60
    if h > 0:
        return f"{h}:{m:02d}:{s:02d}"
    return f"{m}:{s:02d}"


@router.get("/race-predictions", response_model=list[RacePredictionOut])
def race_predictions(
    after: date | None = Query(None),
    before: date | None = Query(None),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Predicted finishing times at standard race distances using the Riegel formula.
    For distances where we have real pace data, the time shown is an actual best effort.
    For other distances it is extrapolated from the nearest shorter reference distance.
    """
    us = _get_user_settings(db, user.id)
    activity_subq = _in_range_activity_ids(db, user.id, us, after, before)
    rows = (
        db.query(PaceBest.distance_meters, func.max(PaceBest.avg_speed_mps))
        .filter(PaceBest.activity_id.in_(activity_subq.select()))
        .group_by(PaceBest.distance_meters)
        .all()
    )
    best_speeds: dict[int, float] = {dist: speed for dist, speed in rows}
    return [RacePredictionOut(**p) for p in race_predictions_from(best_speeds)]


def race_predictions_from(best_speeds: dict[int, float]) -> list[dict]:
    """Finishing times at the standard distances from best speed per distance.

    Pure so the phone's port can be checked against it.
    """
    result = []
    for target_dist, label in _RACE_DISTANCES:
        if target_dist in best_speeds:
            secs = round(target_dist / best_speeds[target_dist])
            result.append({
                "distance_meters":           target_dist,
                "distance_label":            label,
                "predicted_time_seconds":    secs,
                "formatted_time":            _format_race_time(secs),
                "reference_distance_meters": target_dist,
                "is_actual":                 True,
            })
        else:
            # Use the longest reference distance shorter than the target
            ref = max(
                (d for d in best_speeds if d < target_dist),
                default=None,
            )
            if ref is None:
                continue
            secs = _riegel_predict(ref, best_speeds[ref], target_dist)
            result.append({
                "distance_meters":           target_dist,
                "distance_label":            label,
                "predicted_time_seconds":    secs,
                "formatted_time":            _format_race_time(secs),
                "reference_distance_meters": ref,
                "is_actual":                 False,
            })
    return result
