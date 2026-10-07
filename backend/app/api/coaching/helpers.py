# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared helpers for the coaching API package.

User/device scoping (so coaching never leaks another user's activities),
per-user threshold-HR resolution, the TSS-by-date / CTL-ATL load model, and the
build-or-cache pipeline that turns metrics + history into a `CoachingResult`.

These back the daily-coaching endpoints (today/readiness/ai-enhance/plan) and
are imported by the race-plan modules for the TSS load model.
"""

from dataclasses import asdict, fields
from datetime import date, datetime, timedelta, timezone

from sqlalchemy.orm import Session

from app.calculators.mtb import active_mtb_discipline
from app.calculators.coaching import (
    CoachingResult,
    TrainingSignal,
    WorkoutRecommendation,
    compute_recommendations,
)
from app.calculators.local_day import activity_local_date, local_day_start, local_history
from app.calculators.readiness import ReadinessResult, acute_load_ema, compute_readiness
from app.calculators.training_load import calculate_ctl_atl_tsb, estimate_tss, load_calibration
from app.models.activity import Activity, User, UserDevice
from app.models.coaching import CoachingRecommendation, TrainingGoal
from app.models.metrics import DailyMetric
from app.models.user_settings import UserSettings
from app.models.workout import UserWorkoutSession
from app.schemas.coaching import (
    DailyCoachingOut,
    ReadinessOut,
    TrainingSignalOut,
    WorkoutRecommendationOut,
)


def _claimed_device_ids(db: Session, user_id: int) -> list[int]:
    """Devices claimed by THIS user — must always be scoped to prevent
    leaking other users' activities into coaching computations."""
    return [row.device_id for row in db.query(UserDevice.device_id).filter_by(user_id=user_id).all()]


def _settings_for(db: Session, user: User) -> UserSettings | None:
    """Lookup THIS user's settings. Callers pass the authenticated user
    obtained via Depends(require_auth) — never `db.query(User).first()`."""
    return db.query(UserSettings).filter_by(user_id=user.id).first()


def _get_user_and_settings(db: Session, user: User) -> tuple[User, UserSettings | None]:
    """Pair the authed user with their settings — backwards-compat shim."""
    return user, _settings_for(db, user)


def _effective_threshold_hr(us: UserSettings | None) -> float | None:
    if us is None:
        return None
    if us.threshold_hr_mode == "manual":
        return float(us.threshold_hr_manual) if us.threshold_hr_manual else None
    return float(us.threshold_hr_auto) if us.threshold_hr_auto else None


def _active_goal(db: Session, user_id: int) -> TrainingGoal | None:
    """The single goal to coach toward. When multiple are active, prefer the
    one with the soonest upcoming event date, then most recently created."""
    return (
        db.query(TrainingGoal)
        .filter_by(user_id=user_id, is_active=True)
        .order_by(TrainingGoal.event_date.asc().nullslast(), TrainingGoal.created_at.desc())
        .first()
    )


def _build_tss_by_date(db: Session, user_id: int, us: UserSettings | None) -> dict[date, float]:
    """Build a {date: tss} dict from claimed-device activities.

    Keyed by the day in the account's zone (calculators/local_day.py): an
    evening session in California is today's load, not tomorrow's.
    """
    threshold_hr = _effective_threshold_hr(us)
    tz = us.timezone if us else None
    q = db.query(Activity).filter(Activity.started_at.isnot(None))
    q = q.filter(Activity.device_id.in_(_claimed_device_ids(db, user_id)))
    activities = q.order_by(Activity.started_at).all()
    discipline = active_mtb_discipline(db, user_id)
    calibration = load_calibration(activities, threshold_hr, discipline, tz)

    tss_by_date: dict[date, float] = {}
    for act in activities:
        d = activity_local_date(act.started_at, tz)
        tss_by_date[d] = tss_by_date.get(d, 0.0) + estimate_tss(act, threshold_hr, discipline, calibration)
    return tss_by_date


def _ctl_atl_today(tss_by_date: dict[date, float], today: date) -> tuple[float, float, float | None]:
    """Return (ctl, atl, ctl_7d_ago).

    Forces today into the range with 0 TSS so the EMA decays correctly from
    the last activity date up to and including today.
    """
    if not tss_by_date:
        return 0.0, 0.0, None

    extended = dict(tss_by_date)
    extended.setdefault(today, 0.0)

    daily_loads = [{"date": d, "tss": t} for d, t in sorted(extended.items())]
    points = calculate_ctl_atl_tsb(daily_loads)
    by_date = {p["date"]: p for p in points}

    today_pt = by_date[today]
    week_pt  = by_date.get(today - timedelta(days=7))

    return today_pt["ctl"], today_pt["atl"], (week_pt["ctl"] if week_pt else None)


def _event_load(db: Session, user_id: int, today: date, counts) -> tuple[float, float, float]:
    """(CTL today, TSS of the last six weeks that ``counts`` toward an event,
    all TSS of the last six weeks) — the inputs to a recommended event date
    (calculators/event_date.py). CTL is the dashboard's own number, from the
    same activities `_build_tss_by_date` reads."""
    from app.calculators.event_date import HISTORY_DAYS

    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    ctl, _atl, _ = _ctl_atl_today(_build_tss_by_date(db, user_id, us), today)
    threshold_hr = _effective_threshold_hr(us)
    discipline = active_mtb_discipline(db, user_id)
    since = today - timedelta(days=HISTORY_DAYS)
    q = db.query(Activity).filter(Activity.started_at.isnot(None))
    q = q.filter(Activity.device_id.in_(_claimed_device_ids(db, user_id)))
    activities = q.order_by(Activity.started_at).all()
    tz = us.timezone if us else None
    calibration = load_calibration(activities, threshold_hr, discipline, tz)
    sport_tss = total_tss = 0.0
    for act in activities:
        # By local calendar date, as _build_tss_by_date buckets them.
        if not (since < activity_local_date(act.started_at, tz) <= today):
            continue
        tss = estimate_tss(act, threshold_hr, discipline, calibration)
        total_tss += tss
        if counts(act.sport):
            sport_tss += tss
    return ctl, sport_tss, total_tss


def _acute_load_today(tss_by_date: dict[date, float], today: date) -> float:
    """Acute training load as-of `today`: a fast (~2-day) EMA of daily TSS.

    Readiness-only companion to ATL, used by the acute recovery model to gauge
    how quickly fatigue clears after a hard session. Fills rest days with 0 (and
    forces today in) so the EMA decays right up to today. Does NOT touch the
    shared CTL/ATL/TSB series.
    """
    if not tss_by_date:
        return 0.0
    extended = dict(tss_by_date)
    extended.setdefault(today, 0.0)
    series: list[float] = []
    d = min(extended)
    while d <= today:
        series.append(extended.get(d, 0.0))
        d += timedelta(days=1)
    return acute_load_ema(series)[-1]


def _recent_metrics(db: Session, user_id: int, today: date, limit: int = 7) -> list[DailyMetric]:
    """The `limit` daily-metric rows immediately before `today`, newest first."""
    return (
        db.query(DailyMetric)
        .filter(DailyMetric.user_id == user_id, DailyMetric.date < today)
        .order_by(DailyMetric.date.desc())
        .limit(limit)
        .all()
    )


def _build_result(db: Session, user: User, us: UserSettings | None,
                  today: date, force: bool) -> CoachingResult:
    """Compute (or return cached) coaching result for today."""
    if not force:
        cached = (
            db.query(CoachingRecommendation)
            .filter_by(user_id=user.id, date=today)
            .first()
        )
        if cached and cached.recommendations:
            # Reconstruct the dataclasses from the JSON cache. Filter to known
            # fields so a cache row written by an older/newer recommender shape
            # (fewer or extra keys) still reconstructs instead of raising.
            rec_fields = {f.name for f in fields(WorkoutRecommendation)}
            signal = TrainingSignal(**cached.training_signal)
            readiness = ReadinessResult(**cached.readiness_breakdown)
            recs = [
                WorkoutRecommendation(**{k: v for k, v in r.items() if k in rec_fields})
                for r in cached.recommendations
            ]
            return CoachingResult(readiness=readiness, signal=signal, recommendations=recs)

    today_metric = db.query(DailyMetric).filter_by(user_id=user.id, date=today).first()
    recent_metrics = _recent_metrics(db, user.id, today)

    tss_by_date = _build_tss_by_date(db, user.id, us)
    ctl, atl, ctl_7d = _ctl_atl_today(tss_by_date, today)
    tsb = ctl - atl
    acute = _acute_load_today(tss_by_date, today)

    # Same inputs as the live gauge (/coaching/readiness): with no load at all,
    # TSB is unknown rather than 0, so the note and the gauge never disagree.
    readiness = compute_readiness(today_metric, recent_metrics,
                                  tsb=tsb if (ctl or atl) else None,
                                  acute_load=acute, chronic_load=ctl)
    goal = _active_goal(db, user.id)

    # 90-day activity history drives the recommender's modality/sport signals,
    # each on its local day (local_history).
    tz = us.timezone if us else None
    ninety_days_ago = today - timedelta(days=90)
    history = local_history(
        db.query(Activity)
        .filter(
            Activity.user_id == user.id,
            Activity.started_at >= local_day_start(ninety_days_ago, tz),
            Activity.sport.isnot(None),
            Activity.device_id.in_(_claimed_device_ids(db, user.id)),
        )
        .order_by(Activity.started_at.desc())
        .all(),
        tz,
    )

    # In-app strength sessions (logged via the guided player) never become
    # Activity rows, so fold their completion dates into the modality-balance
    # signal directly. Keeps "days since last strength" honest for gym work.
    strength_dates = [
        (activity_local_date(c, tz) if isinstance(c, datetime) else c)
        for (c,) in (
            db.query(UserWorkoutSession.completed_at)
            .filter(
                UserWorkoutSession.user_id == user.id,
                UserWorkoutSession.completed_at >= local_day_start(ninety_days_ago, tz),
            )
            .all()
        )
        if c is not None
    ]

    result = compute_recommendations(
        readiness_result=readiness,
        ctl=ctl,
        atl=atl,
        ctl_7d_ago=ctl_7d,
        activity_history=history,
        goal=goal,
        threshold_hr=_effective_threshold_hr(us),
        today=today,
        n_recommendations=3,
        strength_session_dates=strength_dates,
    )

    _cache_result(db, user.id, today, result, goal)
    return result


def _cache_result(db: Session, user_id: int, today: date,
                  result: CoachingResult, goal) -> None:
    """Upsert today's CoachingRecommendation row from a freshly computed result."""
    readiness_dict = asdict(result.readiness)
    signal_dict    = asdict(result.signal)
    recs_list      = [asdict(r) for r in result.recommendations]

    existing = db.query(CoachingRecommendation).filter_by(user_id=user_id, date=today).first()
    if existing:
        existing.readiness_score     = result.readiness.score
        existing.readiness_breakdown = readiness_dict
        existing.training_signal     = signal_dict
        existing.recommendations     = recs_list
        existing.goal_id             = goal.id if goal else None
        existing.generated_at        = datetime.now(timezone.utc)
    else:
        db.add(CoachingRecommendation(
            user_id=user_id,
            date=today,
            readiness_score=result.readiness.score,
            readiness_breakdown=readiness_dict,
            training_signal=signal_dict,
            recommendations=recs_list,
            goal_id=goal.id if goal else None,
        ))
    db.commit()


def _result_to_schema(result: CoachingResult, today: date,
                      ai_enhanced: bool = False, ai_response: str | None = None) -> DailyCoachingOut:
    return DailyCoachingOut(
        date=today,
        readiness=ReadinessOut(**asdict(result.readiness)),
        signal=TrainingSignalOut(**asdict(result.signal)),
        recommendations=[WorkoutRecommendationOut(**asdict(rec)) for rec in result.recommendations],
        ai_enhanced=ai_enhanced,
        ai_response=ai_response,
        generated_at=datetime.now(timezone.utc),
    )
