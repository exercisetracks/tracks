# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Unit tests for the session-scoped decryption-key cache and contextvar
propagation (app.services.crypto_context). Uses the autouse `fake_redis`
fixture from conftest.py — no real Redis involved.
"""

import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from fastapi.security import HTTPAuthorizationCredentials

from app.auth import create_token
from app.services import crypto_context, user_crypto


@pytest.fixture(autouse=True)
def _reset_contextvar():
    """Safety net: pytest runs all tests in one OS thread, so a contextvar
    left set by one test (a bug, or an intentionally-unclosed generator)
    would otherwise leak into every test that runs after it."""
    crypto_context.set_current_key(None)
    yield
    crypto_context.set_current_key(None)


def _material():
    gen = user_crypto.generate_user_keys("correct horse battery staple")
    row = SimpleNamespace(
        salt=gen.salt, kdf_params=gen.kdf_params,
        wrapped_dek=gen.wrapped_dek, wrapped_privkey=gen.wrapped_privkey,
    )
    return user_crypto.unwrap_with_password("correct horse battery staple", row)


# ── Redis-backed cache ───────────────────────────────────────────────────────

def test_store_and_load_round_trip():
    material = _material()
    crypto_context.store_session_key("sid-1", material)

    loaded = crypto_context.load_session_key("sid-1")

    assert loaded is not None
    assert loaded.dek == material.dek
    assert bytes(loaded.privkey) == bytes(material.privkey)


def test_load_missing_session_returns_none():
    assert crypto_context.load_session_key("never-existed") is None


def test_drop_session_key_makes_it_unloadable():
    material = _material()
    crypto_context.store_session_key("sid-2", material)
    assert crypto_context.load_session_key("sid-2") is not None

    crypto_context.drop_session_key("sid-2")

    assert crypto_context.load_session_key("sid-2") is None


def test_load_renews_sliding_ttl(fake_redis):
    material = _material()
    crypto_context.store_session_key("sid-3", material)
    fake_redis.expire(crypto_context._cache_key("sid-3"), 1)  # about to expire

    crypto_context.load_session_key("sid-3")

    ttl = fake_redis.ttl(crypto_context._cache_key("sid-3"))
    assert ttl > 1  # reset back to the full configured TTL


def test_cached_material_is_encrypted_at_rest_in_redis(fake_redis):
    """The raw DEK bytes must never appear verbatim in what's stored — only
    a sealed blob a process holding session_cache_key can open."""
    material = _material()
    crypto_context.store_session_key("sid-4", material)

    raw_value = fake_redis.get(crypto_context._cache_key("sid-4"))
    assert material.dek not in raw_value
    assert bytes(material.privkey) not in raw_value


# ── Contextvar propagation ───────────────────────────────────────────────────

def test_get_current_key_without_context_raises():
    with pytest.raises(crypto_context.MissingDecryptionKey):
        crypto_context.get_current_key()


def test_set_and_reset_current_key():
    material = _material()
    token = crypto_context.set_current_key(material)
    try:
        assert crypto_context.get_current_key() is material
    finally:
        crypto_context.reset_current_key(token)

    with pytest.raises(crypto_context.MissingDecryptionKey):
        crypto_context.get_current_key()


def test_run_with_key_sets_key_for_the_call_only():
    material = _material()

    def inner():
        return crypto_context.get_current_key()

    assert crypto_context.run_with_key(material, inner) is material

    with pytest.raises(crypto_context.MissingDecryptionKey):
        crypto_context.get_current_key()


def test_bare_thread_does_not_inherit_context():
    """The exact gotcha the plan calls out: a raw threading.Thread does NOT
    see a contextvar set on the calling thread."""
    material = _material()
    reset_token = crypto_context.set_current_key(material)

    result = {}

    def worker():
        try:
            crypto_context.get_current_key()
            result["raised"] = False
        except crypto_context.MissingDecryptionKey:
            result["raised"] = True

    try:
        t = threading.Thread(target=worker)
        t.start()
        t.join()
    finally:
        crypto_context.reset_current_key(reset_token)

    assert result["raised"] is True


def test_run_with_key_fixes_the_thread_gotcha():
    material = _material()
    result = {}

    def worker():
        result["key"] = crypto_context.get_current_key()

    t = threading.Thread(target=lambda: crypto_context.run_with_key(material, worker))
    t.start()
    t.join()

    assert result["key"] is material


def test_run_with_key_fixes_process_pool_executor_boundary_too():
    """ProcessPoolExecutor workers are separate interpreters — no contextvar
    exists there at all. A worker function must accept the key explicitly
    and set it itself; this documents that run_with_key works the same way
    inside a thread pool (the in-process analogue) as it does bare."""
    material = _material()

    def worker(m):
        return crypto_context.run_with_key(m, crypto_context.get_current_key)

    with ThreadPoolExecutor(max_workers=1) as pool:
        returned = pool.submit(worker, material).result()

    assert returned is material


# ── require_crypto_session dependency ───────────────────────────────────────
#
# require_crypto_session is `async def` (must be — see the module docstring
# and the comment above its definition for why a sync generator dependency
# can't reliably propagate a contextvar to the endpoint in FastAPI). These
# tests drive it directly with asyncio.run rather than through a real
# request — asyncio.run is fine to call more than once against the same
# async generator object here since nothing in the dependency holds onto
# loop-specific resources across awaits.

import asyncio


def _bearer(token: str) -> HTTPAuthorizationCredentials:
    return HTTPAuthorizationCredentials(scheme="Bearer", credentials=token)


def _anext(gen):
    return asyncio.run(gen.__anext__())


class TestRequireCryptoSession:
    def test_yields_material_for_a_valid_session(self):
        """Enter, assert, and exit all run inside ONE asyncio.run() call —
        matching how a real request stays within a single asyncio Task
        throughout. Splitting this across separate asyncio.run() calls (as
        an earlier version of this test did) doesn't represent that: each
        asyncio.run() gets its own Task with its own context copy, so a
        contextvar mutation from one wouldn't be visible to the next —
        exactly the multi-thread-copy problem require_crypto_session exists
        to avoid, just manifesting a different way here as a test artifact,
        not a real bug."""
        material = _material()
        sid = "sid-valid"
        crypto_context.store_session_key(sid, material)
        token = create_token(user_id=1, sid=sid)

        async def scenario():
            gen = crypto_context.require_crypto_session(credentials=_bearer(token))
            yielded = await gen.__anext__()

            assert yielded.dek == material.dek
            assert crypto_context.get_current_key() is yielded

            # Finalizing the generator (as FastAPI does on request teardown)
            # resets the contextvar.
            with pytest.raises(StopAsyncIteration):
                await gen.__anext__()
            with pytest.raises(crypto_context.MissingDecryptionKey):
                crypto_context.get_current_key()

        asyncio.run(scenario())

    def test_no_credentials_raises_401(self):
        from fastapi import HTTPException

        gen = crypto_context.require_crypto_session(credentials=None)
        with pytest.raises(HTTPException) as exc_info:
            _anext(gen)
        assert exc_info.value.status_code == 401

    def test_valid_token_but_expired_session_raises_distinguishable_401(self):
        """The JWT is fine (not expired, well-formed) but nothing is cached
        behind its sid — this must be a *different* signal than 'not logged
        in' so the frontend can prompt for password re-entry instead of a
        full logout."""
        from fastapi import HTTPException

        token = create_token(user_id=1, sid="sid-never-stored")

        gen = crypto_context.require_crypto_session(credentials=_bearer(token))
        with pytest.raises(HTTPException) as exc_info:
            _anext(gen)
        assert exc_info.value.status_code == 401
        assert exc_info.value.detail == "session_expired"

    def test_token_without_sid_raises_401(self):
        """An old-style token minted without create_token's sid param (or a
        forged one) has no session to look up."""
        from fastapi import HTTPException
        from jose import jwt
        from app.config import settings

        token = jwt.encode({"sub": "1"}, settings.jwt_secret, algorithm="HS256")

        gen = crypto_context.require_crypto_session(credentials=_bearer(token))
        with pytest.raises(HTTPException) as exc_info:
            _anext(gen)
        assert exc_info.value.status_code == 401
