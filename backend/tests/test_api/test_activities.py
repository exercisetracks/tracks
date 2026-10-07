# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime, timedelta

import pytest

from app.models.activity import Activity, DataPoint, PaceBest
from app.models.user_keys import UserKey
from app.services import user_crypto


def _auth(client, db, user, password="testpass123"):
    """Establishes a real crypto session for `user` — PATCH /activities/{id}
    and POST /activities/backfill-metrics now touch DataPoint.lat/lng (via
    _load_dp_dicts), which requires an active session to decrypt. Returns
    headers for authenticated HTTP calls; calling /auth/setup also takes the
    whole app out of open-access mode, so every call in that test needs
    these headers, not just the ones touching DataPoint."""
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": password,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    headers = {"Authorization": f"Bearer {resp.json()['access_token']}"}
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    material = user_crypto.unwrap_with_password(password, uk)
    return headers, material


def _make_activity(db, user, **kwargs):
    defaults = dict(
        user_id=user.id,
        device_id=1,  # test device created by the user fixture
        sport="running",
        started_at=datetime(2024, 6, 1, 8, 0, 0),
        duration_seconds=3600,
        avg_heart_rate=140,
        max_heart_rate=170,
        distance_meters=10000.0,
    )
    defaults.update(kwargs)
    a = Activity(**defaults)
    db.add(a)
    db.commit()
    db.refresh(a)
    return a


class TestListActivities:
    def test_empty_returns_empty_page(self, client, user):
        resp = client.get("/activities/")
        assert resp.status_code == 200
        data = resp.json()
        assert data["total"] == 0
        assert data["items"] == []

    def test_returns_created_activity(self, client, user, db):
        _make_activity(db, user)
        resp = client.get("/activities/")
        assert resp.json()["total"] == 1

    def test_sport_filter(self, client, user, db):
        _make_activity(db, user, sport="running")
        _make_activity(db, user, sport="cycling",
                       started_at=datetime(2024, 6, 2, 8, 0, 0))
        resp = client.get("/activities/?sport=cycling")
        data = resp.json()
        assert data["total"] == 1
        assert data["items"][0]["sport"] == "cycling"

    def test_date_range_filter(self, client, user, db):
        _make_activity(db, user, started_at=datetime(2024, 1, 1))
        _make_activity(db, user, started_at=datetime(2024, 6, 1))
        resp = client.get("/activities/?after=2024-03-01")
        assert resp.json()["total"] == 1

    def test_pagination(self, client, user, db):
        for i in range(5):
            _make_activity(db, user,
                           started_at=datetime(2024, 6, 1) + timedelta(days=i))
        resp = client.get("/activities/?page=1&page_size=2")
        data = resp.json()
        assert data["total"] == 5
        assert len(data["items"]) == 2
        assert data["page"] == 1

    def test_ordered_newest_first(self, client, user, db):
        _make_activity(db, user, started_at=datetime(2024, 1, 1))
        _make_activity(db, user, started_at=datetime(2024, 6, 1))
        items = client.get("/activities/").json()["items"]
        assert items[0]["started_at"] > items[1]["started_at"]


class TestGetActivity:
    def test_get_existing(self, client, user, db):
        a = _make_activity(db, user)
        resp = client.get(f"/activities/{a.id}")
        assert resp.status_code == 200
        assert resp.json()["id"] == a.id

    def test_get_missing_returns_404(self, client, user):
        resp = client.get("/activities/99999")
        assert resp.status_code == 404


