# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Hybrid logical clocks — how every synced edit is ordered.

## Why not wall-clock timestamps

`planned_workouts.field_updated_at` used wall time, which was fine while the
server was the only writer. With phones as peers it is not: a phone whose clock
runs a day fast would win every conflict for a day, and one set to 2035 would
win them forever. An HLC is wall time that can only move forwards and that
advances past every stamp it has seen, so a device can never mint an edit that
sorts below one it already accepted — whatever its own clock says.

## The format

`<wall_ms:016d>-<counter:04x>-<node:16 hex>`, fixed width, so string
comparison is clock comparison in Python, Kotlin, SQLite and Postgres alike.
That is what lets stamps live in JSON and be compared without parsing. The
rules are spec/sync.yaml's; spec/fixtures/sync_scenarios.json is what holds
this file and the phone's to them.
"""
from __future__ import annotations

import re
import secrets
import threading
import time
from dataclasses import dataclass

from app.spec.sync import HLC_MAX_SKEW_MS

_PATTERN = re.compile(r"^(\d{16})-([0-9a-f]{4})-([0-9a-f]{16})$")
_MAX_COUNTER = 0xFFFF


class ClockError(ValueError):
    """A stamp that cannot be accepted: malformed, too far ahead, or overflowed."""


class SkewRefused(ClockError):
    """A stamp more than HLC_MAX_SKEW_MS ahead of this clock's wall time.

    Refused rather than accepted-and-clamped: a phone whose clock was set years
    ahead must not poison a field permanently, and there is no correct value to
    clamp it to.
    """


@dataclass(frozen=True, order=True)
class Hlc:
    wall_ms: int
    counter: int
    node: str

    def __str__(self) -> str:
        return f"{self.wall_ms:016d}-{self.counter:04x}-{self.node}"

    @classmethod
    def parse(cls, text: str) -> "Hlc":
        m = _PATTERN.match(text or "")
        if not m:
            raise ClockError(f"not an HLC stamp: {text!r}")
        return cls(int(m.group(1)), int(m.group(2), 16), m.group(3))


def new_node() -> str:
    """64 random bits, as the 16 hex digits a stamp carries."""
    return secrets.token_hex(8)


def _checked(wall: int, counter: int, node: str) -> Hlc:
    # An error rather than a wrap: wrapping would mint a stamp that sorts
    # below the previous one, which is the one thing a clock must never do.
    if counter > _MAX_COUNTER:
        raise ClockError("HLC counter overflow")
    return Hlc(wall, counter, node)


def tick(last: Hlc | None, now_ms: int, node: str) -> Hlc:
    """The stamp for a local event."""
    if last is None or now_ms > last.wall_ms:
        return _checked(now_ms, 0, node)
    return _checked(last.wall_ms, last.counter + 1, node)


def receive(last: Hlc | None, remote: Hlc, now_ms: int, node: str,
            check_skew: bool = True) -> Hlc:
    """Advance past a stamp from elsewhere. Raises SkewRefused if it is too far ahead.

    `check_skew=False` is the client receiving a *pulled* stamp: the server
    already vetted it, and refusing would stall a phone with a wrong clock on
    the same page forever. The server always checks.
    """
    if check_skew and remote.wall_ms > now_ms + HLC_MAX_SKEW_MS:
        raise SkewRefused(f"stamp {remote} is more than {HLC_MAX_SKEW_MS} ms ahead")
    last_wall = last.wall_ms if last else -1
    last_counter = last.counter if last else 0
    wall = max(now_ms, last_wall, remote.wall_ms)
    if wall == last_wall == remote.wall_ms:
        counter = max(last_counter, remote.counter) + 1
    elif wall == last_wall:
        counter = last_counter + 1
    elif wall == remote.wall_ms:
        counter = remote.counter + 1
    else:
        counter = 0
    return _checked(wall, counter, node)


def _now_ms() -> int:
    return time.time_ns() // 1_000_000


class Clock:
    """A node's clock: the last stamp it issued or saw, behind a lock.

    Per process, not shared through the database. Two workers can therefore
    each be behind a stamp the other has received — which matters only for a
    server-originated write landing on a field a phone stamped from a clock
    running ahead, and [stamp_after] covers exactly that case by never issuing
    a local stamp at or below the one already on the field.
    """

    def __init__(self, node: str, now=_now_ms):
        self.node = node
        self._now = now
        self._last: Hlc | None = None
        self._lock = threading.Lock()

    def tick(self) -> Hlc:
        with self._lock:
            self._last = tick(self._last, self._now(), self.node)
            return self._last

    def receive(self, remote: Hlc) -> Hlc:
        with self._lock:
            self._last = receive(self._last, remote, self._now(), self.node)
            return self._last

    def stamp_after(self, existing: str | None) -> Hlc:
        """A local stamp strictly greater than `existing`.

        A live edit (the web app) must win over whatever is stored: it is being
        made now, by someone looking at the current value. Without this, a
        field last written by a phone whose clock runs ahead would silently
        refuse every web edit until wall time caught up.
        """
        stamp = self.tick()
        if existing is not None and str(stamp) <= existing:
            try:
                self.receive(Hlc.parse(existing))
            except ClockError:
                # A stored stamp that no longer parses, or is absurdly far
                # ahead, cannot be allowed to freeze the field: write anyway.
                return stamp
            stamp = self.tick()
        return stamp

    def current(self) -> Hlc:
        with self._lock:
            if self._last is None:
                self._last = tick(None, self._now(), self.node)
            return self._last
