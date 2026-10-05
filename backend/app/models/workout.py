# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Workout builder models — custom workouts with algorithmic progressive overload.

user_workouts        — saved workout definitions with tags
user_workout_exercises — individual exercises within a workout
user_workout_sessions   — completed workout log entries
"""
from sqlalchemy import (
    Boolean, Column, DateTime, Float, ForeignKey, Integer, String, Text,
    UniqueConstraint, Index,
)
from sqlalchemy.sql import func
from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class UserWorkout(Base, Synced):
    __tablename__ = "user_workouts"

    id          = Column(Integer, primary_key=True)
    user_id     = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    name        = Column(String(128), nullable=False)
    description = Column(Text)
    # tags: ["upper_body","lower_body","core","push","pull","aerobic",...]
    tags        = Column(PJson, nullable=False, default=lambda: [])
    include_in_plan = Column(Boolean, nullable=False, default=True)
    sync_to_watch = Column(Boolean, nullable=False, default=False)
    created_at  = Column(DateTime(timezone=True), server_default=func.now())
    updated_at  = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (UniqueConstraint("user_id", "name"),)


sync_indexes(UserWorkout)


class UserWorkoutExercise(Base, Synced):
    __tablename__ = "user_workout_exercises"

    id               = Column(Integer, primary_key=True)
    workout_id       = Column(Integer, ForeignKey("user_workouts.id", ondelete="CASCADE"), nullable=False)
    # Denormalised from the workout; see FlowStretch.user_id.
    user_id          = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # Null on a rest block, which names no exercise.
    exercise_name    = Column(String(128), nullable=True)
    # "library" or "custom"
    exercise_source  = Column(String(16), nullable=False, default="library")
    # Target reps per set — one number, not a range: the watch's workout step
    # has a single reps duration, so a range could only ever be half-written
    # to it. 8 is also the FIT encoder's default for a step with no reps.
    target_reps      = Column(Integer, nullable=False, default=8)
    target_sets      = Column(Integer, nullable=False, default=3)
    # Reps in Reserve target (0=to failure, 2=standard working set)
    rir_target       = Column(Integer, nullable=False, default=2)
    # Rest between sets (seconds)
    rest_seconds     = Column(Integer, nullable=False, default=90)
    # Weight prescription method: "fixed", "percentage_e1rm", "rpe"
    weight_method    = Column(String(16), nullable=False, default="percentage_e1rm")
    # For fixed method: the literal weight in kg
    # For percentage_e1rm: percentage of e1RM (0.0-1.0 scale)
    # For rpe: target RPE (1-10)
    weight_value     = Column(Float)
    # A fractional ordering key (app.sync.order); see FlowStretch.order_index.
    order_index      = Column(String(64, collation="C"), nullable=False, default="V")
    # Notes/coaching cues for this specific exercise
    notes            = Column(Text)
    # Blocks — see spec/sync.yaml, workout_exercise. `item_kind` is "rest" for
    # a rest block (duration in rest_seconds) and null for an exercise. The
    # group_* columns are repeated on every member of a repeat group or
    # superset; consecutive rows sharing group_uid form the block.
    item_kind           = Column(String(16), nullable=True)
    group_uid           = Column(String(64), nullable=True)
    group_kind          = Column(String(16), nullable=True)
    group_rounds        = Column(Integer, nullable=True)
    group_rest_seconds  = Column(Integer, nullable=True)
    created_at       = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (Index("ix_wo_ex_workout", "workout_id"),)


sync_indexes(UserWorkoutExercise)


class UserWorkoutSession(Base, Synced):
    """Completed workout log — one row per workout execution."""
    __tablename__ = "user_workout_sessions"

    id            = Column(Integer, primary_key=True)
    workout_id    = Column(Integer, ForeignKey("user_workouts.id", ondelete="SET NULL"), nullable=True)
    # The generated PlannedWorkout this session completed, if logged from the
    # guided session runner (rather than the manual workout builder).
    planned_workout_id = Column(Integer, ForeignKey("planned_workouts.id", ondelete="SET NULL"), nullable=True)
    user_id       = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # JSONB snapshot of the workout + actual reps/weights logged
    # [{exercise_name, target_sets, target_reps,
    #   sets: [{weight_kg, reps, rpe}], ...}]
    session_data  = Column(PJson, nullable=False, default=lambda: [])
    # When the workout was performed
    completed_at  = Column(DateTime(timezone=True), server_default=func.now())
    # Total volume for the session (sum of weight×reps across all sets)
    total_volume_kg = Column(Float)
    # Perceived exertion 1-10 for the whole session
    session_rpe   = Column(Integer)
    notes         = Column(Text)
    created_at    = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (Index("ix_wo_session_user", "user_id"),
                       Index("ix_wo_session_workout", "workout_id"),
                       Index("ix_wo_session_planned", "planned_workout_id"))


sync_indexes(UserWorkoutSession)
