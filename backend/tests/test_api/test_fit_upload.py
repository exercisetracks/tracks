# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Tests for /fit/upload — now encrypts each file under the live session's DEK
before persisting anything, then parses/inserts immediately (there's a key
right here, no need to queue like sync-agent ingestion does). The FIT
*parser* is mocked (app.services.fit_import._parse_bytes) — see
test_services/test_fit_import.py's docstring for why.
"""

import io
from unittest.mock import patch

from app.services import fit_import


def _auth_headers(client, username="admin", password="originalpass1"):
    resp = client.post("/auth/setup", json={
        "username": username, "name": "Admin", "password": password,
    })
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


_PARSED = {
    "type": "activity",
    "device": {"serial_number": "77", "manufacturer": "garmin", "manufacturer_id": 1},
    "activity": {"sport": "swimming", "duration_seconds": 1200},
    "data_points": [], "laps": [], "strength_sets": [],
    "climb_splits": [], "power_curve": {}, "pace_curve": {},
}


def _upload(client, headers, filename="activity.fit", content=b"fake fit bytes"):
    return client.post(
        "/fit/upload", headers=headers,
        files=[("files", (filename, io.BytesIO(content), "application/octet-stream"))],
    )


class TestUploadAuth:
    def test_no_session_rejected(self, client, user):
        resp = _upload(client, {})
        assert resp.status_code == 401


class TestUpload:
    def test_uploaded_file_is_encrypted_at_rest_and_imported(self, client, user, db):
        from app.models.activity import Activity
        from app.models.imports import Import

        headers = _auth_headers(client)
        content = b"a very distinctive plaintext marker"

        with patch.object(fit_import, "_parse_bytes", return_value=_PARSED):
            resp = _upload(client, headers, content=content)

        assert resp.status_code == 200
        body = resp.json()
        assert body["saved"] == 1
        assert body["skipped"] == 0

        # The upload committed via its own session in a background thread —
        # this test's `db` session needs a fresh snapshot to see it.
        db.expire_all()
        activity = db.query(Activity).filter_by(user_id=user.id).first()
        assert activity is not None
        assert activity.sport == "swimming"

        imp = db.query(Import).filter_by(user_id=user.id, source="upload").first()
        assert imp is not None
        blob_id = imp.extra["blob_id"]

        from app.services import object_storage
        stored = object_storage.load_blob(blob_id)
        assert content not in stored
        assert stored != content

    def test_duplicate_upload_is_skipped(self, client, user):
        headers = _auth_headers(client)
        content = b"same bytes both times"

        with patch.object(fit_import, "_parse_bytes", return_value=_PARSED):
            first = _upload(client, headers, filename="a.fit", content=content)
            second = _upload(client, headers, filename="a.fit", content=content)

        assert first.json()["saved"] == 1
        assert second.json()["saved"] == 0
        assert second.json()["skipped"] == 1

    def test_non_fit_extension_rejected(self, client, user):
        headers = _auth_headers(client)
        resp = _upload(client, headers, filename="notes.txt", content=b"hello")
        assert resp.status_code == 400

    def test_unrecognised_fit_type_still_recorded_not_an_error(self, client, user, db):
        from app.models.imports import Import

        headers = _auth_headers(client)
        with patch.object(fit_import, "_parse_bytes", return_value=None):
            resp = _upload(client, headers, content=b"unknown fit type bytes")

        assert resp.status_code == 200
        assert resp.json()["saved"] == 1
        db.expire_all()
        assert db.query(Import).filter_by(user_id=user.id, source="upload").first() is not None
