# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Refresh tokens — rotation, reuse detection, and session revocation.

The security-relevant behaviour is what happens on the unhappy paths: a
replayed token, a revoked device, an expired chain. Those are the cases worth
pinning down, because the happy path is indistinguishable from a broken
implementation that never invalidates anything.
"""
from datetime import datetime, timedelta, timezone

import pytest

from app.models import refresh_tokens
from app.models.refresh_tokens import RefreshToken


def _setup(client, password="testpass123"):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": password,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text


def _login(client, *, refresh=True, label="Pixel 9", password="testpass123"):
    resp = client.post("/auth/login", json={
        "username": "admin", "password": password,
        "issue_refresh_token": refresh, "device_label": label,
    })
    assert resp.status_code == 200, resp.text
    return resp.json()


class TestIssuance:
    def test_not_issued_unless_asked_for(self, client, user, db):
        """The web app has nowhere safe to keep a 90-day credential, so the
        default must stay off."""
        _setup(client)
        assert _login(client, refresh=False)["refresh_token"] is None

    def test_issued_on_request(self, client, user, db):
        _setup(client)
        body = _login(client)
        assert body["refresh_token"]
        assert body["access_token"]

    def test_stored_hashed_never_in_plaintext(self, client, user, db):
        """A database dump must not hand over usable credentials."""
        _setup(client)
        token = _login(client)["refresh_token"]
        rows = db.query(RefreshToken).all()
        assert len(rows) == 1
        assert rows[0].token_hash != token
        assert rows[0].token_hash == refresh_tokens.hash_token(token)

    def test_device_label_is_recorded(self, client, user, db):
        _setup(client)
        _login(client, label="Alex's Pixel")
        assert db.query(RefreshToken).first().device_label == "Alex's Pixel"


class TestRotation:
    def test_refresh_returns_new_tokens(self, client, user, db):
        _setup(client)
        first = _login(client)
        resp = client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["access_token"]
        assert body["refresh_token"] != first["refresh_token"]

    def test_new_access_token_actually_works(self, client, user, db):
        _setup(client)
        body = client.post("/auth/refresh", json={
            "refresh_token": _login(client)["refresh_token"]}).json()
        headers = {"Authorization": f"Bearer {body['access_token']}"}
        assert client.get("/users/me", headers=headers).status_code == 200

    def test_refresh_preserves_the_crypto_session(self, client, user, db):
        """A fresh sid would orphan the cached DEK and drop the user into
        vault_locked on every refresh — a password prompt treadmill."""
        from app.auth import decode_token

        _setup(client)
        first = _login(client)
        original_sid = decode_token(first["access_token"])["sid"]

        refreshed = client.post("/auth/refresh", json={
            "refresh_token": first["refresh_token"]}).json()
        assert decode_token(refreshed["access_token"])["sid"] == original_sid

    def test_old_token_stops_working(self, client, user, db):
        _setup(client)
        first = _login(client)
        client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
        again = client.post("/auth/refresh", json={"refresh_token": first["refresh_token"]})
        assert again.status_code == 401


class TestReuseDetection:
    def test_replay_revokes_the_whole_family(self, client, user, db):
        """Presenting a consumed token means either a duplicated request or a
        stolen one, and there's no way to tell from here. Assume theft."""
        _setup(client)
        first = _login(client)
        second = client.post("/auth/refresh", json={
            "refresh_token": first["refresh_token"]}).json()

        # Replay the consumed token.
        assert client.post("/auth/refresh", json={
            "refresh_token": first["refresh_token"]}).status_code == 401

        # The still-current token from the same chain must now be dead too.
        assert client.post("/auth/refresh", json={
            "refresh_token": second["refresh_token"]}).status_code == 401

    def test_unrelated_family_survives_a_revocation(self, client, user, db):
        """Signing out a compromised phone must not sign out the tablet."""
        _setup(client)
        phone = _login(client, label="phone")
        tablet = _login(client, label="tablet")

        client.post("/auth/refresh", json={"refresh_token": phone["refresh_token"]})
        client.post("/auth/refresh", json={"refresh_token": phone["refresh_token"]})

        assert client.post("/auth/refresh", json={
            "refresh_token": tablet["refresh_token"]}).status_code == 200

    def test_garbage_token_is_rejected(self, client, user, db):
        _setup(client)
        assert client.post("/auth/refresh", json={
            "refresh_token": "nonsense"}).status_code == 401


