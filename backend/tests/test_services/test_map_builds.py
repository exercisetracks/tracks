# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The map build queue: one writer, resumable phases, honest failures.

The registry is a stand-in holding rows in memory; the phases' heavy work
(downloads, tippecanoe, contour tracing) is replaced by recorders, since what
these pin is the queue's decisions — what runs, in what order, what is skipped
on a resume, and what a removal hands to the areas that remain.
"""

import pytest
from shapely.geometry import box, shape

from app.config import settings
from app.services import download_cancel, map_builds, map_store, region_registry


class Rows:
    def __init__(self):
        self.rows = {}
        self.calls = []

    def add(self, rid, status, bbox, **kw):
        self.rows[rid] = {"id": rid, "name": f"Area {rid}", "bbox": list(bbox), "geometry": None,
                          "status": status, "progress": 0.0, "detail": None, "error": None,
                          "stale": False, "phases_done": [], "owned": None, **kw}
        return self.rows[rid]


@pytest.fixture
def reg(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    rows = Rows()
    live = lambda: [r for r in rows.rows.values() if r["status"] != "deleted"]  # noqa: E731

    def update(rid, status, **kw):
        r = rows.rows.get(rid)
        if r is None or r["status"] == "deleted":
            return
        r["status"] = status
        for k in ("error", "detail", "progress", "size_bytes"):
            if kw.get(k) is not None:
                r[k] = kw[k]

    def add_phase(rid, phase):
        if phase not in rows.rows[rid]["phases_done"]:
            rows.rows[rid]["phases_done"].append(phase)

    def record_attempt(rid):
        rows.rows[rid]["phases_done"].append("attempt")
        return rows.rows[rid]["phases_done"].count("attempt")

    monkeypatch.setattr(region_registry, "list_active", live)
    monkeypatch.setattr(region_registry, "get", lambda rid: rows.rows.get(rid))
    monkeypatch.setattr(region_registry, "update_status", update)
    monkeypatch.setattr(region_registry, "add_phase_done", add_phase)
    monkeypatch.setattr(region_registry, "record_attempt", record_attempt)
    monkeypatch.setattr(region_registry, "reset_phases", lambda rid: rows.rows[rid].update(phases_done=[]))
    monkeypatch.setattr(region_registry, "set_owned", lambda rid, g: rows.rows[rid].update(owned=g))
    monkeypatch.setattr(region_registry, "heartbeat", lambda rid: None)
    monkeypatch.setattr(region_registry, "mark_deleted", lambda rid: rows.rows[rid].update(status="deleted"))
    monkeypatch.setattr(map_store, "write_reload_trigger", lambda: None)
    monkeypatch.setattr(map_builds, "preflight_disk", lambda bbox: None)
    monkeypatch.setattr(map_builds, "PARALLEL", False)
    return rows


@pytest.fixture
def phases(monkeypatch, reg):
    """Every phase's work replaced by a recorder that writes its output file."""
    ran = []

    def extract(bbox, out, progress=None, cancel=None):
        ran.append("extract")
        out.mkdir(parents=True, exist_ok=True)
        (out / "source.pmtiles").write_bytes(b"s")
        (out / "dem.pmtiles").write_bytes(b"d")

    def overlay(owned, out, progress=None, cancel=None):
        ran.append(("overlay", owned.bounds))
        out.write_bytes(b"o")
        return True

    from app.routes.regions import thematic
    from app.services import region_downloader
    monkeypatch.setattr(region_downloader, "extract_layers", extract)
    monkeypatch.setattr(thematic, "build_overlay_part", overlay)
    monkeypatch.setattr(map_store, "publish", lambda name, part, **k: ran.append(("publish", name)))
    monkeypatch.setattr(map_store, "rebuild_overlay", lambda **k: ran.append("rebuild_overlay"))
    monkeypatch.setattr(map_store, "drop_area", lambda name, area, rem: ran.append(("drop", name)))
    monkeypatch.setattr(map_builds, "_build_contours", lambda *a: ran.append("contours"))
    monkeypatch.setattr(map_builds, "_index_pois", lambda src: None)
    return ran


