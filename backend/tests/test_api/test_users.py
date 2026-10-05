# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import pytest


@pytest.fixture
def admin(user, db):
    """Promote the default open-access user to admin, in-place."""
    user.is_admin = True
    db.commit()
    return user


class TestCreateUser:
    def test_returns_one_time_recovery_key_and_persists_user_keys(self, client, admin, db):
        from app.models.user_keys import UserKey
        from app.services import user_crypto

        resp = client.post("/users/", json={
            "username": "newkid", "name": "New Kid", "password": "newpassword1",
        })
        assert resp.status_code == 201
        data = resp.json()
        recovery_key = data["recovery_key"]
        assert len(recovery_key.split()) == 12  # 12-word BIP39 phrase

        uk = db.query(UserKey).filter_by(user_id=data["id"]).first()
        assert uk is not None
        material = user_crypto.unwrap_with_password("newpassword1", uk)
        material_via_recovery = user_crypto.unwrap_with_recovery_key(
            user_crypto.parse_recovery_key(recovery_key), uk
        )
        assert material.dek == material_via_recovery.dek

    def test_list_users_does_not_leak_recovery_key(self, client, admin):
        client.post("/users/", json={
            "username": "newkid", "name": "New Kid", "password": "newpassword1",
        })
        resp = client.get("/users/")
        assert resp.status_code == 200
        assert all("recovery_key" not in u for u in resp.json())


class TestChangePassword:
    def test_rewraps_dek_under_new_password(self, client, admin, db):
        from app.models.user_keys import UserKey
        from app.services import user_crypto

        setup_resp = client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "originalpass1",
        })
        token = setup_resp.json()["access_token"]
        recovery_key = setup_resp.json()["recovery_key"]
        auth_headers = {"Authorization": f"Bearer {token}"}

        uk_before = db.query(UserKey).filter_by(user_id=admin.id).first()
        dek_before = user_crypto.unwrap_with_password("originalpass1", uk_before).dek

        resp = client.post("/users/me/password", json={
            "current_password": "originalpass1", "new_password": "brandnewpass1",
        }, headers=auth_headers)
        assert resp.status_code == 200

        db.refresh(uk_before)
        # Old password no longer unwraps anything.
        with pytest.raises(user_crypto.WrongSecret):
            user_crypto.unwrap_with_password("originalpass1", uk_before)

        # New password recovers the *same* DEK — data isn't re-encrypted,
        # just re-keyed.
        dek_after = user_crypto.unwrap_with_password("brandnewpass1", uk_before).dek
        assert dek_after == dek_before

        # Recovery key (untouched by a password change) still works too.
        dek_via_recovery = user_crypto.unwrap_with_recovery_key(
            user_crypto.parse_recovery_key(recovery_key), uk_before
        ).dek
        assert dek_via_recovery == dek_before

    def test_a_password_change_ends_every_other_session(self, client, admin):
        """Changing the password is what someone does when they think it has
        leaked. A session the intruder already opened held the decryption key
        in Redis, and the key does not change with the password — so it went
        on reading health data until it fell idle. It must end here; the
        session making the change, which just proved the password, stays."""
        from app.auth import decode_token
        from app.services import crypto_context

        mine = client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "originalpass1",
        }).json()["access_token"]
        theirs = client.post("/auth/login", json={
            "username": "admin", "password": "originalpass1",
        }).json()["access_token"]
        my_sid, their_sid = decode_token(mine)["sid"], decode_token(theirs)["sid"]
        assert crypto_context.load_session_key(their_sid) is not None

        resp = client.post("/users/me/password", json={
            "current_password": "originalpass1", "new_password": "brandnewpass1",
        }, headers={"Authorization": f"Bearer {mine}"})

        assert resp.status_code == 200
        assert crypto_context.load_session_key(their_sid) is None
        assert crypto_context.load_session_key(my_sid) is not None

    def test_wrong_current_password_rejected(self, client, admin):
        setup_resp = client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "originalpass1",
        })
        auth_headers = {"Authorization": f"Bearer {setup_resp.json()['access_token']}"}
        resp = client.post("/users/me/password", json={
            "current_password": "wrongpass1", "new_password": "brandnewpass1",
        }, headers=auth_headers)
        assert resp.status_code == 401


