# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import logging
from datetime import datetime, timedelta, timezone

import bcrypt
import redis
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt
from sqlalchemy.orm import Session

from app.config import settings
from app.database import get_db
from app.models.activity import User
from app.models.user_settings import UserSettings
from app.services.redis_client import get_redis

log = logging.getLogger(__name__)

_ALGORITHM = "HS256"
_TOKEN_EXPIRE_DAYS = 30
_BEARER = HTTPBearer(auto_error=False)


# bcrypt 5.x raises ValueError on inputs >72 bytes instead of silently
# truncating. Truncate to 72 bytes ourselves (the algorithm only ever uses the
# first 72) so an over-long password can never crash setup or login with a 500.
_BCRYPT_MAX_BYTES = 72


def hash_password(password: str) -> str:
    return bcrypt.hashpw(password.encode()[:_BCRYPT_MAX_BYTES], bcrypt.gensalt()).decode()


def verify_password(password: str, hashed: str) -> bool:
    return bcrypt.checkpw(password.encode()[:_BCRYPT_MAX_BYTES], hashed.encode())


def create_token(user_id: int, sid: str | None = None) -> str:
    """`sid` identifies this login's crypto session (see
    app.services.crypto_context) — the JWT authenticates the user for the
    full 30 days, but the Redis-cached decryption key behind `sid` expires
    much sooner, on its own sliding TTL. Every login/setup call should pass
    one; it's optional only so existing tests/tools that mint a bare
    identity token still work."""
    expire = datetime.now(timezone.utc) + timedelta(days=_TOKEN_EXPIRE_DAYS)
    payload = {"sub": str(user_id), "exp": expire}
    if sid is not None:
        payload["sid"] = sid
    return jwt.encode(payload, settings.jwt_secret, algorithm=_ALGORITHM)


def decode_token(token: str) -> dict:
    """Raises JWTError on an invalid/expired token — callers decide the HTTP
    response. Shared by require_auth and require_crypto_session so both
    dependencies agree on exactly what makes a token valid."""
    return jwt.decode(token, settings.jwt_secret, algorithms=[_ALGORITHM])


# Cached setup state: flips to True once an admin with a password exists, and
# setup is one-way, so it never needs to flip back. Saves two DB queries on
# every authenticated request.
#
# Two layers: a per-process bool (zero-cost once warm — the common case, so
# no Redis round trip on every request forever) backed by a Redis flag
# shared across every worker/replica. A replica that starts after setup
# completed elsewhere warms its local bool from that one fast Redis read
# instead of needing its own DB round trip. (If the DB is ever wiped
# underneath a running instance, this flag doesn't self-clear — see
# docker-compose.yml's redis service, run with no persistence, so a Redis
# restart is the reset path, same "restart to re-enter open-mode" contract
# as before, just at the Redis layer instead of per-backend-process. Tests
# reset via _reset_setup_cache.)
_setup_complete = False
_SETUP_COMPLETE_KEY = "tracks:setup_complete"


def _reset_setup_cache() -> None:
    global _setup_complete
    _setup_complete = False
    try:
        get_redis().delete(_SETUP_COMPLETE_KEY)
    except redis.RedisError:
        pass


def require_auth(
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
    db: Session = Depends(get_db),
) -> User:
    """
    FastAPI dependency that validates the JWT bearer token.
    Open-mode (no auth required) only while no admin account has been configured.
    """
    global _setup_complete
    if not _setup_complete:
        # Best-effort: this flag is purely a cross-replica speed-up over the
        # DB check below, never the sole source of truth, so a Redis blip
        # here should fall through to the DB rather than 500 every request.
        try:
            if get_redis().get(_SETUP_COMPLETE_KEY):
                _setup_complete = True
        except redis.RedisError:
            pass

    if not _setup_complete:
        # Open-mode: no admin with a password has been set up yet.
        admin = db.query(User).filter_by(is_admin=True).first()
        if admin is None:
            # Fall back to any existing user (created by file watcher before setup).
            user = db.query(User).order_by(User.id).first()
            if user is None:
                raise HTTPException(status_code=503, detail="No user configured")
            return user

        us = db.query(UserSettings).filter_by(user_id=admin.id).first()
        if us is None or us.password_hash is None:
            return admin  # admin exists but no password yet — still in setup

        _setup_complete = True
        try:
            get_redis().set(_SETUP_COMPLETE_KEY, "1")
        except redis.RedisError:
            pass  # this process still knows; a sibling replica just re-checks the DB once

    if credentials is None:
        raise HTTPException(status_code=401, detail="Not authenticated")

    try:
        payload = decode_token(credentials.credentials)
        user_id = int(payload["sub"])
    except (JWTError, KeyError, ValueError):
        raise HTTPException(status_code=401, detail="Invalid or expired token")

    user = db.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=401, detail="User not found")
    return user
