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
    created_at  = Column(DateTime(timezone=True), server_default=func.now())
    updated_at  = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())
