# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""iCalendar (.ics) subscription tokens and public calendar feeds.

Two routers:
  router      authenticated /coaching token management:
                GET/DELETE /goals/{id}/ics-token   per-goal token (legacy)
                GET/DELETE /ics-token              permanent per-user token
  ics_router  public, no-auth feeds subscribed by external calendar apps:
                GET /ics/user/{token}  all active event goals in one calendar
                GET /ics/{token}       single-goal feed (legacy)

The feed body is hand-assembled to RFC 5545 (VCALENDAR/VEVENT with line
folding) rather than pulling in an icalendar dependency.
"""

import secrets
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import PlainTextResponse
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.config import settings
from app.database import get_db
from app.models.activity import User
from app.models.coaching import PLAN_GOAL_TYPES, TrainingGoal
from app.models.training_plan import (
    IcsToken,
    PlannedWorkout,
    TrainingPlan,
    UserIcsToken,
)
from app.schemas.training_plan import IcsTokenOut, UserIcsTokenOut

from .helpers import _get_goal_or_404

router = APIRouter()
ics_router = APIRouter(tags=["ics"])   # no auth — subscribed by external calendar apps


# ─────────────────────────────────────────
# Per-goal ICS token management
# ─────────────────────────────────────────

def _ics_url(token: str, request_base: str | None = None) -> str:
    base = (request_base or "").rstrip("/") or "http://localhost:8000"
    return f"{base}/ics/{token}"


@router.get("/goals/{goal_id}/ics-token", response_model=IcsTokenOut)
def get_or_create_ics_token(
    goal_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    _get_goal_or_404(db, goal_id, user)
    tok = db.query(IcsToken).filter_by(goal_id=goal_id, user_id=user.id).first()
    if tok is None:
        tok = IcsToken(
            goal_id=goal_id,
            user_id=user.id,
            token=secrets.token_urlsafe(24),
        )
        db.add(tok)
        db.commit()
        db.refresh(tok)

    return IcsTokenOut(goal_id=tok.goal_id, token=tok.token,
                       ics_url=_ics_url(tok.token, settings.public_base_url))


@router.delete("/goals/{goal_id}/ics-token", response_model=IcsTokenOut)
def regenerate_ics_token(
    goal_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Revoke the current ICS token and issue a new one."""
    _get_goal_or_404(db, goal_id, user)
    tok = db.query(IcsToken).filter_by(goal_id=goal_id, user_id=user.id).first()
    if tok:
        db.delete(tok)
        db.flush()

    tok = IcsToken(
        goal_id=goal_id,
        user_id=user.id,
        token=secrets.token_urlsafe(24),
    )
    db.add(tok)
    db.commit()
    db.refresh(tok)

    return IcsTokenOut(goal_id=tok.goal_id, token=tok.token,
                       ics_url=_ics_url(tok.token, settings.public_base_url))


# ─────────────────────────────────────────
# User-level ICS token (permanent, survives goal changes)
# ─────────────────────────────────────────

def _user_ics_url(token: str) -> str:
    return f"{settings.public_base_url.rstrip('/')}/ics/user/{token}"


