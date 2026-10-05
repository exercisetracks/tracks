# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, String, DateTime, ForeignKey
from sqlalchemy.sql import func
from app.database import Base


class SyncAgent(Base):
    """
    A paired external agent (garmin-sync USB dock, a future mobile app) that
    ingests/egests data via a per-agent bearer pairing token — never a
    single secret shared by the whole install (see the branch plan's
    "pluggable ingest + egest" section).

    user_id NULL means a "household" agent: it can act on behalf of ANY user
    in this instance, resolved per-call from an X-Garmin-Device-Serial header
    against that device's UserDevice claim — the same resolution today's
    single-secret model already does, just no longer gated by one secret
    shared across every agent in the install. This is what a shared USB dock
    serving multiple family members pairs as.

    user_id set means a personal agent (e.g. a phone syncing its own user's
    watch): always scoped to exactly that user, no device-serial lookup
    needed. A future mobile app pairs this way.
    """

    __tablename__ = "sync_agents"

    id                 = Column(Integer, primary_key=True)
    user_id            = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=True)
    created_by_user_id = Column(Integer, ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    kind               = Column(String, nullable=False)   # "garmin-usb" | "mobile-app" | ...
    label              = Column(String, nullable=False)   # user-chosen, e.g. "Living room USB dock"
    token_hash         = Column(String, nullable=False, unique=True)
    created_at         = Column(DateTime(timezone=True), server_default=func.now())
    last_seen_at       = Column(DateTime(timezone=True), nullable=True)
    revoked_at         = Column(DateTime(timezone=True), nullable=True)
