# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Tests for the tippecanoe overlay writer (replaces the old pure-Python tiler).

The NDJSON contract is checked unconditionally; the build_overlay / tile-join
paths are skipped when the binaries aren't installed (host dev box) — they run in
the backend container, which ships tippecanoe + tile-join.
"""

import json
import shutil

import pytest

from shapely.geometry import LineString, Polygon

from app.services import tippecanoe_writer as tw

HAS_TIPPECANOE = shutil.which("tippecanoe") is not None
HAS_TILEJOIN = shutil.which("tile-join") is not None


def _header_max_zoom(path):
    from pmtiles.reader import MmapSource, Reader
    with open(path, "rb") as f:
        return Reader(MmapSource(f)).header()["max_zoom"]


def _assert_is_pmtiles(path):
    # Guards the tmp-extension bug: tippecanoe/tile-join pick output format from
    # the file extension, so a wrong temp suffix silently emits MBTiles (SQLite).
    with open(path, "rb") as f:
        assert f.read(7) == b"PMTiles", f"{path} is not a PMTiles archive"


def test_features_to_ndjson_writes_minzoom_and_props(tmp_path):
    feats = [
        (LineString([(0, 0), (1, 1)]), {"kind": "path", "min_zoom": 9}, 9),
        (Polygon([(0, 0), (0, 1), (1, 1), (1, 0), (0, 0)]), {"kind": "wood"}, 11),
    ]
    out = tmp_path / "trails.ndjson"
    n = tw.features_to_ndjson(feats, out, max_zoom=15)
    assert n == 2

    lines = out.read_text().splitlines()
    assert len(lines) == 2
    line, poly = (json.loads(s) for s in lines)

    # tippecanoe per-feature minzoom carries the LOD; properties are preserved.
    assert line["type"] == "Feature"
    assert line["tippecanoe"] == {"minzoom": 9}
    assert line["properties"]["kind"] == "path"
    assert line["geometry"]["type"] == "LineString"
    assert poly["tippecanoe"] == {"minzoom": 11}
    assert poly["geometry"]["type"] == "Polygon"


def test_features_to_ndjson_clamps_minzoom_to_max(tmp_path):
    out = tmp_path / "x.ndjson"
    tw.features_to_ndjson([(LineString([(0, 0), (1, 0)]), {}, 30)], out, max_zoom=12)
    assert json.loads(out.read_text())["tippecanoe"]["minzoom"] == 12


def test_features_to_ndjson_appends(tmp_path):
    """Chunked builds append each cell's features to the same per-layer file."""
    out = tmp_path / "a.ndjson"
    tw.features_to_ndjson([(LineString([(0, 0), (1, 0)]), {}, 9)], out)
    tw.features_to_ndjson([(LineString([(2, 2), (3, 3)]), {}, 9)], out)
    assert len(out.read_text().splitlines()) == 2


def test_features_to_ndjson_skips_empty_geometry(tmp_path):
    out = tmp_path / "e.ndjson"
    n = tw.features_to_ndjson(
        [(None, {}, 9), (LineString(), {}, 9), (LineString([(0, 0), (1, 0)]), {}, 9)],
        out,
    )
    assert n == 1


def test_build_overlay_no_layers_is_noop(tmp_path):
    out = tmp_path / "overlay.pmtiles"
    assert tw.build_overlay({}, out) == 0
    assert not out.exists()

    empty = tmp_path / "empty.ndjson"
    empty.write_text("")
    assert tw.build_overlay({"trails": empty}, out) == 0


@pytest.mark.skipif(not HAS_TIPPECANOE, reason="tippecanoe not installed")
def test_build_overlay_builds_multilayer_pmtiles(tmp_path):
    trails = tmp_path / "trails.ndjson"
    water = tmp_path / "water.ndjson"
    tw.features_to_ndjson(
        [(LineString([(-122.4, 37.7), (-122.3, 37.8)]), {"kind": "path"}, 9)], trails)
    tw.features_to_ndjson(
        [(LineString([(-122.4, 37.7), (-122.35, 37.75)]), {"kind": "stream"}, 11)], water)

    out = tmp_path / "overlay.pmtiles"
    n = tw.build_overlay({"trails": trails, "water": water}, out, max_zoom=14)
    assert n == 2
    assert out.exists() and out.stat().st_size > 0
    _assert_is_pmtiles(out)
    assert _header_max_zoom(out) == 14


