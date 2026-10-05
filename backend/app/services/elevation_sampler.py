# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Sample terrain elevation along a polyline from the DEM archives.

Reads Terrarium-encoded WebP tiles and bilinearly samples elevation (metres) at
each coordinate. Tries the high-res regional DEM (master_dem.pmtiles, z8-12) first
and falls back to the global low-res overview (planet_dem_z7.pmtiles, z0-7), so a
point gets fine elevation inside a downloaded region and a coarse-but-real value
everywhere else — long-trail elevation profiles work worldwide, not only where a
region has been downloaded. Self-hosted, no external elevation API.
"""

from __future__ import annotations

import io
import logging
import math
from collections import OrderedDict
from pathlib import Path

import numpy as np
from PIL import Image
from pmtiles.reader import MmapSource, Reader

from app.config import settings

logger = logging.getLogger(__name__)

TILE_PX = 512
_CACHE_MAX = 64

# DEM archives in priority order: high-res regional first, global z0-7 overview as
# the worldwide fallback. First archive that covers a point wins.
_DEM_FILES = ("master_dem.pmtiles", "planet_dem_z7.pmtiles")


def _decode_terrarium(raw: bytes) -> np.ndarray | None:
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception:
        return None
    arr = np.array(img, dtype=np.float32)
    r, g, b = arr[:, :, 0], arr[:, :, 1], arr[:, :, 2]
    elev = r * 256.0 + g + b / 256.0 - 32768.0
    elev[elev < -500.0] = np.nan
    return elev


def _world_px(lng: float, lat: float, z: int) -> tuple[float, float]:
    """Fractional web-mercator tile coordinate (x east, y south)."""
    n = 2.0 ** z
    sx = (lng + 180.0) / 360.0 * n
    siny = min(max(math.sin(math.radians(lat)), -0.9999), 0.9999)
    sy = (0.5 - math.log((1 + siny) / (1 - siny)) / (4 * math.pi)) * n
    return sx, sy


def _bilinear(arr: np.ndarray, px: float, py: float) -> float:
    h, w = arr.shape
    x0 = min(max(int(math.floor(px)), 0), w - 1)
    y0 = min(max(int(math.floor(py)), 0), h - 1)
    x1 = min(x0 + 1, w - 1)
    y1 = min(y0 + 1, h - 1)
    fx, fy = px - x0, py - y0
    return float(
        arr[y0, x0] * (1 - fx) * (1 - fy) + arr[y0, x1] * fx * (1 - fy)
        + arr[y1, x0] * (1 - fx) * fy + arr[y1, x1] * fx * fy
    )


def _open_archive(path: Path) -> dict:
    """Open one DEM pmtiles → reader bundle with its own LRU tile cache."""
    f = open(path, "rb")
    reader = Reader(MmapSource(f))
    header = reader.header()
    return {"f": f, "reader": reader, "zmax": header["max_zoom"],
            "zmin": max(0, header.get("min_zoom", 0)), "cache": OrderedDict()}


def _arc_tile(arc: dict, z: int, tx: int, ty: int):
    """Decoded elevation array for a tile, cached per-archive."""
    cache = arc["cache"]
    key = (z, tx, ty)
    if key in cache:
        cache.move_to_end(key)
        return cache[key]
    arr = None
    try:
        raw = arc["reader"].get(z, tx, ty)
        if raw:
            decoded = _decode_terrarium(raw)
            if decoded is not None and decoded.shape[:2] == (TILE_PX, TILE_PX):
                arr = decoded
    except Exception:
        arr = None
    cache[key] = arr
    cache.move_to_end(key)
    while len(cache) > _CACHE_MAX:
        cache.popitem(last=False)
    return arr


def _sample_archive(arc: dict, lng: float, lat: float) -> float | None:
    """Bilinear elevation at (lng,lat) from one archive (finest zoom first)."""
    for z in range(arc["zmax"], arc["zmin"] - 1, -1):
        sx, sy = _world_px(lng, lat, z)
        tx, ty = int(sx), int(sy)
        arr = _arc_tile(arc, z, tx, ty)
        if arr is None:
            continue
        v = _bilinear(arr, (sx - tx) * TILE_PX, (sy - ty) * TILE_PX)
        return None if v != v else round(v, 1)
    return None


def sample_elevations(coords: list[list[float]]) -> list[float | None]:
    """Bilinearly sample DEM elevation (metres) at each [lng, lat]; None off-DEM.

    Tries each archive in _DEM_FILES order (high-res regional → global z0-7), so a
    point off any downloaded region still gets coarse worldwide elevation."""
    base = Path(settings.map_data_dir)
    arcs = [_open_archive(base / fn) for fn in _DEM_FILES if (base / fn).exists()]
    if not arcs:
        return [None] * len(coords)
    try:
        out: list[float | None] = []
        for lng, lat in coords:
            val = None
            for arc in arcs:
                val = _sample_archive(arc, lng, lat)
                if val is not None:
                    break
            out.append(val)
        return out
    finally:
        for arc in arcs:
            arc["f"].close()
