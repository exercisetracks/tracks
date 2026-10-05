# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Tests for the sync-agent ingest endpoints (/sync/pubkey, /sync/register-device,
/sync/ingest) — the encrypted replacement for the old filesystem watcher's
direct-to-shared-disk writes. Agents (garmin-sync, a future mobile app) seal
FIT bytes locally against the target user's public key before ever sending
them here; the server stores ciphertext it can't open itself.
"""

import base64
import hashlib

import pytest

from app.services import user_crypto


def _auth_headers(client, username="admin", password="originalpass1"):
    resp = client.post("/auth/setup", json={
        "username": username, "name": "Admin", "password": password,
    })
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


def _create_personal_agent(client, headers, label="Phone"):
    resp = client.post("/sync-agents/", json={"kind": "mobile-app", "label": label}, headers=headers)
    return resp.json()["token"]


def _create_household_agent(client, headers):
    resp = client.post("/sync-agents/", json={
        "kind": "garmin-usb", "label": "Dock", "household": True,
    }, headers=headers)
    return resp.json()["token"]


class TestPubkeyAndRegisterDevice:
    def test_personal_agent_gets_own_pubkey_no_device_needed(self, client, user):
        headers = _auth_headers(client)
        token = _create_personal_agent(client, headers)

        resp = client.get("/sync/pubkey", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200
        assert resp.json()["public_key"]

    @pytest.mark.real_transaction   # registers a device, which uses its own SAVEPOINT
    def test_household_agent_pubkey_requires_claimed_device(self, client, user):
        headers = _auth_headers(client)
        token = _create_household_agent(client, headers)
        agent_headers = {"Authorization": f"Bearer {token}"}

        # No serial at all.
        assert client.get("/sync/pubkey", headers=agent_headers).status_code == 404

        # Register the device (household agents leave it unclaimed).
        client.post("/sync/register-device", json={
            "serial_number": "GARMIN-123", "manufacturer": "Garmin", "manufacturer_id": 1,
        }, headers=agent_headers)
        assert client.get("/sync/pubkey", headers=agent_headers).status_code == 404  # still unclaimed

        # Claim it, then pubkey resolves.
        devices = client.get("/devices/", headers=headers).json()
        dev = next(d for d in devices if d["serial_number"] == "GARMIN-123")
        client.post(f"/devices/{dev['id']}/claim", headers=headers)

        resp = client.get("/sync/pubkey", headers={
            **agent_headers, "X-Garmin-Device-Serial": "GARMIN-123",
        })
        assert resp.status_code == 200
        assert resp.json()["user_id"] == user.id

    def test_personal_agent_registering_device_auto_claims_it(self, client, user):
        headers = _auth_headers(client)
        token = _create_personal_agent(client, headers)
        agent_headers = {"Authorization": f"Bearer {token}"}

        client.post("/sync/register-device", json={
            "serial_number": "GARMIN-456", "manufacturer": "Garmin", "manufacturer_id": 1,
        }, headers=agent_headers)

        devices = client.get("/devices/", headers=headers).json()
        dev = next(d for d in devices if d["serial_number"] == "GARMIN-456")
        assert dev["claimed"] is True


class TestIngest:
    def _seal_payload(self, client, token, plaintext: bytes) -> dict:
        pubkey_b64 = client.get(
            "/sync/pubkey", headers={"Authorization": f"Bearer {token}"}
        ).json()["public_key"]
        sealed = user_crypto.seal_for_user(base64.b64decode(pubkey_b64), plaintext)
        return {
            "filename": "20260101_activity.fit",
            "content_hash": hashlib.sha256(plaintext).hexdigest(),
            "sealed_b64": base64.b64encode(sealed).decode(),
        }

    def test_ingest_queues_a_pending_import(self, client, user, db):
        from app.models.imports import PendingImport
        from app.services import object_storage

        headers = _auth_headers(client)
        token = _create_personal_agent(client, headers)
        plaintext = b"fake raw fit bytes, definitely not real"
        body = self._seal_payload(client, token, plaintext)

        resp = client.post("/sync/ingest", json=body, headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200
        assert resp.json()["status"] == "queued"

        pending = db.query(PendingImport).filter_by(user_id=user.id).first()
        assert pending is not None
        assert pending.content_hash == body["content_hash"]
        assert pending.filename == body["filename"]

        # What's on disk is the sealed ciphertext, unreadable without the
        # user's private key — never the plaintext.
        stored = object_storage.load_blob(pending.blob_id)
        assert stored != plaintext
        assert plaintext not in stored

    def test_duplicate_content_hash_is_a_no_op(self, client, user):
        headers = _auth_headers(client)
        token = _create_personal_agent(client, headers)
        body = self._seal_payload(client, token, b"same bytes every time")
        agent_headers = {"Authorization": f"Bearer {token}"}

        first = client.post("/sync/ingest", json=body, headers=agent_headers)
        assert first.json()["status"] == "queued"

        second = client.post("/sync/ingest", json=body, headers=agent_headers)
        assert second.json()["status"] == "duplicate"

    def test_ingest_for_unclaimed_device_is_rejected(self, client, user):
        headers = _auth_headers(client)
        token = _create_household_agent(client, headers)
        resp = client.post("/sync/ingest", json={
            "device_serial": "NEVER-CLAIMED",
            "filename": "x.fit",
            "content_hash": "abc123",
            "sealed_b64": base64.b64encode(b"whatever").decode(),
        }, headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 404
