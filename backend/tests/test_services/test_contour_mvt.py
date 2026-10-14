# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The hand-written contour encoder against the library it replaced."""

import mapbox_vector_tile
import numpy as np

from app.services.contour_generator.mvt import encode_contours


def _features():
    rng = np.random.default_rng(7)
    out = []
    for i in range(40):
        pts = np.cumsum(rng.integers(-300, 300, size=(rng.integers(2, 60), 2)), axis=0) + 2048
        out.append({
            "geometry": {"type": "LineString", "coordinates": pts.astype(np.int32)},
            "properties": {"ele_ft": int(rng.integers(-300, 14000)), "index": bool(i % 5 == 0)},
        })
    return out


def _decoded(raw):
    layer = mapbox_vector_tile.decode(raw)["contours"]
    return layer["extent"], [(f["geometry"], f["properties"]) for f in layer["features"]]


def test_contours_decode_the_same_as_the_librarys_encoding():
    """Same lines, elevations (negative ones too) and index flags as before, so
    no map style or client notices the encoder changed."""
    feats = _features()
    library = mapbox_vector_tile.encode(
        [{"name": "contours", "features": [
            {"geometry": {"type": "LineString", "coordinates": [tuple(map(int, p)) for p in f["geometry"]["coordinates"]]},
             "properties": f["properties"]} for f in feats]}],
        y_coord_down=True,
    )
    assert _decoded(encode_contours(feats)) == _decoded(library)


def test_a_line_that_collapses_to_one_point_is_dropped():
    """A contour clipped flat against the tile edge draws nothing; a feature
    with a zero-length geometry is invalid MVT."""
    feats = [{"geometry": {"coordinates": np.array([[5, 5], [5, 5], [5, 5]])},
              "properties": {"ele_ft": 100, "index": False}}]
    assert encode_contours(feats) == b""
