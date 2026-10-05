# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Saved places — a water source, a junction, where the car is parked."""

from sqlalchemy import (
    Boolean, Column, DateTime, Float, ForeignKey, Integer, String, func,
)

from app.database import Base
from app.models.sync import Synced, sync_indexes


class Waypoint(Base, Synced):
    """A named point the user saved, optionally loaded onto the watch.

    Watch reconciliation mirrors CustomTrack exactly (see app.models.custom_track):
    ``load_to_device`` is the DESIRED state and the watch_* columns record the
    actual one, so the sync endpoints derive work from the difference rather
    than from a queue that can drift.

    Garmin calls these "locations" and stores them in a single Locations.fit
    rather than one file per point, which is why the upload item for waypoints
    is built from *all* of a user's flagged waypoints at once — see
    ``_waypoint_upload_items``.
    """

    __tablename__ = "waypoints"

    id      = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)

    name    = Column(String, nullable=False, default="Waypoint")
    lat     = Column(Float, nullable=False)
    lng     = Column(Float, nullable=False)
    ele_m   = Column(Float, nullable=True)

    # Presentation. `color` is a hex string as the tracks use; `icon` is one of
    # the map sprite names, so a waypoint drawn on the map and the same waypoint
    # in a list cannot show different symbols.
    # Near-black, because the phone draws a place's symbol *in* this colour
    # rather than on a coloured badge behind it — so the default has to be a
    # colour a symbol reads as. A place nobody has styled should look like ink
    # on the map, not like it has been highlighted.
    color   = Column(String, nullable=False, default="#0f172a")
    icon    = Column(String, nullable=False, default="marker")
    notes   = Column(String, nullable=True)

    # Watch state, mirroring CustomTrack.
    load_to_device    = Column(Boolean, nullable=False, default=False)
    watch_filename    = Column(String, nullable=True)
    watch_uploaded_at = Column(DateTime(timezone=True), nullable=True)
    watch_deleted_at  = Column(DateTime(timezone=True), nullable=True)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


sync_indexes(Waypoint)
