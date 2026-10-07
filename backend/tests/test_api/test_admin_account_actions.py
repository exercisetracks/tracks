# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What an admin can do to other accounts: set their password, and the
instance-wide wipes behind the danger zone."""
import pytest

from app.models.activity import Activity, User
from app.models.user_keys import UserKey
from app.services import user_crypto


@pytest.fixture
def admin(user, db):
    user.is_admin = True
    db.commit()
    return user


def _make_user(client, username="kid"):
    r = client.post("/users/", json={"username": username, "name": username.title(), "password": "firstpass1"})
    assert r.status_code == 201, r.text
    return r.json()


def _run(db, uid):
    db.add(Activity(user_id=uid, device_id=1, sport="running", duration_seconds=60, distance_meters=100.0))
    db.commit()


class TestAdminPasswordReset:
    def test_with_the_recovery_key_the_users_data_survives(self, client, admin, db):
        kid = _make_user(client)
        dek = user_crypto.unwrap_with_password("firstpass1", db.query(UserKey).filter_by(user_id=kid["id"]).one()).dek
        r = client.post(f"/users/{kid['id']}/password",
                        json={"new_password": "secondpass2", "recovery_key": kid["recovery_key"]})
        assert r.status_code == 200, r.text
        assert r.json()["data_kept"] is True
        uk = db.query(UserKey).filter_by(user_id=kid["id"]).one()
        db.refresh(uk)
        assert user_crypto.unwrap_with_password("secondpass2", uk).dek == dek

    def test_without_it_the_admin_must_agree_to_lose_the_data(self, client, admin, db):
        """An admin cannot unwrap someone else's key, so a reset without the
        recovery key cannot keep what it encrypts. It must not happen by accident."""
        kid = _make_user(client)
        _run(db, kid["id"])
        assert client.post(f"/users/{kid['id']}/password", json={"new_password": "secondpass2"}).status_code == 409
        r = client.post(f"/users/{kid['id']}/password", json={"new_password": "secondpass2", "discard_data": True})
        assert r.status_code == 200
        assert r.json()["data_kept"] is False and r.json()["recovery_key"]
        assert db.query(Activity).filter_by(user_id=kid["id"]).count() == 0
        uk = db.query(UserKey).filter_by(user_id=kid["id"]).one()
        db.refresh(uk)
        user_crypto.unwrap_with_password("secondpass2", uk)  # does not raise

    def test_a_wrong_recovery_key_changes_nothing(self, client, admin, db):
        kid = _make_user(client)
        other = _make_user(client, "other")
        r = client.post(f"/users/{kid['id']}/password",
                        json={"new_password": "secondpass2", "recovery_key": other["recovery_key"]})
        assert r.status_code == 403


class TestWipes:
    def test_the_typed_phrase_is_checked_on_the_server_too(self, client, admin):
        assert client.post("/users/admin/wipe-data", json={"confirm": "yes"}).status_code == 400

    def test_wiping_everyones_data_keeps_the_accounts(self, client, admin, db):
        kid = _make_user(client)
        _run(db, kid["id"])
        _run(db, admin.id)
        r = client.post("/users/admin/wipe-data", json={"confirm": "delete everyone's data"})
        assert r.status_code == 200
        assert db.query(Activity).count() == 0
        assert db.get(User, kid["id"]) is not None

    def test_wiping_accounts_keeps_only_the_admin_doing_it(self, client, admin, db):
        _make_user(client)
        _make_user(client, "other")
        r = client.post("/users/admin/wipe-accounts", json={"confirm": "delete all other accounts"})
        assert r.json()["deleted_accounts"] == 2
        assert [u.id for u in db.query(User).all()] == [admin.id]

    def test_a_factory_reset_leaves_no_accounts(self, client, admin, db):
        _make_user(client)
        r = client.post("/users/admin/factory-reset", json={"confirm": "factory reset"})
        assert r.status_code == 200
        assert db.query(User).count() == 0

    def test_only_an_admin_can_wipe(self, client, user, db):
        assert client.post("/users/admin/wipe-data",
                           json={"confirm": "delete everyone's data"}).status_code == 403