@router.get("/ics-token", response_model=UserIcsTokenOut)
def get_or_create_user_ics_token(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Return (or create) the user's permanent calendar subscription token."""
    tok = db.query(UserIcsToken).filter_by(user_id=user.id).first()
    if tok is None:
        tok = UserIcsToken(user_id=user.id, token=secrets.token_urlsafe(24))
        db.add(tok)
        db.commit()
        db.refresh(tok)
    return UserIcsTokenOut(token=tok.token, ics_url=_user_ics_url(tok.token))


@router.delete("/ics-token", response_model=UserIcsTokenOut)
def regenerate_user_ics_token(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Revoke the current user ICS token and issue a new one."""
    tok = db.query(UserIcsToken).filter_by(user_id=user.id).first()
    if tok:
        db.delete(tok)
        db.flush()
    tok = UserIcsToken(user_id=user.id, token=secrets.token_urlsafe(24))
    db.add(tok)
    db.commit()
    db.refresh(tok)
    return UserIcsTokenOut(token=tok.token, ics_url=_user_ics_url(tok.token))


# ─────────────────────────────────────────
# Public ICS feeds (no auth)
# ─────────────────────────────────────────

def _build_ics(goal_plans: list[tuple[TrainingGoal, list[PlannedWorkout]]],
               cal_name: str = "Tracks Training") -> str:
    """Manually assemble an RFC 5545-compliant iCalendar feed from one or more goal/workout pairs."""

    def _escape(text: str) -> str:
        return text.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    def _fold(line: str) -> str:
        if len(line.encode()) <= 75:
            return line
        parts = []
        while len(line.encode()) > 75:
            cut = 74
            while len(line[:cut].encode()) > 74:
                cut -= 1
            parts.append(line[:cut])
            line = " " + line[cut:]
        parts.append(line)
        return "\r\n".join(parts)

    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Tracks//Training Calendar//EN",
        "CALSCALE:GREGORIAN",
        "METHOD:PUBLISH",
        f"X-WR-CALNAME:{_escape(cal_name)}",
        "X-WR-TIMEZONE:UTC",
    ]

    for goal, workouts in goal_plans:
        uid_base = f"tracks-{goal.id}"

        # Race event
        if goal.event_date:
            lines += [
                "BEGIN:VEVENT",
                f"UID:{uid_base}-race@tracks",
                f"DTSTART;VALUE=DATE:{goal.event_date.strftime('%Y%m%d')}",
                f"DTEND;VALUE=DATE:{(goal.event_date + timedelta(days=1)).strftime('%Y%m%d')}",
                f"SUMMARY:{_escape(goal.event_name or 'Race')}",
                "CATEGORIES:RACE",
                "END:VEVENT",
            ]

        for w in workouts:
            if w.workout_type == "race":
                continue
            dt_start = w.scheduled_date.strftime("%Y%m%d")
            dt_end   = (w.scheduled_date + timedelta(days=1)).strftime("%Y%m%d")
            lines += [
                "BEGIN:VEVENT",
                f"UID:{uid_base}-workout-{w.id}@tracks",
                f"DTSTART;VALUE=DATE:{dt_start}",
                f"DTEND;VALUE=DATE:{dt_end}",
                f"SUMMARY:{_escape(w.title)}",
            ]
            if w.description:
                lines.append(f"DESCRIPTION:{_escape(w.description)}")
            lines.append("END:VEVENT")

    lines.append("END:VCALENDAR")
    return "\r\n".join(_fold(line) for line in lines) + "\r\n"


def _ics_response(content: str, filename: str = "training-plan.ics") -> PlainTextResponse:
    return PlainTextResponse(
        content=content,
        media_type="text/calendar; charset=utf-8",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


@ics_router.get("/ics/user/{token}", response_class=PlainTextResponse)
def get_user_ics_feed(token: str, db: Session = Depends(get_db)):
    """Public user-level .ics feed — every active planned goal's workouts in one calendar."""
    tok = db.query(UserIcsToken).filter_by(token=token).first()
    if tok is None:
        raise HTTPException(status_code=404, detail="Calendar not found")

    goals = (
        db.query(TrainingGoal)
        .filter(TrainingGoal.user_id == tok.user_id, TrainingGoal.is_active.is_(True),
                TrainingGoal.goal_type.in_(PLAN_GOAL_TYPES))
        .order_by(TrainingGoal.event_date.asc().nullslast())
        .all()
    )

    goal_plans = []
    for goal in goals:
        plan = db.query(TrainingPlan).filter_by(goal_id=goal.id).first()
        workouts = (
            db.query(PlannedWorkout)
            .filter_by(plan_id=plan.id)
            .order_by(PlannedWorkout.scheduled_date)
            .all()
        ) if plan else []
        goal_plans.append((goal, workouts))

    return _ics_response(_build_ics(goal_plans))


@ics_router.get("/ics/{token}", response_class=PlainTextResponse)
def get_ics_feed(token: str, db: Session = Depends(get_db)):
    """Per-goal .ics feed (legacy — kept for existing subscriptions)."""
    tok = db.query(IcsToken).filter_by(token=token).first()
    if tok is None:
        raise HTTPException(status_code=404, detail="Calendar not found")

    goal = db.query(TrainingGoal).get(tok.goal_id)
    if goal is None:
        raise HTTPException(status_code=404, detail="Goal not found")

    plan = db.query(TrainingPlan).filter_by(goal_id=tok.goal_id).first()
    workouts = (
        db.query(PlannedWorkout)
        .filter_by(plan_id=plan.id)
        .order_by(PlannedWorkout.scheduled_date)
        .all()
    ) if plan else []

    return _ics_response(_build_ics([(goal, workouts)], cal_name=goal.event_name or "Training Plan"))
