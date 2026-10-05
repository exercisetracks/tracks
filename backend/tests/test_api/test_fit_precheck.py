# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from app.models.imports import Import


def _add_import(db, filename, size=None, source="upload", source_id=None):
    extra = {"filename": filename}
    if size is not None:
        extra["size"] = size
    db.add(Import(
        user_id=1,
        source=source,
        source_id=source_id or f"hash-{filename}-{size}",
        extra=extra,
    ))
    db.commit()


class TestFitPrecheck:
    def test_empty_request(self, client, user):
        resp = client.post("/fit/precheck", json={"files": []})
        assert resp.status_code == 200
        assert resp.json() == {"known": [], "new": []}

    def test_unknown_files_are_new(self, client, user):
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "2026-07-14-08-00-00.fit", "size": 1234}]
        })
        assert resp.json()["new"] == ["2026-07-14-08-00-00.fit"]

    def test_matching_name_and_size_is_known(self, client, user, db):
        _add_import(db, "A1B2C3D4.fit", size=555)
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "A1B2C3D4.fit", "size": 555}]
        })
        assert resp.json()["known"] == ["A1B2C3D4.fit"]

    def test_same_name_different_size_is_new(self, client, user, db):
        # Monitor files grow during the day; a size change must re-transfer.
        _add_import(db, "M4C21234.FIT", size=1000)
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "M4C21234.FIT", "size": 2000}]
        })
        assert resp.json()["new"] == ["M4C21234.FIT"]

    def test_legacy_row_without_size_matches_by_name(self, client, user, db):
        _add_import(db, "old.fit", size=None)
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "old.fit", "size": 99}]
        })
        assert resp.json()["known"] == ["old.fit"]

    def test_request_without_size_matches_any(self, client, user, db):
        _add_import(db, "x.fit", size=42)
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "x.fit"}]
        })
        assert resp.json()["known"] == ["x.fit"]

    def test_sync_agent_source_also_matches(self, client, user, db):
        # A sync agent (garmin-sync, a future mobile app) records "sync",
        # not "upload" — precheck should recognize either.
        _add_import(db, "watch.fit", size=321, source="sync")
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "watch.fit", "size": 321}]
        })
        assert resp.json()["known"] == ["watch.fit"]

    def test_unrelated_sources_ignored(self, client, user, db):
        _add_import(db, "strava.fit", size=10, source="strava")
        resp = client.post("/fit/precheck", json={
            "files": [{"filename": "strava.fit", "size": 10}]
        })
        assert resp.json()["new"] == ["strava.fit"]
