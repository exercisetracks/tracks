# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What the search box understands beyond "find a place with this name".

A search that only matches names is a gazetteer lookup. These cover the four
other shapes of question people actually type — a coordinate, a category, a
category somewhere else, and a street corner — and, just as importantly, the
cases where each must *not* fire: "Coffee Creek" is a place, "bed and
breakfast" is not a junction, and answering either the clever way would be
confidently wrong.
"""
import pytest

from app.models.poi_search import PoiSearch
from app.models.streets import StreetJunction


@pytest.fixture
def headers(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "testpass123",
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


_next_id = iter(range(1, 10_000))


def _poi(db, name, kind="spring", lat=40.0, lng=-150.0, min_zoom=12, kind_detail=None):
    row = PoiSearch(id=next(_next_id), name=name, kind=kind, kind_detail=kind_detail,
                    lat=lat, lng=lng, min_zoom=min_zoom)
    db.add(row)
    db.flush()
    return row


def _junction(db, node_id, names, lat, lng):
    from app.routes.poi.streets import street_tokens
    tokens = sorted({t for n in names for t in street_tokens(n)})
    row = StreetJunction(id=node_id, lat=lat, lng=lng, names=names, tokens=tokens)
    db.add(row)
    db.flush()
    return row


def _texts(resp):
    return [f["text"] for f in resp.json()["features"]]


# A small box, so "near" means something.
_BBOX = "bbox=-150.35,39.95,-150.15,40.10"


class TestCoordinateQueries:
    def test_a_pasted_coordinate_becomes_a_pin(self, client, headers, db):
        resp = client.get("/maps/poi/search?q=39.7392,-149.9903", headers=headers)

        features = resp.json()["features"]
        assert len(features) == 1
        assert features[0]["geometry"]["coordinates"] == [-149.9903, 39.7392]
        assert features[0]["properties"]["kind"] == "coordinate"

    def test_degrees_minutes_seconds_work_too(self, client, headers, db):
        resp = client.get("/maps/poi/search?q=39%C2%B045%2723%22N 150%C2%B013%2710%22W",
                          headers=headers)

        features = resp.json()["features"]
        assert len(features) == 1
        lng, lat = features[0]["center"]
        assert round(lat, 3) == 39.756 and round(lng, 3) == -150.219

    def test_a_coordinate_needs_no_data_at_all(self, client, headers, db):
        """Nothing in the table, and it still answers — the point is that this
        is not a lookup."""
        resp = client.get("/maps/poi/search?q=0,0", headers=headers)
        assert len(resp.json()["features"]) == 1


class TestCategoryQueries:
    def test_a_category_returns_things_of_that_kind(self, client, headers, db):
        """The headline case: "coffee" finds the cafe, not only the place with
        Coffee in its name."""
        _poi(db, "Ozo", kind="cafe", lat=40.02, lng=-150.27)
        _poi(db, "Trident", kind="cafe", lat=40.018, lng=-150.28)
        _poi(db, "Granite Peak", kind="peak", lat=40.25, lng=-150.6)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=coffee&{_BBOX}", headers=headers)

        assert sorted(_texts(resp)) == ["Ozo", "Trident"]

    def test_categories_are_ordered_by_distance(self, client, headers, db):
        """"Coffee" means the nearest one. Importance is the wrong axis here —
        nobody wants the most famous cafe in the state."""
        _poi(db, "Far", kind="cafe", lat=40.09, lng=-150.17, min_zoom=2)
        _poi(db, "Near", kind="cafe", lat=40.025, lng=-150.25, min_zoom=18)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=coffee&{_BBOX}", headers=headers)

        assert _texts(resp)[0] == "Near"

    def test_both_importers_answer_one_category(self, client, headers, db):
        """GNIS calls it "Summit", OSM calls it "peak"; "peaks" wants both."""
        _poi(db, "Ridge Mountain", kind="peak", lat=40.0, lng=-150.29)
        _poi(db, "Eagle Peak", kind="summit", kind_detail="Summit", lat=40.0, lng=-150.3)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=peaks&{_BBOX}", headers=headers)

        assert sorted(_texts(resp)) == ["Eagle Peak", "Ridge Mountain"]

    def test_an_unnamed_feature_is_labelled_by_its_category(self, client, headers, db):
        """OSM rarely names a public toilet. "The nearest toilets" is still a
        useful answer, so it is shown by what it is rather than dropped."""
        _poi(db, "", kind="toilets", lat=40.02, lng=-150.27)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=bathroom&{_BBOX}", headers=headers)

        assert len(resp.json()["features"]) == 1
        assert _texts(resp)[0] != ""

    def test_a_place_named_like_a_category_is_still_a_place(self, client, headers, db):
        """Coffee Creek is in California. This is the guard that keeps a
        category from hijacking a name search."""
        _poi(db, "Coffee Creek", kind="stream", lat=41.1, lng=-122.7)
        _poi(db, "Ozo", kind="cafe", lat=40.02, lng=-150.27)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=coffee creek&{_BBOX}", headers=headers)

        assert _texts(resp) == ["Coffee Creek"]

    def test_filler_words_do_not_stop_a_category(self, client, headers, db):
        _poi(db, "Conoco", kind="fuel", lat=40.02, lng=-150.27)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=find me the nearest gas station&{_BBOX}",
                          headers=headers)

        assert _texts(resp) == ["Conoco"]


class TestNearAPlace:
    def test_a_category_can_be_asked_for_somewhere_else(self, client, headers, db):
        """"coffee near Riverside" should search around Riverside, not around the map.
        The cafe by Riverside wins even though the viewport is over the harbor."""
        _poi(db, "Riverside", kind="locality", lat=40.07, lng=-150.51, min_zoom=10)
        _poi(db, "Harbor Cafe", kind="cafe", lat=40.018, lng=-150.28)
        _poi(db, "Riverside Coffee Hut", kind="cafe", lat=40.072, lng=-150.512)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=coffee near Riverside&{_BBOX}", headers=headers)

        assert _texts(resp)[0] == "Riverside Coffee Hut"

    def test_near_me_means_the_map_not_a_place_called_me(self, client, headers, db):
        _poi(db, "Harbor Cafe", kind="cafe", lat=40.018, lng=-150.28)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=coffee near me&{_BBOX}", headers=headers)

        assert _texts(resp) == ["Harbor Cafe"]

    def test_an_unresolvable_place_falls_back_rather_than_failing(self, client, headers, db):
        """"coffee near Zzyzx" when there is no Zzyzx: still answer with
        coffee, around the map, rather than returning nothing."""
        _poi(db, "Harbor Cafe", kind="cafe", lat=40.018, lng=-150.28)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=coffee near Zzyzxqq&{_BBOX}", headers=headers)

        assert resp.status_code == 200
        assert _texts(resp) == ["Harbor Cafe"]


class TestIntersections:
    def test_two_streets_that_meet(self, client, headers, db):
        _junction(db, 1, ["Maple Street", "North Broadway"], 40.019, -150.281)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=Maple and Broadway&{_BBOX}", headers=headers)

        features = resp.json()["features"]
        assert len(features) == 1
        assert features[0]["properties"]["kind"] == "intersection"
        assert features[0]["geometry"]["coordinates"] == [-150.281, 40.019]
        assert set(features[0]["properties"]["streets"]) == {"Maple Street", "North Broadway"}

    def test_the_street_type_is_optional(self, client, headers, db):
        """"Maple St and Broadway" and "Maple and Broadway" are one question."""
        _junction(db, 1, ["Maple Street", "Broadway"], 40.019, -150.281)
        db.commit()

        # The ampersand is percent-encoded, as any real client must — bare, it
        # would end the `q` parameter and start a new one.
        for q in ("Maple St and Broadway", "Maple Street %26 Broadway",
                  "maple and broadway"):
            resp = client.get(f"/maps/poi/search?q={q}&{_BBOX}", headers=headers)
            assert len(resp.json()["features"]) == 1, q

    def test_numbered_streets_normalise(self, client, headers, db):
        _junction(db, 1, ["5th Street", "Walnut Street"], 40.017, -150.283)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=5th and Walnut&{_BBOX}", headers=headers)

        assert len(resp.json()["features"]) == 1

    def test_streets_that_never_meet_give_no_corner(self, client, headers, db):
        """Two real streets, no shared node. Inventing a midpoint would be a
        confident lie about where to stand."""
        _junction(db, 1, ["Maple Street", "Broadway"], 40.019, -150.281)
        _junction(db, 2, ["Lincoln Avenue", "Elm Street"], 40.014, -150.26)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=Maple and Elm&{_BBOX}", headers=headers)

        assert resp.json()["features"] == []

    def test_a_phrase_containing_and_is_not_a_corner(self, client, headers, db):
        """"bed and breakfast" reaches the junction lookup, finds nothing, and
        falls through to the ordinary name search."""
        _poi(db, "Bed and Breakfast Inn", kind="hotel", lat=40.02, lng=-150.27)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=bed and breakfast&{_BBOX}", headers=headers)

        assert _texts(resp) == ["Bed and Breakfast Inn"]

    def test_the_nearest_corner_wins_when_a_name_repeats(self, client, headers, db):
        """Main & 1st exists in a hundred towns. The one on screen is meant."""
        _junction(db, 1, ["Main Street", "1st Street"], 40.02, -150.27)
        _junction(db, 2, ["Main Street", "1st Street"], 38.0, -149.0)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=Main and 1st&{_BBOX}", headers=headers)

        assert resp.json()["features"][0]["center"] == [-150.27, 40.02]


class TestRankingAndVariety:
    def test_proximity_breaks_a_tie_between_identical_names(self, client, headers, db):
        _poi(db, "Mill Creek", kind="stream", lat=40.02, lng=-150.27)
        _poi(db, "Mill Creek", kind="stream", lat=37.0, lng=-153.0)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=Mill Creek&{_BBOX}", headers=headers)

        assert resp.json()["features"][0]["center"] == [-150.27, 40.02]

    def test_one_name_cannot_fill_the_whole_list(self, client, headers, db):
        """A state can have dozens of Spring Creeks. Ten of them is not an answer."""
        for i in range(8):
            _poi(db, "Spring Creek", kind="stream", lat=40.0 + i / 50, lng=-150.3 - i / 50)
        _poi(db, "Spring Creek Trailhead", kind="trailhead", lat=40.05, lng=-150.32)
        db.commit()

        resp = client.get(f"/maps/poi/search?q=Spring Creek&{_BBOX}", headers=headers)

        texts = _texts(resp)
        assert texts.count("Spring Creek") <= 3
        assert "Spring Creek Trailhead" in texts


class TestStreetReindexEndpoint:
    """The corner index is rebuilt from a path, so the path is attacker input."""

    def test_a_path_outside_map_data_is_refused(self, client, headers):
        resp = client.post("/maps/poi/streets/reindex?pbf=/etc/passwd", headers=headers)
        assert resp.status_code == 400

    def test_traversal_out_of_map_data_is_refused(self, client, headers):
        """Resolved, not just prefix-checked — "/map-data/../etc/x.pbf" starts
        with the right string and is not inside it."""
        resp = client.post(
            "/maps/poi/streets/reindex?pbf=/map-data/../etc/shadow.pbf", headers=headers)
        assert resp.status_code == 400

    def test_a_non_pbf_under_map_data_is_refused(self, client, headers):
        resp = client.post(
            "/maps/poi/streets/reindex?pbf=/map-data/planet_basemap.pmtiles",
            headers=headers)
        assert resp.status_code == 400

    def test_a_missing_extract_is_a_404_not_a_crash(self, client, headers):
        resp = client.post(
            "/maps/poi/streets/reindex?pbf=/map-data/osm/nope.osm.pbf", headers=headers)
        assert resp.status_code == 404

    def test_it_requires_authentication(self, client, headers):
        assert client.post("/maps/poi/streets/reindex").status_code == 401
