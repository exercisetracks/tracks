# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, BigInteger, String, Float, Text, DateTime, Boolean
from sqlalchemy.sql import func

from app.database import Base, PJson


class MapRegion(Base):
    __tablename__ = "map_regions"

    id          = Column(Integer, primary_key=True, autoincrement=True)
    name        = Column(String, nullable=False)
    bbox        = Column(PJson, nullable=False)
    # GeoJSON Polygon/MultiPolygon for merged areas (union of constituent bboxes).
    # NULL for a plain single-bbox area — the frontend then draws the bbox rectangle.
    geometry    = Column(PJson)
    status      = Column(String, nullable=False, default="pending")
    progress    = Column(Float, nullable=False, default=0.0)
    # Free-text progress detail for the current phase (e.g. "120 / 270 MB",
    # "tile 740 / 1200"), shown under the region's progress bar.
    detail      = Column(Text)
    error       = Column(Text)
    size_bytes  = Column(BigInteger)
    deleted     = Column(Boolean, nullable=False, default=False)
    # The build queue (services.map_builds). `heartbeat_at` is refreshed every
    # few seconds by whichever process is building the area, so "stalled"
    # means the builder is gone, not that a phase has been quiet for a while.
    heartbeat_at = Column(DateTime(timezone=True))
    # Phases whose output is finished and safe to reuse, so a build that was
    # interrupted resumes from the next one rather than from nothing.
    phases_done  = Column(PJson)
    # The ground this area's overlay part covers: its box minus whatever
    # other areas already covered when it was built (GeoJSON). Areas overlap
    # freely, but overlay features are owned by exactly one area, so the
    # master never draws a trail twice and a delete knows what to rebuild.
    owned        = Column(PJson)
    created_at  = Column(DateTime(timezone=True), server_default=func.now())
    updated_at  = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
