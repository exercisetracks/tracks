# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The two POI endpoints the map itself uses: /features and /search.

Neither had any coverage until the suite moved to PostgreSQL, and that was the
reason: both are built out of Postgres-only SQL — `to_tsvector`, the pg_trgm
similarity operators, and now a geometry containment test for the bounding box.
Under SQLite they could not be executed at all, so the busiest read path in the
app and the one users type into were verified only by looking at them.

/features runs on every pan and zoom, so it is also the query whose shape is
most worth pinning: what is inside the box, what the zoom budget admits, and
what gets excluded as basemap furniture.
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


def _poi(db, name="Spring", kind="spring", lat=40.0, lng=-150.0, min_zoom=12, **extra):
    row = PoiSearch(id=next(_next_id), name=name, kind=kind,
                    lat=lat, lng=lng, min_zoom=min_zoom, **extra)
    db.add(row)
    db.flush()
    return row


def _names(resp):
    return sorted(f["properties"]["name"] for f in resp.json()["features"])


class TestFeatures:
    """The map canvas layer: everything in view, at this zoom."""

    def test_returns_points_inside_the_bbox_as_geojson(self, client, headers, db):
        _poi(db, name="Blue Lake", lat=39.65, lng=-150.5)
        db.commit()

        resp = client.get("/maps/poi/features?bbox=-150.6,39.6,-150.4,39.7&zoom=12",
                          headers=headers)

        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["type"] == "FeatureCollection"
        assert _names(resp) == ["Blue Lake"]
        feature = body["features"][0]
        assert feature["geometry"]["type"] == "Point"
        # GeoJSON is lng,lat — the one ordering mistake that puts a point in
        # the wrong ocean.
        assert feature["geometry"]["coordinates"] == [-150.5, 39.65]

    def test_excludes_points_outside_the_bbox(self, client, headers, db):
        _poi(db, name="Far Away", lat=10.0, lng=10.0)
        db.commit()

        resp = client.get("/maps/poi/features?bbox=-150.6,39.6,-150.4,39.7&zoom=12",
                          headers=headers)

        assert resp.json()["features"] == []

    def test_a_point_exactly_on_the_boundary_is_inside(self, client, headers, db):
        """The bbox is inclusive on every edge. Worth pinning explicitly: the
        containment test is a geometry operator now, not four comparisons, and
        an off-by-one at the edge would show up as POIs flickering at a pan."""
        _poi(db, name="Corner", lat=39.6, lng=-150.6)
        _poi(db, name="Far Corner", lat=39.7, lng=-150.4)
        _poi(db, name="Edge", lat=39.65, lng=-150.6)
        db.commit()

        resp = client.get("/maps/poi/features?bbox=-150.6,39.6,-150.4,39.7&zoom=12",
                          headers=headers)

        assert _names(resp) == ["Corner", "Edge", "Far Corner"]

    def test_features_too_minor_for_this_zoom_are_left_out(self, client, headers, db):
        """min_zoom is the importance dial: a big feature appears zoomed out, a
        minor one only once you are close."""
        _poi(db, name="Big Peak", lat=39.65, lng=-150.5, min_zoom=4)
        _poi(db, name="Tiny Culvert", lat=39.65, lng=-150.5, min_zoom=18)
        db.commit()

        resp = client.get("/maps/poi/features?bbox=-150.6,39.6,-150.4,39.7&zoom=5",
                          headers=headers)

        assert _names(resp) == ["Big Peak"]

    def test_zooming_in_admits_the_minor_feature(self, client, headers, db):
        _poi(db, name="Big Peak", lat=39.65, lng=-150.5, min_zoom=4)
        _poi(db, name="Small Spring", lat=39.655, lng=-150.52, min_zoom=13)
        db.commit()

        resp = client.get("/maps/poi/features?bbox=-150.6,39.6,-150.4,39.7&zoom=14",
                          headers=headers)

        assert _names(resp) == ["Big Peak", "Small Spring"]

    def test_place_labels_and_boundaries_are_left_to_the_basemap(self, client, headers, db):
        """These are drawn by the basemap's own label layers. Returning them
        here would double-draw them and spend the result budget doing it."""
        _poi(db, name="Fairview", kind="locality", lat=39.65, lng=-150.5)
        _poi(db, name="Pine County", kind="county", lat=39.65, lng=-150.5)
        _poi(db, name="Hollowbrook Trailhead", kind="trailhead", lat=39.65, lng=-150.5)
        db.commit()

        resp = client.get("/maps/poi/features?bbox=-150.6,39.6,-150.4,39.7&zoom=12",
                          headers=headers)

        assert _names(resp) == ["Hollowbrook Trailhead"]

    def test_a_malformed_bbox_is_refused(self, client, headers):
        assert client.get("/maps/poi/features?bbox=nope&zoom=12",
                          headers=headers).status_code == 400


