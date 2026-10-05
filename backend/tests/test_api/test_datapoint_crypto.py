# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""DataPoint.lat/lng encryption — the routes that read them require an active
crypto session, ciphertext is genuinely opaque at rest, and the (now-removed)
background heatmap pre-warm doesn't come back as a latent 500 or a silent
no-op that hides missing data.
"""
from datetime import datetime, timedelta

from sqlalchemy import text

from app.models.activity import Activity, DataPoint
from app.models.user_keys import UserKey
from app.services import user_crypto, crypto_context


def _auth(client, db, user, password="testpass123"):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": password,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    headers = {"Authorization": f"Bearer {resp.json()['access_token']}"}
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    material = user_crypto.unwrap_with_password(password, uk)
    return headers, material


def _make_activity_with_track(db, user, material, *, lat0=40.0, lng0=-150.0, npts=6):
    a = Activity(user_id=user.id, device_id=1, sport="running",
                started_at=datetime(2024, 6, 1, 8), duration_seconds=3600,
                distance_meters=1000.0)
    db.add(a)
    db.flush()
    t0 = datetime(2024, 6, 1, 8, 0, 0)
    for i in range(npts):
        db.add(DataPoint(id=a.id * 100000 + i, activity_id=a.id,
                         recorded_at=t0 + timedelta(seconds=i * 10),
                         lat=lat0 + i * 0.001, lng=lng0 + i * 0.001,
                         altitude=1500.0 + i * 5, heart_rate=140, speed=3.0))
    token = crypto_context.set_current_key(material)
    try:
        db.commit()
    finally:
        crypto_context.reset_current_key(token)
    db.refresh(a)
    return a


class TestTrackRequiresCryptoSession:
    def test_track_without_session_is_401(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material)
        resp = client.get(f"/activities/{a.id}/track")  # no Authorization header at all
        assert resp.status_code == 401

    def test_track_returns_decrypted_points(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material, lat0=40.0, lng0=-150.0)
        resp = client.get(f"/activities/{a.id}/track", headers=headers)
        assert resp.status_code == 200
        points = resp.json()
        assert len(points) == 6
        assert points[0]["lat"] == 40.0
        assert points[0]["lng"] == -150.0


class TestCiphertextAtRest:
    def test_lat_lng_are_not_stored_as_plaintext_floats(self, client, user, db):
        """The whole point of Stage 5: a raw row scan (what a stolen DB dump
        or a curious admin running SQL directly would see) must never reveal
        GPS coordinates."""
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material, lat0=40.0, lng0=-150.0)

        raw = db.execute(
            text("SELECT lat, lng FROM data_points WHERE activity_id = :aid ORDER BY recorded_at"),
            {"aid": a.id},
        ).fetchall()
        assert len(raw) == 6
        for lat_blob, lng_blob in raw:
            # psycopg2 hands back a bytea as a memoryview rather than bytes, so
            # the assertion is "binary", not one particular Python type for it.
            assert isinstance(lat_blob, (bytes, bytearray, memoryview))
            assert isinstance(lng_blob, (bytes, bytearray, memoryview))
            # Ciphertext, not a serialized float — "40.0"/"-150.0" never appear.
            assert b"40." not in bytes(lat_blob)
            assert b"105." not in bytes(lng_blob)


class TestHeatmapRequiresCryptoSession:
    def test_heatmap_without_session_is_401(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        resp = client.get("/activities/heatmap")
        assert resp.status_code == 401

    def test_heatmap_computes_on_demand_with_session(self, client, user, db):
        """No background warm-up exists anymore (it can't decrypt anything —
        see heatmap_cache.py) — the cache is filled lazily by an
        authenticated request instead."""
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        resp = client.get("/activities/heatmap", headers=headers)
        assert resp.status_code == 200
        tracks = resp.json()
        assert len(tracks) == 1
        assert len(tracks[0]) >= 2

    def test_tracks_geojson_without_session_is_401(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        resp = client.get("/activities/tracks-geojson")
        assert resp.status_code == 401

    def test_tracks_geojson_with_session(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material)
        resp = client.get("/activities/tracks-geojson", headers=headers)
        assert resp.status_code == 200
        gj = resp.json()
        assert len(gj["features"]) == 1


class TestNoBackgroundHeatmapWarm:
    def test_warm_heatmap_cache_no_longer_exists(self):
        from app.api import activities as activities_module

        assert not hasattr(activities_module, "warm_heatmap_cache")

    def test_heatmap_cache_module_has_no_warm_thread(self):
        from app.api.activities import heatmap_cache

        assert not hasattr(heatmap_cache, "warm_heatmap_cache")
        assert not hasattr(heatmap_cache, "_warm_cache_thread")
