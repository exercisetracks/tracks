# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, String, Boolean, DateTime, ForeignKey, Index, UniqueConstraint, text
from sqlalchemy.sql import func
from app.database import Base, PJson


class PendingImport(Base):
    """A sealed FIT blob ingested by a sync agent (garmin-sync, a future
    mobile app) with no live user session available to decrypt it. Sits here
    until the target user next logs in — see
    app.services.fit_import.process_pending_imports_for_user, called from
    the login flow. blob_id points into app.services.object_storage;
    content_hash (of the PLAINTEXT bytes, computed agent-side) is the dedup
    key, matching the pre-encryption Import.source_id convention."""

    __tablename__ = "pending_imports"

    id           = Column(Integer, primary_key=True)
    user_id      = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    blob_id      = Column(String, nullable=False)
    content_hash = Column(String, nullable=False)
    filename     = Column(String, nullable=True)
    created_at   = Column(DateTime(timezone=True), server_default=func.now())
    processed_at = Column(DateTime(timezone=True), nullable=True)
    error        = Column(String, nullable=True)

    __table_args__ = (UniqueConstraint("user_id", "content_hash"),)


class ImportSource(Base):
    __tablename__ = "import_sources"

    id          = Column(Integer, primary_key=True)
    user_id     = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"))
    source_type = Column(String, nullable=False)
    config      = Column(PJson, server_default="{}")
    is_active   = Column(Boolean, server_default=text("true"))
    created_at  = Column(DateTime(timezone=True), server_default=func.now())


class Import(Base):
    __tablename__ = "imports"

    id               = Column(Integer, primary_key=True)
    activity_id      = Column(Integer, ForeignKey("activities.id", ondelete="SET NULL"))
    user_id          = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"))
    import_source_id = Column(Integer, ForeignKey("import_sources.id"))
    source           = Column(String, nullable=False)
    source_id        = Column(String, nullable=False)
    imported_at      = Column(DateTime(timezone=True), server_default=func.now())
    extra            = Column(PJson, server_default="{}")

    __table_args__ = (
        Index("idx_imports_source", "source", "source_id"),
        Index("idx_imports_activity_id", "activity_id"),
        # Per user: two people in one household can hold the same file.
        UniqueConstraint("user_id", "source", "source_id"),
    )