@pytest.mark.skipif(not HAS_TILEJOIN, reason="tile-join not installed")
@pytest.mark.skipif(not HAS_TIPPECANOE, reason="tippecanoe not installed")
def test_merge_overlays_joins_regions(tmp_path):
    def _overlay(name, line):
        nd = tmp_path / f"{name}.ndjson"
        tw.features_to_ndjson([(LineString(line), {"kind": "path"}, 9)], nd)
        out = tmp_path / f"{name}.pmtiles"
        tw.build_overlay({"trails": nd}, out, max_zoom=12)
        return str(out)

    a = _overlay("a", [(-122.4, 37.7), (-122.3, 37.8)])
    b = _overlay("b", [(-71.1, 42.3), (-71.0, 42.4)])
    master = tmp_path / "master_overlay.pmtiles"
    assert tw.merge_overlays([a, b], master) == 2
    assert master.exists() and master.stat().st_size > 0
    _assert_is_pmtiles(master)


def test_merge_overlays_single_source_copies(tmp_path):
    src = tmp_path / "one.pmtiles"
    src.write_bytes(b"PMTILES-FAKE-CONTENT")
    out = tmp_path / "master_overlay.pmtiles"
    assert tw.merge_overlays([str(src)], out) == 1
    assert out.read_bytes() == b"PMTILES-FAKE-CONTENT"


def test_merge_overlays_no_sources(tmp_path):
    out = tmp_path / "master_overlay.pmtiles"
    assert tw.merge_overlays([], out) == 0
    assert not out.exists()


@pytest.mark.skipif(not HAS_TILEJOIN, reason="tile-join not installed")
@pytest.mark.skipif(not HAS_TIPPECANOE, reason="tippecanoe not installed")
def test_joining_only_shared_tiles_matches_a_full_tile_join(tmp_path):
    """The shared-tiles-only merge is a speed-up, not a different answer: every
    tile, shared or not, decodes to the same features as tile-joining it all —
    the seam along a common edge included."""
    import gzip
    import subprocess

    import mapbox_vector_tile
    from pmtiles.reader import MmapSource, Reader, all_tiles

    def _overlay(name, line):
        nd = tmp_path / f"{name}.ndjson"
        tw.features_to_ndjson([(LineString(line), {"kind": "path"}, 6)], nd)
        out = tmp_path / f"{name}.pmtiles"
        tw.build_overlay({"trails": nd}, out, max_zoom=12)
        return str(out)

    # Two areas side by side: they share low-zoom tiles and the edge band.
    a = _overlay("a", [(-121.40, 44.00), (-121.21, 44.10)])
    b = _overlay("b", [(-121.19, 44.00), (-121.00, 44.10)])
    full = tmp_path / "full.pmtiles"
    subprocess.run(["tile-join", "-o", str(full), "--force", "--no-tile-size-limit", a, b],
                   check=True, capture_output=True)
    hybrid = tmp_path / "hybrid.pmtiles"
    tw.merge_overlays([a, b], hybrid)

    def tiles(path):
        with open(path, "rb") as f:
            out = {}
            for zxy, data in all_tiles(Reader(MmapSource(f)).get_bytes):
                layers = mapbox_vector_tile.decode(gzip.decompress(data))
                out[zxy] = sorted(
                    (name, str(feat["geometry"])) for name, layer in layers.items() for feat in layer["features"]
                )
            return out

    expected, got = tiles(full), tiles(hybrid)
    assert got.keys() == expected.keys()
    assert any(len(v) > 1 for v in expected.values()), "the areas should share a tile"
    for zxy in expected:
        assert got[zxy] == expected[zxy], zxy
