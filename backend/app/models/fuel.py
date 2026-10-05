# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What an athlete eats and drinks while racing, and how their gut took it.

`FuelProduct` is the user's own library — the gels and drinks they actually
carry — from which a race plan's fuelling timeline is built. `GutTrainingLog`
records how a fuelled long session went; the gut-training progression
(calculators/fuel_plan.py) adapts from it, identically on the phone.
"""
from sqlalchemy import Column, Date, ForeignKey, Integer, Numeric, String, Text, DateTime
from sqlalchemy.sql import func

from app.database import Base
from app.models.sync import Synced, sync_indexes


class FuelProduct(Base, Synced):
    __tablename__ = "fuel_products"

    id          = Column(Integer, primary_key=True)
    user_id     = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    name        = Column(Text, nullable=False)
    kind        = Column(String, nullable=False, server_default="gel")  # gel|drink|chew|bar|other
    carbs_g     = Column(Numeric(6, 1), nullable=False, server_default="0")
    sodium_mg   = Column(Integer, nullable=False, server_default="0")
    caffeine_mg = Column(Integer, nullable=False, server_default="0")
    fluid_ml    = Column(Integer, nullable=False, server_default="0")
    serving     = Column(Text)
    notes       = Column(Text)
    created_at  = Column(DateTime(timezone=True), server_default=func.now())


sync_indexes(FuelProduct)


class GutTrainingLog(Base, Synced):
    __tablename__ = "gut_training_logs"

    id                 = Column(Integer, primary_key=True)
    user_id            = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    planned_workout_id = Column(Integer, ForeignKey("planned_workouts.id", ondelete="SET NULL"))
    activity_id        = Column(Integer, ForeignKey("activities.id", ondelete="SET NULL"))
    date               = Column(Date, nullable=False)
    duration_min       = Column(Integer, nullable=True)
    carbs_g            = Column(Numeric(6, 1), nullable=False, server_default="0")
    comfort            = Column(Integer, nullable=False)   # 1 (bad) .. 5 (no issues)
    notes              = Column(Text)
    created_at         = Column(DateTime(timezone=True), server_default=func.now())


sync_indexes(GutTrainingLog)
