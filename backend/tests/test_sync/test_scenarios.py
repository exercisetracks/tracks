# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The shared sync corpus, replayed against the server's merge.

spec/fixtures/sync_scenarios.json is hand-written, and it is the specification:
the phone's Kotlin suite replays the same file. A case failing here means the
server would converge to a different state than a phone given the same edits —
the one kind of sync bug that no amount of retrying repairs.
"""
import copy
import json
from pathlib import Path

import pytest

from app.sync import hlc, merge, uids

FIXTURES = json.loads(Path("/spec/fixtures/sync_scenarios.json").read_text())


def _hlc_or_none(text):
    return hlc.Hlc.parse(text) if text else None


@pytest.mark.parametrize("case", FIXTURES["hlc"], ids=lambda c: c["name"])
def test_the_clock_follows_the_shared_corpus(case):
    """Tick/receive rules: a divergence here orders the same two edits
    differently on the phone and the server."""
    if case["op"] == "format":
        assert str(hlc.Hlc(case["wall_ms"], case["counter"], case["node"])) == case["expect"]
        return
    last = _hlc_or_none(case["last"])
    if case["op"] == "tick":
        assert str(hlc.tick(last, case["now"], case["node"])) == case["expect"]
        return
    remote = hlc.Hlc.parse(case["remote"])
    if case["op"] == "receive_pulled":
        assert str(hlc.receive(last, remote, case["now"], case["node"], check_skew=False)) == case["expect"]
        return
    if case["expect"] == "refused":
        with pytest.raises(hlc.SkewRefused):
            hlc.receive(last, remote, case["now"], case["node"])
    else:
        assert str(hlc.receive(last, remote, case["now"], case["node"])) == case["expect"]


@pytest.mark.parametrize("case", FIXTURES["uids"], ids=lambda c: c["key"])
def test_natural_keys_derive_the_same_uid_everywhere(case):
    """Two devices creating the same watch or the same day offline must land on
    one row; that only happens if both derive the identical uid."""
    assert uids.uuid5_for(case["key"]) == case["expect"]


@pytest.mark.parametrize("case", FIXTURES["merge"], ids=lambda c: c["name"])
def test_the_merge_follows_the_shared_corpus(case):
    """Field LWW, delete-wins, cascade, log immutability, readonly refusal."""
    rows = copy.deepcopy(case["before"])
    try:
        status, refused = merge.apply_change(rows, case["change"])
    except merge.Rejected:
        status, refused = merge.REJECTED, {}
    assert status == case["status"]
    assert refused == case["refused"]
    assert rows == (case["before"] if status == merge.REJECTED else case["after"])


@pytest.mark.parametrize("case", FIXTURES["derived"], ids=lambda c: c["name"])
def test_read_time_rules_follow_the_shared_corpus(case):
    """Invariants resolved on read, identically on every replica."""
    rows = case["rows"]
    if case["rule"] == "active_goal":
        goals = {k.split("/", 1)[1]: r for k, r in rows.items() if k.startswith("goal/")}
        assert merge.active_goal(goals) == case["expect"]
    elif case["rule"] == "dead_workouts":
        assert sorted(merge.dead_workouts(rows)) == sorted(case["expect"])
    else:
        pytest.fail(f"unknown rule {case['rule']}")


def test_a_regeneration_arriving_before_its_plan_is_not_reaped():
    """Workouts of a newer generation can arrive before the plan's generation
    edit does. Reaping them would be a delete, and deletes win everywhere."""
    plan = {"fields": {"generation": "g1"}, "clock": {"generation": "0000000000001000-0000-000000000000000a"},
            "deleted": None}
    early = {"fields": {"plan_uid": "p", "generation": "g2"},
             "clock": {"plan_uid": "0000000000002000-0000-000000000000000b",
                       "generation": "0000000000002000-0000-000000000000000b"},
             "deleted": None}
    assert not merge.workout_is_dead(early, plan)


def test_uuid7_is_a_version_7_uuid_and_time_ordered():
    """UUIDv7 is what keeps new rows clustered in the uid index."""
    import uuid
    a, b = uids.uuid7(), uids.uuid7()
    assert uuid.UUID(a).version == 7
    assert a[:8] <= b[:8]
