# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Session-scoped decryption-key cache, and propagation of "which key is
active" within one request/task.

On login/setup, a user's unwrapped DEK+privkey (see app.services.user_crypto)
are cached in Redis under a random session id (`sid`, embedded in the JWT) so
authenticated requests can transparently decrypt sensitive columns without
re-deriving the Argon2id KEK on every request — that derivation is
deliberately expensive (~150-400ms) and must only happen at login.

The cache holds key material for CRYPTO_SESSION_TTL_SECONDS, sliding on each
read, independent of and deliberately shorter than the JWT's own 30-day
expiry. This is a real UX regression from "log in once, forget about it
forever": once the crypto session lapses, the JWT still authenticates the
user for endpoints that don't touch encrypted data, but anything that does
needs a fresh password entry — surfaced to the frontend as a distinct
"vault_locked" state, not a full logout.

Redis holds this data ENCRYPTED, under a key derived from
settings.session_cache_key — shared across every backend/worker process
(deliberately, unlike a per-process ephemeral key, which would make a
session cached by one replica unreadable by whichever replica happens to
serve the next request). A Redis-only compromise (a leaked RDB backup, an
exposed port) doesn't hand over usable key material without also having that
secret; only a live in-process compromise, or a compromise of both, does.
Run this Redis instance/keyspace with `maxmemory-policy noeviction` and no
persistence (see docker-compose.yml) — key material surviving to disk
unencrypted defeats the point; a restart forcing re-login is the acceptable
failure mode.

