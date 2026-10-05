# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Custom-track simplify + merge, and the merged-activity metric isolation.

These exercise the maps/courses API added for the GPX-studio-style merge/simplify
feature. The central guarantee under test: a merged-trip Activity shows in the
activity list but never contributes to a training metric.
"""
from datetime import datetime, timedelta

from app.models.activity import Activity, DataPoint
from app.models.user_keys import UserKey
from app.services import crypto_context, user_crypto


# ── helpers ─────────────────────────────────────────────────────────────────

def _line(n=6, lat0=40.0, lng0=-150.0, ele0=1500.0):
    """A simple ascending [lng,lat,ele] polyline of n points."""
    return [[lng0 + i * 0.001, lat0 + i * 0.001, ele0 + i * 10.0] for i in range(n)]


def _make_course(client, coords, name="Track", headers=None):
    resp = client.post("/maps/courses", json={"name": name, "coords": coords}, headers=headers)
    assert resp.status_code == 201, resp.text
    return resp.json()


def _auth(client, db, user, password="testpass123"):
    """Establishes a real crypto session for `user`. Returns (headers,
    material): headers for authenticated HTTP calls (merge/course-from-
    activity now require an active session — DataPoint.lat/lng are
    encrypted), material for test helpers that write DataPoint rows
    directly via the `db` fixture, bypassing HTTP entirely (there's no
    request in that path to carry a session — the contextvar has to be set
    by hand for the duration of the insert)."""
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": password,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    headers = {"Authorization": f"Bearer {resp.json()['access_token']}"}
    uk = db.query(UserKey).filter_by(user_id=user.id).first()
    material = user_crypto.unwrap_with_password(password, uk)
    return headers, material


def _make_activity_with_track(db, user, material, *, start, npts=6, lat0=40.0, lng0=-150.0,
                              tss=None, **kw):
    a = Activity(user_id=user.id, device_id=1, sport="hiking", started_at=start,
                 duration_seconds=3600, distance_meters=1000.0,
                 training_stress_score=tss, **kw)
    db.add(a)
    db.flush()
    # DataPoint.id is BigInteger (no SQLite autoincrement) — assign explicit ids.
    for i in range(npts):
        db.add(DataPoint(id=a.id * 100000 + i, activity_id=a.id,
                         recorded_at=start + timedelta(seconds=i * 10),
                         lat=lat0 + i * 0.001, lng=lng0 + i * 0.001, altitude=1500.0 + i * 5))
    token = crypto_context.set_current_key(material)
    try:
        db.commit()
    finally:
        crypto_context.reset_current_key(token)
    db.refresh(a)
    return a


# ── simplify (geometry replace via PATCH coords) ────────────────────────────

class TestSimplify:
    def test_replacing_coords_recomputes_geometry_and_stats(self, client, user):
        course = _make_course(client, _line(12))
        assert len(course["geometry"]) == 12
        d0 = course["distance_m"]

        # Apply a coarser (fewer-point) line — what the client-side simplify sends.
        simplified = _line(3)
        resp = client.patch(f"/maps/courses/{course['id']}", json={"coords": simplified})
        assert resp.status_code == 200, resp.text
        out = resp.json()
        assert len(out["geometry"]) == 3
        assert out["distance_m"] != d0          # stats recomputed from new geometry
        assert out["distance_m"] > 0

    def test_too_few_points_rejected(self, client, user):
        course = _make_course(client, _line(8))
        resp = client.patch(f"/maps/courses/{course['id']}", json={"coords": [[1, 2, 3]]})
        assert resp.status_code == 400


# ── per-track visibility ────────────────────────────────────────────────────

class TestVisibility:
    def test_hidden_track_drops_out_of_geojson_but_stays_listed(self, client, user):
        course = _make_course(client, _line())
        client.patch(f"/maps/courses/{course['id']}", json={"hidden": True})

        listed = client.get("/maps/courses").json()
        assert any(t["id"] == course["id"] and t["hidden"] for t in listed)

        gj = client.get("/maps/courses/geojson").json()
        assert all(f["id"] != course["id"] for f in gj["features"])


# ── from-activity (reads DataPoint.lat/lng, needs a crypto session) ────────

class TestCourseFromActivity:
    def test_requires_crypto_session(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material, start=datetime(2024, 6, 1, 8))
        resp = client.post(f"/maps/courses/from-activity/{a.id}")  # no headers
        assert resp.status_code == 401

    def test_builds_a_track_from_the_activitys_gps(self, client, user, db):
        headers, material = _auth(client, db, user)
        a = _make_activity_with_track(db, user, material, start=datetime(2024, 6, 1, 8))
        resp = client.post(f"/maps/courses/from-activity/{a.id}", headers=headers)
        assert resp.status_code == 201, resp.text
        body = resp.json()
        assert body["activity_id"] == a.id
        assert len(body["geometry"]) == 6


# ── merge ───────────────────────────────────────────────────────────────────

class TestMerge:
    def test_merge_activities_makes_track_and_merged_activity(self, client, user, db):
        headers, material = _auth(client, db, user)
        a1 = _make_activity_with_track(db, user, material, start=datetime(2024, 6, 1, 8))
        a2 = _make_activity_with_track(db, user, material, start=datetime(2024, 6, 2, 8),
                                       lat0=41.0, lng0=-151.0)

        resp = client.post("/maps/courses/merge", headers=headers, json={
            "name": "Big Trip",
            "sources": [{"kind": "activity", "id": a1.id},
                        {"kind": "activity", "id": a2.id}],
        })
        assert resp.status_code == 201, resp.text
        body = resp.json()
        track = body["track"]
        assert track["source"] == "merged"
        # Geometry is both tracks concatenated.
        assert len(track["geometry"]) == 12
        assert body["activity_id"] is not None

        # The merged Activity: in the list, flagged, no device, carries totals.
        merged = client.get(f"/activities/{body['activity_id']}", headers=headers).json()
        assert merged["is_merged"] is True
        assert merged["device_id"] is None
        assert merged["extra"]["custom_track_id"] == track["id"]
        assert set(merged["extra"]["source_activity_ids"]) == {a1.id, a2.id}
        assert merged["distance_meters"] > 0

        ids = [a["id"] for a in client.get("/activities/", headers=headers).json()["items"]]
        assert body["activity_id"] in ids

    def test_merge_hides_source_tracks(self, client, user, db):
        headers, _material = _auth(client, db, user)
        t1 = _make_course(client, _line(), name="Day 1", headers=headers)
        t2 = _make_course(client, _line(lat0=41.0), name="Day 2", headers=headers)

        resp = client.post("/maps/courses/merge", headers=headers, json={
            "name": "Combined",
            "hide_sources": True,
            "sources": [{"kind": "track", "id": t1["id"]},
                        {"kind": "track", "id": t2["id"]}],
        })
        assert resp.status_code == 201, resp.text
        merged_id = resp.json()["track"]["id"]

        by_id = {t["id"]: t for t in client.get("/maps/courses", headers=headers).json()}
        assert by_id[t1["id"]]["hidden"] and by_id[t2["id"]]["hidden"]
        assert not by_id[merged_id]["hidden"]

        gj_ids = {f["id"] for f in client.get("/maps/courses/geojson", headers=headers).json()["features"]}
        assert merged_id in gj_ids and t1["id"] not in gj_ids and t2["id"] not in gj_ids

    def test_merge_reverse_flips_a_segment(self, client, user, db):
        headers, material = _auth(client, db, user)
        a1 = _make_activity_with_track(db, user, material, start=datetime(2024, 6, 1, 8))
        resp = client.post("/maps/courses/merge", headers=headers, json={
            "sources": [{"kind": "activity", "id": a1.id},
                        {"kind": "activity", "id": a1.id, "reverse": True}],
            "create_activity": False,
        })
        assert resp.status_code == 201, resp.text
        geom = resp.json()["track"]["geometry"]
        # First half ascends, reversed half descends back — the join meets at the end.
        assert geom[0][:2] == geom[-1][:2]

    def test_merge_requires_two_sources(self, client, user, db):
        headers, _material = _auth(client, db, user)
        t1 = _make_course(client, _line(), headers=headers)
        resp = client.post("/maps/courses/merge", headers=headers, json={
            "sources": [{"kind": "track", "id": t1["id"]}]})
        assert resp.status_code == 400


# ── metric isolation (the core requirement) ─────────────────────────────────

class TestMergedActivityExcludedFromMetrics:
    def test_training_load_unchanged_by_merged_activity(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, start=datetime(2024, 6, 1, 8), tss=80)
        params = {"after": "2024-05-01"}

        before = client.get("/metrics/training-load", params=params, headers=headers).json()
        assert before  # the real activity produces load

        # A merged trip on the same day with a large duration that WOULD inflate an
        # estimated TSS if it were ever counted.
        db.add(Activity(user_id=user.id, device_id=None, is_merged=True,
                        sport="hiking", started_at=datetime(2024, 6, 1, 9),
                        duration_seconds=48 * 3600, distance_meters=200000.0))
        db.commit()

        after = client.get("/metrics/training-load", params=params, headers=headers).json()
        assert after == before

    def test_merged_activity_absent_from_summary_counts(self, client, user, db):
        headers, material = _auth(client, db, user)
        _make_activity_with_track(db, user, material, start=datetime(2024, 6, 1, 8), tss=50)
        db.add(Activity(user_id=user.id, device_id=None, is_merged=True,
                        sport="hiking", started_at=datetime(2024, 6, 1, 9),
                        duration_seconds=36000, distance_meters=100000.0))
        db.commit()

        summary = client.get("/metrics/summary", params={"after": "2024-05-01"},
                             headers=headers).json()
        assert summary["activity_count"] == 1   # only the real activity
