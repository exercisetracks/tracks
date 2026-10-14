# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Asking for a map area you already have.

Every download request used to create a row and start an extraction, so framing
an area that was already on the server downloaded it a second time into a second
directory — and because the suggested name comes from the state the box falls
in, the list filled with identically-named copies of one place.
"""
import pytest

from app.config import settings
from app.routes.regions import crud


STATE = [-154.06, 36.99, -147.04, 41.00]
TOWN = [-150.30, 39.95, -150.15, 40.10]


@pytest.fixture
def registry(monkeypatch):
    """A stand-in region registry that records what got created."""
    state = {"regions": [], "created": [], "started": []}

    def _region(**kwargs):
        return {"id": kwargs.get("id", 1), "name": "Example State", "bbox": STATE,
                "geometry": None, "status": "installed", "progress": 100.0,
                "detail": None, "error": None, "stale": False, **kwargs}

    def _create(name, bbox):
        made = _region(id=len(state["regions"]) + 100, name=name, bbox=bbox,
                       status="downloading", progress=0.0)
        state["regions"].append(made)
        state["created"].append(made)
        return made

    monkeypatch.setattr(crud.region_registry, "list_active", lambda: list(state["regions"]))
    monkeypatch.setattr(crud.region_registry, "create", _create)
    monkeypatch.setattr(crud.geocode, "suggest_region_name", lambda bbox: "Example State")
    monkeypatch.setattr(settings, "pmtiles_source_url", "http://example.invalid/planet.pmtiles")

    def _update(region_id, status, **kwargs):
        for r in state["regions"]:
            if r["id"] == region_id:
                r["status"] = status

    monkeypatch.setattr(crud.region_registry, "update_status", _update)
    monkeypatch.setattr(crud.region_registry, "clear_attempts", lambda rid: None)
    monkeypatch.setattr(crud.region_registry, "get",
                        lambda rid: next((r for r in state["regions"] if r["id"] == rid), None))
    # The build itself runs in the queue; a request only wakes it.
    monkeypatch.setattr(crud.map_builds, "kick", lambda: state["started"].append("kick"))
    state["region"] = _region
    return state


class TestCoverage:
    def test_a_box_inside_another_is_covered(self):
        assert crud._covers(STATE, TOWN)

    def test_a_box_is_covered_by_itself(self):
        assert crud._covers(STATE, STATE)

    def test_a_box_poking_out_is_not_covered(self):
        assert not crud._covers(TOWN, STATE)

    def test_a_drag_that_misses_by_a_metre_still_counts(self):
        nudged = [c + 1e-5 for c in STATE]
        assert crud._covers(STATE, nudged)


class TestAlreadyHave:
    def test_an_installed_area_answers_for_a_box_inside_it(self, registry):
        registry["regions"].append(registry["region"]())
        assert crud._already_have(TOWN)["id"] == 1

    def test_a_download_in_flight_answers_too(self, registry):
        registry["regions"].append(registry["region"](status="downloading_dem"))
        assert crud._already_have(TOWN) is not None

    def test_a_failed_area_does_not_block_a_retry(self, registry):
        registry["regions"].append(registry["region"](status="error"))
        assert crud._already_have(TOWN) is None

    def test_an_area_being_cancelled_does_not_block_a_retry(self, registry):
        registry["regions"].append(registry["region"](status="cancelling"))
        assert crud._already_have(TOWN) is None

    def test_a_stalled_download_does_not_block_a_retry(self, registry):
        # Nothing is driving it anymore, so pointing at it would hang forever.
        registry["regions"].append(registry["region"](status="merging", stale=True))
        assert crud._already_have(TOWN) is None

    def test_neighbouring_areas_together_cover_a_box_across_their_border(self, registry):
        """Areas overlap and abut freely now; a box straddling two installed
        neighbours is already on the server, and building it again would be
        the duplicate the list used to fill up with."""
        registry["regions"].append(registry["region"](id=1, bbox=[-151.0, 39.0, -150.2, 41.0]))
        registry["regions"].append(registry["region"](id=2, bbox=[-150.2, 39.0, -149.0, 41.0]))
        answer = crud._already_have(TOWN)
        assert answer is not None and answer["id"] in (1, 2)

    def test_coverage_follows_an_areas_shape_not_its_envelope(self, registry):
        # An L-shaped area from an older version's merge: its bbox is the
        # envelope, and the empty corner of the L must not count as covered.
        ell = {"type": "Polygon", "coordinates": [[
            [-154.06, 36.99], [-147.04, 36.99], [-147.04, 38.0],
            [-153.0, 38.0], [-153.0, 41.0], [-154.06, 41.0], [-154.06, 36.99]]]}
        registry["regions"].append(registry["region"](geometry=ell))
        assert crud._already_have(TOWN) is None
        assert crud._already_have([-154.0, 39.0, -153.5, 40.0]) is not None

    def test_a_failed_area_asked_for_again_resumes(self, registry):
        """The same box again after a failure picks the failed area up, from
        its last finished phase, rather than starting a second copy."""
        registry["regions"].append(registry["region"](status="error", bbox=TOWN))
        answer = crud._already_have(TOWN)
        assert answer is not None and answer["status"] == "queued"


class TestDownloadEndpoint:
    def test_a_new_area_is_downloaded(self, client, user, registry):
        resp = client.post("/maps/regions/download", json={"bbox": STATE})
        assert resp.status_code == 200
        assert resp.json()["status"] == "downloading"
        assert len(registry["created"]) == 1
        assert registry["started"] == ["kick"]

    def test_an_area_already_installed_starts_nothing(self, client, user, registry):
        registry["regions"].append(registry["region"]())
        resp = client.post("/maps/regions/download", json={"bbox": TOWN})

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "exists"
        assert body["region"]["id"] == 1
        assert registry["created"] == []

    def test_a_second_request_joins_the_download_in_flight(self, client, user, registry):
        registry["regions"].append(registry["region"](status="downloading"))
        resp = client.post("/maps/regions/download", json={"bbox": TOWN})

        assert resp.json()["status"] == "downloading"
        assert resp.json()["region"]["id"] == 1
        assert registry["created"] == [], "one area, not two"

    def test_neighbouring_areas_get_names_that_tell_them_apart(self, client, user, registry):
        west = client.post("/maps/regions/download",
                           json={"bbox": [-153.0, 38.0, -152.0, 39.0]}).json()
        east = client.post("/maps/regions/download",
                           json={"bbox": [-149.0, 38.0, -148.0, 39.0]}).json()
        assert west["region"]["name"] == "Example State"
        assert east["region"]["name"] == "Example State (2)"

    def test_a_name_the_user_chose_is_kept(self, client, user, registry):
        resp = client.post("/maps/regions/download",
                           json={"bbox": STATE, "name": "Ski trip"})
        assert resp.json()["region"]["name"] == "Ski trip"