class TestExpiry:
    def test_expired_token_is_refused(self, client, user, db):
        _setup(client)
        token = _login(client)["refresh_token"]
        row = db.query(RefreshToken).first()
        row.expires_at = datetime.now(timezone.utc) - timedelta(days=1)
        db.commit()
        assert client.post("/auth/refresh", json={"refresh_token": token}).status_code == 401

    def test_revoked_token_is_refused(self, client, user, db):
        _setup(client)
        token = _login(client)["refresh_token"]
        row = db.query(RefreshToken).first()
        row.revoked_at = datetime.now(timezone.utc)
        db.commit()
        assert client.post("/auth/refresh", json={"refresh_token": token}).status_code == 401


class TestSessionManagement:
    def test_lists_one_entry_per_device(self, client, user, db):
        _setup(client)
        phone = _login(client, label="phone")
        _login(client, label="tablet")
        # Rotating the phone must not make it appear twice.
        client.post("/auth/refresh", json={"refresh_token": phone["refresh_token"]})

        headers = {"Authorization": f"Bearer {_login(client, refresh=False)['access_token']}"}
        sessions = client.get("/auth/sessions", headers=headers).json()
        assert sorted(s["device_label"] for s in sessions) == ["phone", "tablet"]

    def test_revoking_a_session_kills_its_chain(self, client, user, db):
        """Revoking only the leaf would let the device mint a replacement on
        its next refresh."""
        _setup(client)
        phone = _login(client, label="phone")
        rotated = client.post("/auth/refresh", json={
            "refresh_token": phone["refresh_token"]}).json()

        headers = {"Authorization": f"Bearer {_login(client, refresh=False)['access_token']}"}
        sessions = client.get("/auth/sessions", headers=headers).json()
        phone_id = next(s["id"] for s in sessions if s["device_label"] == "phone")

        assert client.delete(f"/auth/sessions/{phone_id}", headers=headers).status_code == 204
        assert client.post("/auth/refresh", json={
            "refresh_token": rotated["refresh_token"]}).status_code == 401

    def test_revoking_a_session_locks_the_vault_behind_it(self, client, user, db):
        """The point of signing a lost phone out is that it stops reading your
        data — not that it stops renewing its token.

        The access token it already holds is stateless and outlives the
        revocation, so if the crypto session survived too, that phone would
        keep decrypting health data for the rest of the session TTL. Dropping
        the cached key is what makes the button mean what it says.
        """
        from app.auth import decode_token
        from app.services import crypto_context

        _setup(client)
        phone = _login(client, label="phone")
        phone_sid = decode_token(phone["access_token"])["sid"]
        assert crypto_context.load_session_key(phone_sid) is not None

        headers = {"Authorization": f"Bearer {_login(client, refresh=False)['access_token']}"}
        sessions = client.get("/auth/sessions", headers=headers).json()
        phone_id = next(s["id"] for s in sessions if s["device_label"] == "phone")
        assert client.delete(f"/auth/sessions/{phone_id}", headers=headers).status_code == 204

        assert crypto_context.load_session_key(phone_sid) is None

    def test_revoking_one_session_leaves_the_others_decrypting(self, client, user, db):
        """Each login mints its own sid, so signing the phone out must not lock
        the vault on the laptop the user is doing it from."""
        from app.auth import decode_token
        from app.services import crypto_context

        _setup(client)
        phone = _login(client, label="phone")
        tablet = _login(client, label="tablet")
        tablet_sid = decode_token(tablet["access_token"])["sid"]

        headers = {"Authorization": f"Bearer {_login(client, refresh=False)['access_token']}"}
        sessions = client.get("/auth/sessions", headers=headers).json()
        phone_id = next(s["id"] for s in sessions if s["device_label"] == "phone")
        client.delete(f"/auth/sessions/{phone_id}", headers=headers)

        assert crypto_context.load_session_key(tablet_sid) is not None

    def test_cannot_revoke_another_users_session(self, client, user, db):
        from app.models.activity import User as UserModel

        _setup(client)
        other = UserModel(name="Other")
        db.add(other)
        db.flush()
        _token, row = refresh_tokens.issue(db, other.id, "some-sid", device_label="theirs")
        db.commit()

        headers = {"Authorization": f"Bearer {_login(client, refresh=False)['access_token']}"}
        assert client.delete(f"/auth/sessions/{row.id}", headers=headers).status_code == 404
