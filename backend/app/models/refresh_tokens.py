# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Long-lived refresh tokens, so an installed app isn't a 30-day countdown.

The access JWT lasts 30 days with no refresh endpoint, no rotation, and no
server-side revocation — the only recovery is re-POSTing the password, and the
only way to cut off a lost device is to rotate JWT_SECRET and log everyone out.
That is survivable for a browser tab. It is not for an app on a phone that can
be lost, and it forces the app to either store the password or die on day 31.

## Rotation and reuse detection

Every refresh consumes the presented token and issues a new one. If a token
that was already consumed shows up again, either the network duplicated a
request or someone is replaying a stolen token — and there is no way to tell
which from here. The safe reading is theft, so the whole family is revoked and
the user re-authenticates. This is the standard OAuth 2.0 BCP treatment.

`family_id` links a chain back to the login that started it, so revoking one
compromised token revokes its lineage rather than just the leaf.

## Why the session id rides along

`sid` is what binds a JWT to the Redis-cached DEK (see crypto_context), so a
refresh reuses the original `sid` rather than minting a new one. A fresh `sid`
would orphan the cached key material and drop the user into `vault_locked` on
every single refresh — turning a convenience feature into a password prompt
treadmill.

Reusing `sid` deliberately does NOT extend the crypto session's own lifetime.
That still expires on its own schedule and still needs the password, because
unwrapping the DEK requires deriving the Argon2id KEK from it. A refresh token
keeps a client *authenticated*; only a password re-entry can *unlock the vault*.
Those are different things and the API keeps them separate on purpose.
"""

import hashlib
import secrets
import uuid
from datetime import datetime, timedelta, timezone

from sqlalchemy import (
    Column, DateTime, ForeignKey, Index, Integer, String,
)
from sqlalchemy.sql import func

from app.database import Base

# Long enough that a phone syncing weekly stays signed in across a holiday;
# short enough that an abandoned install stops being a credential.
REFRESH_TOKEN_TTL_DAYS = 90


class RefreshToken(Base):
    __tablename__ = "refresh_tokens"

    id           = Column(Integer, primary_key=True)
    user_id      = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # SHA-256 of the token. Stored hashed for the same reason sync-agent tokens
    # are: a database dump must not hand over usable credentials. Unlike a
    # password these are high-entropy random values, so a plain digest is
    # enough — there is nothing to brute-force.
    token_hash   = Column(String, nullable=False, unique=True)
    # The crypto-session id this family is pinned to. See the module docstring.
    sid          = Column(String, nullable=False)
    family_id    = Column(String, nullable=False)
    device_label = Column(String, nullable=True)
    # What the client says it is, from its X-Tracks-Client header — e.g.
    # `android/1.2.0 (10200)`. Self-reported and unauthenticated beyond the
    # token itself, so it is only ever *shown* (the version panels), never
    # used to decide anything. Kept up to date by every login, refresh and
    # GET /version, and carried across rotation.
    client_version = Column(String, nullable=True)
    # The device key this family belongs to, once the device has enrolled one
    # (stamped at enrolment, and set by every device-key unlock). It is what
    # lets an unlock end the device's previous family: each unlock mints a new
    # sid and so a new family, and without the link the old one stayed live —
    # listed under Signed-in devices, at whatever app version it last saw,
    # beside the device's current entry, until it expired.
    device_key_id = Column(Integer, ForeignKey("device_keys.id", ondelete="SET NULL"), nullable=True)
    created_at   = Column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    last_used_at = Column(DateTime(timezone=True), nullable=True)
    expires_at   = Column(DateTime(timezone=True), nullable=False)
    # Set when this token is rotated away. A token with this set showing up
    # again is the reuse signal that revokes the family.
    replaced_at  = Column(DateTime(timezone=True), nullable=True)
    revoked_at   = Column(DateTime(timezone=True), nullable=True)

    __table_args__ = (
        Index("idx_refresh_tokens_user", "user_id"),
        Index("idx_refresh_tokens_family", "family_id"),
    )

    @property
    def is_usable(self) -> bool:
        if self.revoked_at is not None or self.replaced_at is not None:
            return False
        expires = self.expires_at
        if expires.tzinfo is None:
            expires = expires.replace(tzinfo=timezone.utc)
        return expires > datetime.now(timezone.utc)


def clean_client_version(value: str | None) -> str | None:
    """A client's self-description, trimmed to something safe to store and
    show: printable ASCII, 64 characters. Anything else is dropped rather than
    stored, since nothing depends on it being present."""
    value = (value or "").strip()[:64]
    if not value or not all(32 <= ord(c) < 127 for c in value):
        return None
    return value


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def issue(
    db,
    user_id: int,
    sid: str,
    *,
    device_label: str | None = None,
    family_id: str | None = None,
    client_version: str | None = None,
    device_key_id: int | None = None,
) -> tuple[str, RefreshToken]:
    """Mint a refresh token. Returns (plaintext, row) — the plaintext is the
    only copy that will ever exist, so the caller must return it to the client.

    Pass `family_id` when rotating so the new token stays in the chain; omit it
    on a fresh login to start one.
    """
    token = secrets.token_urlsafe(48)
    row = RefreshToken(
        user_id=user_id,
        token_hash=hash_token(token),
        sid=sid,
        family_id=family_id or uuid.uuid4().hex,
        device_label=(device_label or "").strip()[:100] or None,
        client_version=clean_client_version(client_version),
        device_key_id=device_key_id,
        expires_at=datetime.now(timezone.utc) + timedelta(days=REFRESH_TOKEN_TTL_DAYS),
    )
    db.add(row)
    return token, row


def revoke_family(db, family_id: str) -> int:
    """Revoke every unrevoked token in a rotation chain. Returns the count."""
    now = datetime.now(timezone.utc)
    return (
        db.query(RefreshToken)
        .filter(RefreshToken.family_id == family_id, RefreshToken.revoked_at.is_(None))
        .update({RefreshToken.revoked_at: now}, synchronize_session=False)
    )


def revoke_device_families(db, device_key_id: int) -> int:
    """Revoke every live family belonging to one device key — the device's
    earlier sign-ins, which a fresh device-key unlock replaces. Returns the
    number of tokens revoked."""
    families = {
        f for (f,) in db.query(RefreshToken.family_id).filter(
            RefreshToken.device_key_id == device_key_id,
            RefreshToken.revoked_at.is_(None),
        )
    }
    return sum(revoke_family(db, f) for f in families)


class RevokedSession(Base):
    """An access-token session (`sid`) that has been signed out.

    Access JWTs are stateless and last 30 days, so dropping a session's cached
    decryption key (logout, "sign this device out") only ever closed the
    encrypted half of the API: the token went on authenticating everything
    that touches no encrypted column — medications, meals, injuries, settings —
    until it expired. require_auth now refuses any token whose `sid` is listed
    here.

    In Postgres rather than Redis because Redis here runs without persistence
    (see crypto_context): a revocation that a container restart forgets would
    quietly re-admit every token it was meant to stop. One primary-key lookup
    per authenticated request is the price; it is the same order as the User
    lookup require_auth already makes.

    Rows are only needed until every token carrying the sid has expired, so
    `expires_at` is the latest such moment and `revoke_sid` prunes past ones.
    """
    __tablename__ = "revoked_sessions"

    sid        = Column(String, primary_key=True)
    expires_at = Column(DateTime(timezone=True), nullable=False)


def revoke_sid(db, sid: str, *, lifetime_days: int) -> None:
    """Record `sid` as signed out until no token carrying it can still be
    valid. `lifetime_days` is the longest a token minted now could live — the
    access-token lifetime, or the refresh-token lifetime when a refresh chain
    could otherwise keep minting tokens for it. The caller commits."""
    now = datetime.now(timezone.utc)
    db.query(RevokedSession).filter(RevokedSession.expires_at < now).delete(
        synchronize_session=False)
    expires = now + timedelta(days=lifetime_days)
    row = db.get(RevokedSession, sid)
    if row is None:
        db.add(RevokedSession(sid=sid, expires_at=expires))
    elif row.expires_at < expires:
        row.expires_at = expires


def is_sid_revoked(db, sid: str) -> bool:
    return db.get(RevokedSession, sid) is not None
