# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A Mapbox Vector Tile encoder for exactly one thing: contour lines.

`mapbox_vector_tile.encode` was 40% of contour generation (profiled
2026-10-14): it is general — shapely geometry, any property type, coordinate
transforms — and pure Python per point. Contours are only ever integer
LineStrings already in tile units with an elevation and an index flag, so the
protobuf is small enough to write by hand, and the per-point work (delta,
zigzag, varint) is done in numpy over a whole line at once.

The output decodes to the same features `mapbox_vector_tile` produced;
test_contour_mvt pins that against the library's own decoder.
"""

from __future__ import annotations

import numpy as np

from .constants import MVT_EXTENT

_MOVE_TO_ONE = (1 & 0x7) | (1 << 3)
_LINE_TO = 2


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _varints(v: np.ndarray) -> bytes:
    """Varint-encode an array of non-negative ints below 2^35, in order."""
    v = v.astype(np.uint64)
    n = 1 + sum((v >= (1 << (7 * i))).astype(np.int64) for i in range(1, 5))
    groups = np.stack([((v >> np.uint64(7 * i)) & np.uint64(0x7F)) for i in range(5)], axis=1).astype(np.uint8)
    idx = np.arange(5)[None, :]
    groups |= ((idx < (n[:, None] - 1)) * 0x80).astype(np.uint8)
    return groups[idx < n[:, None]].tobytes()


def _zigzag(v: np.ndarray) -> np.ndarray:
    v = v.astype(np.int64)
    return (v << 1) ^ (v >> 63)


def _field(tag: int, payload: bytes) -> bytes:
    """A length-delimited field (wire type 2)."""
    return _varint((tag << 3) | 2) + _varint(len(payload)) + payload


def _line_geometry(coords) -> bytes | None:
    """MoveTo the first point, LineTo the rest, as zigzagged deltas from (0, 0)."""
    pts = np.asarray(coords, dtype=np.int64).reshape(-1, 2)
    if len(pts) < 2:
        return None
    # A zero-length step draws nothing; the library dropped them too.
    keep = np.concatenate([[True], np.any(np.diff(pts, axis=0) != 0, axis=1)])
    pts = pts[keep]
    if len(pts) < 2:
        return None
    deltas = np.diff(pts, axis=0, prepend=np.zeros((1, 2), dtype=np.int64))
    params = _zigzag(deltas.reshape(-1))
    commands = np.concatenate([
        [_MOVE_TO_ONE], params[:2], [_LINE_TO | ((len(pts) - 1) << 3)], params[2:],
    ])
    return _varints(commands)


def encode_contours(features: list[dict], layer_name: str = "contours") -> bytes:
    """One layer of LineStrings with integer `ele_ft` and boolean `index`."""
    keys = ["ele_ft", "index"]
    values: list[tuple[str, object]] = []
    value_index: dict[tuple[str, object], int] = {}

    def value_id(kind: str, v) -> int:
        k = (kind, v)
        if k not in value_index:
            value_index[k] = len(values)
            values.append(k)
        return value_index[k]

    body = bytearray()
    for feature in features:
        geometry = _line_geometry(feature["geometry"]["coordinates"])
        if geometry is None:
            continue
        props = feature["properties"]
        tags = [0, value_id("int", int(props["ele_ft"])), 1, value_id("bool", bool(props["index"]))]
        f = (
            _field(2, b"".join(_varint(t) for t in tags))
            + _varint((3 << 3) | 0) + _varint(2)  # type = LINESTRING
            + _field(4, geometry)
        )
        body += _field(2, f)

    if not body:
        return b""
    layer = bytearray()
    layer += _varint((15 << 3) | 0) + _varint(2)  # version 2
    layer += _field(1, layer_name.encode())
    layer += body
    for k in keys:
        layer += _field(3, k.encode())
    for kind, v in values:
        if kind == "bool":
            payload = _varint((7 << 3) | 0) + _varint(1 if v else 0)
        else:
            payload = _varint((6 << 3) | 0) + _varint(int(_zigzag(np.array([v]))[0]))
        layer += _field(4, payload)
    layer += _varint((5 << 3) | 0) + _varint(MVT_EXTENT)
    return _field(3, bytes(layer))
