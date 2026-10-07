# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Bearer-token auth for paired sync agents (garmin-sync, a future mobile app)
— replaces the single instance-wide GARMIN_SYNC_SECRET. See
app.models.sync_agents.SyncAgent and the branch plan.

Tokens are high-entropy random values (secrets.token_urlsafe), stored only
as a SHA-256 hash — a straight hash-then-DB-lookup is the standard pattern
for bearer API tokens like this (unlike a fixed shared secret compared with
`==`, there's no meaningful timing side-channel to defend against here: the
attacker can't narrow down a *specific* target value to time against, only
probe whether some hash exists in the table).
"""

import hashlib
import secrets
from datetime import datetime, timezone

from fastapi import Depends, Header, HTTPException
from sqlalchemy.orm import Session

from app.database import get_db
from app.models.activity import Device, User, UserDevice
from app.models.sync_agents import SyncAgent


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def generate_pairing_token() -> tuple[str, str]:
    """Returns (raw_token, token_hash). The raw token is shown to the caller
    exactly once (at pairing time) and never stored — only its hash is."""
    raw = secrets.token_urlsafe(32)
    return raw, _hash_token(raw)


def require_sync_agent(
    authorization: str = Header(default=""),
    db: Session = Depends(get_db),
) -> SyncAgent:
    """FastAPI dependency: validates the `Authorization: Bearer <token>`
    header against a paired, non-revoked SyncAgent."""
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing sync agent token")

    token = authorization.removeprefix("Bearer ").strip()
    if not token:
        raise HTTPException(status_code=401, detail="Missing sync agent token")

    agent = db.query(SyncAgent).filter_by(token_hash=_hash_token(token)).first()
    if agent is None or agent.revoked_at is not None:
        raise HTTPException(status_code=401, detail="Invalid or revoked sync agent token")

    agent.last_seen_at = datetime.now(timezone.utc)
    db.commit()
    return agent


def is_shared_dock(agent: SyncAgent) -> bool:
    """Whether this agent syncs watches that may belong to anyone.

    A household agent obviously does. So does the host's USB bridge
    ("garmin-usb"), even though it is provisioned for the admin: it is a port
    on the server, and whoever plugs a watch into it is not necessarily the
    admin. Treating it as the admin's personal agent filed every docked watch's
    health data into the admin's account, whoever had claimed the watch. A
    phone's agent is personal in fact — the watch it syncs is paired to that
    phone — and keeps resolving to the phone's own account.
    """
    return agent.user_id is None or agent.kind == "garmin-usb"


def _sole_account(db: Session, agent: SyncAgent) -> int | None:
    """The admin behind a host dock, when the instance has no other account.

    With one account there is no one else a docked watch could belong to, so
    the dock claims it on that account's behalf rather than asking the only
    user to claim their own watch. With more than one, a watch only ever seen
    by the server is claimed first-come from the Devices page.
    """
    if agent.user_id is None:
        return None
    return agent.user_id if db.query(User.id).limit(2).count() == 1 else None


def resolve_sync_user_id(db: Session, agent: SyncAgent, device_serial: str | None) -> int | None:
    """Resolve which user's data this agent call should act on.

    - Personal agent (a phone's): always that user, no lookup.
    - Shared dock (household agent, or the host USB bridge — see
      is_shared_dock): the device_serial's UserDevice claim. With no claim
      yet, a host dock on a one-account instance acts for that account;
      otherwise None. Returns None if unresolvable (the device isn't claimed
      by anyone yet, or more than one account claims it) — callers should
      surface a clear "not claimed yet" response, not silently guess. A watch
      claimed by two people is refused rather than filed under whichever claim
      the database happened to return first: that would put one person's
      health data in another's account.
    """
    if not is_shared_dock(agent):
        return agent.user_id
    if not device_serial:
        # A bridge that cannot say which watch this is: only safe when there
        # is one account it could belong to.
        return _sole_account(db, agent)
    device = db.query(Device).filter_by(serial_number=device_serial).first()
    claims = (
        db.query(UserDevice.user_id).filter_by(device_id=device.id).limit(2).all()
        if device is not None else []
    )
    if len(claims) == 1:
        return claims[0][0]
    if claims:
        return None
    return _sole_account(db, agent)


def auto_claimant(db: Session, agent: SyncAgent) -> int | None:
    """Who a newly registered watch is claimed for, without asking anyone:
    the phone's owner for a phone's agent, the only account for a host dock
    on a one-account instance, and nobody otherwise (first-come, from the
    Devices page)."""
    if not is_shared_dock(agent):
        return agent.user_id
    return _sole_account(db, agent)


_HOST_GARMIN_LABEL = "Host USB sync"


def provision_host_garmin_agent(db: Session, user_id: int) -> None:
    """Pre-authorize the host's garmin-sync container for `user_id`, keyed
    to GARMIN_SYNC_BOOTSTRAP_TOKEN — see the setting's docstring in
    app.config for why this one agent skips the manual pairing flow. A
    no-op if that token isn't configured (no Garmin device). Idempotent:
    re-running (e.g. re-enabling after a prior revoke) reuses the same row
    rather than colliding with token_hash's uniqueness constraint."""
    from app.config import settings

    token = settings.garmin_sync_bootstrap_token
    if not token:
        return

    token_hash = _hash_token(token)
    agent = db.query(SyncAgent).filter_by(token_hash=token_hash).first()
    if agent is None:
        db.add(SyncAgent(
            user_id=user_id, created_by_user_id=user_id,
            kind="garmin-usb", label=_HOST_GARMIN_LABEL, token_hash=token_hash,
        ))
    else:
        agent.user_id = user_id
        agent.revoked_at = None


def revoke_host_garmin_agent(db: Session, user_id: int) -> None:
    """Undo provision_host_garmin_agent — used when the onboarding toggle is
    off, or the user disables it later."""
    from app.config import settings

    token = settings.garmin_sync_bootstrap_token
    if not token:
        return

    agent = db.query(SyncAgent).filter_by(token_hash=_hash_token(token), user_id=user_id).first()
    if agent is not None:
        agent.revoked_at = datetime.now(timezone.utc)
