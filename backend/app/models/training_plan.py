# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import (
    BigInteger, Boolean, Column, Date, DateTime, Float, ForeignKey,
    Integer, String, Text, Index, UniqueConstraint,
)
from sqlalchemy.sql import false, func
from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class TrainingPlan(Base, Synced):
    __tablename__ = "training_plans"

    id           = Column(Integer, primary_key=True)
    goal_id      = Column(Integer, ForeignKey("training_goals.id", ondelete="CASCADE"), nullable=False, unique=True)
    user_id      = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    vdot         = Column(Float)
    sport        = Column(String, nullable=False, default="running")
    # A fresh UUIDv7 each time the plan is (re)generated. Generated workouts
    # carry the generation they came from; one whose generation is not the
    # plan's, and is older than it, is dead — see app.sync.merge.workout_is_dead.
    # This is what lets two phones regenerate offline and converge on one set
    # of workouts rather than two.
    generation   = Column(String(36), nullable=True)
    generated_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at   = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


sync_indexes(TrainingPlan)


class PlannedWorkout(Base, Synced):
    __tablename__ = "planned_workouts"

    id                    = Column(Integer, primary_key=True)
    # Nullable: a workout the user added themselves belongs to no generated
    # plan. That is also what keeps it safe — both paths regeneration deletes
    # through filter on plan_id, so an ad-hoc workout needs no special case.
    plan_id               = Column(Integer, ForeignKey("training_plans.id", ondelete="CASCADE"), nullable=True)
    user_id               = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    scheduled_date        = Column(Date, nullable=False)
    sport                 = Column(String, nullable=False)
    workout_type          = Column(String, nullable=False)
    title                 = Column(String, nullable=False)
    description           = Column(Text)
    duration_minutes      = Column(Integer)
    distance_meters       = Column(Float)
    steps                 = Column(PJson, default=lambda: [])
    is_complete           = Column(Boolean, nullable=False, default=False)
    completed_activity_id = Column(Integer, ForeignKey("activities.id", ondelete="SET NULL"))
    completion_pct        = Column(Float)
    watch_filename        = Column(String)
    watch_uploaded_at     = Column(DateTime(timezone=True))
    watch_deleted_at      = Column(DateTime(timezone=True))
    # Garmin-epoch seconds written into this workout's FIT file_id.time_created.
    # Set once on first upload to a current timestamp; reused on subsequent
    # schedule regenerations so the schedule_msg FK stays consistent.
    fit_time_created      = Column(BigInteger)
    # Hand-set gut-training target (carbs g/h) for this session; null = the
    # progression's, computed from the race target and the logs.
    fuel_carbs_per_hour   = Column(Integer, nullable=True)
    # Custom workout reference — set when this session was generated from a
    # user's saved workout rather than the auto-generated strength planner.
    custom_workout_id     = Column(Integer, ForeignKey("user_workouts.id", ondelete="SET NULL"), nullable=True)
    custom_workout_name   = Column(String, nullable=True)
    # Set when the user has filled in (or dismissed) the post-workout
    # animation-feedback recap for this workout. While NULL, the workout
    # surfaces in the "pending recaps" list on the dashboard. Only matters
    # for workouts that (a) have a matched activity and (b) contain at
    # least one step whose (cat, subtype) is in Garmin's manifest.
    recap_completed_at    = Column(DateTime(timezone=True), nullable=True)
    # "plan" (the generator wrote it) | "user" (somebody added it). Decides
    # whether regeneration may replace it and how the calendar labels it.
    origin                = Column(String, nullable=False, default="plan", server_default="plan")
    # True once the user has dragged this workout to another day. Regeneration
    # then keeps it on that day — refreshing its content as fitness changes,
    # but never moving it back or replacing it with a session elsewhere.
    moved_by_user         = Column(Boolean, nullable=False, default=False, server_default=false())
    # The plan generation that wrote this workout; NULL when added by hand.
    generation            = Column(String(36), nullable=True)

    __table_args__ = (
        Index("idx_planned_workouts_plan_date", "plan_id", "scheduled_date"),
        Index("idx_planned_workouts_user_date", "user_id", "scheduled_date"),
    )


sync_indexes(PlannedWorkout)


class IcsToken(Base):
    __tablename__ = "ics_tokens"

    id         = Column(Integer, primary_key=True)
    goal_id    = Column(Integer, ForeignKey("training_goals.id", ondelete="CASCADE"), nullable=False, unique=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    token      = Column(String, nullable=False, unique=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class UserIcsToken(Base):
    """One permanent ICS subscription token per user — survives goal changes."""
    __tablename__ = "user_ics_tokens"

    id         = Column(Integer, primary_key=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True)
    token      = Column(String, nullable=False, unique=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())


class WatchPendingDelete(Base):
    """Filenames that need to be deleted from the watch even after their PlannedWorkout row is gone.

    Per user. It was global, and the delete-list endpoint served every row to
    whichever watch asked — so with two accounts, one person's regeneration
    told the other person's watch to delete files, and watch filenames are
    short enough to collide.
    """
    __tablename__ = "watch_pending_deletes"

    id         = Column(Integer, primary_key=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    filename   = Column(String, nullable=False)
    created_at = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (UniqueConstraint("user_id", "filename"),)


class UserFitnessFingerprint(Base):
    """Per-user, per-sport progression state for smooth training load adaptation."""
    __tablename__ = "user_fitness_fingerprints"

    id                 = Column(Integer, primary_key=True)
    user_id            = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    sport_family       = Column(String, nullable=False)
    effective_weeks    = Column(Float, nullable=False, default=0.0)
    vdot               = Column(Float)
    sessions_completed = Column(Integer, nullable=False, default=0)
    updated_at         = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (UniqueConstraint("user_id", "sport_family"),)
