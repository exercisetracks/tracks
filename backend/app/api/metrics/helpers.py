# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for the metrics API package.

Query scoping (which devices a user owns), per-user threshold-HR resolution,
the base date-ranged Activity query, slim TSS-only row loading, and the
TSS-bucketing → CTL/ATL/TSB computation shared by the training-load endpoint
and the cache builder.

Every query is scoped to the *requesting* user's claimed devices. This is
mandatory for multi-user correctness: never fall back to "the first user".
"""

from datetime import date, timedelta

from sqlalchemy.orm import Session

from app.calculators.local_day import activity_local_date, local_day_start
from app.calculators.training_load import calculate_ctl_atl_tsb, estimate_tss, load_calibration
from app.models.activity import Activity, UserDevice
from app.models.user_settings import UserSettings


def _claimed_device_ids(db: Session, user_id: int) -> list[int]:
    """Devices claimed by THIS user — never fall back to 'the first user'."""
    return [row.device_id for row in db.query(UserDevice.device_id).filter_by(user_id=user_id).all()]


def _get_user_settings(db: Session, user_id: int) -> UserSettings | None:
    """Settings for THIS user — never fall back to 'the first user'."""
    return db.query(UserSettings).filter_by(user_id=user_id).first()


def _account_zone(us: UserSettings | None) -> str | None:
    """The zone every day here is read in (calculators/local_day.py)."""
    return us.timezone if us else None


def _started_in(q, us: UserSettings | None, after: date | None, before: date | None):
    """Activities whose local day is within [after, before].

    Bounded at local midnight, not UTC midnight: the window has to hold the
    same activities its day buckets count, or "the last 7 days" would drop
    this evening's run in California and keep one from eight days ago.
    """
    tz = _account_zone(us)
    if after:
        q = q.filter(Activity.started_at >= local_day_start(after, tz))
    if before:
        q = q.filter(Activity.started_at < local_day_start(before + timedelta(days=1), tz))
    return q


def _effective_threshold_hr(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.threshold_hr_mode == "manual":
        return float(us.threshold_hr_manual) if us.threshold_hr_manual else None
    return float(us.threshold_hr_auto) if us.threshold_hr_auto else None


def _activity_query(db: Session, user_id: int, us: UserSettings | None, after: date | None, before: date | None):
    """Base Activity query with date range, hidden_sports, and claimed-device filters applied."""
    q = _started_in(db.query(Activity).filter(Activity.started_at.isnot(None)), us, after, before)
    if us and us.hidden_sports:
        q = q.filter(Activity.sport.notin_(us.hidden_sports))
    q = q.filter(Activity.device_id.in_(_claimed_device_ids(db, user_id)))
    return q


def _slim_activity_rows(db: Session, user_id: int, us, after: date | None = None, before: date | None = None):
    """Fetch only the columns needed for TSS estimation — 5× less data than a full ORM load."""
    q = (
        db.query(
            Activity.started_at,
            Activity.sport,
            Activity.training_stress_score,
            Activity.effective_tss,
            Activity.duration_seconds,
            Activity.avg_heart_rate,
            Activity.max_heart_rate,
            # What the load is estimated from when there is no heart rate
            # (training_load.estimated_tss) — a phone-only athlete's whole load.
            Activity.distance_meters,
            Activity.total_ascent,
        )
        .filter(Activity.started_at.isnot(None))
    )
    q = _started_in(q, us, after, before)
    if us and us.hidden_sports:
        q = q.filter(Activity.sport.notin_(us.hidden_sports))
    q = q.filter(Activity.device_id.in_(_claimed_device_ids(db, user_id)))
    return q.order_by(Activity.started_at).all()


def _full_calibration(db: Session, user_id: int, us, threshold_hr, mtb_discipline) -> dict[str, float]:
    """The athlete's load calibration from their whole history — what a
    date-windowed view must use, or an activity's load would change with the
    window (training_load.load_calibration)."""
    return load_calibration(_slim_activity_rows(db, user_id, us), threshold_hr, mtb_discipline,
                            _account_zone(us))


def _compute_tload_points(rows, threshold_hr, mtb_discipline: str | None = None,
                          calibration: dict[str, float] | None = None,
                          tz_name: str | None = None) -> list[dict]:
    """Shared computation: TSS bucketing → CTL/ATL/TSB → 7-day ctl_ramp.

    ``calibration`` defaults to the one ``rows`` give, which is right only
    when ``rows`` is the whole history; a windowed caller passes the full one.
    Each activity's load falls on its day in ``tz_name``, the account's zone.
    """
    if not rows:
        return []
    if calibration is None:
        calibration = load_calibration(rows, threshold_hr, mtb_discipline, tz_name)
    tss_by_date: dict[date, float] = {}
    for r in rows:
        d = activity_local_date(r.started_at, tz_name)
        tss_by_date[d] = tss_by_date.get(d, 0.0) + estimate_tss(r, threshold_hr, mtb_discipline, calibration)
    daily_loads = [{"date": d, "tss": tss} for d, tss in sorted(tss_by_date.items())]
    points = calculate_ctl_atl_tsb(daily_loads)
    ctl_by_date = {p["date"]: p["ctl"] for p in points}
    for p in points:
        ctl_7ago = ctl_by_date.get(p["date"] - timedelta(days=7))
        p["ctl_ramp"] = round(p["ctl"] - ctl_7ago, 1) if ctl_7ago is not None else None
    return points
