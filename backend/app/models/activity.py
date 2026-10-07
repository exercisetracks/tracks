# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import (
    BigInteger, Boolean, Column, DateTime, Float, ForeignKey, Integer,
    String, Index, UniqueConstraint, text,
)
from sqlalchemy.orm import relationship
from sqlalchemy.sql import func
from app.database import Base, PJson


def _uuid7() -> str:
    from app.sync.uids import uuid7  # late: app.sync imports these models
    return uuid7()
from app.services.encrypted_columns import EncryptedFloat
from app.models.sync import Synced, sync_indexes

# Boolean server defaults must be text("false") / text("true"), never the bare
# strings "false"/"true". A plain string renders as a QUOTED literal
# (DEFAULT 'false'). Postgres coerces that to a boolean, but SQLite stores the
# four-character string, and SQLAlchemy's Boolean reads a non-empty string back
# as True — so under SQLite the default is silently inverted, and assigning
# `row.flag = True` looks like a no-op to the unit of work, emitting no UPDATE.
#
# This is not hypothetical: it made `users.is_admin` unqueryable under SQLite,
# which kept require_auth permanently in its open-access branch and disabled
# authentication across the whole test suite. Tests asserting 401 still passed,
# because require_crypto_session rejected them for an unrelated reason.


class User(Base):
    __tablename__ = "users"

    id         = Column(Integer, primary_key=True)
    name       = Column(String, nullable=False)
    username   = Column(String, nullable=True, unique=True)
    is_admin   = Column(Boolean, nullable=False, server_default=text("false"))
    # Garmin product_id of the user's main watch. Drives animation-confirmation
    # lookup: when the planner decides whether to schedule an exercise, it
    # checks animation_confirmations for this product_id first, then falls
    # back to Garmin's manifest. Nullable for users who haven't synced a
    # FIT file yet — the planner treats them like a no-confirmations device.
    primary_device_product_id = Column(Integer, nullable=True)
    created_at = Column(DateTime(timezone=True), server_default=func.now())
    # The account's identity to a phone. A phone's data belongs to the account
    # it was first linked to, recorded as (server_id, this uid); signing in to
    # another account while holding data is refused client-side, because
    # merging one person's history into another's is not recoverable. Stable
    # for the life of the account — never regenerated.
    uid        = Column(String(36), nullable=False, unique=True, default=lambda: _uuid7())
    # Bumped by "Delete my data". A phone whose epoch is older wipes its copy,
    # and its pushes are refused — see app.sync.store.push.
    sync_epoch = Column(Integer, nullable=False, default=0, server_default="0")
    # Access tokens issued before this moment are refused (app.auth). Set by a
    # password change — "I think I am compromised" — which has to shut every
    # session out, including ones it cannot name: a token's `sid` is only
    # recorded while its decryption key is cached, so an index of sids would
    # miss exactly the idle sessions a thief is most likely holding. The
    # session making the change is handed a fresh token.
    tokens_valid_after = Column(DateTime(timezone=True), nullable=True)


class Device(Base):
    __tablename__ = "devices"

    id               = Column(Integer, primary_key=True)
    serial_number    = Column(String, nullable=False)
    manufacturer     = Column(String)
    manufacturer_id  = Column(Integer)
    product_name     = Column(String)
    product_id       = Column(Integer)
    software_version = Column(String)

    __table_args__ = (UniqueConstraint("serial_number", "manufacturer_id"),)


class UserDevice(Base, Synced):
    __tablename__ = "user_devices"

    user_id   = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    device_id = Column(Integer, ForeignKey("devices.id", ondelete="CASCADE"), primary_key=True)
    # What this user calls the watch, and whether it is their main one. Per
    # user, on the claim, because two people can own the same model.
    label      = Column(String, nullable=True)
    is_primary = Column(Boolean, nullable=False, default=False, server_default=text("false"))

    device = relationship("Device", lazy="joined")


sync_indexes(UserDevice)


