# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The masters-only map store: adding an area's tiles, removing them, and the
overlay union. Built on small real archives, since what matters is which tiles
end up in the file go-pmtiles serves."""

import pytest
from pmtiles.tile import Compression, TileType, tileid_to_zxy, zxy_to_tileid
from pmtiles.writer import write
from shapely.geometry import box

from app.config import settings
from app.services import map_store, tile_archives


@pytest.fixture
def store(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    return tmp_path


def _archive(path, tiles: dict):
    """tiles: {(z, x, y): bytes}"""
    with write(str(path)) as w:
        for zxy in sorted(tiles, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*zxy), tiles[zxy])
        w.finalize({"tile_type": TileType.PNG, "tile_compression": Compression.NONE,
                    "min_zoom": 10, "max_zoom": 10, "min_lon_e7": 0, "min_lat_e7": 0,
                    "max_lon_e7": 0, "max_lat_e7": 0, "center_lon_e7": 0, "center_lat_e7": 0,
                    "center_zoom": 10}, {})
    return path


def _zxys(path):
    return {tileid_to_zxy(t) for t in tile_archives.tile_ids(path)}


def _tiles_in(lonlat_box, z=10):
    """Every z10 tile whose bounds intersect the box."""
    w, s, e, n = lonlat_box
    out = set()
    for x in range(2 ** z):
        tw, _, te, _ = map_store.tile_bounds(z, x, 0)
        if te < w or tw > e:
            continue
        for y in range(2 ** z):
            _, ts, _, tn = map_store.tile_bounds(z, x, y)
            if tn < s or ts > n:
                continue
            out.add((z, x, y))
    return out


A = (-121.0, 44.0, -120.0, 45.0)
B = (-120.0, 44.0, -119.0, 45.0)   # shares A's eastern edge


def test_publishing_an_area_adds_its_tiles_and_the_new_ones_win(store):
    master = map_store.master_path(map_store.MASTER_DEM)
    _archive(master, {(10, 1, 1): b"old", (10, 2, 2): b"old"})
    part = _archive(store / "part.pmtiles", {(10, 2, 2): b"new", (10, 3, 3): b"new"})
    assert map_store.publish(map_store.MASTER_DEM, part) == 3
    with open(master, "rb") as f:
        from pmtiles.reader import MmapSource, Reader
        r = Reader(MmapSource(f))
        assert r.get(10, 2, 2) == b"new"
        assert r.get(10, 1, 1) == b"old"
        assert r.header()["clustered"], "written in tile order, no cluster pass needed"


def test_removing_an_area_keeps_the_tiles_a_neighbour_still_touches(store):
    """The border tiles between two areas belong to both. Removing one must
    leave them for the other — and take everything only it needed."""
    a_tiles, b_tiles = _tiles_in(A), _tiles_in(B)
    master = map_store.master_path(map_store.MASTER_BASEMAP)
    _archive(master, {t: b"x" for t in a_tiles | b_tiles})
    map_store.drop_area(map_store.MASTER_BASEMAP, box(*A), box(*B))
    left = _zxys(master)
    assert left == b_tiles
    assert a_tiles & b_tiles, "the areas should share a border band"


def test_removing_the_last_area_removes_the_archive(store):
    """An empty master must stop serving what it held, not be left behind."""
    master = map_store.master_path(map_store.MASTER_CONTOURS)
    _archive(master, {t: b"x" for t in _tiles_in(A)})
    map_store.drop_area(map_store.MASTER_CONTOURS, box(*A), None)
    assert not master.exists()


def test_the_overlay_is_the_union_of_every_areas_parts(store, monkeypatch):
    """Parts are joined feature by feature (tippecanoe_writer.merge_overlays),
    never by tile id, which would drop one side of every border tile."""
    from app.services import tippecanoe_writer
    for rid, names in ((3, ["overlay.pmtiles"]), (5, ["overlay.pmtiles", "overlay-from-9.pmtiles"])):
        d = map_store.area_dir(rid)
        d.mkdir(parents=True)
        for n in names:
            (d / n).write_bytes(b"part")
    (map_store.area_dir(7)).mkdir()   # an area with no overlay yet
    seen = {}
    monkeypatch.setattr(tippecanoe_writer, "merge_overlays",
                        lambda parts, out, **k: seen.setdefault("parts", parts))
    map_store.rebuild_overlay()
    assert [p.split("/regions/")[1] for p in seen["parts"]] == [
        "3/overlay.pmtiles", "5/overlay-from-9.pmtiles", "5/overlay.pmtiles"]


def test_no_overlay_parts_left_removes_the_served_overlay(store):
    master = map_store.master_path(map_store.MASTER_OVERLAY)
    master.write_bytes(b"stale")
    assert map_store.rebuild_overlay() == 0
    assert not master.exists()
