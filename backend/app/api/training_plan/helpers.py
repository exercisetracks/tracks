# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for the training-plan API package.

Query/lookup helpers used across the generation, matching, ICS, and sync
sub-modules:
  - goal ownership guard
  - running pace-bests + recent activity history
  - today's running fitness (the VDOT every running pace comes from)
  - per-user effective FTP / max-HR / threshold-HR resolution
  - fitness-fingerprint effective-weeks lookup
  - watch orphan-delete queue (shared by plan regen, workout delete, and sync)
"""

import statistics
from datetime import date, timedelta

from fastapi import HTTPException
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.calculators.local_day import activity_local_date, local_day_start, local_history, user_today
from app.models.activity import Activity, PaceBest, User
from app.models.coaching import TrainingGoal
from app.models.training_plan import (
    PlannedWorkout,
    UserFitnessFingerprint,
    WatchPendingDelete,
)
from app.models.user_settings import UserSettings


def _get_goal_or_404(db: Session, goal_id: int, user: User) -> TrainingGoal:
    goal = db.query(TrainingGoal).filter_by(id=goal_id, user_id=user.id).first()
    if goal is None:
        raise HTTPException(status_code=404, detail="Goal not found")
    return goal


def _get_pace_bests(db: Session, user_id: int) -> list[tuple[int, float]]:
    """Return (distance_m, best_avg_speed_mps) for running activities."""
    rows = (
        db.query(
            PaceBest.distance_meters,
            func.max(PaceBest.avg_speed_mps).label("best_speed"),
        )
        .join(Activity, Activity.id == PaceBest.activity_id)
        .filter(
            Activity.user_id == user_id,
            Activity.sport.in_(["running", "trail_running", "treadmill_running"]),
        )
        .group_by(PaceBest.distance_meters)
        .all()
    )
    return [(r.distance_meters, r.best_speed) for r in rows]


_RUN_SPORTS = ["running", "trail_running", "treadmill_running", "road_running", "virtual_running"]
# Resting HR is read as the median of this many recent days, so one bad
# night's reading does not move every pace.
_RESTING_HR_DAYS = 30


def _get_running_evidence(db: Session, user_id: int, today: date) -> tuple[list, list, float | None]:
    """(efforts, runs, resting HR) — what today's running fitness is read from.

    Efforts are every pace best of the last year with its run's date (the
    estimator ages each one); runs are the last year's running activities (it
    reads HR and the watch's VO2max from the last 90 days, and the date of the
    latest run for a layoff); resting HR is the median of the last 30 days.
    The phone gathers the same from its library
    (com.tracks.core.local.LocalRunningEvidence.evidence). Every date is the
    run's day in the account's zone (calculators/local_day.py), as the
    phone's are.
    """
    from app.calculators.plan.running_fitness import EFFORT_MAX_AGE_DAYS
    from app.models.metrics import DailyMetric

    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    tz = us.timezone if us else None
    cutoff = local_day_start(today - timedelta(days=EFFORT_MAX_AGE_DAYS), tz)
    rows = (
        db.query(Activity)
        .filter(Activity.user_id == user_id, Activity.sport.in_(_RUN_SPORTS),
                Activity.is_merged.is_(False), Activity.started_at.isnot(None),
                Activity.started_at >= cutoff)
        # Newest first, as the phone reads them: the watch's VO2max on a day
        # with two runs is the later run's on both.
        .order_by(Activity.started_at.desc(), Activity.id.desc())
        .all()
    )
    runs = [{
        "date": activity_local_date(a.started_at, tz), "sport": a.sport, "distance_m": a.distance_meters,
        "duration_s": a.duration_seconds, "avg_speed": a.avg_speed, "avg_hr": a.avg_heart_rate,
        "ascent_m": a.total_ascent, "vo2max": a.vo2max_estimate,
    } for a in rows]
    efforts = [
        {"date": activity_local_date(started, tz), "distance_m": dist, "speed_mps": speed}
        for dist, speed, started in (
            db.query(PaceBest.distance_meters, PaceBest.avg_speed_mps, Activity.started_at)
            .join(Activity, Activity.id == PaceBest.activity_id)
            .filter(Activity.user_id == user_id, Activity.sport.in_(_RUN_SPORTS),
                    Activity.is_merged.is_(False), Activity.started_at >= cutoff)
            .all()
        )
    ]
    rhr = [r for (r,) in (
        db.query(DailyMetric.resting_hr)
        .filter(DailyMetric.user_id == user_id, DailyMetric.resting_hr.isnot(None),
                DailyMetric.date <= today,
                DailyMetric.date > today - timedelta(days=_RESTING_HR_DAYS))
        .all()
    )]
    return efforts, runs, (statistics.median(rhr) if rhr else None)


def _get_running_fitness(db: Session, user_id: int, us: UserSettings | None,
                         today: date, evidence: tuple | None = None) -> dict:
    """Today's running fitness (calculators/plan/running_fitness.py) from this
    user's data: the one estimate the training plan and race plan both read.
    ``evidence`` is ``_get_running_evidence``'s answer when the caller has it."""
    from app.calculators.plan.running_fitness import estimate_running_fitness

    efforts, runs, resting_hr = evidence or _get_running_evidence(db, user_id, today)
    return estimate_running_fitness(
        efforts, runs, today,
        max_hr=_effective_max_hr(us),
        resting_hr=resting_hr,
        sex=us.sex if us else None,
        height_cm=us.height_cm if us else None,
        weight_kg=us.weight_kg if us else None,
        birth_year=us.birth_year if us else None,
        frequencies=us.activity_frequency if us else None,
    )


