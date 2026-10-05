# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Tests for sync agent pairing (/sync-agents) and the require_sync_agent
bearer-token dependency that replaces the old single GARMIN_SYNC_SECRET.
"""
import pytest


@pytest.fixture
def admin(user, db):
    user.is_admin = True
    db.commit()
    return user


def _auth_headers(client, username="admin", password="originalpass1"):
    resp = client.post("/auth/setup", json={
        "username": username, "name": "Admin", "password": password,
    })
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


class TestCreateSyncAgent:
    def test_personal_agent_any_user_can_create(self, client, user):
        headers = _auth_headers(client)
        resp = client.post("/sync-agents/", json={
            "kind": "mobile-app", "label": "My Phone", "household": False,
        }, headers=headers)
        assert resp.status_code == 201
        body = resp.json()
        assert body["household"] is False
        assert len(body["token"]) > 20

    def test_household_agent_requires_admin(self, client, db):
        # Admin bootstraps first (open-access mode ends once any password exists).
        admin_headers = _auth_headers(client, username="admin", password="adminpass123")

        # Admin creates a second, non-admin account (this is what actually
        # generates its UserKey row — a raw DB-inserted user wouldn't have
        # one, and login() requires it).
        client.post("/users/", json={
            "username": "regular", "name": "Regular", "password": "regularpass1",
        }, headers=admin_headers)

        login = client.post("/auth/login", json={"username": "regular", "password": "regularpass1"})
        regular_headers = {"Authorization": f"Bearer {login.json()['access_token']}"}

        resp = client.post("/sync-agents/", json={
            "kind": "garmin-usb", "label": "Kitchen dock", "household": True,
        }, headers=regular_headers)
        assert resp.status_code == 403

        resp2 = client.post("/sync-agents/", json={
            "kind": "garmin-usb", "label": "Kitchen dock", "household": True,
        }, headers=admin_headers)
        assert resp2.status_code == 201
        assert resp2.json()["household"] is True

    def test_invalid_kind_rejected(self, client, user):
        headers = _auth_headers(client)
        resp = client.post("/sync-agents/", json={
            "kind": "toaster", "label": "x", "household": False,
        }, headers=headers)
        assert resp.status_code == 422


class TestListAndRevoke:
    def test_list_excludes_token(self, client, user):
        headers = _auth_headers(client)
        client.post("/sync-agents/", json={"kind": "mobile-app", "label": "Phone"}, headers=headers)
        resp = client.get("/sync-agents/", headers=headers)
        assert resp.status_code == 200
        assert all("token" not in a for a in resp.json())

    def test_owner_can_revoke_own_agent(self, client, user):
        headers = _auth_headers(client)
        created = client.post("/sync-agents/", json={"kind": "mobile-app", "label": "Phone"}, headers=headers).json()
        resp = client.delete(f"/sync-agents/{created['id']}", headers=headers)
        assert resp.status_code == 204
        assert created["id"] not in [a["id"] for a in client.get("/sync-agents/", headers=headers).json()]

    def test_revoked_token_no_longer_authenticates(self, client, user, admin):
        headers = _auth_headers(client)
        created = client.post("/sync-agents/", json={"kind": "mobile-app", "label": "Phone"}, headers=headers).json()
        token = created["token"]

        # Sanity: the fresh token authenticates a sync-agent-guarded route.
        ok = client.get("/training-plan/sync/upload-list", headers={"Authorization": f"Bearer {token}"})
        assert ok.status_code == 200

        client.delete(f"/sync-agents/{created['id']}", headers=headers)

        revoked = client.get("/training-plan/sync/upload-list", headers={"Authorization": f"Bearer {token}"})
        assert revoked.status_code == 401


class TestRequireSyncAgent:
    def test_no_token_rejected(self, client):
        resp = client.get("/training-plan/sync/upload-list")
        assert resp.status_code == 401

    def test_garbage_token_rejected(self, client):
        resp = client.get("/training-plan/sync/upload-list", headers={"Authorization": "Bearer not-a-real-token"})
        assert resp.status_code == 401

    def test_personal_agent_resolves_to_its_own_user_no_serial_needed(self, client, user):
        headers = _auth_headers(client)
        created = client.post("/sync-agents/", json={"kind": "mobile-app", "label": "Phone"}, headers=headers).json()
        agent_headers = {"Authorization": f"Bearer {created['token']}"}

        resp = client.get("/training-plan/sync/upload-list", headers=agent_headers)
        assert resp.status_code == 200  # resolves without X-Garmin-Device-Serial

    def test_household_agent_without_serial_or_claim_is_unresolved(self, client, admin):
        headers = _auth_headers(client, username="admin", password="adminpass123")
        created = client.post("/sync-agents/", json={
            "kind": "garmin-usb", "label": "Dock", "household": True,
        }, headers=headers).json()
        agent_headers = {"Authorization": f"Bearer {created['token']}"}

        # No device-serial header, no claim to resolve against.
        resp = client.get("/training-plan/sync/upload-list", headers=agent_headers)
        assert resp.status_code == 400
