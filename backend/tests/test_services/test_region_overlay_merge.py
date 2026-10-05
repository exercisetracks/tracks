# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""How two downloaded areas are combined into the served overlay.

This is the seam. Two regions never partition the tile grid — a tile boundary
does not follow a user-drawn box, so any two areas that touch share the whole
band of tiles along their common edge, at every zoom. Combining those archives
by tile id keeps one region's version of each shared tile and throws the other's
away entirely, which draws as a hard line across the map where one area's trails
simply stop. It has to be a feature-level union, everywhere.

The other half of the same problem is on the way in: a region's archive must not
contain features from outside its own box, or the union double-draws every
feature two neighbours both happen to carry.
"""

import pytest

from app.services import region_merger, tippecanoe_writer


@pytest.fixture
def data_dir(tmp_path, monkeypatch):
    """A map-data directory with two regions' overlays already on disk."""
    monkeypatch.setattr(region_merger.settings, "map_data_dir", str(tmp_path))
    for region_id in (1, 2):
        d = tmp_path / "regions" / str(region_id)
        d.mkdir(parents=True)
        (d / "overlay.pmtiles").write_bytes(b"overlay-%d" % region_id)
    return tmp_path


@pytest.fixture
def calls(monkeypatch):
    """Record what the assembly asks of tile-join and of the tile-id merger."""
    recorded = {"tile_join": [], "custom_merge": []}

    def fake_merge_overlays(sources, output, **kwargs):
        recorded["tile_join"].append((list(sources), str(output)))
        # tile-join writes its output; later steps stat it.
        with open(output, "wb") as f:
            f.write(b"joined")
        return len(sources)

    def fake_merge_archives(sources, output, **kwargs):
        recorded["custom_merge"].append((list(sources), str(output), kwargs))
        with open(output, "wb") as f:
            f.write(b"merged")
        return 1

    monkeypatch.setattr(tippecanoe_writer, "merge_overlays", fake_merge_overlays)
    monkeypatch.setattr(region_merger.custom_merge, "merge_archives", fake_merge_archives)
    monkeypatch.setattr(region_merger, "_count_tiles", lambda p: 7)
    monkeypatch.setattr(region_merger, "_is_clustered", lambda p: True)
    return recorded


ACTIVE = [{"id": 1, "status": "installed"}, {"id": 2, "status": "installed"}]


class TestAssembleOverlay:
    def test_unions_every_region_in_one_tile_join(self, data_dir, calls):
        region_merger._assemble_overlay(ACTIVE)

        assert len(calls["tile_join"]) == 1
        sources, output = calls["tile_join"][0]
        assert [s.split("/")[-2] for s in sources] == ["1", "2"]
        assert output.endswith("master_overlay.pmtiles")

    def test_never_merges_the_overlay_by_tile_id(self, data_dir, calls):
        """The bug, stated as a test.

        A tile-id merge is last-wins: for every tile the two areas share it keeps
        one and discards the other, whole. Measured on a real pair of areas
        meeting at 39.84°N, that cost 3,513 tiles and ~2.5 MB of features, and cut
        one z11 tile from 86 kB to 99 bytes — an empty tile where a town's worth
        of trails had been.
        """
        region_merger._assemble_overlay(ACTIVE)
        assert calls["custom_merge"] == []

    def test_does_not_split_the_zoom_range(self, data_dir, calls):
        """No band is exempt.

        The earlier design tile-joined only z6-8, on the theory that regions are
        spatially disjoint above it. The shared-edge band is thinner at z15 than
        at z9, but it is never empty.
        """
        region_merger._assemble_overlay(ACTIVE)
        for _, _, kwargs in calls["custom_merge"]:
            assert "min_zoom" not in kwargs and "max_zoom" not in kwargs

    def test_a_single_region_is_still_assembled(self, data_dir, calls):
        region_merger._assemble_overlay([ACTIVE[0]])
        sources, _ = calls["tile_join"][0]
        assert len(sources) == 1

    def test_no_regions_removes_the_served_archive(self, data_dir, calls):
        served = data_dir / "master_overlay.pmtiles"
        served.write_bytes(b"stale")

        region_merger._assemble_overlay([])

        assert not served.exists()
        assert calls["tile_join"] == []

    def test_a_region_with_no_overlay_yet_is_skipped(self, data_dir, calls):
        (data_dir / "regions" / "2" / "overlay.pmtiles").unlink()

        region_merger._assemble_overlay(ACTIVE)

        sources, _ = calls["tile_join"][0]
        assert len(sources) == 1


class TestCountTiles:
    def test_an_unreadable_archive_is_not_an_empty_one(self, tmp_path):
        """-1, not 0.

        `_finalize_master` deletes the archive on 0, so a counting failure
        answering "empty" would delete a perfectly good master.
        """
        bad = tmp_path / "truncated.pmtiles"
        bad.write_bytes(b"not a pmtiles archive")
        assert region_merger._count_tiles(bad) == -1


class TestClipBoundingBox:
    def test_the_region_box_reaches_tippecanoe(self, tmp_path, monkeypatch):
        """Without this, an archive spills past the area it claims to cover.

        Overpass returns whole ways and relations that merely intersect the query
        box, so a trail crossing the edge comes back complete. Two neighbours then
        hold thousands of identical features, and the union draws each of them
        twice — measured at exactly 2x on a tile 0.16° outside the older area's
        own boundary.
        """
        layer = tmp_path / "trails.geojson"
        layer.write_text('{"type":"Feature"}\n')
        seen = {}
        monkeypatch.setattr(tippecanoe_writer, "_run",
                            lambda cmd, label, **kw: seen.setdefault("cmd", cmd))
        monkeypatch.setattr(tippecanoe_writer.os, "replace", lambda a, b: None)

        tippecanoe_writer.build_overlay(
            {"trails": layer}, tmp_path / "overlay.pmtiles",
            clip_bbox=[-150.5, 39.5, -149.9, 39.8],
        )

        assert "--clip-bounding-box=-150.5,39.5,-149.9,39.8" in seen["cmd"]

    def test_omitted_when_there_is_no_box(self, tmp_path, monkeypatch):
        """The global route archive has no region to clip to."""
        layer = tmp_path / "trails.geojson"
        layer.write_text('{"type":"Feature"}\n')
        seen = {}
        monkeypatch.setattr(tippecanoe_writer, "_run",
                            lambda cmd, label, **kw: seen.setdefault("cmd", cmd))
        monkeypatch.setattr(tippecanoe_writer.os, "replace", lambda a, b: None)

        tippecanoe_writer.build_overlay({"trails": layer}, tmp_path / "overlay.pmtiles")

        assert not any(c.startswith("--clip-bounding-box") for c in seen["cmd"])
