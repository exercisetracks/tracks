# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The web map's one-request-per-tile source: archives joined into one MVT."""

import gzip

import mapbox_vector_tile
import pytest

from app.services import merged_tiles


def _tile(layers):
    return mapbox_vector_tile.encode(
        [{"name": name, "features": feats} for name, feats in layers.items()],
        default_options={"y_coord_down": True},
    )


def _line(coords, **props):
    return {"geometry": {"type": "LineString", "coordinates": coords}, "properties": props}


@pytest.fixture
def archives(monkeypatch):
    """Archives as a dict of (name, z, x, y) -> tile bytes, read by _raw."""
    store = {}
    monkeypatch.setattr(merged_tiles, "_raw", lambda name, z, x, y: store.get((name, z, x, y)))
    monkeypatch.setattr(merged_tiles, "version", lambda: "test")
    merged_tiles._cache.clear()
    yield store
    merged_tiles._cache.clear()


def test_a_renamed_layer_keeps_its_features():
    """Only the name changes: the overlay's `water` must not land on top of the
    basemap's `water`, which a decoder would keep only one of."""
    raw = _tile({"water": [_line([(0, 0), (100, 100)], kind="river")]})
    out = mapbox_vector_tile.decode(merged_tiles.rename_layers(raw, "ov_"))
    assert list(out) == ["ov_water"]
    assert out["ov_water"]["features"][0]["properties"] == {"kind": "river"}


def test_every_archive_is_one_tile_under_its_own_prefix(archives):
    """One request carries the basemap, overlay, routes and contours, with
    the same-named layers kept apart."""
    archives[("planet_basemap", 10, 5, 5)] = _tile({"water": [_line([(0, 0), (10, 10)])]})
    archives[("master_overlay", 10, 5, 5)] = _tile({"water": [_line([(0, 0), (20, 20)])]})
    archives[("master_routes", 10, 5, 5)] = _tile({"routes": [_line([(0, 0), (30, 30)], feat_key="pct:1")]})
    archives[("master_contours", 10, 5, 5)] = _tile({"contours": [_line([(0, 0), (40, 40)], ele_ft=100)]})
    out = mapbox_vector_tile.decode(gzip.decompress(merged_tiles.merged_tile(10, 5, 5)))
    assert set(out) == {"water", "ov_water", "rt_routes", "ct_contours"}


def test_a_tile_with_nothing_anywhere_is_none(archives):
    assert merged_tiles.merged_tile(10, 5, 5) is None


def test_past_its_last_zoom_an_archive_is_cut_from_its_parent(archives):
    """Routes stop at z12. A z13 tile gets the quarter of the z12 one it covers,
    scaled up, so a route line still lines up with the basemap under it."""
    # A line inside the top-left quarter of the z12 tile (y down).
    archives[("master_routes", 12, 10, 10)] = _tile({"routes": [_line([(0, 512), (1600, 512)], feat_key="a:1")]})
    archives[("master_basemap_detail", 13, 20, 20)] = _tile({"roads": [_line([(0, 0), (1, 1)])]})
    out = mapbox_vector_tile.decode(
        gzip.decompress(merged_tiles.merged_tile(13, 20, 20)), default_options={"y_coord_down": True},
    )
    (route,) = out["rt_routes"]["features"]
    assert route["properties"] == {"feat_key": "a:1"}
    assert route["geometry"]["coordinates"] == [[0, 1024], [3200, 1024]]
    # The quarter the line does not cross gets nothing of it.
    archives[("master_basemap_detail", 13, 21, 21)] = _tile({"roads": [_line([(0, 0), (1, 1)])]})
    far = mapbox_vector_tile.decode(gzip.decompress(merged_tiles.merged_tile(13, 21, 21)))
    assert "rt_routes" not in far


def test_detail_basemap_replaces_the_planet_never_doubles_it(archives):
    """Roads drawn from both would draw twice; the planet's cut-down z12 is only
    for ground no region covers, so the map is not blank there."""
    archives[("planet_basemap", 12, 10, 10)] = _tile({"roads": [_line([(0, 0), (4096, 4096)], src="planet")]})
    archives[("master_basemap_detail", 13, 20, 20)] = _tile({"roads": [_line([(0, 0), (9, 9)], src="detail")]})
    covered = mapbox_vector_tile.decode(gzip.decompress(merged_tiles.merged_tile(13, 20, 20)))
    assert [f["properties"]["src"] for f in covered["roads"]["features"]] == ["detail"]
    uncovered = mapbox_vector_tile.decode(gzip.decompress(merged_tiles.merged_tile(13, 21, 21)))
    assert [f["properties"]["src"] for f in uncovered["roads"]["features"]] == ["planet"]


def test_a_polygon_clipped_along_the_tile_edge_still_encodes():
    """A polygon that touches the cut box along an edge clips to a
    GeometryCollection (area + stray line). The encoder refused those and the
    whole merged tile failed with a 500; only the area must be kept."""
    from shapely.geometry import GeometryCollection, LineString, Polygon

    from app.services.merged_tiles import _same_kind

    area = Polygon([(0, 0), (10, 0), (10, 10), (0, 10)])
    mixed = GeometryCollection([area, LineString([(10, 10), (20, 10)])])
    kept = _same_kind(mixed, area)
    assert kept.geom_type == "Polygon" and kept.equals(area)
