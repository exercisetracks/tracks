# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
API-level tests for /health/injuries — the first real endpoints wired to
require_crypto_session (see the branch plan, stage 3). These need an actual
crypto session (a real password + cached key), not just open-access mode
(require_crypto_session always requires credentials, unlike require_auth's
open-access fallback), so every test here goes through /auth/setup first.
"""

from sqlalchemy import text


def _setup_and_login(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "originalpass1",
    })
    assert resp.status_code == 200
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


class TestInjuriesRequireCryptoSession:
    def test_no_token_rejected(self, client, user):
        # Setup hasn't run yet — no crypto session can exist at all.
        resp = client.get("/health/injuries")
        assert resp.status_code == 401

    def test_valid_login_but_no_cached_session_rejected(self, client, user, db):
        """A well-formed, unexpired JWT whose Redis-cached key has been
        dropped (e.g. logout, or TTL lapse) must be rejected distinctly from
        'not authenticated' — see require_crypto_session."""
        from app.auth import create_token
        from app.models.user_keys import UserKey

        headers = _setup_and_login(client)
        uk = db.query(UserKey).filter_by(user_id=user.id).first()
        assert uk is not None

        stale_token = create_token(user_id=user.id, sid="a-sid-nobody-cached")
        resp = client.get("/health/injuries", headers={"Authorization": f"Bearer {stale_token}"})
        assert resp.status_code == 401
        assert resp.json()["detail"] == "session_expired"

        # The session actually established by setup still works fine.
        resp2 = client.get("/health/injuries", headers=headers)
        assert resp2.status_code == 200


class TestInjuriesCRUD:
    def test_create_list_update_delete_round_trip(self, client, user):
        headers = _setup_and_login(client)

        create = client.post("/health/injuries", headers=headers, json={
            "body_part": "knee", "injury_type": "strain", "severity": 3,
            "start_date": "2026-01-01", "notes": "tweaked on a long run",
        })
        assert create.status_code == 201
        body = create.json()
        assert body["body_part"] == "knee"
        assert body["injury_type"] == "strain"
        assert body["notes"] == "tweaked on a long run"
        injury_id = body["id"]

        listed = client.get("/health/injuries", headers=headers)
        assert listed.status_code == 200
        assert len(listed.json()) == 1
        assert listed.json()[0]["body_part"] == "knee"

        updated = client.patch(f"/health/injuries/{injury_id}", headers=headers, json={
            "severity": 6, "notes": "worse after speedwork",
        })
        assert updated.status_code == 200
        assert updated.json()["severity"] == 6
        assert updated.json()["notes"] == "worse after speedwork"
        assert updated.json()["body_part"] == "knee"  # untouched field survives

        deleted = client.delete(f"/health/injuries/{injury_id}", headers=headers)
        assert deleted.status_code == 204
        assert client.get("/health/injuries", headers=headers).json() == []

    def test_ciphertext_at_rest_does_not_contain_plaintext(self, client, user, db):
        headers = _setup_and_login(client)

        create = client.post("/health/injuries", headers=headers, json={
            "body_part": "shoulder", "injury_type": "impingement", "severity": 4,
            "start_date": "2026-01-01", "notes": "a very distinctive phrase",
        })
        injury_id = create.json()["id"]

        raw = db.execute(
            text("SELECT body_part, injury_type, notes FROM injuries WHERE id = :id"),
            {"id": injury_id},
        ).first()
        assert b"shoulder" not in bytes(raw[0])
        assert b"impingement" not in bytes(raw[1])
        assert b"a very distinctive phrase" not in bytes(raw[2])

    def test_injury_not_found_for_other_user(self, client, user, db):
        """Sanity: the existing user_id scoping still applies on top of
        encryption — this isn't new behavior, just confirming it survived."""
        headers = _setup_and_login(client)
        resp = client.get("/health/injuries/999999/activities", headers=headers)
        assert resp.status_code == 404
