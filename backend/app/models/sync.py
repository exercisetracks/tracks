# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The columns and tables that make a row syncable.

Every table in spec/sync.yaml carries the same four columns, added through
[Synced] so they cannot differ between tables:

- `uid` — the row's identity everywhere but this database. The integer `id`
  stays for foreign keys and every existing query; nothing on the wire ever
  carries it.
- `clock` — `{field: hlc}`, when each synced field was last written. A field
  with no entry has never been written by anyone who syncs it, which is
  different from being written as null.
- `server_seq` — this row's place in the one arrival order every synced table
  shares (see app.sync.store for why it is assigned at commit, not at write).
  NULL for rows no one has written a synced field of; -1 while a transaction
  that stamped the row is still open.
- `pending_refs` — `{field: uid}` for references to rows that have not
  arrived yet. Phones deliver parents and children in whatever order they
  like, so a reference is kept rather than refused.
"""
from sqlalchemy import (
    BigInteger, Boolean, CheckConstraint, Column, DateTime, ForeignKey, Index, Integer,
    Sequence, String, UniqueConstraint,
)
from sqlalchemy.sql import func

from app.database import Base, PJson

# One sequence across every synced table, so a single integer cursor orders
# all of them. Its own object rather than a per-column default because it is
# assigned in bulk at commit time, not on insert.
SYNC_SEQ = Sequence("sync_seq", metadata=Base.metadata)

# Sentinel for "stamped in a transaction that has not committed yet".
SEQ_PENDING = -1


class Synced:
    uid          = Column(String(36), nullable=False)
    clock        = Column(PJson, nullable=False, default=dict, server_default="{}")
    server_seq   = Column(BigInteger, nullable=True)
    pending_refs = Column(PJson, nullable=True)


def sync_indexes(cls) -> None:
    """Uid unique within one user's data; pull scans by (user, seq).

    Unique per user rather than globally: a standalone phone mints uids before
    it knows which server user it will belong to, and natural-key uids (the
    same watch, the same date) are *meant* to collide across users.
    """
    t = cls.__table__
    Index(f"uq_{t.name}_user_uid", t.c.user_id, t.c.uid, unique=True)
    Index(f"ix_{t.name}_user_seq", t.c.user_id, t.c.server_seq)


class SyncNode(Base):
    """This server's HLC node id. One row, written once, never changed.

    In the database rather than configuration because it must be identical
    across every worker and survive restarts — two ids for one server would
    make its own edits tie-break inconsistently against each other.
    """
    __tablename__ = "sync_node"

    id   = Column(Integer, primary_key=True)
    node = Column(String(16), nullable=False)

    __table_args__ = (CheckConstraint("id = 1", name="sync_node_single_row"),)


class SyncTombstone(Base):
    """A deleted synced row: all that survives of it is its uid and when.

    Tombstones rather than soft-delete columns so that no existing query has to
    learn to skip deleted rows; the row itself is really gone. Never pruned —
    see docs/offline-first.md: a phone offline past any horizon would keep
    rows the server dropped. A data wipe clears them along with the data and
    bumps the account's epoch instead (User.sync_epoch).
    """
    __tablename__ = "sync_tombstones"

    id         = Column(Integer, primary_key=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    entity     = Column(String, nullable=False)
    uid        = Column(String(36), nullable=False)
    deleted    = Column(String(38), nullable=False)
    server_seq = Column(BigInteger, nullable=True)

    __table_args__ = (
        UniqueConstraint("user_id", "entity", "uid"),
        Index("ix_sync_tombstones_user_seq", "user_id", "server_seq"),
    )


class SyncPendingRow(Base):
    """Edits to a row this server cannot create yet.

    An activity exists only once its FIT file has been parsed, but a phone can
    rename the ride before uploading the file. The edit is kept here, pulled by
    other phones like any row, and folded into the activity when the import
    creates it.
    """
    __tablename__ = "sync_pending_rows"

    id         = Column(Integer, primary_key=True)
    user_id    = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    entity     = Column(String, nullable=False)
    uid        = Column(String(36), nullable=False)
    fields     = Column(PJson, nullable=False, default=dict)
    clock      = Column(PJson, nullable=False, default=dict)
    server_seq = Column(BigInteger, nullable=True)

    __table_args__ = (
        UniqueConstraint("user_id", "entity", "uid"),
        Index("ix_sync_pending_rows_user_seq", "user_id", "server_seq"),
    )


class FitFile(Base, Synced):
    """Every FIT file an account has, whoever delivered it.

    The manifest a phone downloads history from, and the `fit_file` log of
    spec/sync.yaml. Its own table rather than `pending_imports` (the spec's
    hint), because a browser upload never passes through that queue and a
    phone may announce a file before its bytes arrive — the registry of files
    and the queue of work are different things.
    """
    __tablename__ = "fit_files"

    id            = Column(Integer, primary_key=True)
    user_id       = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    sha256        = Column(String(64), nullable=False)
    size_bytes    = Column(BigInteger)
    file_kind     = Column(String)
    device_serial = Column(String)
    started_at    = Column(String)
    filename      = Column(String)
    # Where the bytes are, if this server has them: a sealed blob in
    # pending_imports or a DEK-encrypted upload. Not synced — storage is local.
    blob_id       = Column(String)
    sealed        = Column(Boolean, nullable=False, default=False, server_default="false")
    created_at    = Column(DateTime(timezone=True), server_default=func.now())


sync_indexes(FitFile)
