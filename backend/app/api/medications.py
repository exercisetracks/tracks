# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime, timezone, timedelta, date, time
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.medications import Medication, MedicationSchedule, MedicationLog

router = APIRouter(prefix="/medications", tags=["medications"])


# ── Schemas ───────────────────────────────────────────────────────────────────

class ScheduleIn(BaseModel):
    time_of_day:  str              # "HH:MM"
    days_of_week: Optional[list[int]] = None
    start_date:   Optional[str]    = None
    end_date:     Optional[str]    = None
    notify:       bool             = False
    is_as_needed: bool             = False


class ScheduleOut(BaseModel):
    id:           int
    time_of_day:  str
    days_of_week: Optional[list[int]]
    start_date:   Optional[str]
    end_date:     Optional[str]
    notify:       bool
    is_as_needed: bool

    model_config = {"from_attributes": True}

    @classmethod
    def from_orm_obj(cls, s: MedicationSchedule) -> "ScheduleOut":
        return cls(
            id=s.id,
            time_of_day=s.time_of_day.strftime("%H:%M") if s.time_of_day else "00:00",
            days_of_week=s.days_of_week,
            start_date=s.start_date.isoformat() if s.start_date else None,
            end_date=s.end_date.isoformat() if s.end_date else None,
            notify=s.notify,
            is_as_needed=s.is_as_needed,
        )


class MedicationIn(BaseModel):
    name:      str
    dose:      Optional[str] = None
    dose_unit: Optional[str] = None
    form:      Optional[str] = None
    notes:     Optional[str] = None
    is_active: bool          = True
    schedules: list[ScheduleIn] = []


class MedicationOut(BaseModel):
    id:        int
    name:      str
    dose:      Optional[str]
    dose_unit: Optional[str]
    form:      Optional[str]
    notes:     Optional[str]
    is_active: bool
    schedules: list[ScheduleOut]

    model_config = {"from_attributes": True}


class LogIn(BaseModel):
    medication_id: int
    schedule_id:   Optional[int]   = None
    status:        str             # 'taken' | 'skipped' | 'as_needed'
    scheduled_for: Optional[datetime] = None
    notes:         Optional[str]   = None


class LogOut(BaseModel):
    id:            int
    medication_id: int
    schedule_id:   Optional[int]
    status:        str
    scheduled_for: Optional[datetime]
    logged_at:     datetime
    notes:         Optional[str]

    model_config = {"from_attributes": True}


# ── Helpers ───────────────────────────────────────────────────────────────────

def _parse_time(s: str) -> time:
    h, m = s.split(":")
    return time(int(h), int(m))


def _build_schedules(db: Session, med_id: int, schedules: list[ScheduleIn]):
    for s in schedules:
        sched = MedicationSchedule(
            medication_id=med_id,
            time_of_day=_parse_time(s.time_of_day),
            days_of_week=s.days_of_week,
            start_date=date.fromisoformat(s.start_date) if s.start_date else date.today(),
            end_date=date.fromisoformat(s.end_date) if s.end_date else None,
            notify=s.notify,
            is_as_needed=s.is_as_needed,
        )
        db.add(sched)


def _med_out(db: Session, med: Medication) -> MedicationOut:
    scheds = db.query(MedicationSchedule).filter_by(medication_id=med.id).all()
    return MedicationOut(
        id=med.id,
        name=med.name,
        dose=med.dose,
        dose_unit=med.dose_unit,
        form=med.form,
        notes=med.notes,
        is_active=med.is_active,
        schedules=[ScheduleOut.from_orm_obj(s) for s in scheds],
    )


# ── Medication CRUD ───────────────────────────────────────────────────────────

@router.get("", response_model=list[MedicationOut])
def list_medications(db: Session = Depends(get_db), user: User = Depends(require_auth)):
    meds = db.query(Medication).filter_by(user_id=user.id).order_by(Medication.name).all()
    return [_med_out(db, m) for m in meds]


