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
from app.routes.regions import crud, download


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

    class _Thread:
        def __init__(self, target=None, args=(), daemon=False):
            state["started"].append(args)

        def start(self):
            pass

    monkeypatch.setattr(crud.threading, "Thread", _Thread)
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

    def test_a_merged_area_only_answers_for_its_exact_box(self, registry):
        # Its bbox is the envelope of the areas it combines; an L-shaped merge
        # has corners the envelope claims and no tile covers.
        merged = registry["region"](geometry={"type": "Polygon", "coordinates": []})
        registry["regions"].append(merged)
        assert crud._already_have(TOWN) is None
        assert crud._already_have(STATE) is not None


class TestDownloadEndpoint:
    def test_a_new_area_is_downloaded(self, client, user, registry):
        resp = client.post("/maps/regions/download", json={"bbox": STATE})
        assert resp.status_code == 200
        assert resp.json()["status"] == "downloading"
        assert len(registry["created"]) == 1
        assert len(registry["started"]) == 1

    def test_an_area_already_installed_starts_nothing(self, client, user, registry):
        registry["regions"].append(registry["region"]())
        resp = client.post("/maps/regions/download", json={"bbox": TOWN})

        assert resp.status_code == 200
        body = resp.json()
        assert body["status"] == "exists"
        assert body["region"]["id"] == 1
        assert registry["created"] == []
        assert registry["started"] == []

    def test_a_second_request_joins_the_download_in_flight(self, client, user, registry):
        registry["regions"].append(registry["region"](status="downloading"))
        resp = client.post("/maps/regions/download", json={"bbox": TOWN})

        assert resp.json()["status"] == "downloading"
        assert resp.json()["region"]["id"] == 1
        assert registry["started"] == [], "one extraction, not two"

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


class TestResumeInterrupted:
    """A build the server abandoned when it restarted.

    `download_and_merge` runs in a daemon thread, so a deploy, an OOM kill or a
    `docker compose up -d` on any other service ends it with nothing written
    down anywhere. The row went on reporting progress for a build that had not
    existed for hours, and the only way out was to delete an area that might
    have been most of the way through gigabytes of extraction and start again.
    """

    @pytest.fixture
    def resumable(self, monkeypatch):
        from app.routes.regions import download as download_module

        state = {"regions": [], "built": [], "settled": [], "purged": [], "cleared": []}

        def _update(region_id, status, **kwargs):
            state["settled"].append((region_id, status))
            for r in state["regions"]:
                if r["id"] == region_id:
                    r["status"] = status

        monkeypatch.setattr(download_module.region_registry, "list_active",
                            lambda: list(state["regions"]))
        monkeypatch.setattr(download_module.region_registry, "update_status", _update)
        monkeypatch.setattr(download_module.download_cancel, "clear",
                            lambda rid: state["cleared"].append(rid))
        monkeypatch.setattr(download_module, "download_and_merge",
                            lambda rid, bbox: state["built"].append(rid))
        monkeypatch.setattr(download_module, "purge_region",
                            lambda rid: state["purged"].append(("delete", rid)))
        monkeypatch.setattr(download_module, "purge_cancelled_region",
                            lambda rid: state["purged"].append(("cancel", rid)))

        class _Thread:
            def __init__(self, target=None, args=(), daemon=False, name=None):
                self._target, self._args = target, args

            def start(self):
                # Run it here: the point of the test is what the thread does,
                # and a real one would race the assertions.
                self._target(*self._args)

        monkeypatch.setattr(download_module.threading, "Thread", _Thread)
        return state

    def _region(self, region_id, status):
        return {"id": region_id, "name": f"Area {region_id}", "bbox": TOWN,
                "status": status, "progress": 40.0, "geometry": None,
                "detail": None, "error": None, "stale": False}

    @pytest.mark.parametrize(
        "status", ["downloading", "downloading_dem", "merging", "trails", "contours"],
    )
    def test_a_build_stopped_mid_phase_starts_again(self, resumable, status):
        resumable["regions"].append(self._region(7, status))

        assert download.resume_interrupted() == 1
        assert resumable["built"] == [7]

    def test_an_installed_area_is_left_alone(self, resumable):
        resumable["regions"].append(self._region(7, "installed"))

        assert download.resume_interrupted() == 0
        assert resumable["built"] == []

    def test_a_failed_area_is_not_retried_behind_the_users_back(self, resumable):
        # Restarting a build that errored would loop on whatever broke it, and
        # the user has a re-download button for the case where it was transient.
        resumable["regions"].append(self._region(7, "error"))

        assert download.resume_interrupted() == 0
        assert resumable["built"] == []

    def test_a_stale_cancel_marker_cannot_kill_the_new_run(self, resumable):
        # The dead run's marker file survives it, and the resumed build checks
        # for cancellation at its first phase boundary — so without clearing it
        # the resume would abort instantly and look like a failure.
        resumable["regions"].append(self._region(7, "trails"))

        download.resume_interrupted()

        assert resumable["cleared"] == [7]

    def test_an_area_that_was_merging_is_settled_not_rebuilt(self, resumable):
        # The download itself finished before `combining` began: folding
        # overlapping areas together is an optimisation, and re-running the
        # whole extraction to redo it would be minutes of work for nothing.
        resumable["regions"].append(self._region(7, "combining"))

        assert download.resume_interrupted() == 0
        assert resumable["built"] == []
        assert (7, "installed") in resumable["settled"]

    def test_a_teardown_caught_halfway_is_finished(self, resumable):
        resumable["regions"].append(self._region(7, "cancelling"))
        resumable["regions"].append(self._region(8, "deleting"))

        download.resume_interrupted()

        assert ("cancel", 7) in resumable["purged"]
        assert ("delete", 8) in resumable["purged"]

    def test_several_interrupted_builds_all_come_back(self, resumable):
        resumable["regions"].append(self._region(7, "downloading"))
        resumable["regions"].append(self._region(8, "contours"))

        assert download.resume_interrupted() == 2
        # In order, in one thread: each build is already parallel inside itself
        # and they share a disk and a merge lock.
        assert resumable["built"] == [7, 8]