class TestSearch:
    """The geocoder box. Postgres-only scoring throughout — full-text,
    trigram similarity and word similarity, blended with importance."""

    def test_finds_an_exact_name(self, client, headers, db):
        _poi(db, name="Crescent Lake", lat=39.6, lng=-152.1)
        db.commit()

        resp = client.get("/maps/poi/search?q=Crescent Lake", headers=headers)

        assert resp.status_code == 200, resp.text
        assert "Crescent Lake" in [f["text"] for f in resp.json()["features"]]

    def test_finds_by_prefix(self, client, headers, db):
        _poi(db, name="Granite Peak", lat=40.25, lng=-150.6)
        db.commit()

        resp = client.get("/maps/poi/search?q=Granite", headers=headers)

        assert [f["text"] for f in resp.json()["features"]] == ["Granite Peak"]

    def test_finds_despite_a_typo(self, client, headers, db):
        """The trigram half of the scoring — this is the clause that cannot run
        anywhere but Postgres, and the reason this file needed a real one."""
        _poi(db, name="Hollowbrook Park", lat=40.0, lng=-150.28)
        db.commit()

        resp = client.get("/maps/poi/search?q=Holowbrook", headers=headers)

        assert [f["text"] for f in resp.json()["features"]] == ["Hollowbrook Park"]

    def test_word_order_does_not_matter(self, client, headers, db):
        _poi(db, name="Lake Marian", lat=40.07, lng=-150.63)
        db.commit()

        resp = client.get("/maps/poi/search?q=Marian Lake", headers=headers)

        assert [f["text"] for f in resp.json()["features"]] == ["Lake Marian"]

    def test_a_query_matching_nothing_returns_nothing(self, client, headers, db):
        """Rather than the nearest-looking thing. A confident wrong answer is
        worse than an empty one."""
        _poi(db, name="Granite Peak", lat=40.25, lng=-150.6)
        db.commit()

        resp = client.get("/maps/poi/search?q=zzzzqqqq", headers=headers)

        assert resp.json()["features"] == []

    def test_symbols_only_does_not_error(self, client, headers, db):
        """A tsquery built from punctuation alone is a syntax error if it
        reaches Postgres, so the builder substitutes a no-match term."""
        _poi(db, name="Granite Peak", lat=40.25, lng=-150.6)
        db.commit()

        resp = client.get("/maps/poi/search?q=%2B%2B%2B", headers=headers)

        assert resp.status_code == 200
        assert resp.json()["features"] == []

    def test_the_limit_is_honoured(self, client, headers, db):
        for i in range(8):
            _poi(db, name=f"Green Lake {i}", lat=40.0 + i / 100, lng=-150.5)
        db.commit()

        resp = client.get("/maps/poi/search?q=Green Lake&limit=3", headers=headers)

        assert len(resp.json()["features"]) == 3

    def test_the_viewport_lifts_what_is_in_view(self, client, headers, db):
        """Two equally good name matches, one on screen. Bias is a tie-breaker
        between things the user might have meant."""
        _poi(db, name="Mill Creek", lat=39.65, lng=-150.5)
        _poi(db, name="Mill Creek", lat=44.0, lng=-120.0)
        db.commit()

        resp = client.get(
            "/maps/poi/search?q=Mill Creek&bbox=-150.6,39.6,-150.4,39.7",
            headers=headers,
        )

        features = resp.json()["features"]
        assert len(features) == 2
        # The in-viewport one ranks first.
        assert features[0]["geometry"]["coordinates"] == [-150.5, 39.65]
