# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import (
    Boolean, Column, DateTime, Float, ForeignKey, Integer, String, Text,
)
from sqlalchemy.sql import func

from app.database import Base, PJson
from app.models.sync import Synced, sync_indexes


class CustomTrackFolder(Base, Synced):
    """An organisation folder for custom tracks. `load_to_device` is a folder-level
    sync switch: toggling it cascades to every member track's load_to_device, so a
    whole folder of routes can be pushed to / removed from the watch at once."""
    __tablename__ = "custom_track_folders"

    id             = Column(Integer, primary_key=True)
    user_id        = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    name           = Column(String, nullable=False, default="Folder")
    color          = Column(String, nullable=False, default="#64748b")   # hex, for the folder chip
    load_to_device = Column(Boolean, nullable=False, default=False)
    created_at     = Column(DateTime(timezone=True), server_default=func.now())
    updated_at     = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


sync_indexes(CustomTrackFolder)


class CustomTrack(Base, Synced):
    """A user-drawn / imported course that can be rendered on the map and loaded
    to a Garmin watch as a navigable course FIT file.

    Watch reconciliation mirrors PlannedWorkout (see app.models.training_plan):
    `load_to_device` is the DESIRED state, the watch_* columns track actual state.
    The sync endpoints derive upload/delete work from the difference:

      upload-list = load_to_device AND watch_uploaded_at IS NULL AND NOT is_external
      delete-list = watch_uploaded_at NOT NULL AND watch_deleted_at IS NULL
                    AND load_to_device = False

    Deleting a track that is currently on the watch sets load_to_device=False and
    purge_after_delete=True so the row survives long enough for the next sync to
    remove the file, then it is purged on mark-course-deleted. External courses
    (discovered on the device, not created here) carry is_external=True.
    """
    __tablename__ = "custom_tracks"

    id          = Column(Integer, primary_key=True)
    user_id     = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    folder_id   = Column(Integer, ForeignKey("custom_track_folders.id", ondelete="SET NULL"), nullable=True)

    name        = Column(String, nullable=False, default="Custom Track")
    color       = Column(String, nullable=False, default="#2563eb")   # hex
    source      = Column(String, nullable=False, default="builder")   # builder|gpx|fit|activity|merged
    sport       = Column(String, nullable=False, default="hiking")    # running|hiking|cycling|...

    # Source activity this track was built from (source="activity") — lets the
    # track detail link back to the activity page. NULL for drawn/imported tracks.
    activity_id = Column(Integer, ForeignKey("activities.id", ondelete="SET NULL"), nullable=True)

    # Full geometry as [[lng, lat, ele_m|null], ...]
    geometry    = Column(PJson, nullable=False, default=lambda: [])
    distance_m  = Column(Float, nullable=False, default=0.0)
    ascent_m    = Column(Float, nullable=False, default=0.0)
    descent_m   = Column(Float, nullable=False, default=0.0)
    bounds      = Column(PJson)                                        # [w, s, e, n]
    profile     = Column(PJson)                                       # route_profile.build_profile output

    # Turn-by-turn (road running etc.) — cached BRouter-derived turn points.
    turn_by_turn = Column(Boolean, nullable=False, default=False)
    course_points = Column(PJson)                                     # [{d_m, lat, lng, type, name}, ...]

    # Device-discovered course that was not created in this app.
    is_external = Column(Boolean, nullable=False, default=False)
    device_size = Column(Integer)   # bytes of the source file on the watch (inventory dedup)

    # Per-track map visibility. A hidden track stays in the list (eye-off state) but
    # is dropped from the courses geojson so the map stops drawing it. Merging tracks
    # hides the sources (rather than deleting them) by setting this.
    hidden      = Column(Boolean, nullable=False, default=False)

    # ── Watch reconciliation (mirrors PlannedWorkout) ────────────────────────
    load_to_device     = Column(Boolean, nullable=False, default=False)
    watch_filename     = Column(String)
    watch_uploaded_at  = Column(DateTime(timezone=True))
    watch_deleted_at   = Column(DateTime(timezone=True))
    purge_after_delete = Column(Boolean, nullable=False, default=False)

    notes       = Column(Text)
    created_at  = Column(DateTime(timezone=True), server_default=func.now())
    updated_at  = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


sync_indexes(CustomTrack)
