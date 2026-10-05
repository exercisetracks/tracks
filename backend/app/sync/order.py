# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fractional ordering keys for lists that sync.

A flow's stretches and a workout's exercises used to be ordered by an integer
index, renumbered on every edit. Renumbering is a write to every sibling, so a
reorder on one phone and an insert on another would conflict on rows neither
person touched. A key strictly between its neighbours changes only the moved
row.

Keys use the alphabet 0-9A-Za-z, compare bytewise, and never end in "0" —
that is what guarantees there is always room for another key before any given
one. The midpoint is rocicorp's fractional-indexing algorithm (MIT), without
its integer prefix.
"""
from __future__ import annotations

DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"


def _midpoint(a: str, b: str | None) -> str:
    if b is not None and a >= b:
        raise ValueError(f"{a!r} is not below {b!r}")
    if a.endswith("0") or (b and b.endswith("0")):
        raise ValueError("ordering keys never end in '0'")
    if b is not None:
        n = 0
        while (a[n] if n < len(a) else "0") == b[n]:
            n += 1
        if n > 0:
            return b[:n] + _midpoint(a[n:], b[n:])
    digit_a = DIGITS.index(a[0]) if a else 0
    digit_b = DIGITS.index(b[0]) if b is not None else len(DIGITS)
    if digit_b - digit_a > 1:
        # Round half up, as the reference does — Python's round() would not.
        return DIGITS[(digit_a + digit_b + 1) // 2]
    if b is not None and len(b) > 1:
        return b[:1]
    return DIGITS[digit_a] + _midpoint(a[1:], None)


def key_between(before: str | None, after: str | None) -> str:
    """A key sorting strictly between `before` and `after` (either may be None)."""
    return _midpoint(before or "", after)


def keys_for(count: int) -> list[str]:
    """`count` ascending keys for a list written whole, as the web app does."""
    keys: list[str] = []
    prev: str | None = None
    for _ in range(count):
        prev = key_between(prev, None)
        keys.append(prev)
    return keys