def _get_activity_history(db: Session, user_id: int, days: int = 90):
    """The plan generators' history, each activity on its local day
    (calculators/local_day.local_history), as the phone's
    LocalPlanning.history gives them."""
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    tz = us.timezone if us else None
    cutoff = local_day_start(user_today(db, user_id) - timedelta(days=days), tz)
    return local_history(
        db.query(Activity)
        .filter(Activity.user_id == user_id, Activity.started_at >= cutoff,
                Activity.is_merged.is_(False))
        .all(),
        tz,
    )


def _effective_ftp(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.ftp_mode == "manual":
        return float(us.ftp_manual) if us.ftp_manual else None
    return float(us.ftp_auto) if us.ftp_auto else None


def _effective_max_hr(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.max_hr_mode == "manual":
        return float(us.max_hr_manual) if us.max_hr_manual else None
    return float(us.max_hr_auto) if us.max_hr_auto else None


def _effective_threshold_hr(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.threshold_hr_mode == "manual":
        return float(us.threshold_hr_manual) if us.threshold_hr_manual else None
    return float(us.threshold_hr_auto) if us.threshold_hr_auto else None


def _get_fingerprint_ew(db: Session, user_id: int, family: str) -> float | None:
    """Return stored effective_weeks for this user/sport family, or None if no record yet."""
    fp = db.query(UserFitnessFingerprint).filter_by(
        user_id=user_id, sport_family=family
    ).first()
    return float(fp.effective_weeks) if fp else None


# ── Orphan-delete queue (called when PlannedWorkout rows are removed) ─────────
# Shared by plan regeneration (generation.py), workout delete (generation.py),
# and the Garmin sync delete-list endpoint (sync.py).

def _upsert_pending_delete(db: Session, user_id: int, filename: str) -> None:
    if not db.query(WatchPendingDelete).filter_by(user_id=user_id, filename=filename).first():
        db.add(WatchPendingDelete(user_id=user_id, filename=filename))


def _queue_orphan_deletes(db: Session, plan_id: int,
                          future_only: bool = False,
                          today: date | None = None,
                          except_moved: bool = False) -> None:
    """Queue watch-file deletions for all uploaded-but-not-deleted workouts in plan_id.

    ``except_moved`` for a regeneration: workouts the user moved survive it
    (calculators/plan/moved.py), so their files stay on the watch.
    """
    q = (
        db.query(PlannedWorkout.watch_filename, PlannedWorkout.user_id)
        .filter(
            PlannedWorkout.plan_id == plan_id,
            PlannedWorkout.watch_filename.isnot(None),
            PlannedWorkout.watch_deleted_at.is_(None),
        )
    )
    if except_moved:
        q = q.filter(PlannedWorkout.moved_by_user.is_(False))
    if future_only and today:
        q = q.filter(
            PlannedWorkout.scheduled_date >= today,
            PlannedWorkout.is_complete.is_(False),
        )
    for fname, owner in q.all():
        _upsert_pending_delete(db, owner, fname)
