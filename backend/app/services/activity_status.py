# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What the server is doing for one user right now — the sidebar's status.

## Why it was rewritten

The sidebar used to spin "Importing…" whenever a queued import row was
unprocessed, and "Syncing watch…" whenever one instance-wide flag was set. Both
were true far more often than anything was happening. Queued rows wait for the
user's key, and with the vault shut they can wait for days, so the spinner
turned for days while nothing ran (found 2026-10-07: 54 rows queued since the
3rd, the worker logging "session expired" each time it was asked). And a watch
docked for one account spun the sidebar of every account on the instance.

So each activity is now a heartbeat set by the thing actually doing the work,
for the user it is doing it for, with a short TTL — a crashed worker or an
unplugged watch simply stops reporting and the row disappears on its own:

* ``import``  — fit_import.process_pending_imports_for_user, per file, with
  progress.
* ``watch``   — the USB agent (garmin-sync) between its start and its finish.
* ``phone``   — any phone push, pull or file download, so a phone syncing with
  the server shows. Its last time is kept too, without a TTL, for "synced".

Files that are queued but cannot be read yet are reported separately by the
status endpoint, as waiting, not as importing.

Fail-open on any Redis error, meaning "nothing happening": a missing spinner
is a far better failure than a stuck one.
"""

from __future__ import annotations

import json
import logging
import time

import redis

from app.services.redis_client import get_redis

log = logging.getLogger(__name__)

_PREFIX = "tracks:activity:"

#: How long each kind may go without a heartbeat before it is presumed over.
TTL = {"import": 120, "watch": 300, "phone": 20}


def _key(user_id: int, kind: str) -> str:
    return f"{_PREFIX}{user_id}:{kind}"


def mark(user_id: int | None, kind: str, **detail) -> None:
    """Record (or refresh) that ``kind`` is under way for this user."""
    if user_id is None:
        return
    try:
        r = get_redis()
        r.set(_key(user_id, kind), json.dumps({**detail, "at": time.time()}), ex=TTL[kind])
        if kind == "phone":
            r.set(_key(user_id, "phone_last"), str(time.time()))
    except redis.RedisError:
        log.debug("could not record %s activity for user %s", kind, user_id)


def clear(user_id: int | None, kind: str) -> None:
    if user_id is None:
        return
    try:
        get_redis().delete(_key(user_id, kind))
    except redis.RedisError:
        pass


def snapshot(user_id: int) -> dict:
    """``{"import": {...}|None, "watch": ..., "phone": ..., "phone_last": epoch|None}``."""
    out: dict = {k: None for k in TTL}
    out["phone_last"] = None
    try:
        r = get_redis()
        keys = [_key(user_id, k) for k in TTL] + [_key(user_id, "phone_last")]
        values = r.mget(keys)
    except redis.RedisError:
        return out
    for kind, raw in zip(list(TTL) + ["phone_last"], values):
        if raw is None:
            continue
        try:
            out[kind] = float(raw) if kind == "phone_last" else json.loads(raw)
        except (ValueError, TypeError):
            pass
    return out


def claim_import_kick(user_id: int, seconds: int = 60) -> bool:
    """True for the first caller in ``seconds`` — so the status poll can start
    a stalled queue without enqueueing a task every two seconds."""
    try:
        return bool(get_redis().set(_key(user_id, "kick"), "1", nx=True, ex=seconds))
    except redis.RedisError:
        return False
