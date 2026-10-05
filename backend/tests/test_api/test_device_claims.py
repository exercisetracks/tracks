# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Claiming a device, in a household with more than one account.

A claim decides whose account a watch's data is filed under, so it is an
authorization decision: these tests pin that one person cannot use it to take
another's history.
"""
import pytest

import app.api.devices as devices_api
from app.auth import create_token, hash_password
from app.models.activity import Activity, Device, User, UserDevice
from app.models.user_settings import UserSettings


def _account(db, username, *, admin=False):
    u = User(name=username.title(), username=username, is_admin=admin)
    db.add(u)
    db.flush()
    db.add(UserSettings(user_id=u.id, password_hash=hash_password("x" * 12), hidden_sports=[]))
    return u


def _headers(u):
    return {"Authorization": f"Bearer {create_token(u.id)}"}


@pytest.fixture
def household(db, monkeypatch):
    """Two accounts and one watch. The reattribution normally runs in a
    background thread on its own session; here it runs inline on the test's,
    so its effect is visible to the assertions."""
    alice = _account(db, "alice", admin=True)
    bob = _account(db, "bob")
    watch = Device(serial_number="3990001111", manufacturer="garmin", manufacturer_id=1)
    db.add(watch)
    db.flush()

    class _Shared:
        def __getattr__(self, name):
            return getattr(db, name)

        def close(self):
            pass

    monkeypatch.setattr(devices_api, "SessionLocal", lambda: _Shared())
    monkeypatch.setattr(devices_api, "_trigger_reorg", devices_api._reattribute_activities_for_user)
    monkeypatch.setattr(devices_api, "_invalidate_all_caches", lambda *a: None)
    db.commit()
    return alice, bob, watch


def test_a_device_held_by_another_account_cannot_be_claimed(client, db, household):
    """Claiming Alice's watch used to file every activity it ever recorded
    under Bob's account. The claim is refused and nothing moves."""
    alice, bob, watch = household
    db.add(UserDevice(user_id=alice.id, device_id=watch.id))
    run = Activity(user_id=alice.id, device_id=watch.id, name="Alice's run", sport="running")
    db.add(run)
    db.commit()

    resp = client.post(f"/devices/{watch.id}/claim", headers=_headers(bob))

    assert resp.status_code == 409
    db.refresh(run)
    assert run.user_id == alice.id
    assert db.query(UserDevice).filter_by(user_id=bob.id).count() == 0


def test_a_released_device_does_not_bring_its_history_along(client, db, household):
    """A watch passed on to someone else is theirs from now on; the runs its
    previous owner recorded on it stay in the previous owner's account."""
    alice, bob, watch = household
    run = Activity(user_id=alice.id, device_id=watch.id, name="Alice's run", sport="running")
    db.add(run)
    db.commit()

    resp = client.post(f"/devices/{watch.id}/claim", headers=_headers(bob))

    assert resp.status_code == 200
    db.refresh(run)
    assert run.user_id == alice.id


def test_claiming_files_the_devices_unattributed_activities(client, db, household):
    """What reattribution is for: activities imported before anyone claimed
    the watch belong to whoever does. (The old filter compared user_id with
    `!=`, which is never true of NULL, so this never actually happened.)"""
    _, bob, watch = household
    orphan = Activity(user_id=None, device_id=watch.id, name="Imported early", sport="running")
    db.add(orphan)
    db.commit()

    resp = client.post(f"/devices/{watch.id}/claim", headers=_headers(bob))

    assert resp.status_code == 200
    db.refresh(orphan)
    assert orphan.user_id == bob.id


def test_claiming_your_own_device_again_is_harmless(client, db, household):
    alice, _, watch = household
    db.add(UserDevice(user_id=alice.id, device_id=watch.id))
    db.commit()

    resp = client.post(f"/devices/{watch.id}/claim", headers=_headers(alice))

    assert resp.status_code == 200
    assert resp.json()["claimed"] is True
