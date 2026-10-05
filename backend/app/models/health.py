# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from sqlalchemy import Column, Integer, Date, DateTime, ForeignKey, Index
from sqlalchemy.sql import func
from app.database import Base
from app.services.encrypted_columns import EncryptedString
from app.models.sync import Synced, sync_indexes


class Injury(Base, Synced):
    """
    body_part/injury_type/notes are encrypted (EncryptedString) — free-text
    and category fields with no SQL-side filter/sort/aggregate dependency
    anywhere in the app (confirmed: only user_id/start_date/end_date/severity
    are ever queried on this table — see idx_injuries_user_date below and
    app/api/training_plan/injectors.py's _get_active_injuries).

    severity/start_date/end_date stay plaintext: start_date is indexed, and
    both dates are needed for range queries (active-injury lookups, the
    injury/activity correlation view).

    Loading ANY row from this table now requires an active decryption key
    (SQLAlchemy hydrates every mapped column, not just the ones a caller
    happens to read) — background code without a live session (e.g. plan
    regeneration triggered outside a request) must catch
    crypto_context.MissingDecryptionKey and degrade gracefully. See
    _get_active_injuries's caller in injectors.py for the pattern.
    """

    __tablename__ = "injuries"

    id          = Column(Integer, primary_key=True)
    user_id     = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    body_part   = Column(EncryptedString, nullable=False)
    injury_type = Column(EncryptedString, nullable=False)
    severity    = Column(Integer, nullable=False)
    start_date  = Column(Date, nullable=False)
    end_date    = Column(Date)
    notes       = Column(EncryptedString)
    created_at  = Column(DateTime(timezone=True), server_default=func.now())
    updated_at  = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())

    __table_args__ = (Index("idx_injuries_user_date", "user_id", "start_date"),)


sync_indexes(Injury)
