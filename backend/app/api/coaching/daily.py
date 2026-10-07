# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Daily coaching endpoints.

Rule-based (optionally AI-enhanced) day-to-day guidance:
  GET  /coaching/today       cached daily workout recommendations
  GET  /coaching/readiness   current readiness score + breakdown
  POST /coaching/ai-enhance  today's recs with AI coaching notes
  GET  /coaching/plan        7-day forward plan with projected CTL/ATL
  GET  /coaching/history     past cached recommendations, newest first

All are static paths; they register before the dynamic /goals/{id} routes.
"""

from dataclasses import asdict
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.calculators.coaching import compute_weekly_plan
from app.calculators.local_day import local_day_start, local_history, user_today
from app.calculators.readiness import compute_readiness
from app.database import get_db
from app.models.activity import Activity, User
from app.models.coaching import CoachingRecommendation
from app.models.metrics import DailyMetric
from app.schemas.coaching import (
    CoachingHistoryOut,
    DailyCoachingOut,
    PlanDayOut,
    ReadinessOut,
    TrainingSignalOut,
    WorkoutRecommendationOut,
)
from app.services.encryption import decrypt

from .helpers import (
    _acute_load_today,
    _active_goal,
    _build_result,
    _build_tss_by_date,
    _claimed_device_ids,
    _ctl_atl_today,
    _effective_threshold_hr,
    _get_user_and_settings,
    _recent_metrics,
    _result_to_schema,
)

router = APIRouter()


@router.get("/today", response_model=DailyCoachingOut)
def today_recommendation(
    force_refresh: bool = Query(False, description="Recompute even if cached"),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Today's workout recommendations (rule-based, cached per day)."""
    user, us = _get_user_and_settings(db, user)
    if not _claimed_device_ids(db, user.id):
        raise HTTPException(status_code=503, detail="No devices claimed")
    today = user_today(db, user.id)
    result = _build_result(db, user, us, today, force=force_refresh)
    return _result_to_schema(result, today)


@router.get("/readiness", response_model=ReadinessOut)
def readiness(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Current readiness score and breakdown."""
    user, us = _get_user_and_settings(db, user)
    today = user_today(db, user.id)
    today_metric = db.query(DailyMetric).filter_by(user_id=user.id, date=today).first()
    recent = _recent_metrics(db, user.id, today)
    tss_by_date = _build_tss_by_date(db, user.id, us)
    ctl, atl, _ = _ctl_atl_today(tss_by_date, today)
    tsb = ctl - atl if ctl or atl else None
    acute = _acute_load_today(tss_by_date, today)
    return ReadinessOut(**asdict(compute_readiness(
        today_metric, recent, tsb=tsb, acute_load=acute, chronic_load=ctl)))


@router.post("/ai-enhance", response_model=DailyCoachingOut)
async def ai_enhance(
    force_refresh: bool = Query(False),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Return today's recommendations enhanced with AI coaching notes.

    Requires ai_provider, ai_endpoint, and ai_model to be configured in user
    settings.
    """
    from app.services.ai_client import enhance_recommendations

    user, us = _get_user_and_settings(db, user)
    today = user_today(db, user.id)
    result = _build_result(db, user, us, today, force=force_refresh)

    if us is None or not us.ai_provider or not us.ai_model:
        raise HTTPException(
            status_code=422,
            detail="AI provider not configured — set ai_provider, ai_endpoint, and ai_model in user settings",
        )

    api_key = decrypt(us.ai_api_key_enc) if us.ai_api_key_enc else None
    goal = _active_goal(db, user.id)
    context = {
        "readiness":       asdict(result.readiness),
        "signal":          asdict(result.signal),
        "recommendations": [asdict(r) for r in result.recommendations],
        "goal":            goal.event_name if goal else None,
    }

    ai_text = await enhance_recommendations(
        context=context,
        provider=us.ai_provider,
        endpoint=us.ai_endpoint or "",
        model=us.ai_model,
        api_key=api_key,
    )

    # Persist the AI response onto today's cached row.
    cached = db.query(CoachingRecommendation).filter_by(user_id=user.id, date=today).first()
    if cached:
        cached.ai_enhanced = ai_text is not None
        cached.ai_response = ai_text
        db.commit()

    return _result_to_schema(result, today, ai_enhanced=ai_text is not None, ai_response=ai_text)


@router.get("/plan", response_model=list[PlanDayOut])
def weekly_plan(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """7-day forward-looking training plan with projected CTL/ATL."""
    user, us = _get_user_and_settings(db, user)
    today = user_today(db, user.id)

    today_metric = db.query(DailyMetric).filter_by(user_id=user.id, date=today).first()
    recent = _recent_metrics(db, user.id, today)
    plan_tss_by_date = _build_tss_by_date(db, user.id, us)
    plan_ctl, plan_atl, _ = _ctl_atl_today(plan_tss_by_date, today)
    readiness = compute_readiness(
        today_metric, recent, tsb=plan_ctl - plan_atl,
        acute_load=_acute_load_today(plan_tss_by_date, today), chronic_load=plan_ctl)

    # Each on its local day, as the recommender reads them (local_history).
    tz = us.timezone if us else None
    ninety_days_ago = today - timedelta(days=90)
    history = local_history(
        db.query(Activity)
        .filter(Activity.user_id == user.id,
                Activity.started_at >= local_day_start(ninety_days_ago, tz),
                Activity.sport.isnot(None),
                Activity.device_id.in_(_claimed_device_ids(db, user.id)))
        .order_by(Activity.started_at.desc())
        .all(),
        tz,
    )

    plan = compute_weekly_plan(
        readiness_result=readiness,
        tss_by_date=plan_tss_by_date,
        activity_history=history,
        goal=_active_goal(db, user.id),
        threshold_hr=_effective_threshold_hr(us),
        today=today,
    )

    output = []
    for day in plan:
        rec = day["recommendation"]
        output.append(PlanDayOut(
            date=day["date"],
            ctl=day["ctl"],
            atl=day["atl"],
            tsb=day["tsb"],
            recommendation=WorkoutRecommendationOut(**asdict(rec)) if rec else None,
        ))
    return output


@router.get("/history", response_model=list[CoachingHistoryOut])
def coaching_history(
    days: int = Query(30, ge=1, le=365, description="How many days back to return"),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Past daily coaching recommendations from the cache, newest first."""
    since = user_today(db, user.id) - timedelta(days=days - 1)
    rows = (
        db.query(CoachingRecommendation)
        .filter(
            CoachingRecommendation.user_id == user.id,
            CoachingRecommendation.date >= since,
        )
        .order_by(CoachingRecommendation.date.desc())
        .all()
    )

    result = []
    for row in rows:
        signal = TrainingSignalOut(**row.training_signal) if row.training_signal else None
        recs = (
            [WorkoutRecommendationOut(**r) for r in row.recommendations]
            if row.recommendations else None
        )
        result.append(CoachingHistoryOut(
            date=row.date,
            readiness_score=row.readiness_score,
            signal=signal,
            recommendations=recs,
            ai_enhanced=row.ai_enhanced,
            ai_response=row.ai_response,
            generated_at=row.generated_at,
        ))

    return result