class TestOrder:
    def test_removals_come_first_then_abandoned_builds_then_the_queue(self, reg):
        """Removals free disk; a build a dead worker left behind was asked for
        before anything still queued."""
        reg.add(1, "queued", (0, 0, 1, 1))
        reg.add(2, "trails", (0, 0, 1, 1))
        reg.add(3, "deleting", (0, 0, 1, 1))
        assert map_builds.next_job()["id"] == 3
        reg.rows[3]["status"] = "deleted"
        assert map_builds.next_job()["id"] == 2
        reg.rows[2]["status"] = "installed"
        assert map_builds.next_job()["id"] == 1


class TestBuild:
    def test_a_build_runs_every_phase_and_installs(self, reg, phases):
        job = reg.add(1, "queued", (-121, 44, -120, 45))
        # Contours are traced from master_dem, which the (stand-in) DEM
        # publish would have written.
        map_store.master_path(map_store.MASTER_DEM).write_bytes(b"dem")
        map_builds.build(job)
        assert reg.rows[1]["status"] == "installed"
        assert phases == [
            "extract", ("overlay", (-121.0, 44.0, -120.0, 45.0)),
            ("publish", "master_dem"), "contours", ("publish", "master_basemap_detail"),
            "rebuild_overlay",
        ]
        assert (map_store.area_dir(1) / "overlay.pmtiles").exists()
        assert not map_store.work_dir(1).exists(), "the scratch goes once it is published"

    def test_the_progress_bar_only_moves_forward(self, reg, phases, monkeypatch):
        """One percentage for the whole build: each step owns a slice, so the
        bar no longer resets to 0 at every step or sits at 50% while merging."""
        seen = []
        original = region_registry.update_status

        def record(rid, status, **kw):
            if kw.get("progress") is not None and status != "installed":
                seen.append(kw["progress"])
            original(rid, status, **kw)
        monkeypatch.setattr(region_registry, "update_status", record)
        map_store.master_path(map_store.MASTER_DEM).write_bytes(b"dem")
        map_builds.build(reg.add(1, "queued", (-121, 44, -120, 45)))
        assert seen == sorted(seen), seen
        assert seen[0] < 5 and seen[-1] >= 92

    def test_a_resumed_build_starts_after_its_last_finished_phase(self, reg, phases):
        """A restart used to start the whole build again, re-downloading
        gigabytes that were already on disk (audit F16)."""
        job = reg.add(1, "trails", (-121, 44, -120, 45), phases_done=["attempt", "extract", "overlay"])
        work = map_store.work_dir(1)
        work.mkdir(parents=True)
        for f in ("source.pmtiles", "dem.pmtiles", "overlay.pmtiles"):
            (work / f).write_bytes(b"x")
        map_builds.build(job)
        assert "extract" not in phases
        assert not any(isinstance(p, tuple) and p[0] == "overlay" for p in phases)
        assert reg.rows[1]["status"] == "installed"

    def test_a_build_that_keeps_dying_stops_trying(self, reg, phases):
        """One that kills its process every time (out of memory) must not loop
        on every restart forever."""
        job = reg.add(1, "downloading", (0, 0, 1, 1), phases_done=["attempt"] * map_builds.MAX_ATTEMPTS)
        map_builds.build(job)
        assert reg.rows[1]["status"] == "error"
        assert phases == []

    def test_a_failed_phase_is_named_and_nothing_is_installed(self, reg, phases, monkeypatch):
        """An area that lost its trails used to finish as installed with no
        trails on it (audit F20)."""
        from app.routes.regions import thematic

        def broken(*a, **k):
            raise RuntimeError("tippecanoe exited 1")
        monkeypatch.setattr(thematic, "build_overlay_part", broken)
        job = reg.add(1, "queued", (0, 0, 1, 1))
        map_builds.build(job)
        assert reg.rows[1]["status"] == "error"
        assert reg.rows[1]["error"].startswith("Building trails failed")
        assert "extract" in reg.rows[1]["phases_done"], "the download is kept for the retry"

    def test_a_cancelled_build_removes_what_it_published(self, reg, phases, monkeypatch):
        job = reg.add(1, "queued", (-121, 44, -120, 45))

        def cancel_during(name, part, **k):
            phases.append(("publish", name))
            map_store.master_path(name).write_bytes(b"published")
            reg.rows[1]["status"] = "cancelling"
        monkeypatch.setattr(map_store, "publish", cancel_during)
        monkeypatch.setattr(download_cancel.CancelToken, "raise_if_cancelled",
                            lambda self: (_ for _ in ()).throw(download_cancel.DownloadCancelled())
                            if reg.rows[1]["status"] == "cancelling" else None)
        map_builds.build(job)
        assert reg.rows[1]["status"] == "deleted"
        assert ("drop", "master_dem") in phases


