# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""How many processes draw contours at once.

By cores alone, a many-core server with modest memory started more workers
than it could hold; the kernel killed one, and the whole build failed with
"A process in the process pool was terminated abruptly while the future was
running or pending" — shown on the map page as a contour error.
"""
from app.services.contour_generator import builder


def test_memory_caps_the_pool_below_the_core_count(monkeypatch):
    monkeypatch.setattr(builder.os, "cpu_count", lambda: 32)
    # 2 GB free: three quarters of it fits three workers at the budget.
    monkeypatch.setattr(builder, "_available_bytes", lambda: 2 * 1024**3)
    assert builder._worker_count(10_000) == 3


def test_there_is_always_one_worker(monkeypatch):
    monkeypatch.setattr(builder.os, "cpu_count", lambda: 8)
    monkeypatch.setattr(builder, "_available_bytes", lambda: 10 * 1024**2)
    assert builder._worker_count(10_000) == 1


def test_without_a_memory_reading_the_cores_decide(monkeypatch):
    monkeypatch.setattr(builder.os, "cpu_count", lambda: 6)
    monkeypatch.setattr(builder, "_available_bytes", lambda: None)
    assert builder._worker_count(10_000) == 6
    assert builder._worker_count(2) == 2


def test_the_cached_reader_returns_what_pmtiles_does(tmp_path):
    """CachedReader replaces pmtiles' Reader.get for speed only: every tile,
    present or absent, must come back byte-identical."""
    from pmtiles.reader import MmapSource, Reader
    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import write

    from app.services.contour_generator.dem import CachedReader

    path = tmp_path / "t.pmtiles"
    tiles = {(10, x, y): bytes([x % 251, y % 251]) * 3 for x in range(150, 190) for y in range(300, 340)}
    with write(str(path)) as w:
        for (z, x, y), data in sorted(tiles.items(), key=lambda kv: zxy_to_tileid(*kv[0])):
            w.write_tile(zxy_to_tileid(z, x, y), data)
        w.finalize({"tile_type": TileType.PNG, "tile_compression": Compression.NONE,
                    "min_zoom": 10, "max_zoom": 10, "min_lon": -180.0, "max_lon": 180.0,
                    "min_lat": -85.0, "max_lat": 85.0, "center_lon": 0.0, "center_lat": 0.0,
                    "center_zoom": 10}, {})
    with open(path, "rb") as f:
        plain, cached = Reader(MmapSource(f)), CachedReader(MmapSource(f))
        for z, x, y in list(tiles) + [(10, 0, 0), (9, 75, 150)]:
            assert cached.get(z, x, y) == plain.get(z, x, y)


def test_a_failed_rebuild_keeps_the_previous_contours(tmp_path, monkeypatch):
    """A retrace that fails or is killed must leave the area its old contours.
    The builder used to delete them before starting, so a failed rebuild took
    the contours off the whole area at the next master assembly."""
    from app.services.contour_generator import builder

    dem = tmp_path / "dem.pmtiles"
    dem.write_bytes(b"not a pmtiles archive")
    out = tmp_path / "contours.pmtiles"
    out.write_bytes(b"previous contours")
    try:
        builder.build_contours_pmtiles(dem, out)
    except Exception:
        pass
    assert out.read_bytes() == b"previous contours"
