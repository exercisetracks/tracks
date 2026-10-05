# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, Numeric, Text, ForeignKey, DateTime, Index
from sqlalchemy.sql import func
from app.database import Base
from app.models.sync import Synced, sync_indexes


class Meal(Base, Synced):
    __tablename__ = "meals"

    id         = Column(Integer, primary_key=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    name       = Column(Text, nullable=False)
    calories   = Column(Integer, nullable=False, server_default="0")
    protein_g  = Column(Numeric(6, 1))
    carbs_g    = Column(Numeric(6, 1))
    fat_g      = Column(Numeric(6, 1))
    notes      = Column(Text)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


sync_indexes(Meal)


class MealLog(Base, Synced):
    __tablename__ = "meal_log"

    id        = Column(Integer, primary_key=True)
    user_id   = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    meal_id   = Column(Integer, ForeignKey("meals.id", ondelete="SET NULL"))
    name      = Column(Text, nullable=False)
    calories  = Column(Integer, nullable=False, server_default="0")
    protein_g = Column(Numeric(6, 1))
    carbs_g   = Column(Numeric(6, 1))
    fat_g     = Column(Numeric(6, 1))
    logged_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    notes     = Column(Text)

    __table_args__ = (Index("meal_log_user_date", "user_id", "logged_at"),)


sync_indexes(MealLog)
