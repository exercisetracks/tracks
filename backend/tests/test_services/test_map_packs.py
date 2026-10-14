# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Map packs: what goes in a cell's pack, what a manifest offers, and the
version rule that keeps a phone from storing a pack that changed under it."""

import random

import pytest
from pmtiles.tile import Compression, TileType, tileid_to_zxy, zxy_to_tileid
from pmtiles.writer import write

from app.config import settings
from app.services import map_packs, map_store, region_registry, tile_archives


def _archive(path, zxys, payload=lambda z, x, y: f"{z}/{x}/{y}".encode(), minz=0, maxz=15):
    path.parent.mkdir(parents=True, exist_ok=True)
    with write(str(path)) as w:
        for zxy in sorted(zxys, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*zxy), payload(*zxy))
        w.finalize({"tile_type": TileType.MVT, "tile_compression": Compression.NONE,
                    "min_zoom": minz, "max_zoom": maxz, "min_lon_e7": -1800000000,
                    "min_lat_e7": -850000000, "max_lon_e7": 1800000000, "max_lat_e7": 850000000,
                    "center_lon_e7": 0, "center_lat_e7": 0, "center_zoom": 0}, {})
    return path


def _under(x8, y8, minz, maxz):
    out = []
    for z in range(minz, maxz + 1):
        k = 2 ** (z - 8)
        out += [(z, x8 * k + dx, y8 * k + dy) for dx in range(k) for dy in range(k)]
    return out


class TestRanges:
    @pytest.mark.parametrize("seed", range(5))
    def test_a_cells_tiles_are_a_few_contiguous_id_ranges(self, seed):
        """The Hilbert property everything here rests on: an aligned block is
        one run of ids at each zoom."""
        rnd = random.Random(seed)
        x, y = rnd.randrange(256), rnd.randrange(256)
        expected = {zxy_to_tileid(*t) for t in _under(x, y, 8, 12)}
        got = set()
        for lo, hi in tile_archives.descendant_ranges(8, x, y, 8, 12):
            got |= set(range(lo, hi))
        assert got == expected

    def test_sizes_come_from_the_directory_alone(self, tmp_path):
        tiles = _under(44, 90, 8, 11) + _under(45, 90, 8, 11)
        path = _archive(tmp_path / "a.pmtiles", tiles, payload=lambda z, x, y: b"x" * (z + 1))
        want = sum(z + 1 for z, x, _ in tiles if (x >> (z - 8)) == 44)
        assert tile_archives.bytes_in_ranges(path, tile_archives.descendant_ranges(8, 44, 90, 8, 11)) == want


@pytest.fixture
def server(tmp_path, monkeypatch):
    """A server with the planet basemap and one installed area in cell 8/44/90."""
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    _archive(tmp_path / "planet_basemap.pmtiles",
             [(z, x, y) for z in range(0, 4) for x in range(2 ** z) for y in range(2 ** z)]
             + _under(44, 90, 8, 12) + _under(45, 90, 8, 12))
    _archive(map_store.master_path(map_store.MASTER_BASEMAP), _under(44, 90, 13, 14))
    _archive(map_store.master_path(map_store.MASTER_OVERLAY), _under(44, 90, 8, 14))
    w, s, e, n = map_packs.cell_bounds(44, 90)
    area = {"id": 1, "name": "A", "bbox": [w + 0.1, s + 0.1, e - 0.1, n - 0.1], "geometry": None,
            "status": "installed", "updated_at": "t1", "phases_done": [], "owned": None}
    rows = [area]
    monkeypatch.setattr(region_registry, "list_active", lambda: list(rows))
    return {"area": area, "rows": rows, "cell": (w, s, e, n)}


def test_a_cell_pack_holds_its_cells_tiles_and_nothing_else(server):
    m = map_packs.manifest(list(server["cell"]))
    entry = next(c for c in m["cells"] if c["cell"] == "8/44/90")["layers"]["basemap"]
    path = map_packs.pack_file("8/44/90/basemap", entry["version"])
    got = {tileid_to_zxy(t) for t in tile_archives.tile_ids(path)}
    assert got == set(_under(44, 90, 8, 14)), "planet z8-12 plus the area's z13-14 detail"


def test_off_the_installed_ground_only_the_basemap_is_offered(server):
    """Trails and contours exist only where an area was downloaded; offering
    them elsewhere would promise a phone layers that come back empty."""
    w, s, e, n = map_packs.cell_bounds(45, 90)
    cell = next(c for c in map_packs.manifest([w + 0.01, s + 0.01, e - 0.01, n - 0.01])["cells"]
                if c["cell"] == "8/45/90")
    assert set(cell["layers"]) == {"basemap"}
    assert cell["covered"] is False


def test_a_pack_asked_for_by_an_old_version_is_refused(server):
    """The phone checks every pack against the sha256 it was promised; a pack
    rebuilt between the manifest and the download must be a clear 'ask again',
    not a file that fails verification."""
    m = map_packs.manifest(list(server["cell"]))
    v = next(c for c in m["cells"] if c["cell"] == "8/44/90")["layers"]["overlay"]["version"]
    server["area"]["updated_at"] = "t2"   # the area was rebuilt
    with pytest.raises(map_packs.StaleVersion):
        map_packs.pack_file("8/44/90/overlay", v)


def test_a_change_elsewhere_leaves_this_cells_packs_alone(server):
    """Saving the next state over must not invalidate every pack in the
    country — only cells near the area that changed."""
    before = map_packs.cell_version("overlay", 44, 90, server["rows"])
    far = {"id": 2, "name": "B", "bbox": [10, 10, 11, 11], "geometry": None,
           "status": "installed", "updated_at": "t9"}
    assert map_packs.cell_version("overlay", 44, 90, server["rows"] + [far]) == before


def test_the_cache_keeps_to_its_cap(server, monkeypatch):
    monkeypatch.setattr(map_packs, "cache_cap", lambda: 1)
    m = map_packs.manifest(list(server["cell"]))
    layers = next(c for c in m["cells"] if c["cell"] == "8/44/90")["layers"]
    for name in ("basemap", "overlay"):
        map_packs.pack_file(f"8/44/90/{name}", layers[name]["version"])
    left = [p for p in map_packs.packs_dir().rglob("*.pmtiles")]
    assert len(left) <= 1
