# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Auth endpoint tests.

Open-access mode: when a User exists but UserSettings.password_hash is None,
all authenticated routes accept requests without a token.
"""

import pytest

import app.api.auth as auth_module
from app.models.activity import User
from app.models.user_settings import UserSettings


@pytest.fixture(autouse=True)
def clear_brute_force():
    """Reset the failed-attempts store between tests."""
    auth_module._reset_all()
    yield
    auth_module._reset_all()


# ── /auth/setup ──────────────────────────────────────────────────────────────

class TestSetup:
    _SETUP_BODY = {"username": "admin", "name": "Admin User", "password": "securepass1"}

    def test_setup_creates_password_and_returns_token(self, client, user):
        resp = client.post("/auth/setup", json=self._SETUP_BODY)
        assert resp.status_code == 200
        data = resp.json()
        assert data["token_type"] == "bearer"
        assert len(data["access_token"]) > 20

    def test_setup_returns_one_time_recovery_key_and_persists_user_keys(self, client, user, db):
        from app.models.user_keys import UserKey
        from app.services import user_crypto

        resp = client.post("/auth/setup", json=self._SETUP_BODY)
        assert resp.status_code == 200
        recovery_key = resp.json()["recovery_key"]
        assert len(recovery_key.split()) == 12  # 12-word BIP39 phrase

        uk = db.query(UserKey).filter_by(user_id=user.id).first()
        assert uk is not None

        # The returned recovery key actually unwraps this user's keys.
        material_via_password = user_crypto.unwrap_with_password(self._SETUP_BODY["password"], uk)
        material_via_recovery = user_crypto.unwrap_with_recovery_key(
            user_crypto.parse_recovery_key(recovery_key), uk
        )
        assert material_via_password.dek == material_via_recovery.dek

    def test_setup_rejected_when_password_already_set(self, client, user):
        client.post("/auth/setup", json=self._SETUP_BODY)
        resp = client.post("/auth/setup", json={**self._SETUP_BODY, "password": "secondpass1"})
        # 400 "Admin account already configured", not 409 "Username already
        # taken". This used to assert 409 because the fixture harness could not
        # see the admin row the first call committed, so /setup fell past its
        # own guard and only tripped the username-uniqueness check. Right
        # status, wrong reason.
        assert resp.status_code == 400
        assert "already configured" in resp.json()["detail"]

    def test_setup_rejects_short_password(self, client, user):
        resp = client.post("/auth/setup", json={**self._SETUP_BODY, "password": "short"})
        assert resp.status_code == 422

    def test_setup_requires_user_to_exist(self, client):
        # No user fixture → no user in DB.  The /setup endpoint may still
        # succeed (200) because the lifespan creates a default user.
        resp = client.post("/auth/setup", json=self._SETUP_BODY)
        assert resp.status_code == 200


class TestSetupHostGarminSync:
    """/auth/setup pre-authorizes the host's garmin-sync container (no
    manual pairing) — see app.services.sync_agent_auth.provision_host_garmin_agent
    and app.config.Settings.garmin_sync_bootstrap_token."""

    _SETUP_BODY = {"username": "admin", "name": "Admin User", "password": "securepass1"}
    _BOOTSTRAP_TOKEN = "test-bootstrap-token-abc123"

    @pytest.fixture(autouse=True)
    def bootstrap_token_configured(self, monkeypatch):
        from app.config import settings
        monkeypatch.setattr(settings, "garmin_sync_bootstrap_token", self._BOOTSTRAP_TOKEN)

    def test_enabled_by_default_provisions_host_agent(self, client, user, db):
        from app.models.sync_agents import SyncAgent
        from app.services.sync_agent_auth import _hash_token

        resp = client.post("/auth/setup", json=self._SETUP_BODY)
        assert resp.status_code == 200

        agent = db.query(SyncAgent).filter_by(token_hash=_hash_token(self._BOOTSTRAP_TOKEN)).first()
        assert agent is not None
        assert agent.user_id == user.id
        assert agent.kind == "garmin-usb"
        assert agent.revoked_at is None

    def test_provisioned_token_actually_authenticates(self, client, user):
        resp = client.post("/auth/setup", json=self._SETUP_BODY)
        assert resp.status_code == 200

        egest = client.get("/training-plan/sync/upload-list", headers={
            "Authorization": f"Bearer {self._BOOTSTRAP_TOKEN}",
        })
        assert egest.status_code == 200

    def test_toggle_off_does_not_provision(self, client, user, db):
        from app.models.sync_agents import SyncAgent
        from app.services.sync_agent_auth import _hash_token

        resp = client.post("/auth/setup", json={**self._SETUP_BODY, "enable_garmin_sync": False})
        assert resp.status_code == 200

        agent = db.query(SyncAgent).filter_by(token_hash=_hash_token(self._BOOTSTRAP_TOKEN)).first()
        assert agent is None

    def test_no_bootstrap_token_configured_is_a_silent_no_op(self, client, user, db, monkeypatch):
        from app.config import settings
        from app.models.sync_agents import SyncAgent

        monkeypatch.setattr(settings, "garmin_sync_bootstrap_token", "")

        resp = client.post("/auth/setup", json=self._SETUP_BODY)
        assert resp.status_code == 200
        assert db.query(SyncAgent).count() == 0


# ── /auth/login ──────────────────────────────────────────────────────────────

class TestLogin:
    _LOGIN_USERNAME = "admin"

    def _setup_password(self, client, password="mypassword"):
        client.post("/auth/setup", json={
            "username": self._LOGIN_USERNAME, "name": "Admin", "password": password,
        })

    def test_login_with_correct_password_returns_token(self, client, user):
        self._setup_password(client)
        resp = client.post("/auth/login", json={
            "username": self._LOGIN_USERNAME, "password": "mypassword",
        })
        assert resp.status_code == 200
        assert "access_token" in resp.json()

    def test_login_with_wrong_password_returns_401(self, client, user):
        self._setup_password(client)
        resp = client.post("/auth/login", json={
            "username": self._LOGIN_USERNAME, "password": "wrongpassword",
        })
        assert resp.status_code == 401

    def test_login_before_setup_returns_400(self, client, user):
        resp = client.post("/auth/login", json={
            "username": self._LOGIN_USERNAME, "password": "anything",
        })
        assert resp.status_code == 401

    def test_brute_force_lockout_after_five_failures(self, client, user):
        self._setup_password(client)
        for _ in range(5):
            client.post("/auth/login", json={
                "username": self._LOGIN_USERNAME, "password": "wrong",
            })
        resp = client.post("/auth/login", json={
            "username": self._LOGIN_USERNAME, "password": "wrong",
        })
        assert resp.status_code == 429

    def test_successful_login_clears_failure_count(self, client, user):
        self._setup_password(client)
        for _ in range(4):
            client.post("/auth/login", json={
                "username": self._LOGIN_USERNAME, "password": "wrong",
            })
        client.post("/auth/login", json={
            "username": self._LOGIN_USERNAME, "password": "mypassword",
        })
        # Now wrong login should not immediately lock (counter was reset)
        resp = client.post("/auth/login", json={
            "username": self._LOGIN_USERNAME, "password": "wrong",
        })
        assert resp.status_code == 401  # not 429


# ── Login triggers pending-import processing ────────────────────────────────

class TestLoginProcessesPendingImports:
    """A sync agent may have ingested sealed FIT files while this user had
    no active session — login is the first moment a key capable of opening
    them exists (see app.services.fit_import). The real endpoint queues this
    onto the Celery worker (app.tasks.imports); conftest.py configures
    task_always_eager so it runs synchronously here instead of needing a
    real broker/worker process for the test to observe the result."""

    def test_pending_import_processed_on_login(self, client, user, db):
        import hashlib
        from unittest.mock import patch

        from app.models.imports import PendingImport
        from app.models.user_keys import UserKey
        from app.services import fit_import, object_storage, user_crypto

        setup_resp = client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "originalpass1",
        })
        assert setup_resp.status_code == 200

        uk = db.query(UserKey).filter_by(user_id=user.id).first()
        plaintext = b"pretend fit bytes"
        sealed = user_crypto.seal_for_user(uk.public_key, plaintext)
        blob_id = object_storage.store_blob(sealed)
        pending = PendingImport(
            user_id=user.id, blob_id=blob_id,
            content_hash=hashlib.sha256(plaintext).hexdigest(), filename="watch.fit",
        )
        db.add(pending)
        db.commit()

        parsed = {
            "type": "activity",
            "device": {"serial_number": "999", "manufacturer": "garmin", "manufacturer_id": 1},
            "activity": {"sport": "cycling", "duration_seconds": 600},
            "data_points": [], "laps": [], "strength_sets": [],
            "climb_splits": [], "power_curve": {}, "pace_curve": {},
        }
        with patch.object(fit_import, "_parse_bytes", return_value=parsed):
            login_resp = client.post("/auth/login", json={
                "username": "admin", "password": "originalpass1",
            })
        assert login_resp.status_code == 200

        db.expire_all()
        assert db.query(PendingImport).filter_by(id=pending.id).first().processed_at is not None


# ── Re-triggering pending-import processing without a fresh login ──────────

class TestSyncPendingImports:
    """A sync agent's ingest isn't tied to any browser session — a user who
    stays logged in while their watch syncs would otherwise see nothing new
    until they log out and back in (processing normally triggers on login).
    POST /auth/sync-pending-imports lets the frontend re-trigger it while
    still authenticated (see AuthContext.jsx's poll)."""

    def test_queues_processing_for_the_current_user(self, client, user, db):
        import hashlib
        from unittest.mock import patch

        from app.models.imports import PendingImport
        from app.models.user_keys import UserKey
        from app.services import fit_import, object_storage, user_crypto

        setup_resp = client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "originalpass1",
        })
        assert setup_resp.status_code == 200
        token = setup_resp.json()["access_token"]

        uk = db.query(UserKey).filter_by(user_id=user.id).first()
        plaintext = b"pretend fit bytes"
        sealed = user_crypto.seal_for_user(uk.public_key, plaintext)
        blob_id = object_storage.store_blob(sealed)
        pending = PendingImport(
            user_id=user.id, blob_id=blob_id,
            content_hash=hashlib.sha256(plaintext).hexdigest(), filename="watch.fit",
        )
        db.add(pending)
        db.commit()

        parsed = {
            "type": "activity",
            "device": {"serial_number": "999", "manufacturer": "garmin", "manufacturer_id": 1},
            "activity": {"sport": "cycling", "duration_seconds": 600},
            "data_points": [], "laps": [], "strength_sets": [],
            "climb_splits": [], "power_curve": {}, "pace_curve": {},
        }
        with patch.object(fit_import, "_parse_bytes", return_value=parsed):
            resp = client.post("/auth/sync-pending-imports", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 202

        db.expire_all()
        assert db.query(PendingImport).filter_by(id=pending.id).first().processed_at is not None

    def test_requires_a_token(self, client, user):
        resp = client.post("/auth/sync-pending-imports")
        assert resp.status_code == 401

    def test_rejects_an_invalid_token(self, client, user):
        resp = client.post("/auth/sync-pending-imports", headers={"Authorization": "Bearer garbage"})
        assert resp.status_code == 401


# ── Token authentication ──────────────────────────────────────────────────────

class TestTokenAuth:
    _USER = {"username": "admin", "name": "Admin", "password": "mypassword"}

    def test_protected_route_requires_token_after_password_set(self, client, user):
        # The name always said what should happen; the assertion said 200.
        # Open-access mode is supposed to end the moment an admin has a
        # password, but the harness could not see that admin, so require_auth
        # stayed in its open branch and served every guarded route unauthenticated.
        client.post("/auth/setup", json=self._USER)
        resp = client.get("/users/me")
        assert resp.status_code == 401

    def test_protected_route_is_open_before_setup(self, client, user):
        # The other half of the contract: a fresh instance with no admin
        # password is deliberately reachable, so first-run setup can happen.
        assert client.get("/users/me").status_code == 200

    def test_protected_route_works_with_valid_token(self, client, user):
        client.post("/auth/setup", json=self._USER)
        token = client.post("/auth/login", json={
            "username": "admin", "password": "mypassword",
        }).json()["access_token"]
        resp = client.get("/users/me", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200

    def test_open_access_mode_allows_unauthenticated_requests(self, client, user):
        # Password not set → open access
        resp = client.get("/users/me")
        assert resp.status_code == 200
