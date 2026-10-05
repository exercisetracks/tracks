# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Device keys — silent vault unlock for an enrolled device.

This is a third credential that decrypts the same data as the password, so the
tests that matter are the ones about what it must NOT do: work after
revocation, work after a password change, be readable from the database, or be
enrollable without already holding the keys.
"""
from datetime import datetime, timedelta, timezone

import pytest

from app.models import refresh_tokens
from app.models.activity import Activity, DataPoint
from app.models.device_keys import DeviceKey
from app.models.refresh_tokens import RefreshToken
from app.models.user_keys import UserKey
from app.services import crypto_context, user_crypto

PASSWORD = "testpass123"


def _setup(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": PASSWORD,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


def _enrol(client, headers, label="Pixel 9"):
    resp = client.post("/auth/device-keys", json={"label": label}, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


def _unlock(client, enrolled, **extra):
    return client.post("/auth/device-unlock", json={
        "device_key_id": enrolled["id"],
        "device_secret": enrolled["device_secret"],
        **extra,
    })


def _make_activity_with_track(db, user, material, *, lat0=40.0, lng0=-150.0):
    a = Activity(user_id=user.id, device_id=1, sport="running",
                 started_at=datetime(2024, 6, 1, 8, tzinfo=timezone.utc),
                 duration_seconds=3600, distance_meters=1000.0, is_merged=False)
    db.add(a)
    db.flush()
    for i in range(4):
        db.add(DataPoint(id=a.id * 100000 + i, activity_id=a.id,
                         recorded_at=datetime(2024, 6, 1, 8, 0, i, tzinfo=timezone.utc),
                         lat=lat0 + i * 0.001, lng=lng0 + i * 0.001,
                         altitude=1500.0, heart_rate=140, speed=3.0))
    token = crypto_context.set_current_key(material)
    try:
        db.commit()
    finally:
        crypto_context.reset_current_key(token)
    db.refresh(a)
    return a


class TestEnrolment:
    def test_returns_a_secret_exactly_once(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        assert enrolled["device_secret"]
        # Listing must never hand it back — it is unrecoverable by design.
        listed = client.get("/auth/device-keys", headers=headers).json()
        assert listed[0]["id"] == enrolled["id"]
        assert "device_secret" not in listed[0]

    def test_secret_is_not_stored_anywhere(self, client, user, db):
        """A database dump must not yield a working credential."""
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        raw = user_crypto.parse_device_secret(enrolled["device_secret"])

        row = db.query(DeviceKey).first()
        for blob in (row.salt, row.wrapped_dek, row.wrapped_privkey):
            assert raw not in blob
        assert enrolled["device_secret"] not in (row.label or "")

    def test_requires_a_live_crypto_session(self, client, user, db):
        """Enrolment re-wraps live key material, so it cannot happen from a
        token whose vault has lapsed — a device key must never grant access the
        enroling session did not already have."""
        headers = _setup(client)
        # Drop the cached key behind this token, keeping the JWT valid.
        client.post("/auth/logout", headers=headers)
        assert client.post("/auth/device-keys", json={"label": "x"},
                           headers=headers).status_code == 401

    def test_stores_the_label(self, client, user, db):
        headers = _setup(client)
        _enrol(client, headers, label="Alex's Pixel")
        assert db.query(DeviceKey).first().label == "Alex's Pixel"


class TestUnlock:
    def test_returns_a_working_access_token(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)

        resp = _unlock(client, enrolled)
        assert resp.status_code == 200, resp.text
        new_headers = {"Authorization": f"Bearer {resp.json()['access_token']}"}
        assert client.get("/users/me", headers=new_headers).status_code == 200

    def test_actually_unlocks_the_vault(self, client, user, db):
        """The whole point: encrypted data must be readable afterwards, with no
        password anywhere in the exchange."""
        headers = _setup(client)
        uk = db.query(UserKey).filter_by(user_id=user.id).first()
        material = user_crypto.unwrap_with_password(PASSWORD, uk)
        activity = _make_activity_with_track(db, user, material)

        enrolled = _enrol(client, headers)
        unlocked = {"Authorization":
                    f"Bearer {_unlock(client, enrolled).json()['access_token']}"}

        resp = client.get(f"/activities/{activity.id}/track", headers=unlocked)
        assert resp.status_code == 200, resp.text
        points = resp.json()
        assert len(points) == 4
        assert points[0]["lat"] == pytest.approx(40.0)

    def test_needs_no_password(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        # Nothing password-shaped is sent, and it still works.
        assert _unlock(client, enrolled).status_code == 200

    def test_wrong_secret_is_rejected(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        other = user_crypto.format_device_secret(b"\x00" * 32)
        resp = client.post("/auth/device-unlock", json={
            "device_key_id": enrolled["id"], "device_secret": other,
        })
        assert resp.status_code == 401

    def test_malformed_secret_is_rejected_not_crashed(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        for bad in ("", "!!!!", "short"):
            resp = client.post("/auth/device-unlock", json={
                "device_key_id": enrolled["id"], "device_secret": bad,
            })
            assert resp.status_code == 401, f"{bad!r} returned {resp.status_code}"

    def test_unknown_key_id_is_rejected(self, client, user, db):
        _setup(client)
        resp = client.post("/auth/device-unlock", json={
            "device_key_id": 9999, "device_secret": user_crypto.format_device_secret(b"\x01" * 32),
        })
        assert resp.status_code == 401

    def test_records_last_used(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        assert db.query(DeviceKey).first().last_used_at is None
        _unlock(client, enrolled)
        db.expire_all()
        assert db.query(DeviceKey).first().last_used_at is not None

    def test_issues_a_refresh_token_pinned_to_the_new_session(self, client, user, db):
        """Unlock mints a new sid, so any refresh token the client already held
        points at a dead session. The replacement must carry the new one."""
        from app.auth import decode_token

        headers = _setup(client)
        enrolled = _enrol(client, headers)
        body = _unlock(client, enrolled, issue_refresh_token=True).json()
        assert body["refresh_token"]

        sid = decode_token(body["access_token"])["sid"]
        row = db.query(RefreshToken).filter_by(
            token_hash=refresh_tokens.hash_token(body["refresh_token"])).first()
        assert row is not None
        assert row.sid == sid


class TestRevocation:
    def test_revoked_key_stops_unlocking(self, client, user, db):
        """The answer to a lost phone, and the reason a device key is worth its
        risk — a password cannot be revoked without changing it everywhere."""
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        assert _unlock(client, enrolled).status_code == 200

        assert client.delete(f"/auth/device-keys/{enrolled['id']}",
                             headers=headers).status_code == 204
        assert _unlock(client, enrolled).status_code == 401

    def test_revoked_key_disappears_from_the_list(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        client.delete(f"/auth/device-keys/{enrolled['id']}", headers=headers)
        assert client.get("/auth/device-keys", headers=headers).json() == []

    def test_cannot_revoke_another_users_key(self, client, user, db):
        from app.models.activity import User as UserModel

        headers = _setup(client)
        other = UserModel(name="Other")
        db.add(other)
        db.flush()
        theirs = DeviceKey(user_id=other.id, label="theirs", salt=b"s" * 16,
                           wrapped_dek=b"x", wrapped_privkey=b"y")
        db.add(theirs)
        db.commit()

        assert client.delete(f"/auth/device-keys/{theirs.id}",
                             headers=headers).status_code == 404

    def test_password_change_revokes_every_device(self, client, user, db):
        """Changing a password is what a user does when they think they are
        compromised. The DEK does not change, so without this an enrolled device
        would keep working forever."""
        headers = _setup(client)
        enrolled = _enrol(client, headers)

        resp = client.post("/users/me/password", json={
            "current_password": PASSWORD, "new_password": "brandnewpass456",
        }, headers=headers)
        assert resp.status_code == 200, resp.text
        assert resp.json()["revoked_device_keys"] == 1

        assert _unlock(client, enrolled).status_code == 401

    def test_password_change_revokes_refresh_tokens_too(self, client, user, db):
        headers = _setup(client)
        login = client.post("/auth/login", json={
            "username": "admin", "password": PASSWORD,
            "issue_refresh_token": True,
        }).json()

        client.post("/users/me/password", json={
            "current_password": PASSWORD, "new_password": "brandnewpass456",
        }, headers=headers)

        assert client.post("/auth/refresh", json={
            "refresh_token": login["refresh_token"]}).status_code == 401


class TestCrypto:
    def test_device_wrapping_round_trips(self, client, user, db):
        _setup(client)
        uk = db.query(UserKey).filter_by(user_id=user.id).first()
        material = user_crypto.unwrap_with_password(PASSWORD, uk)

        generated = user_crypto.wrap_for_device(material)
        row = DeviceKey(user_id=user.id, salt=generated.salt,
                        wrapped_dek=generated.wrapped_dek,
                        wrapped_privkey=generated.wrapped_privkey)
        recovered = user_crypto.unwrap_with_device_secret(generated.device_secret, row)

        assert recovered.dek == material.dek
        assert bytes(recovered.privkey) == bytes(material.privkey)

    def test_each_enrolment_gets_an_independent_secret(self, client, user, db):
        """Two devices for one user must derive unrelated keys, so revoking one
        cannot be worked around with the other's secret."""
        headers = _setup(client)
        first = _enrol(client, headers, label="phone")
        second = _enrol(client, headers, label="tablet")

        assert first["device_secret"] != second["device_secret"]
        rows = {r.id: r for r in db.query(DeviceKey).all()}
        assert rows[first["id"]].salt != rows[second["id"]].salt

        # The phone's secret must not open the tablet's wrapping.
        resp = client.post("/auth/device-unlock", json={
            "device_key_id": second["id"], "device_secret": first["device_secret"],
        })
        assert resp.status_code == 401

    def test_secret_is_256_bits(self, client, user, db):
        headers = _setup(client)
        enrolled = _enrol(client, headers)
        assert len(user_crypto.parse_device_secret(enrolled["device_secret"])) == 32
