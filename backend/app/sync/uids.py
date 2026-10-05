# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Sync identities.

Random rows get UUIDv7 — time-ordered, so they index well in both Postgres
and SQLite. Rows with a natural key get UUIDv5 of that key under the contract's
namespace, so two devices creating "the same" thing offline (the same watch,
the same day's weight) produce the same uid and merge rather than duplicate.
The templates live in spec/sync.yaml, not here.
"""
from __future__ import annotations

import os
import time
import uuid

from app.spec.sync import UID_NAMESPACE

_NAMESPACE = uuid.UUID(UID_NAMESPACE)


def uuid7() -> str:
    """RFC 9562 UUIDv7: 48 bits of Unix milliseconds, then randomness.

    Hand-rolled because the standard library only gained it in 3.14.
    """
    ms = time.time_ns() // 1_000_000
    rand = int.from_bytes(os.urandom(10), "big")
    value = (ms & ((1 << 48) - 1)) << 80
    value |= 0x7 << 76                        # version
    value |= ((rand >> 62) & 0xFFF) << 64     # rand_a
    value |= 0b10 << 62                       # variant
    value |= rand & ((1 << 62) - 1)           # rand_b
    return str(uuid.UUID(int=value))


def uuid5_for(key: str) -> str:
    return str(uuid.uuid5(_NAMESPACE, key))


def natural_uid(template: str, **values) -> str:
    """The uid for a natural-key row, e.g. natural_uid("daily:{date}", date="2026-09-24")."""
    return uuid5_for(template.format(**values))