Within one request, the active key is exposed via a contextvar, set by the
`require_crypto_session` dependency — which MUST be `async def` (it is).
FastAPI/Starlette runs every *sync* dependency and the sync path operation
function itself via anyio.to_thread.run_sync, each in its own worker thread
against its own throwaway *copy* of the current contextvars.Context —
a `.set()` made inside one such call never becomes visible to a later one,
even within the same request (this bit in practice; see the comment above
require_crypto_session's definition for the full explanation). An `async
def` dependency runs directly on the event loop as part of the request's
own asyncio Task, so its `.set()` mutates that Task's live context, which
every subsequent threadpool call (later dependencies, the endpoint itself)
copies fresh from — already including the mutation.

This propagation is scoped to one request's asyncio Task. It is NOT
inherited by anything spawned with a bare `threading.Thread`, a
`ProcessPoolExecutor`, or a Celery task — those are separate execution
contexts and must call `run_with_key()` explicitly (or, for Celery, fetch
the key from Redis and set the contextvar themselves at the top of the task
body — see backend/app/tasks/).
"""

import contextvars
import hashlib
import logging
import os
from typing import Callable, TypeVar

import anyio.to_thread
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from nacl.public import PrivateKey

from app.config import settings
from app.services.redis_client import get_redis
from app.services.user_crypto import UserKeyMaterial

# A second HTTPBearer instance, not app.auth's — deliberately, to avoid a
# module-level import of app.auth here. app.auth imports app.models.activity
# (for User), and this module gets imported from inside app/models/*.py files
# (via app.services.encrypted_columns, applied to encrypted columns) — a
# module-level `from app.auth import ...` here would make that a circular
# import the moment any model imports encrypted_columns before app.auth has
# finished loading app.models.activity. Functionally identical either way
# (HTTPBearer(auto_error=False) has no state); decode_token is imported
# lazily inside require_crypto_session for the same reason.
_BEARER = HTTPBearer(auto_error=False)

log = logging.getLogger(__name__)

_NONCE_LEN = 12
_DEK_LEN = 32
_T = TypeVar("_T")


class MissingDecryptionKey(Exception):
    """Raised when code that needs the active user's key (an encrypted
    column type, a task touching encrypted data) finds none set for the
    current execution context. Deliberately distinct from returning `None`
    — `None` already means "no data" throughout the calculators, so
    conflating "key missing" with "no data" would silently produce wrong
    output instead of a clear, loud failure."""


# ── Redis-backed session cache ─────────────────────────────────────────────

def _cache_key(sid: str) -> str:
    return f"tracks:crypto_session:{sid}"


def _cache_enc_key() -> bytes:
    # Same "derive a 32-byte key via SHA-256 of the configured secret"
    # convention as app.services.encryption's JWT_SECRET fallback — accepts
    # any-length secret string, not just an exact-length base64 key.
    secret = settings.session_cache_key or settings.jwt_secret
    return hashlib.sha256(secret.encode()).digest()


def _seal_for_cache(material: UserKeyMaterial) -> bytes:
    payload = material.dek + bytes(material.privkey)  # fixed-width: 32 + 32 bytes
    nonce = os.urandom(_NONCE_LEN)
    return nonce + AESGCM(_cache_enc_key()).encrypt(nonce, payload, None)


def _unseal_from_cache(blob: bytes) -> UserKeyMaterial:
    nonce, ciphertext = blob[:_NONCE_LEN], blob[_NONCE_LEN:]
    payload = AESGCM(_cache_enc_key()).decrypt(nonce, ciphertext, None)
    return UserKeyMaterial(dek=payload[:_DEK_LEN], privkey=PrivateKey(payload[_DEK_LEN:]))


def _user_sessions_key(user_id: int) -> str:
    return f"tracks:user_crypto_sessions:{user_id}"


def store_session_key(sid: str, material: UserKeyMaterial, user_id: int | None = None) -> None:
    """Called once, right after a password unwraps the DEK+privkey (login or
    setup) — seeds the crypto session so the rest of that same request, and
    every request after it until the TTL lapses, can decrypt without the
    password.

    With `user_id`, the session is also recorded against its account, which
    is what lets a password change end every other session at once (see
    drop_user_sessions). The index never expires on its own — a session's TTL
    slides, so an index that expired could lose a session still in use — and
    is pruned of dead entries here instead."""
    r = get_redis()
    r.set(_cache_key(sid), _seal_for_cache(material), ex=settings.crypto_session_ttl_seconds)
    if user_id is not None:
        index = _user_sessions_key(user_id)
        for member in r.smembers(index):
            known = member.decode() if isinstance(member, bytes) else member
            if not r.exists(_cache_key(known)):
                r.srem(index, member)
        r.sadd(index, sid)


def load_session_key(sid: str) -> UserKeyMaterial | None:
    """Returns None if the session has never existed or has expired — the
    caller (require_crypto_session) turns that into a distinct 401 rather
    than treating it as "not logged in"."""
    r = get_redis()
    blob = r.get(_cache_key(sid))
    if blob is None:
        return None
    r.expire(_cache_key(sid), settings.crypto_session_ttl_seconds)  # sliding TTL
    try:
        return _unseal_from_cache(blob)
    except Exception:
        log.warning("Failed to unseal cached session key (sid=%s) — treating as expired", sid[:8])
        return None


def session_unlocked(sid: str) -> bool:
    """Whether ``sid``'s key is still cached — without renewing it.

    For the sidebar's status poll, which runs every few seconds for as long as
    a tab is open. Asking through load_session_key would slide the TTL on every
    poll, so an idle tab left open would keep the vault unlocked indefinitely;
    the TTL is meant to measure use, not an open window.
    """
    try:
        return bool(get_redis().exists(_cache_key(sid)))
    except Exception:
        return False


def drop_session_key(sid: str) -> None:
    get_redis().delete(_cache_key(sid))


def drop_user_sessions(user_id: int, keep: str | None = None) -> int:
    """End every recorded crypto session of `user_id` except `keep`, and
    return how many were ended.

    The answer to "I think someone has my password": without it, a session
    already open elsewhere goes on decrypting for as long as it stays active,
    whatever the password is changed to, because the DEK it holds does not
    change. Sessions opened before the index existed are not in it and run
    out on their TTL instead."""
    r = get_redis()
    index = _user_sessions_key(user_id)
    dropped = 0
    for member in r.smembers(index):
        sid = member.decode() if isinstance(member, bytes) else member
        if sid == keep:
            continue
        dropped += r.delete(_cache_key(sid))
        r.srem(index, member)
    return dropped


def renew_session_key(sid: str) -> None:
    """Heartbeat for long-running Celery tasks: a sliding TTL driven only by
    HTTP activity would otherwise expire mid-task on a job with no
    intervening requests. Tasks that hold a key across meaningful work
    should call this periodically while running."""
    get_redis().expire(_cache_key(sid), settings.crypto_session_ttl_seconds)


# ── Contextvar propagation ─────────────────────────────────────────────────

_current_key: contextvars.ContextVar[UserKeyMaterial | None] = contextvars.ContextVar(
    "current_key", default=None
)


def get_current_key() -> UserKeyMaterial:
    material = _current_key.get()
    if material is None:
        raise MissingDecryptionKey("No decryption key set for the current execution context")
    return material


def set_current_key(material: UserKeyMaterial | None) -> contextvars.Token:
    return _current_key.set(material)


def reset_current_key(token: contextvars.Token) -> None:
    _current_key.reset(token)


def run_with_key(material: UserKeyMaterial, fn: Callable[..., _T], *args, **kwargs) -> _T:
    """Explicitly propagate `material` into a manually-spawned thread or
    task. Contextvars do NOT cross threading.Thread / ProcessPoolExecutor /
    Celery task boundaries on their own — call this at the top of any such
    callable instead of relying on ambient context."""
    token = set_current_key(material)
    try:
        return fn(*args, **kwargs)
    finally:
        reset_current_key(token)


# ── FastAPI dependency ──────────────────────────────────────────────────────
#
# This MUST be `async def`, not a plain sync generator. FastAPI/Starlette
# runs every sync dependency (and the sync path operation function itself)
# via anyio.to_thread.run_sync, and each of those calls executes in its own
# worker thread against its own *copy* of the current contextvars.Context —
# mutations made inside one such call (e.g. a contextvar .set()) are made to
# that throwaway copy and never become visible to a later call, even within
# the same request. Concretely: a sync generator dependency's `yield`
# (entered via one threadpool call) and its cleanup (a SEPARATE threadpool
# call, per fastapi.concurrency.contextmanager_in_threadpool) don't even
# share a context with each other, let alone with the endpoint function's
# own threadpool call — so `_current_key.set(...)` there would never reach
# the code that actually needs it, and `Token.reset()` would raise
# "created in a different Context" on top of that.
#
# An `async def` dependency runs directly on the event loop, as part of the
# request's own asyncio Task — so `.set()` here mutates that Task's *live*
# context, which every *subsequent* run_in_threadpool call (later
# dependencies, and the endpoint itself) copies fresh from, already
# including this mutation. The one blocking call inside (the Redis fetch)
# is explicitly offloaded so it doesn't stall the event loop; everything
# else here is cheap, CPU-only JWT/contextvar work safe to run inline.

async def require_crypto_session(
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
):
    """Dependency for any endpoint that reads/writes encrypted columns.
    Distinct from app.auth.require_auth: a valid, unexpired JWT is not
    sufficient here — the Redis-cached session key must also still be
    present. Raises a 401 with a distinguishable detail so the frontend can
    prompt for password re-entry ("vault_locked") instead of a full
    logout/redirect-to-login."""
    if credentials is None:
        raise HTTPException(status_code=401, detail="Not authenticated")

    from app.auth import decode_token  # see the _BEARER comment above

    try:
        payload = decode_token(credentials.credentials)
        sid = payload["sid"]
    except Exception:
        raise HTTPException(status_code=401, detail="Invalid or expired token")

    material = await anyio.to_thread.run_sync(load_session_key, sid)
    if material is None:
        raise HTTPException(status_code=401, detail="session_expired")

    token = set_current_key(material)
    try:
        yield material
    finally:
        reset_current_key(token)


async def optional_crypto_session(
    credentials: HTTPAuthorizationCredentials | None = Depends(_BEARER),
):
    """`require_crypto_session` for an endpoint that can do without the key:
    the session's key is made current when it is cached, and the endpoint
    runs either way (yielding None when it is not).

    For plan generation, which reads one encrypted thing — the person's
    injuries, to keep strength off an injured part — and must not refuse to
    rebuild a plan over a locked vault. Without the key it plans as if there
    were no injuries (injectors._get_active_injuries says so in the log);
    with it, it plans as their phone does, which always holds the key. Same
    source and same lifetime as the required form: the key cached for this
    token's own session, set for this request only. Must be `async def` for
    the reason given above `require_crypto_session`.
    """
    material = None
    if credentials is not None:
        from app.auth import decode_token  # see the _BEARER comment above
        try:
            sid = decode_token(credentials.credentials)["sid"]
        except Exception:
            sid = None
        if sid:
            material = await anyio.to_thread.run_sync(load_session_key, sid)
    token = set_current_key(material)
    try:
        yield material
    finally:
        reset_current_key(token)
