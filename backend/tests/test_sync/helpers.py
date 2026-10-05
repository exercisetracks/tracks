# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared plumbing for the sync endpoint tests: accounts with live vaults, a
phone-side clock, and change builders in the wire shape of spec/sync.yaml."""
from __future__ import annotations

import itertools
import time

from app.sync.uids import uuid7

_counter = itertools.count(1)


def setup_admin(client) -> dict:
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "originalpass1",
    })
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


def second_account(client, admin_headers, username="partner") -> dict:
    resp = client.post("/users/", headers=admin_headers, json={
        "username": username, "name": username.title(), "password": "secondpass12",
    })
    assert resp.status_code == 201, resp.text
    login = client.post("/auth/login", json={"username": username, "password": "secondpass12"})
    assert login.status_code == 200, login.text
    return {"Authorization": f"Bearer {login.json()['access_token']}"}


class Phone:
    """A phone's clock: real wall time plus a counter, and its own node id."""

    def __init__(self, node: str, offset_ms: int = 0):
        self.node = node
        self.offset_ms = offset_ms
        self._last = (0, 0)

    def stamp(self) -> str:
        now = int(time.time() * 1000) + self.offset_ms
        wall, counter = self._last
        if now > wall:
            wall, counter = now, 0
        else:
            counter += 1
        self._last = (wall, counter)
        return f"{wall:016d}-{counter:04x}-{self.node}"

    def change(self, entity, uid=None, deleted=False, **fields) -> dict:
        s = self.stamp()
        c = {"entity": entity, "uid": uid or uuid7(),
             "fields": {k: [v, s] for k, v in fields.items()}}
        if deleted:
            c["deleted"] = s
        return c


def push(client, headers, *changes, epoch=None):
    body = {"node": "0" * 16, "changes": list(changes)}
    if epoch is not None:
        body["epoch"] = epoch
    resp = client.post("/sync/push", headers=headers, json=body)
    assert resp.status_code == 200, resp.text
    return resp.json()


def pull_all(client, headers, since=0):
    """Every change after `since`, walking pages; returns (changes, last page)."""
    out = []
    while True:
        resp = client.get(f"/sync/pull?since={since}&limit=50", headers=headers)
        assert resp.status_code == 200, resp.text
        page = resp.json()
        out.extend(page["changes"])
        since = page["next"]
        if not page["has_more"]:
            return out, page


def latest(changes, entity, uid):
    """The last pulled state of one row."""
    found = [c for c in changes if c["entity"] == entity and c["uid"] == uid]
    return found[-1] if found else None
