# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Flexibility and mobility database models.

StretchLibrary — curated stretch/pose definitions with Garmin FIT yoga/stretch
                  mappings, muscle group targeting, and sport relevance.
UserFlexibilityFlow   — user-created stretch routines (like strength workouts).
FlowStretch           — individual stretches within a flexibility flow.
UserFlexibilityPreference — per-user preference for specific stretches.
"""

from sqlalchemy import (
    Boolean, Column, DateTime, ForeignKey, Integer,
    String, Text, UniqueConstraint, Index,
)
from sqlalchemy.sql import func

from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class StretchLibrary(Base):
    """
    Master stretch reference table.

    Maps to two Garmin FIT exercise_category values:
      - category 31 (warm_up):      stretch subtypes with animations on Fenix 6X+
      - category 36 (pose):         yoga pose subtypes with first-class animations
      - category 35 (move):         Pilates-style movements

    Populated by seed migration and not user-editable.
    """
    __tablename__ = "stretch_library"

    id                = Column(Integer, primary_key=True)
    name              = Column(String(128), nullable=False, unique=True)

    # Garmin FIT SDK exercise category:
    #   31 = warm_up  (stretch animations)
    #   36 = pose     (yoga pose animations)
    #   35 = move     (Pilates movement animations)
    #   NULL for stretches with no Garmin equivalent
    garmin_category   = Column(String(32))

    # Subtype integer within the category enum.
    # For category 'warm_up':       values from warm_up_exercise_name (31-70)
    # For category 'pose':          values from pose_exercise_name (0-115)
    # For category 'move':          values from move_exercise_name
    garmin_subtype    = Column(Integer)

    # True when (garmin_category, garmin_subtype) is in Garmin Connect's
    # public animation manifest (Yoga.json / Mobility.json / etc.) — i.e.
    # the watch is expected to play an animation for this stretch during a
    # workout. For library rows it comes from spec/library (loaded by
    # app.seed); custom stretches set it on save.
    has_animation     = Column(Boolean, nullable=False, default=False,
                               server_default="false")

    # Primary and secondary muscle groups targeted
    primary_muscles   = Column(PJson, nullable=False, default=lambda: [])
    secondary_muscles = Column(PJson, nullable=False, default=lambda: [])

    # How long to hold the stretch per side (seconds)
    duration_per_side_sec = Column(Integer, nullable=False, default=60)

    # Whether this stretch is performed on each side of the body
    each_side         = Column(Boolean, nullable=False, default=False)

    # Number of sets/repetitions per side
    sets              = Column(Integer, nullable=False, default=1)

    # Movement pattern for filtering:
    # static_stretch, dynamic_stretch, yoga_pose, balance_pose, pnf_stretch, myofascial_release
    movement_pattern  = Column(String(32), nullable=False, default="static_stretch")

    # Equipment: bodyweight, strap, foam_roller, block, wall, mat
    equipment         = Column(PJson, nullable=False, default=lambda: ["bodyweight"])

    # Sport relevance scores 0–3 per sport family
    sport_relevance   = Column(PJson, nullable=False, default=lambda: {})

    # 1=beginner (accessible to all), 2=intermediate (some flexibility required), 3=advanced
    difficulty        = Column(Integer, nullable=False, default=1)

    # Whether this is a compound stretch (targets multiple muscle groups simultaneously)
    is_compound       = Column(Boolean, nullable=False, default=True)

    # Brief coaching cue for the watch display
    description       = Column(Text)

    # Step-by-step instructions
    instructions      = Column(Text)

    # Contraindications / caution notes
    cautions          = Column(Text)

    # Short coaching cues (2-3 reminders), surfaced in the flow player and modal.
    cues              = Column(PJson, nullable=False, default=lambda: [],
                               server_default="[]")

    # A single breathing cue for the guided flow player (e.g. "Exhale as you fold").
    breath_cue        = Column(Text)

    # Starting body position, used to sequence flows sensibly
    # (standing → kneeling → seated → supine/prone, calming closer last):
    # standing | kneeling | seated | supine | prone
    position          = Column(String(16))

    created_at        = Column(DateTime(timezone=True), server_default=func.now())


class UserFlexibilityFlow(Base, Synced):
    """
    User-created flexibility flow (stretch routine).

    Can be: synced to Garmin watch, included in training plans, or used standalone.
    """
    __tablename__ = "user_flexibility_flows"

    id                = Column(Integer, primary_key=True)
    user_id           = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"),
                               nullable=False)
    name              = Column(String(128), nullable=False)
    description       = Column(Text)

    # Whether to auto-include this flow in generated training plans
    include_in_plan   = Column(Boolean, nullable=False, default=True)

    # Whether to sync this flow to Garmin watch (max 25 watch slots)
    sync_to_watch     = Column(Boolean, nullable=False, default=True)

    # Tags for filtering: upper_body, lower_body, core, full_body, yoga, mobility, recovery
    tags              = Column(PJson, nullable=False, default=lambda: [])

    created_at        = Column(DateTime(timezone=True), server_default=func.now())
    updated_at        = Column(DateTime(timezone=True), server_default=func.now(),
                               onupdate=func.now())


sync_indexes(UserFlexibilityFlow)


class FlowStretch(Base, Synced):
    """
    A single stretch within a flexibility flow, with ordering and timing.
    """
    __tablename__ = "flow_stretches"

    id                = Column(Integer, primary_key=True)
    flow_id           = Column(Integer, ForeignKey("user_flexibility_flows.id",
                               ondelete="CASCADE"), nullable=False)
    # Denormalised from the flow so a synced child is scoped to its owner on
    # its own — a uid lookup must never be able to reach another user's row.
    user_id           = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # Null on a rest block, which names no stretch.
    exercise_name     = Column(String(128), nullable=True)

    # A fractional ordering key (app.sync.order), not a position: moving one
    # stretch rewrites only that stretch, so a reorder on one phone and an
    # insert on another do not conflict.
    #
    # Collation "C" because the keys compare BYTEWISE ("V" < "k"). Under the
    # database's locale collation ORDER BY put "k" before "V", and any list of
    # more than a couple of items came back shuffled.
    order_index       = Column(String(64, collation="C"), nullable=False, default="V")

    # Per-stretch customization (overrides library defaults when set)
    sets              = Column(Integer, nullable=True)
    duration_seconds  = Column(Integer, nullable=True)   # Per side
    rest_seconds      = Column(Integer, nullable=True)   # Between sides

    # Coaching note specific to this position in the flow
    coaching_note     = Column(Text)

    # Blocks, as on UserWorkoutExercise. A rest block's length is
    # duration_seconds.
    item_kind           = Column(String(16), nullable=True)
    group_uid           = Column(String(64), nullable=True)
    group_kind          = Column(String(16), nullable=True)
    group_rounds        = Column(Integer, nullable=True)
    group_rest_seconds  = Column(Integer, nullable=True)

    __table_args__ = (
        Index("ix_flow_stretches_flow_order", "flow_id", "order_index"),
    )


sync_indexes(FlowStretch)


class UserFlexibilityPreference(Base, Synced):
    """
    Per-user preference for a named stretch (library or custom).

    preference='preferred' → algorithm prioritises when generating flows.
    preference='excluded'  → never auto-included.
    No row = neutral.
    """
    __tablename__ = "user_flexibility_preferences"

    id            = Column(Integer, primary_key=True)
    user_id       = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"),
                           nullable=False)
    exercise_name = Column(String(128), nullable=False)
    preference    = Column(String(16), nullable=False, default="preferred")

    __table_args__ = (UniqueConstraint("user_id", "exercise_name"),)


sync_indexes(UserFlexibilityPreference)


class UserCustomStretch(Base, Synced):
    """
    User-created stretch that supplements the curated library.

    Shares schema with StretchLibrary so it can be merged into the stretch
    pool at flow generation time.
    """
    __tablename__ = "user_custom_stretches"

    id                = Column(Integer, primary_key=True)
    user_id           = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"),
                               nullable=False)
    name              = Column(String(128), nullable=False)
    primary_muscles   = Column(PJson, nullable=False, default=lambda: [])
    secondary_muscles = Column(PJson, nullable=False, default=lambda: [])
    equipment         = Column(PJson, nullable=False, default=lambda: ["bodyweight"])
    movement_pattern  = Column(String(32), nullable=False, default="static_stretch")
    is_compound       = Column(Boolean, nullable=False, default=True)
    difficulty        = Column(Integer, nullable=False, default=1)
    duration_per_side_sec = Column(Integer, nullable=False, default=60)
    each_side         = Column(Boolean, nullable=False, default=False)
    sets              = Column(Integer, nullable=False, default=1)
    description       = Column(Text)
    instructions      = Column(Text)
    cautions          = Column(Text)
    cues              = Column(PJson, nullable=False, default=lambda: [],
                               server_default="[]")
    breath_cue        = Column(Text)
    position          = Column(String(16))
    viewer_slug       = Column(String(64))
    garmin_category   = Column(String(32))
    garmin_subtype    = Column(Integer)
    has_animation     = Column(Boolean, nullable=False, default=False,
                               server_default="false")
    created_at        = Column(DateTime(timezone=True), server_default=func.now())

    __table_args__ = (UniqueConstraint("user_id", "name"),)


sync_indexes(UserCustomStretch)
