# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import (
    Column, Integer, Float, Date, DateTime, ForeignKey, Index, UniqueConstraint,
)
from sqlalchemy.sql import func
from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class DailyMetric(Base, Synced):
    __tablename__ = "daily_metrics"

    id                   = Column(Integer, primary_key=True)
    date                 = Column(Date, nullable=False)
    user_id              = Column(Integer, ForeignKey("users.id"), nullable=False)
    device_id            = Column(Integer, ForeignKey("devices.id"), nullable=True)
    resting_hr           = Column(Float, nullable=True)
    hrv                  = Column(Float, nullable=True)
    sleep_hours          = Column(Float, nullable=True)
    sleep_score          = Column(Float, nullable=True)
    sleep_deep_hours     = Column(Float, nullable=True)
    sleep_light_hours    = Column(Float, nullable=True)
    sleep_rem_hours      = Column(Float, nullable=True)
    # Time in bed but not asleep. The watch measures it and the
    # parser was throwing it away; it is deliberately not part of
    # sleep_hours, which is time *asleep*.
    sleep_awake_hours    = Column(Float, nullable=True)
    training_load        = Column(Float, nullable=True)
    # Watch-derived daily activity metrics
    steps                = Column(Integer, nullable=True)
    active_calories      = Column(Integer, nullable=True)
    # What the body spent simply existing, pro-rated to how much of the
    # day has happened. Total calories is this plus active_calories.
    resting_calories     = Column(Integer, nullable=True)
    avg_stress_level     = Column(Float, nullable=True)
    avg_respiration_rate = Column(Float, nullable=True)
    # Pulse oximetry — average SpO2 for the day (%)
    spo2                 = Column(Float, nullable=True)
    # Body Battery, as the watch computes it (0-100). Garmin does not publish
    # the model, so these are stored exactly as reported and never derived here:
    # the whole value of the number is that it is the device's own opinion of
    # how much is left in the tank, and a reimplementation would be a different
    # metric wearing the same name.
    #
    # High and low bound the day; `last` is the most recent reading, which is
    # "right now" for today and "at bedtime" for any earlier day. Charged and
    # drained are the day's totals gained and spent — they do not simply equal
    # high minus low, because a day can rise and fall several times.
    body_battery_high    = Column(Integer, nullable=True)
    body_battery_low     = Column(Integer, nullable=True)
    body_battery_last    = Column(Integer, nullable=True)
    body_battery_charged = Column(Integer, nullable=True)
    body_battery_drained = Column(Integer, nullable=True)
    # Manually-entered body / nutrition metrics
    weight_kg            = Column(Float, nullable=True)
    hydration_ml         = Column(Integer, nullable=True)
    calories_in          = Column(Integer, nullable=True)
    extra                = Column(PJson, default=lambda: {})
    # Cursor for incremental client sync — see Activity.updated_at.
    updated_at           = Column(DateTime(timezone=True), server_default=func.now(),
                                  onupdate=func.now())

    # ── Derived from `extra`, not stored ─────────────────────────────────
    #
    # When the night began and ended, read off the stage timeline the sleep
    # importer already keeps. Columns of their own would mean a migration and
    # a backfill for two numbers that are the first and last elements of a
    # list already sitting on the row — and a column could then disagree with
    # the timeline it was derived from.
    #
    # The whole span, waking included: this is time in bed, which is what a
    # chart of "when did you sleep" is drawing. The stamps are UTC and
    # offset-aware, exactly as stored, so a client shows them in the zone it
    # is standing in rather than in whatever zone the server runs as.

    @property
    def sleep_start(self):
        return self._stage_edge(0, "start")

    @property
    def sleep_end(self):
        return self._stage_edge(-1, "end")

    def _stage_edge(self, index: int, key: str):
        stages = (self.extra or {}).get("sleep_stages") or []
        if not stages:
            return None
        edge = stages[index]
        return edge.get(key) if isinstance(edge, dict) else None

    __table_args__ = (
        Index("idx_daily_metrics_date", "date"),
        Index("idx_daily_metrics_user_id", "user_id"),
        Index("idx_daily_metrics_user_updated", "user_id", "updated_at", "id"),
        UniqueConstraint("date", "user_id"),
    )


sync_indexes(DailyMetric)
