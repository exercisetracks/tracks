# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Who a watch belongs to, by how it reached the server.

A claim decides whose account a watch's health data is filed under. The rule:

- A watch that appears through someone's phone is theirs at once — it is
  paired to that phone, and claiming it as it appears leaves nobody a window
  to claim it first.
- A watch only ever seen by the server's USB dock is claimed first-come from
  the Devices page, unless the instance has a single account.
- Nobody gets a second claim on a watch someone else holds, by any path.

The dock used to act as the admin's personal agent, filing every docked
watch's files into the admin's account whoever had claimed it.
"""
import secrets

import pytest

from app.models.activity import Device, UserDevice
from app.models.sync_agents import SyncAgent
from app.services import sync_agent_auth
from app.services.device_resolution import get_or_create_device
from app.sync.registry import ADAPTERS
from tests.test_sync.helpers import Phone, push, second_account, setup_admin

SERIAL = "3990002222"


def _bearer(token):
    return {"Authorization": f"Bearer {token}"}


def _me(client, headers):
    return client.get("/users/me", headers=headers).json()["id"]


def _phone_agent(client, headers):
    resp = client.post("/sync-agents/", json={"kind": "mobile-app", "label": "Phone"},
                       headers=headers)
    assert resp.status_code in (200, 201), resp.text
    return resp.json()["token"]


def _host_dock(db, admin_id):
    """The server's own USB bridge, provisioned for the admin at setup."""
    token = secrets.token_urlsafe(32)
    db.add(SyncAgent(user_id=admin_id, created_by_user_id=admin_id, kind="garmin-usb",
                     label="Host USB sync", token_hash=sync_agent_auth._hash_token(token)))
    db.commit()
    return token


def _register(client, token):
    resp = client.post("/sync/register-device", headers=_bearer(token), json={
        "serial_number": SERIAL, "manufacturer": "garmin", "manufacturer_id": 1})
    assert resp.status_code == 200, resp.text


def _holders(db):
    device = db.query(Device).filter_by(serial_number=SERIAL).one()
    return {uid for (uid,) in db.query(UserDevice.user_id).filter_by(device_id=device.id)}


def _pubkey_owner(client, token):
    resp = client.get("/sync/pubkey", headers={**_bearer(token), "X-Garmin-Device-Serial": SERIAL})
    return resp.json()["user_id"] if resp.status_code == 200 else None


@pytest.mark.real_transaction   # registering a device takes its own SAVEPOINT
class TestThroughAPhone:
    def test_a_watch_appearing_through_a_phone_is_claimed_for_its_owner(self, client, db, user):
        admin = setup_admin(client)
        partner = second_account(client, admin)

        _register(client, _phone_agent(client, partner))

        assert _holders(db) == {_me(client, partner)}

    def test_a_phone_cannot_add_a_claim_to_a_watch_someone_else_holds(self, client, db, user):
        admin = setup_admin(client)
        partner = second_account(client, admin)
        _register(client, _phone_agent(client, admin))

        _register(client, _phone_agent(client, partner))

        assert _holders(db) == {_me(client, admin)}

    def test_a_phone_pushing_a_claim_on_a_held_watch_is_refused_for_good(self, client, db, user):
        """Refused as `invalid`, which the phone drops from its outbox, rather
        than a second claim that would make the watch unresolvable."""
        admin = setup_admin(client)
        partner = second_account(client, admin)
        _register(client, _phone_agent(client, admin))

        change = {"entity": "device", "fields": {"serial_number": [SERIAL, Phone("b" * 16).stamp()]}}
        change["uid"] = ADAPTERS["device"].natural_uid_from_fields({"serial_number": SERIAL})

        result = push(client, partner, change)["results"][0]

        assert result["status"] == "rejected"
        assert result["detail"] == "this watch is claimed by another account"
        assert _holders(db) == {_me(client, admin)}


@pytest.mark.real_transaction
class TestThroughTheServersDock:
    def test_a_docked_watch_is_left_for_the_first_to_claim_it(self, client, db, user):
        admin = setup_admin(client)
        partner = second_account(client, admin)
        dock = _host_dock(db, _me(client, admin))

        _register(client, dock)

        assert _holders(db) == set()
        assert _pubkey_owner(client, dock) is None, "files wait on the watch until it is claimed"

        device_id = db.query(Device.id).filter_by(serial_number=SERIAL).scalar()
        assert client.post(f"/devices/{device_id}/claim", headers=partner).status_code == 200
        assert _pubkey_owner(client, dock) == _me(client, partner)

    def test_a_docked_watch_goes_to_its_claimant_not_the_admin(self, client, db, user):
        """The leak this fixes: the dock's files went to the admin, whoever
        had claimed the watch."""
        admin = setup_admin(client)
        partner = second_account(client, admin)
        _register(client, _phone_agent(client, partner))
        dock = _host_dock(db, _me(client, admin))

        assert _pubkey_owner(client, dock) == _me(client, partner)

    def test_with_one_account_the_dock_claims_for_it(self, client, db, user):
        admin = setup_admin(client)
        dock = _host_dock(db, _me(client, admin))

        _register(client, dock)

        assert _holders(db) == {_me(client, admin)}
        assert _pubkey_owner(client, dock) == _me(client, admin)

    def test_a_dock_that_cannot_name_the_watch_is_refused_with_several_accounts(self, client, db, user):
        admin = setup_admin(client)
        second_account(client, admin)
        dock = _host_dock(db, _me(client, admin))

        assert client.get("/sync/pubkey", headers=_bearer(dock)).status_code == 404


@pytest.mark.real_transaction
def test_importing_a_file_from_a_held_watch_adds_no_second_claim(client, db, user):
    """An upload, or a phone's personal agent, files the data under its own
    account as before — but the watch stays with whoever claimed it."""
    admin = setup_admin(client)
    partner = second_account(client, admin)
    _register(client, _phone_agent(client, admin))

    get_or_create_device(db, {"serial_number": SERIAL, "manufacturer_id": 1}, _me(client, partner))
    db.commit()

    assert _holders(db) == {_me(client, admin)}
