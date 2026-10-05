# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""GET /maps/poi/offline — the compact export a phone downloads once and
searches locally, with no server to ask a live question of afterwards.

Deliberately not exercising ranking or bias the way `test_api`'s (absent)
`/search` coverage would: this endpoint returns everything that matches the
bbox and the same coarse filters `/features` already applies, and leaves
scoring to the phone. The tests that matter are about what gets left in or
out of that export, and about the size guard that keeps a phone from asking
for a state at once.
"""
import pytest

from app.models.poi_search import PoiSearch


@pytest.fixture
def headers(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "testpass123",
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


_next_id = iter(range(1, 10_000))


def _poi(db, name="Spring", kind="spring", lat=40.0, lng=-150.0, min_zoom=12):
    # An explicit id: BigInteger's autoincrement does not map onto SQLite's
    # native rowid alias the way a plain Integer PK does, and the test suite
    # runs against SQLite rather than the Postgres this table is designed for.
    row = PoiSearch(id=next(_next_id), name=name, kind=kind, lat=lat, lng=lng, min_zoom=min_zoom)
    db.add(row)
    db.flush()
    return row


class TestOfflinePoi:
    def test_returns_points_inside_the_bbox(self, client, headers, db):
        inside = _poi(db, name="Crescent Lake", lat=39.65, lng=-150.5)
        db.commit()

        resp = client.get("/maps/poi/offline?bbox=-150.6,39.6,-150.4,39.7", headers=headers)

        assert resp.status_code == 200, resp.text
        points = resp.json()["points"]
        assert [p["id"] for p in points] == [inside.id]
        assert points[0]["name"] == "Crescent Lake"

    def test_excludes_points_outside_the_bbox(self, client, headers, db):
        _poi(db, name="Far Away", lat=10.0, lng=10.0)
        db.commit()

        resp = client.get("/maps/poi/offline?bbox=-150.6,39.6,-150.4,39.7", headers=headers)

        assert resp.json()["points"] == []

    def test_excludes_blank_names(self, client, headers, db):
        # An unlabelled point — a met tower, a substation — can never be typed
        # to in a text search, so exporting it is only ever wasted bandwidth.
        _poi(db, name="", lat=39.65, lng=-150.5)
        named = _poi(db, name="Trailhead", lat=39.65, lng=-150.5)
        db.commit()

        resp = client.get("/maps/poi/offline?bbox=-150.6,39.6,-150.4,39.7", headers=headers)

        assert [p["id"] for p in resp.json()["points"]] == [named.id]

    def test_excludes_administrative_and_place_label_kinds(self, client, headers, db):
        # Same exclusion /features applies: these are basemap label furniture,
        # not something a person is searching an app for.
        _poi(db, name="Pine County", kind="county", lat=39.65, lng=-150.5)
        _poi(db, name="Fairview", kind="locality", lat=39.65, lng=-150.5)
        real = _poi(db, name="Hollowbrook Trailhead", kind="trailhead", lat=39.65, lng=-150.5)
        db.commit()

        resp = client.get("/maps/poi/offline?bbox=-150.6,39.6,-150.4,39.7", headers=headers)

        assert [p["id"] for p in resp.json()["points"]] == [real.id]

    def test_rejects_an_area_too_large_to_export(self, client, headers):
        resp = client.get("/maps/poi/offline?bbox=-155,35,-145,45", headers=headers)
        assert resp.status_code == 400

    def test_rejects_a_malformed_bbox(self, client, headers):
        resp = client.get("/maps/poi/offline?bbox=not,a,bbox", headers=headers)
        assert resp.status_code == 400

    def test_requires_authentication(self, client, headers):
        # `headers` still runs setup, taking the app out of open-access mode —
        # a request with none of its own must then be refused.
        resp = client.get("/maps/poi/offline?bbox=-150.6,39.6,-150.4,39.7")
        assert resp.status_code == 401