class Activity(Base, Synced):
    __tablename__ = "activities"

    id                        = Column(Integer, primary_key=True)
    device_id                 = Column(Integer, ForeignKey("devices.id"))
    user_id                   = Column(Integer, ForeignKey("users.id"), nullable=True)
    name                      = Column(String)
    notes                     = Column(String)
    sport                     = Column(String)
    sub_sport                 = Column(String)
    started_at                = Column(DateTime(timezone=True))
    duration_seconds          = Column(Integer)
    distance_meters           = Column(Float)
    avg_heart_rate            = Column(Integer)
    max_heart_rate            = Column(Integer)
    total_calories            = Column(Integer)
    training_stress_score     = Column(Float)
    intensity_factor          = Column(Float)
    aerobic_training_effect   = Column(Float)
    anaerobic_training_effect = Column(Float)
    avg_speed                 = Column(Float)
    max_speed                 = Column(Float)
    avg_cadence               = Column(Integer)
    total_ascent              = Column(Float)
    total_descent             = Column(Float)
    avg_power                 = Column(Integer)
    normalized_power          = Column(Integer)
    vo2max_estimate           = Column(Float)
    training_load_peak        = Column(Float)
    total_grit                = Column(Float)
    avg_flow                  = Column(Float)
    # The watch's post-workout prompts, as the FIT session records them:
    # feel 0–100 in steps of 25 (very weak … very strong), perceived effort
    # ×10 (70 = 7/10). Null when the prompt was skipped or never shown.
    workout_feel              = Column(Integer)
    workout_rpe               = Column(Integer)
    efficiency_factor         = Column(Float)
    aerobic_decoupling        = Column(Float)
    effective_tss             = Column(Float)
    # A "merged trip" summary built on the maps page from several activities/tracks.
    # It carries totals only (so the user can see a multiday trip's combined stats)
    # and must NOT contribute to any training metric. Isolation is structural: a
    # merged row has device_id=NULL (absent from every device-scoped query) and the
    # few user-scoped aggregations additionally guard on is_merged. `extra` holds the
    # links {custom_track_id, source_activity_ids, source_track_ids}.
    is_merged                 = Column(Boolean, nullable=False, server_default=text("false"))
    # Hidden by the user without deleting it: the file stays, the activity
    # stays out of lists. Unlike a delete, this can be undone.
    hidden                    = Column(Boolean, nullable=False, default=False, server_default=text("false"))
    extra                     = Column(PJson, server_default="{}")
    # Cursor for incremental client sync (see app.api.sync_delta). `onupdate`
    # covers ORM-level changes; bulk UPDATE statements bypass it, so the few
    # places that use them set this column explicitly — grep updated_at before
    # adding another.
    updated_at                = Column(DateTime(timezone=True), server_default=func.now(),
                                       onupdate=func.now())

    __table_args__ = (
        Index("idx_activities_started_at", "started_at"),
        Index("idx_activities_sport", "sport"),
        Index("idx_activities_user_id", "user_id"),
        Index("idx_activities_device_id", "device_id"),
        Index("idx_activities_user_updated", "user_id", "updated_at", "id"),
    )


sync_indexes(Activity)


class PowerBest(Base):
    __tablename__ = "power_bests"

    id               = Column(Integer, primary_key=True)
    activity_id      = Column(Integer, ForeignKey("activities.id", ondelete="CASCADE"), nullable=False)
    duration_seconds = Column(Integer, nullable=False)
    avg_watts        = Column(Float, nullable=False)

    __table_args__ = (UniqueConstraint("activity_id", "duration_seconds"),)


class PaceBest(Base):
    __tablename__ = "pace_bests"

    id              = Column(Integer, primary_key=True)
    activity_id     = Column(Integer, ForeignKey("activities.id", ondelete="CASCADE"), nullable=False)
    distance_meters = Column(Integer, nullable=False)
    avg_speed_mps   = Column(Float, nullable=False)

    __table_args__ = (UniqueConstraint("activity_id", "distance_meters"),)


