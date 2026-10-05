# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A device's own way to unwrap this user's DEK.

## The problem this solves

A JWT lasts 30 days, but the Redis-cached decryption key behind it does not: it
lapses after 7 days idle and dies outright when Redis restarts. Until now the
only way back was the user typing their password, because unwrapping the DEK
needs the Argon2id KEK derived from it. On a phone that means a password prompt
roughly weekly, forever, including halfway up a mountain with the watch waiting
to sync.

An enrolled device holds a secret that unwraps the same DEK, so it re-unlocks
silently and the user stops seeing prompts.

## What it costs

A device secret is equivalent to the password *for reading data*. Whoever holds
it can decrypt this user's activities. It is therefore only as safe as the
keystore holding it — on Android, hardware-backed storage gated behind device
unlock, which is what the app must use.

What it buys back is revocation, which a password does not have: losing a phone
is a `DELETE /auth/device-keys/{id}` rather than a password rotation, and the
lost device stops working immediately.

The wrapped blobs here are useless without the secret, so a database dump alone
still reveals nothing — the same property the password and recovery-key
wrappings have.

## Why not Argon2id

The password wrapping uses Argon2id because a human password is low-entropy and
guessing must be made expensive. A device secret is 32 bytes of CSPRNG output;
there is nothing to guess, and burning 150-400 ms of server CPU on every cold
start would be pure waste. See user_crypto._derive_device_kek.

## Lifecycle

Enrolment requires a live crypto session, so a device key can never grant access
the enroling session did not already have. Changing the password revokes every
device key: "change my password" is the universal action for "I think I am
compromised", and it would be a poor surprise if a stolen phone kept working
through it.
"""

from datetime import datetime, timezone

from sqlalchemy import (
    Column, DateTime, ForeignKey, Index, Integer, LargeBinary, String,
)
from sqlalchemy.sql import func

from app.database import Base


class DeviceKey(Base):
    __tablename__ = "device_keys"

    id              = Column(Integer, primary_key=True)
    user_id         = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    label           = Column(String, nullable=True)
    # HKDF salt for this device's KEK. Random per device, so two devices
    # enrolled for the same user derive unrelated keys.
    salt            = Column(LargeBinary, nullable=False)
    wrapped_dek     = Column(LargeBinary, nullable=False)
    wrapped_privkey = Column(LargeBinary, nullable=False)
    created_at      = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    last_used_at    = Column(DateTime(timezone=True), nullable=True)
    revoked_at      = Column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        Index("idx_device_keys_user", "user_id"),
    )

    @property
    def is_active(self) -> bool:
        return self.revoked_at is None


def revoke_all_for_user(db, user_id: int) -> int:
    """Revoke every active device key for a user. Returns the count.

    Called on password change. Does not commit — the caller is mid-transaction
    re-wrapping key material, and a revocation that landed while the re-wrap
    rolled back would lock the user's devices out for nothing.
    """
    return (
        db.query(DeviceKey)
        .filter(DeviceKey.user_id == user_id, DeviceKey.revoked_at.is_(None))
        .update({DeviceKey.revoked_at: datetime.now(timezone.utc)},
                synchronize_session=False)
    )
