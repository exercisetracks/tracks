# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Reading a PMTiles archive's real zoom range off its header.

These two numbers decide whether a native client overzooms the deepest tile it
has or draws a blank map, so the parsing is deliberately paranoid: every way a
file can fail to answer the question has to come back as "no answer" rather than
as a plausible-looking range.
"""

import pytest

from app.services.tile_coverage import archive_zooms, tileset_zooms


def write_archive(path, min_zoom, max_zoom, *, magic=b"PMTiles", spec=3, size=127):
    """A PMTiles v3 header, and nothing else.

    Enough for coverage: everything past byte 127 is the directory and the tile
    data, which this never reads.
    """
    header = bytearray(127)
    header[0:7] = magic
    header[7] = spec
    header[100] = min_zoom
    header[101] = max_zoom
    path.write_bytes(bytes(header)[:size])
    return path


class TestArchiveZooms:
    def test_reads_the_declared_range(self, tmp_path):
        p = write_archive(tmp_path / "a.pmtiles", 3, 12)
        assert archive_zooms(p) == (3, 12)

    def test_missing_file_is_no_answer(self, tmp_path):
        assert archive_zooms(tmp_path / "nope.pmtiles") is None

    def test_directory_is_no_answer(self, tmp_path):
        """`open` on a directory raises IsADirectoryError, an OSError — which is
        the same "cannot promise anything" as absent, not a 500."""
        assert archive_zooms(tmp_path) is None

    @pytest.mark.parametrize("kwargs", [
        {"magic": b"NOTPMTL"},
        {"spec": 4},
        {"size": 40},
    ])
    def test_anything_unreadable_is_no_answer(self, tmp_path, kwargs):
        p = write_archive(tmp_path / "a.pmtiles", 3, 12, **kwargs)
        assert archive_zooms(p) is None

    def test_backwards_range_is_rejected(self, tmp_path):
        """A max below the min would widen the source rather than narrow it —
        the exact failure this module exists to prevent, arriving from the file
        instead of from the style."""
        p = write_archive(tmp_path / "a.pmtiles", 12, 3)
        assert archive_zooms(p) is None


class TestTilesetZooms:
    def test_a_tileset_is_normally_one_file_of_the_same_name(self, tmp_path):
        write_archive(tmp_path / "master_routes.pmtiles", 3, 12)
        assert tileset_zooms(tmp_path, "master_routes") == (3, 12)

    def test_nothing_on_disk_means_nothing_promised(self, tmp_path):
        assert tileset_zooms(tmp_path, "master_overlay") is None

    def test_basemap_spans_both_of_its_archives(self, tmp_path):
        """Caddy serves one `basemap` source from two files split at z13, so its
        real reach is the union — this is the number that has to move when a
        region is downloaded."""
        write_archive(tmp_path / "planet_basemap.pmtiles", 0, 12)
        write_archive(tmp_path / "master_basemap_detail.pmtiles", 13, 15)
        assert tileset_zooms(tmp_path, "basemap") == (0, 15)

    def test_basemap_stops_at_the_overview_before_a_region_is_downloaded(self, tmp_path):
        """The stock install. Nothing has z13-15, so promising it is what turns
        the map blank on the phone."""
        write_archive(tmp_path / "planet_basemap.pmtiles", 0, 12)
        assert tileset_zooms(tmp_path, "basemap") == (0, 12)
