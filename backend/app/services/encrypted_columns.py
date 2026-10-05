# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
SQLAlchemy column types that transparently encrypt/decrypt using the active
request's decryption key (see app.services.crypto_context).

Write (process_bind_param): encrypts under crypto_context.get_current_key().dek.
Read (process_result_value): decrypts the same way. SQLAlchemy hydrates every
mapped column on a row when it's loaded — not just the ones the calling code
happens to touch — so ANY query against a table with an encrypted column
needs an active key, even if the caller never reads that column. Background
code that queries such a table without a live session must expect and handle
crypto_context.MissingDecryptionKey (see app.api.training_plan.injectors for
the pattern: catch it, degrade to "no data available this run", log it —
never let it silently produce wrong output or crash a whole request).

Ciphertext is AES-256-GCM with a random nonce per value — non-deterministic,
so these columns can never appear in a SQL WHERE/ORDER BY/aggregate, and
can't be indexed. Only apply these types to columns confirmed to never need
that (see the branch plan's per-table classification policy).
"""

import json

from sqlalchemy import LargeBinary
from sqlalchemy.types import TypeDecorator

from app.services import crypto_context, user_crypto


class _EncryptedBase(TypeDecorator):
    impl = LargeBinary
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        material = crypto_context.get_current_key()
        return user_crypto.encrypt_bytes(material.dek, self._to_bytes(value))

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        material = crypto_context.get_current_key()
        return self._from_bytes(user_crypto.decrypt_bytes(material.dek, value))

    def _to_bytes(self, value) -> bytes:
        raise NotImplementedError

    def _from_bytes(self, raw: bytes):
        raise NotImplementedError


class EncryptedString(_EncryptedBase):
    """For Text/String columns holding free-form or short string data."""

    def _to_bytes(self, value: str) -> bytes:
        return value.encode("utf-8")

    def _from_bytes(self, raw: bytes) -> str:
        return raw.decode("utf-8")


class EncryptedJSON(_EncryptedBase):
    """For structured data that would otherwise be a JSON/JSONB column."""

    def _to_bytes(self, value) -> bytes:
        return json.dumps(value).encode("utf-8")

    def _from_bytes(self, raw: bytes):
        return json.loads(raw.decode("utf-8"))


class EncryptedFloat(_EncryptedBase):
    # Redeclared explicitly — SQLAlchemy's cache-key generation checks this
    # per concrete TypeDecorator subclass rather than resolving it through
    # the MRO, so inheriting it from _EncryptedBase alone isn't enough to
    # silence the "will not produce a cache key" warning.
    cache_ok = True

    def _to_bytes(self, value: float) -> bytes:
        return repr(float(value)).encode("utf-8")

    def _from_bytes(self, raw: bytes) -> float:
        return float(raw.decode("utf-8"))
