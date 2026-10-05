# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Random edits from several phones, synced in random order, must converge.

The corpus checks the rules one case at a time; this checks that they compose.
Each simulated phone keeps its own copy with the pure merge (the same rules
the Kotlin replica implements), edits it offline, and syncs through the real
endpoints — push, then pull — whenever the dice say so. After a final round
every phone, and the server, must hold exactly the same rows.

A failure here is the bug that matters most in sync: replicas that each
believe they are current and disagree, with nothing left to retry.
"""
import copy
import time
import random

import pytest

from app.sync import hlc, merge
from app.sync.uids import uuid7

from .helpers import pull_all, push, setup_admin


class SimPhone:
    def __init__(self, node: str, rng: random.Random, skew_ms: int):
        self.rows: dict = {}
        self.outbox: list = []
        self.cursor = 0
        self.rng = rng
        self._t = int(time.time() * 1000) + skew_ms
        self.clock = hlc.Clock(node, now=lambda: self._t)

    def _stamp(self) -> str:
        self._t += self.rng.randint(0, 3)
        return str(self.clock.tick())

    def _local(self, change) -> None:
        try:
            status, _ = merge.apply_change(self.rows, change)
        except merge.Rejected:
            return
        if status in (merge.APPLIED, merge.PARTIAL):
            self.outbox.append(change)

    def _live(self, entity):
        return [k.split("/", 1)[1] for k, r in self.rows.items()
                if k.startswith(entity + "/") and r["deleted"] is None]

    def act(self) -> None:
        r = self.rng.random()
        flows = self._live("flow")
        points = self._live("waypoint")
        if r < 0.2 or not (flows or points):
            s = self._stamp()
            if self.rng.random() < 0.5:
                self._local({"entity": "waypoint", "uid": uuid7(), "fields": {
                    "name": [f"p{self.rng.randint(0, 99)}", s], "lat": [1.0, s], "lng": [2.0, s]}})
            else:
                self._local({"entity": "flow", "uid": uuid7(),
                             "fields": {"name": [f"f{self.rng.randint(0, 99)}", s]}})
        elif r < 0.55 and points:
            s = self._stamp()
            field = self.rng.choice(["name", "notes", "lat"])
            value = 3.5 if field == "lat" else f"v{self.rng.randint(0, 99)}"
            self._local({"entity": "waypoint", "uid": self.rng.choice(points),
                         "fields": {field: [value, s]}})
        elif r < 0.7 and flows:
            s = self._stamp()
            self._local({"entity": "flow_stretch", "uid": uuid7(), "fields": {
                "flow_uid": [self.rng.choice(flows), s],
                "exercise_name": [self.rng.choice(["Pigeon", "Lizard", "Cobra"]), s],
                "order": [self.rng.choice(["V", "k", "3"]), s]}})
        elif r < 0.8 and flows:
            self._local({"entity": "flow", "uid": self.rng.choice(flows),
                         "fields": {"name": [f"f{self.rng.randint(0, 99)}", self._stamp()]}})
        else:
            pool = [("waypoint", u) for u in points] + [("flow", u) for u in flows] + \
                   [("flow_stretch", u) for u in self._live("flow_stretch")]
            entity, uid = self.rng.choice(pool)
            self._local({"entity": entity, "uid": uid, "deleted": self._stamp()})

    def sync(self, client, headers) -> None:
        if self.outbox:
            res = push(client, headers, *self.outbox, epoch=0)
            assert all(r["status"] != "rejected" for r in res["results"]), res
            self.outbox = []
        changes, page = pull_all(client, headers, since=self.cursor)
        self.cursor = page["next"]
        for c in changes:
            for pair in c["fields"].values():
                self.clock.receive(hlc.Hlc.parse(pair[1]))
            merge.apply_change(self.rows, c, from_client=False)


def _server_state(client, headers) -> dict:
    rows: dict = {}
    changes, _ = pull_all(client, headers)
    for c in changes:
        merge.apply_change(rows, c, from_client=False)
    return rows


@pytest.mark.parametrize("seed", range(6))
def test_phones_syncing_in_random_order_converge(client, user, seed):
    rng = random.Random(seed)
    headers = setup_admin(client)
    phones = [SimPhone(f"{i + 1:016x}", rng, skew_ms=rng.randint(-5000, 5000)) for i in range(3)]

    for _ in range(60):
        phone = rng.choice(phones)
        if rng.random() < 0.3:
            phone.sync(client, headers)
        else:
            phone.act()
    for _ in range(2):
        for phone in phones:
            phone.sync(client, headers)

    server = _server_state(client, headers)
    for phone in phones:
        assert phone.rows == server, f"seed {seed}: a phone disagrees with the server"
    assert server, "the scenario made no rows at all"


def test_a_replayed_history_converges_to_the_same_state(client, user):
    """Delivery order must not matter: the same pushes, shuffled, end equal."""
    rng = random.Random(42)
    headers = setup_admin(client)
    phone = SimPhone("00000000000000aa", rng, 0)
    for _ in range(40):
        phone.act()
    history = copy.deepcopy(phone.outbox)
    phone.sync(client, headers)
    ours = ("waypoint/", "flow/", "flow_stretch/")
    first = {k: v for k, v in _server_state(client, headers).items() if k.startswith(ours)}

    rows: dict = {}
    shuffled = history[:]
    rng.shuffle(shuffled)
    for c in shuffled:
        merge.apply_change(rows, c)
    assert rows == first