class TestPatchActivity:
    def test_update_name_and_notes(self, client, user, db):
        headers, _material = _auth(client, db, user)
        a = _make_activity(db, user)
        resp = client.patch(f"/activities/{a.id}",
                            json={"name": "Morning Run", "notes": "Felt great"},
                            headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["name"] == "Morning Run"
        assert data["notes"] == "Felt great"

    def test_patch_missing_returns_404(self, client, user, db):
        headers, _material = _auth(client, db, user)
        resp = client.patch("/activities/99999", json={"name": "Ghost"}, headers=headers)
        assert resp.status_code == 404

    def test_sport_change_updates_efficiency_factor(self, client, user, db):
        headers, _material = _auth(client, db, user)
        a = _make_activity(db, user, sport="running", avg_heart_rate=150,
                           normalized_power=None, avg_speed=3.5)
        resp = client.patch(f"/activities/{a.id}", json={"sport": "cycling"}, headers=headers)
        assert resp.status_code == 200
        # cycling EF needs normalized_power; avg_speed is not used for cycling → None
        assert resp.json()["efficiency_factor"] is None

    def test_sport_change_same_value_is_noop(self, client, user, db):
        headers, _material = _auth(client, db, user)
        a = _make_activity(db, user, sport="running")
        resp = client.patch(f"/activities/{a.id}", json={"sport": "running"}, headers=headers)
        assert resp.status_code == 200


class TestDeleteActivity:
    def test_delete_removes_activity(self, client, user, db):
        a = _make_activity(db, user)
        resp = client.delete(f"/activities/{a.id}")
        assert resp.status_code == 204
        assert client.get(f"/activities/{a.id}").status_code == 404

    def test_delete_missing_returns_404(self, client, user):
        resp = client.delete("/activities/99999")
        assert resp.status_code == 404


class TestListSports:
    def test_returns_distinct_sports(self, client, user, db):
        _make_activity(db, user, sport="running")
        _make_activity(db, user, sport="cycling",
                       started_at=datetime(2024, 6, 2))
        _make_activity(db, user, sport="running",
                       started_at=datetime(2024, 6, 3))
        resp = client.get("/activities/sports")
        assert resp.status_code == 200
        sports = resp.json()
        assert sorted(sports) == ["cycling", "running"]


class TestBackfillMetrics:
    def test_backfill_with_no_activities(self, client, user, db):
        headers, _material = _auth(client, db, user)
        resp = client.post("/activities/backfill-metrics", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["processed"] == 0
        assert data["skipped"] == 0
        assert data["errors"] == 0

    def test_backfill_skips_activities_without_data_points(self, client, user, db):
        headers, _material = _auth(client, db, user)
        _make_activity(db, user)  # no DataPoints
        resp = client.post("/activities/backfill-metrics", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        assert data["skipped"] == 1
        assert data["processed"] == 0

    def test_backfill_processes_activities_with_data_points(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity(db, user, sport="running", avg_heart_rate=150, avg_speed=3.5)
        t0 = datetime(2024, 6, 1, 8, 0, 0)
        # Provide explicit IDs: SQLite only auto-increments INTEGER PK, not BIGINT
        db.bulk_insert_mappings(DataPoint, [
            {"id": i + 1, "activity_id": a.id,
             "recorded_at": t0 + timedelta(seconds=i),
             "heart_rate": 150, "speed": 3.5}
            for i in range(10)
        ])
        db.commit()
        resp = client.post("/activities/backfill-metrics", headers=headers)
        assert resp.status_code == 200
        assert resp.json()["processed"] == 1

    def test_running_backfill_twice_leaves_an_mtb_load_where_once_left_it(self, client, user, db):
        """It used to multiply the stored load by the MTB factor on every run,
        so each backfill inflated every ride's training load further."""
        headers, _material = _auth(client, db, user)
        a = _make_activity(db, user, sport="mountain_biking", avg_heart_rate=150,
                           effective_tss=None, training_stress_score=None)
        t0 = datetime(2024, 6, 1, 8, 0, 0)
        db.bulk_insert_mappings(DataPoint, [
            {"id": i + 1, "activity_id": a.id,
             "recorded_at": t0 + timedelta(seconds=i), "heart_rate": 150, "speed": 3.5}
            for i in range(10)
        ])
        db.commit()
        client.post("/activities/backfill-metrics", headers=headers)
        db.refresh(a)
        once = a.effective_tss
        client.post("/activities/backfill-metrics", headers=headers)
        db.refresh(a)
        assert once is not None
        assert a.effective_tss == once

    def test_backfill_sets_pace_bests_for_running(self, client, user, db):
        headers, _material = _auth(client, db, user)
        a = _make_activity(db, user, sport="running")
        t0 = datetime(2024, 6, 1, 8, 0, 0)
        db.bulk_insert_mappings(DataPoint, [
            {"id": i + 1, "activity_id": a.id,
             "recorded_at": t0 + timedelta(seconds=i),
             "speed": 3.5}
            for i in range(3700)
        ])
        db.commit()
        client.post("/activities/backfill-metrics", headers=headers)
        bests = db.query(PaceBest).filter_by(activity_id=a.id).all()
        assert len(bests) > 0

    def test_backfill_only_touches_callers_own_activities(self, client, user, db):
        """Regression test: backfill_metrics used to loop every user's
        activities with no user_id filter, and read UserSettings via a bare
        `.first()` instead of scoping to the caller — a cross-user privacy
        bug that would also crash once DataPoint.lat/lng became encrypted
        (AES-GCM auth failure decrypting another user's ciphertext under the
        caller's key)."""
        from app.models.activity import Device, User, UserDevice
        from app.models.user_settings import UserSettings

        headers, _material = _auth(client, db, user)

        other = User(name="Other User")
        db.add(other)
        db.flush()
        db.add(UserSettings(user_id=other.id, units="metric", timezone="UTC"))
        other_dev = Device(serial_number="other-serial", manufacturer="test", manufacturer_id=2)
        db.add(other_dev)
        db.flush()
        db.add(UserDevice(user_id=other.id, device_id=other_dev.id))
        db.commit()

        other_activity = _make_activity(db, other, device_id=other_dev.id,
                                        sport="running", avg_heart_rate=150, avg_speed=3.5)
        t0 = datetime(2024, 6, 1, 8, 0, 0)
        db.bulk_insert_mappings(DataPoint, [
            {"id": i + 1, "activity_id": other_activity.id,
             "recorded_at": t0 + timedelta(seconds=i),
             "heart_rate": 150, "speed": 3.5}
            for i in range(10)
        ])
        db.commit()

        resp = client.post("/activities/backfill-metrics", headers=headers)
        assert resp.status_code == 200
        data = resp.json()
        # The caller has no activities of their own — the other user's
        # activity must not be counted, touched, or attempted to be decrypted.
        assert data["processed"] == 0
        assert data["skipped"] == 0
        assert data["errors"] == 0