@router.post("", response_model=MedicationOut, status_code=201)
def create_medication(body: MedicationIn, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    med = Medication(
        user_id=user.id,
        name=body.name,
        dose=body.dose,
        dose_unit=body.dose_unit,
        form=body.form,
        notes=body.notes,
        is_active=body.is_active,
    )
    db.add(med)
    db.flush()
    _build_schedules(db, med.id, body.schedules)
    db.commit()
    db.refresh(med)
    return _med_out(db, med)


@router.patch("/{med_id}", response_model=MedicationOut)
def update_medication(med_id: int, body: MedicationIn, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    med = db.query(Medication).filter_by(id=med_id, user_id=user.id).first()
    if med is None:
        raise HTTPException(status_code=404, detail="Medication not found")
    for k in ("name", "dose", "dose_unit", "form", "notes", "is_active"):
        setattr(med, k, getattr(body, k))
    # Replace schedules
    db.query(MedicationSchedule).filter_by(medication_id=med.id).delete()
    _build_schedules(db, med.id, body.schedules)
    db.commit()
    db.refresh(med)
    return _med_out(db, med)


@router.delete("/{med_id}", status_code=204)
def delete_medication(med_id: int, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    med = db.query(Medication).filter_by(id=med_id, user_id=user.id).first()
    if med is None:
        raise HTTPException(status_code=404, detail="Medication not found")
    db.delete(med)
    db.commit()


# ── Medication log ────────────────────────────────────────────────────────────

@router.get("/log", response_model=list[LogOut])
def list_log(
    days: int = 30,
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    since = datetime.now(timezone.utc) - timedelta(days=days)
    return (
        db.query(MedicationLog)
        .filter(MedicationLog.user_id == user.id, MedicationLog.logged_at >= since)
        .order_by(MedicationLog.logged_at.desc())
        .all()
    )


@router.post("/log", response_model=LogOut, status_code=201)
def log_dose(body: LogIn, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    med = db.query(Medication).filter_by(id=body.medication_id, user_id=user.id).first()
    if med is None:
        raise HTTPException(status_code=404, detail="Medication not found")
    entry = MedicationLog(
        user_id=user.id,
        medication_id=body.medication_id,
        schedule_id=body.schedule_id,
        status=body.status,
        scheduled_for=body.scheduled_for,
        logged_at=datetime.now(timezone.utc),
        notes=body.notes,
    )
    db.add(entry)
    db.commit()
    db.refresh(entry)
    return entry


@router.delete("/log/{entry_id}", status_code=204)
def delete_log(entry_id: int, db: Session = Depends(get_db), user: User = Depends(require_auth)):
    e = db.query(MedicationLog).filter_by(id=entry_id, user_id=user.id).first()
    if e is None:
        raise HTTPException(status_code=404, detail="Log entry not found")
    db.delete(e)
    db.commit()


@router.get("/due")
def get_due_medications(
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
):
    """
    Returns all scheduled doses for today with their log status.
    The frontend uses this to drive notification timing.
    """
    today = date.today()
    now   = datetime.now(timezone.utc)

    meds = db.query(Medication).filter_by(user_id=user.id, is_active=True).all()
    result = []

    for med in meds:
        scheds = db.query(MedicationSchedule).filter_by(medication_id=med.id).all()
        for s in scheds:
            if s.is_as_needed:
                continue
            if s.end_date and s.end_date < today:
                continue
            if s.start_date and s.start_date > today:
                continue
            dow = today.weekday()  # 0=Mon; convert to 0=Sun convention
            dow_sun = (dow + 1) % 7
            if s.days_of_week and dow_sun not in s.days_of_week:
                continue

            scheduled_dt = datetime.combine(today, s.time_of_day, tzinfo=timezone.utc)
            # Check if already logged today for this schedule
            logged = db.query(MedicationLog).filter(
                MedicationLog.user_id == user.id,
                MedicationLog.schedule_id == s.id,
                MedicationLog.scheduled_for >= datetime.combine(today, time(0, 0), tzinfo=timezone.utc),
            ).first()

            result.append({
                "medication_id":   med.id,
                "medication_name": med.name,
                "dose":            med.dose,
                "dose_unit":       med.dose_unit,
                "schedule_id":     s.id,
                "time_of_day":     s.time_of_day.strftime("%H:%M"),
                "scheduled_for":   scheduled_dt.isoformat(),
                "notify":          s.notify,
                "status":          logged.status if logged else None,
                "log_id":          logged.id if logged else None,
                "is_overdue":      now > scheduled_dt and not logged,
            })

    result.sort(key=lambda x: x["time_of_day"])
    return result