class TestGetMe:
    def test_returns_user(self, client, user):
        resp = client.get("/users/me")
        assert resp.status_code == 200
        assert resp.json()["name"] == "Test User"

    def test_returns_503_when_no_user(self, client):
        resp = client.get("/users/me")
        assert resp.status_code == 503


class TestGetSettings:
    def test_returns_default_settings(self, client, user):
        resp = client.get("/users/me/settings")
        assert resp.status_code == 200
        data = resp.json()
        assert data["units"] == "metric"
        assert data["timezone"] == "UTC"

    def test_ai_configured_false_when_no_key(self, client, user):
        data = client.get("/users/me/settings").json()
        assert data["ai_configured"] is False


class TestUpdateSettings:
    def test_valid_units_update(self, client, user):
        resp = client.patch("/users/me/settings", json={"units": "imperial"})
        assert resp.status_code == 200
        assert resp.json()["units"] == "imperial"

    def test_invalid_units_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"units": "furlongs"})
        assert resp.status_code == 422

    def test_invalid_ai_provider_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"ai_provider": "grok"})
        assert resp.status_code == 422

    def test_valid_ai_provider_accepted(self, client, user):
        resp = client.patch("/users/me/settings", json={"ai_provider": "ollama"})
        assert resp.status_code == 200

    def test_invalid_timezone_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"timezone": "Mars/Olympus"})
        assert resp.status_code == 422

    def test_valid_timezone_accepted(self, client, user):
        resp = client.patch("/users/me/settings", json={"timezone": "America/New_York"})
        assert resp.status_code == 200
        assert resp.json()["timezone"] == "America/New_York"

    def test_invalid_avatar_url_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"avatar_url": "javascript:alert(1)"})
        assert resp.status_code == 422

    def test_valid_avatar_url_accepted(self, client, user):
        resp = client.patch("/users/me/settings",
                            json={"avatar_url": "https://example.com/avatar.jpg"})
        assert resp.status_code == 200

    def test_invalid_equipment_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"equipment_available": ["bodyweight", "trampoline"]})
        assert resp.status_code == 422

    def test_valid_equipment_accepted(self, client, user):
        resp = client.patch("/users/me/settings", json={"equipment_available": ["bodyweight", "kettlebell"]})
        assert resp.status_code == 200
        assert resp.json()["equipment_available"] == ["bodyweight", "kettlebell"]

    def test_weight_kg_upper_bound_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"weight_kg": 9999.0})
        assert resp.status_code == 422

    def test_weight_kg_negative_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"weight_kg": -1.0})
        assert resp.status_code == 422

    def test_weight_kg_valid_accepted(self, client, user):
        resp = client.patch("/users/me/settings", json={"weight_kg": 75.5})
        assert resp.status_code == 200
        assert resp.json()["weight_kg"] == 75.5

    def test_threshold_hr_out_of_bounds_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"threshold_hr_manual": 10})
        assert resp.status_code == 422

    def test_ftp_zero_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"ftp_manual": 0})
        assert resp.status_code == 422

    def test_invalid_max_hr_mode_rejected(self, client, user):
        resp = client.patch("/users/me/settings", json={"max_hr_mode": "guess"})
        assert resp.status_code == 422

    def test_api_key_stored_encrypted_not_returned(self, client, user):
        resp = client.patch("/users/me/settings",
                            json={"ai_provider": "anthropic", "ai_api_key": "sk-test-key"})
        assert resp.status_code == 200
        data = resp.json()
        # Plaintext key must never appear in the response
        assert "sk-test-key" not in str(data)
        # But ai_configured should now be True
        assert data["ai_configured"] is True

    def test_hidden_sports_update(self, client, user):
        resp = client.patch("/users/me/settings", json={"hidden_sports": ["swimming"]})
        assert resp.status_code == 200
        assert resp.json()["hidden_sports"] == ["swimming"]