class TestOverlayUpgrade:
    def test_an_area_built_by_an_older_overlay_builder_rebuilds_only_its_overlay(self, reg, phases):
        """A fix to the overlay (public land past the box) reaches areas already
        installed without downloading their maps again."""
        reg.add(1, "installed", (-121, 44, -120, 45), owned=map_store.geojson(box(-121, 44, -120.5, 45)))
        map_builds.upgrade_overlays()
        assert reg.rows[1]["status"] == "queued"
        map_builds.build(reg.rows[1])
        assert reg.rows[1]["status"] == "installed"
        assert phases == [("overlay", (-121.0, 44.0, -120.5, 45.0)), "rebuild_overlay"], \
            "only the overlay, over the ground it already owned"
        assert (map_store.area_dir(1) / "overlay.version").read_text() == str(map_builds.OVERLAY_VERSION)
        map_builds.upgrade_overlays()
        assert reg.rows[1]["status"] == "installed", "once is enough"


class TestOwnership:
    def test_an_area_owns_only_ground_no_installed_area_covers(self, reg):
        """Two copies of a trail in the overlap would be drawn twice: the
        master concatenates the parts."""
        reg.add(1, "installed", (-121, 44, -120, 45))
        owned = map_builds._ground_to_own(2, [-120.5, 44, -119, 45])
        assert owned.bounds == (-120.0, 44.0, -119.0, 45.0)

    def test_an_area_inside_another_owns_nothing(self, reg):
        reg.add(1, "installed", (-121, 44, -120, 45))
        assert map_builds._ground_to_own(2, [-120.8, 44.2, -120.2, 44.8]) is None

    def test_removing_an_area_hands_its_overlap_to_the_oldest_neighbour(self, reg, phases, monkeypatch):
        """The neighbour was cut around this area's ground; without the
        handover, the overlap would lose its trails though it is still covered."""
        reg.add(2, "installed", (-121, 44, -120, 45),
                owned=map_store.geojson(box(-121, 44, -120, 45)))
        reg.add(3, "installed", (-120.5, 44, -119, 45),
                owned=map_store.geojson(box(-120, 44, -119, 45)))
        old = reg.add(1, "deleting", (-120.8, 44, -119.5, 45),
                      owned=map_store.geojson(box(-120.8, 44, -119.5, 45)))
        d = map_store.area_dir(1)
        d.mkdir(parents=True)
        (d / "overlay.pmtiles").write_bytes(b"o")
        map_builds.teardown(old)
        assert reg.rows[1]["status"] == "deleted"
        handed = [p for p in phases if isinstance(p, tuple) and p[0] == "overlay"]
        assert len(handed) == 1
        assert (map_store.area_dir(2) / "overlay-from-1.pmtiles").exists(), "the oldest heir"
        assert shape(reg.rows[2]["owned"]).covers(box(-120.8, 44, -120, 45))
        assert not map_store.area_dir(1).exists()


class TestCancelSignal:
    def test_a_cancel_is_read_from_the_areas_status(self, reg):
        """Every worker sees the database; the old marker file could outlive
        its download and kill the next one."""
        reg.add(1, "trails", (0, 0, 1, 1))
        assert not download_cancel.is_cancel_requested(1)
        reg.rows[1]["status"] = "cancelling"
        assert download_cancel.is_cancel_requested(1)
        assert download_cancel.is_cancel_requested(99), "a vanished area stops its build"


@pytest.mark.real_transaction
def test_only_one_process_builds_at_a_time(monkeypatch):
    """Two uvicorn workers each run a builder; the advisory lock lets exactly
    one work the queue, so masters are never rewritten under each other (F12)."""
    from sqlalchemy import text

    from app.database import engine
    monkeypatch.setattr(map_builds, "migrate_legacy_parts", lambda: None)
    monkeypatch.setattr(map_builds, "recover_missing_masters", lambda: None)
    monkeypatch.setattr(map_builds, "sweep_orphan_pois", lambda: None)
    monkeypatch.setattr(map_builds, "next_job", lambda: None)
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as other:
        other.execute(text("SELECT pg_advisory_lock(:k)"), {"k": map_builds.LOCK_KEY})
        try:
            assert map_builds.drain() is False
        finally:
            other.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": map_builds.LOCK_KEY})
    assert map_builds.drain() is True