class Lap(Base):
    __tablename__ = "laps"

    id                = Column(Integer, primary_key=True)
    activity_id       = Column(Integer, ForeignKey("activities.id", ondelete="CASCADE"), nullable=False)
    lap_number        = Column(Integer, nullable=False)
    start_time        = Column(DateTime(timezone=True))
    duration_seconds  = Column(Float)
    distance_meters   = Column(Float)
    avg_heart_rate    = Column(Integer)
    max_heart_rate    = Column(Integer)
    avg_speed         = Column(Float)
    max_speed         = Column(Float)
    avg_cadence       = Column(Integer)
    total_ascent      = Column(Float)
    total_descent     = Column(Float)
    avg_power         = Column(Integer)
    total_calories    = Column(Integer)
    total_grit        = Column(Float)
    avg_flow          = Column(Float)
    total_strokes     = Column(Integer)
    total_putts       = Column(Integer)
    avg_stroke_distance = Column(Float)
    hole_time_in_zone = Column(Float)

    __table_args__ = (Index("ix_laps_activity", "activity_id"),)


class StrengthSet(Base):
    __tablename__ = "strength_sets"

    id                = Column(Integer, primary_key=True)
    activity_id       = Column(Integer, ForeignKey("activities.id", ondelete="CASCADE"), nullable=False)
    set_number        = Column(Integer, nullable=False)
    set_type          = Column(String(10))
    exercise_category = Column(String(64))
    exercise_name     = Column(String(128))
    weight_kg         = Column(Float)
    repetitions       = Column(Integer)
    duration_seconds  = Column(Float)
    start_time        = Column(DateTime(timezone=True))

    __table_args__ = (Index("ix_strength_sets_activity", "activity_id"),)


class ClimbSplit(Base):
    __tablename__ = "climb_splits"

    id               = Column(Integer, primary_key=True)
    activity_id      = Column(Integer, ForeignKey("activities.id", ondelete="CASCADE"), nullable=False)
    split_number     = Column(Integer, nullable=False)
    split_type       = Column(String(16))
    start_time       = Column(DateTime(timezone=True))
    end_time         = Column(DateTime(timezone=True))
    duration_seconds = Column(Float)
    total_ascent     = Column(Float)
    avg_vert_speed   = Column(Float)
    total_calories   = Column(Integer)
    min_heart_rate   = Column(Integer)
    max_heart_rate   = Column(Integer)
    difficulty_score = Column(Integer)
    grade_level      = Column(Integer)
    climb_result     = Column(Integer)
    user_grade       = Column(String(20))

    __table_args__ = (Index("ix_climb_splits_activity", "activity_id"),)


class DataPoint(Base):
    """
    lat/lng are encrypted (EncryptedFloat) — precise GPS location is the
    single most re-identifying field in the whole schema. The rest of the
    row (heart_rate/power/cadence/speed/altitude) stays plaintext: it feeds
    SQL-side aggregates elsewhere (best-effort curves, threshold estimation)
    and is meaningfully less sensitive than "exactly where you were."

    Loading ANY row from this table requires an active decryption key
    (SQLAlchemy hydrates every mapped column, not just the ones a caller
    reads — same rule as Injury, see app/models/health.py). The raw
    execute_values bulk insert in app/services/fit_import.py bypasses this
    type layer entirely and encrypts lat/lng explicitly before insert — see
    that module's docstring.

    idx_dp_heatmap (see app/main.py's _create_postgres_indexes) is a partial
    index keyed on (activity_id, recorded_at) WHERE lat IS NOT NULL AND
    lng IS NOT NULL — NULL-ness of an encrypted column is unaffected by
    encryption (NULL stays NULL, never ciphertext), so that predicate is
    still valid and still selective post-encryption.
    """

    __tablename__ = "data_points"

    id          = Column(BigInteger, primary_key=True)
    activity_id = Column(Integer, ForeignKey("activities.id", ondelete="CASCADE"), nullable=False)
    recorded_at = Column(DateTime(timezone=True), nullable=False)
    lat         = Column(EncryptedFloat)
    lng         = Column(EncryptedFloat)
    altitude    = Column(Float)
    heart_rate  = Column(Integer)
    power       = Column(Integer)
    cadence     = Column(Integer)
    speed       = Column(Float)
    grit        = Column(Float)
    flow        = Column(Float)
    extra       = Column(PJson, server_default="{}")

    __table_args__ = (
        Index("idx_data_points_activity_recorded", "activity_id", "recorded_at"),
        Index("idx_data_points_recorded_at", "recorded_at"),
    )
