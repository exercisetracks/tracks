# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""DEM tile decoding and neighbour stitching.

Turns Terrarium-encoded WebP DEM tiles into float32 elevation grids (metres),
and assembles a tile ringed by `pad` pixels of its 8 neighbours so contour
crossings and edge smoothing match across tile seams. A small LRU cache avoids
re-decoding each tile up to 9× as it serves as its own neighbours' buffer.
"""

from __future__ import annotations

import io
from collections import OrderedDict

import numpy as np
from PIL import Image

from .constants import _DECODE_CACHE_MAX, TILE_PX, logger


def _decode_terrarium(raw: bytes) -> np.ndarray | None:
    """Decode a Terrarium WebP tile to a float32 elevation array (metres)."""
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:
        logger.debug("Terrarium decode failed: %s", exc)
        return None

    arr = np.array(img, dtype=np.float32)
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    elev_m = r * 256.0 + g + b / 256.0 - 32768.0

    # Mark ocean/void pixels as NaN (Terrarium encodes sea level as exactly
    # 32768 for open ocean tiles; anything below -500 m is also nodata).
    elev_m[(r == 1) & (g == 134) & (b == 160)] = np.nan  # Terrarium ocean sentinel
    elev_m[elev_m < -500.0] = np.nan

    return elev_m


def _elev_m_to_ft(elev_m: np.ndarray) -> np.ndarray:
    return elev_m / 0.3048


def _decode_tile(reader, z: int, x: int, y: int, cache: OrderedDict) -> np.ndarray | None:
    """Fetch + decode a single DEM tile to a TILE_PX² array, with an LRU cache."""
    key = (z, x, y)
    if key in cache:
        cache.move_to_end(key)
        return cache[key]
    arr = None
    try:
        raw = reader.get(z, x, y)
        if raw:
            arr = _decode_terrarium(raw)
            if arr is not None and arr.shape[:2] != (TILE_PX, TILE_PX):
                arr = None
    except Exception:
        arr = None
    cache[key] = arr
    cache.move_to_end(key)
    while len(cache) > _DECODE_CACHE_MAX:
        cache.popitem(last=False)
    return arr


def _padded_elevation(reader, z: int, x: int, y: int, pad: int,
                      cache: OrderedDict, center: np.ndarray) -> np.ndarray:
    """Build a (TILE_PX+2·pad)² elevation grid: the tile, ringed by `pad` pixels
    of its 8 neighbours. Missing neighbours (data edge) stay NaN — smoothing then
    treats them as nodata rather than a hard cliff. Array is [row=y, col=x], with
    row 0 = north, col 0 = west; tile y increases southward so dy=-1 is north."""
    P = TILE_PX
    n = 2 ** z
    out = np.full((P + 2 * pad, P + 2 * pad), np.nan, dtype=np.float32)
    out[pad:pad + P, pad:pad + P] = center

    def nb(dx, dy):
        ny = y + dy
        if ny < 0 or ny >= n:
            return None          # no wrap past the poles
        return _decode_tile(reader, z, (x + dx) % n, ny, cache)

    # edges
    L = nb(-1, 0)
    if L is not None: out[pad:pad + P, 0:pad]             = L[:, P - pad:P]
    R = nb(1, 0)
    if R is not None: out[pad:pad + P, pad + P:pad + 2*P] = R[:, 0:pad]
    T = nb(0, -1)
    if T is not None: out[0:pad, pad:pad + P]             = T[P - pad:P, :]
    B = nb(0, 1)
    if B is not None: out[pad + P:pad + 2*P, pad:pad + P] = B[0:pad, :]
    # corners
    TL = nb(-1, -1)
    if TL is not None: out[0:pad, 0:pad]                    = TL[P - pad:P, P - pad:P]
    TR = nb(1, -1)
    if TR is not None: out[0:pad, pad + P:pad + 2*P]        = TR[P - pad:P, 0:pad]
    BL = nb(-1, 1)
    if BL is not None: out[pad + P:pad + 2*P, 0:pad]        = BL[0:pad, P - pad:P]
    BR = nb(1, 1)
    if BR is not None: out[pad + P:pad + 2*P, pad + P:pad + 2*P] = BR[0:pad, 0:pad]
    return out
