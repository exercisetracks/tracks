# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Marking a goal done.

Before, a goal could only be set aside (is_active false), which left a race
already run looking the same as one merely paused. Done and active never hold
together: completing leaves the active slot, activating again reopens it.
"""
from datetime import date, timedelta

import pytest

from app.spec.sync import ENTITIES

from ..test_sync.helpers import setup_admin


@pytest.fixture(autouse=True)
def no_background_refresh(monkeypatch):
    """Creating a planned goal refreshes plans on a thread, which would share
    this test's connection and race it."""
    from app.api import training_plan as training_plan_api
    monkeypatch.setattr(training_plan_api, "refresh_plans_for_user", lambda *_: None)


def _race(client, h):
    resp = client.post("/coaching/goals", headers=h, json={
        "goal_type": "event", "event_name": "10K", "event_sport": "running",
        "event_date": (date.today() + timedelta(days=40)).isoformat(), "event_distance_meters": 10_000.0,
    })
    assert resp.status_code == 201, resp.text
    return resp.json()


def test_completing_a_goal_takes_it_out_of_the_active_slot(client, db):
    h = setup_admin(client)
    goal = _race(client, h)
    assert goal["is_active"] and goal["completed_at"] is None

    today = date.today().isoformat()
    done = client.patch(f"/coaching/goals/{goal['id']}", headers=h, json={"completed_at": today}).json()
    assert done["completed_at"] == today
    assert done["is_active"] is False


def test_activating_a_completed_goal_reopens_it(client, db):
    """Done and active would otherwise both hold, and the active card would
    show a goal the list calls finished."""
    h = setup_admin(client)
    goal = _race(client, h)
    client.patch(f"/coaching/goals/{goal['id']}", headers=h, json={"completed_at": date.today().isoformat()})
    again = client.patch(f"/coaching/goals/{goal['id']}", headers=h, json={"is_active": True}).json()
    assert again["is_active"] is True
    assert again["completed_at"] is None


def test_completion_syncs_to_the_phone():
    """The phone completes goals offline, so the mark is a synced field."""
    assert "completed_at" in ENTITIES["goal"]["fields"]
