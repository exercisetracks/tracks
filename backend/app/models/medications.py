# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Boolean, Column, Integer, Text, Date, Time, DateTime, ForeignKey, Index, text
from sqlalchemy.sql import func
from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class Medication(Base, Synced):
    __tablename__ = "medications"

    id         = Column(Integer, primary_key=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    name       = Column(Text, nullable=False)
    dose       = Column(Text)
    dose_unit  = Column(Text)
    form       = Column(Text)
    notes      = Column(Text)
    is_active  = Column(Boolean, nullable=False, server_default=text("true"))
    created_at = Column(DateTime(timezone=True), server_default=func.now())


sync_indexes(Medication)


class MedicationSchedule(Base, Synced):
    __tablename__ = "medication_schedules"

    id            = Column(Integer, primary_key=True)
    medication_id = Column(Integer, ForeignKey("medications.id", ondelete="CASCADE"), nullable=False)
    # Denormalised from the medication; see FlowStretch.user_id.
    user_id       = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    time_of_day   = Column(Time, nullable=False)
    days_of_week  = Column(PJson)
    start_date    = Column(Date, nullable=False, server_default=func.current_date())
    end_date      = Column(Date)
    notify        = Column(Boolean, nullable=False, server_default=text("false"))
    is_as_needed  = Column(Boolean, nullable=False, server_default=text("false"))

    __table_args__ = (Index("medication_schedules_med", "medication_id"),)


sync_indexes(MedicationSchedule)


class MedicationLog(Base, Synced):
    __tablename__ = "medication_log"

    id            = Column(Integer, primary_key=True)
    user_id       = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    medication_id = Column(Integer, ForeignKey("medications.id", ondelete="CASCADE"), nullable=False)
    schedule_id   = Column(Integer, ForeignKey("medication_schedules.id", ondelete="SET NULL"))
    status        = Column(Text, nullable=False)
    scheduled_for = Column(DateTime(timezone=True))
    logged_at     = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    notes         = Column(Text)

    __table_args__ = (Index("medication_log_user_date", "user_id", "logged_at"),)


sync_indexes(MedicationLog)
