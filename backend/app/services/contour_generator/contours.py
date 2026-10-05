# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Contour extraction: elevation grid → MVT line features.

Computes which foot-value contour levels fall within a tile's elevation range,
runs contourpy on the (optionally neighbour-padded) grid, and emits MVT
LineString feature dicts in tile coordinate space. Index contours (every
INDEX_EVERY intervals) are flagged for thicker/labelled rendering.
"""

from __future__ import annotations

import math

import numpy as np
from contourpy import contour_generator, LineType

from .constants import INDEX_EVERY, MVT_EXTENT, PX_TO_MVT
from .dem import _elev_m_to_ft



def gaussian_blur(a: np.ndarray, sigma: float, truncate: float = 4.0) -> np.ndarray:
    """`scipy.ndimage.gaussian_filter(a, sigma)`, in numpy.

    This one call was scipy's only use in the backend, and scipy is 140 MB of
    the image. Same kernel (radius `int(truncate * sigma + 0.5)`, normalised),
    same edge handling — scipy's default "reflect" mirrors about the edge
    including the edge sample, which is numpy's "symmetric" — and the same
    dtype out as in, so contours are unchanged: test_contours pins it against
    scipy's own output to float32 precision.

    Separable, so two 1-D passes; each is a weighted sum of shifted views,
    which for a kernel of nine taps is as fast as anything cleverer.
    """
    radius = int(truncate * sigma + 0.5)
    x = np.arange(-radius, radius + 1, dtype=np.float64)
    kernel = np.exp(-0.5 * (x / sigma) ** 2)
    kernel /= kernel.sum()

    out = np.asarray(a, dtype=np.float64)
    for axis in range(out.ndim):
        pad = [(0, 0)] * out.ndim
        pad[axis] = (radius, radius)
        padded = np.pad(out, pad, mode="symmetric")
        acc = np.zeros_like(out)
        for i, w in enumerate(kernel):
            window = [slice(None)] * out.ndim
            window[axis] = slice(i, i + out.shape[axis])
            acc += w * padded[tuple(window)]
        out = acc
    dtype = np.asarray(a).dtype
    return out.astype(dtype) if dtype.kind == "f" else out

def _contour_levels_ft(interval_ft: int, min_ft: float, max_ft: float) -> list[int]:
    """Return integer foot-values for contour lines between min and max."""
    if max_ft <= min_ft or not math.isfinite(min_ft) or not math.isfinite(max_ft):
        return []
    first = math.ceil(min_ft / interval_ft) * interval_ft
    last  = math.floor(max_ft / interval_ft) * interval_ft
    if first > last:
        return []
    return list(range(first, last + 1, interval_ft))


def _is_index(level_ft: int, interval_ft: int) -> bool:
    """True if level_ft is an index contour (every INDEX_EVERY intervals)."""
    index_interval = interval_ft * INDEX_EVERY
    return level_ft % index_interval == 0


def _generate_mvt_features(elev_m: np.ndarray, interval_ft: int, pad: int = 0) -> list[dict]:
    """Generate MVT feature dicts for contour lines in one tile.

    `elev_m` may be padded with `pad` pixels of neighbour elevation on every side
    (see _padded_elevation). The inner TILE_PX² region maps to MVT
    [0, MVT_EXTENT]; the padding becomes a small overlap (±buf) into neighbouring
    tiles so contour lines join seamlessly across tile boundaries.
    """
    h, w = elev_m.shape
    if h == 0 or w == 0:
        return []

    # Smooth elevation to reduce noise-induced micro-contours. With the neighbour
    # padding present, the smoothing kernel near the tile edge uses real adjacent
    # data, so contour crossings match across seams.
    elev_smooth = gaussian_blur(np.nan_to_num(elev_m, nan=np.nanmean(elev_m)
                                 if not np.all(np.isnan(elev_m)) else 0.0),
                                sigma=1.0)

    elev_ft = _elev_m_to_ft(elev_smooth)
    valid = elev_ft[~np.isnan(elev_ft)]
    if valid.size == 0:
        return []

    levels_ft = _contour_levels_ft(interval_ft, float(valid.min()), float(valid.max()))
    if not levels_ft:
        return []

    # contourpy works in array-index space (x=col, y=row) of the padded grid.
    # Shift by -pad so the inner tile origin is 0, scale a tile pixel to MVT
    # units, and allow geometry up to `buf` units beyond the edge (the overlap).
    cg = contour_generator(z=elev_ft, line_type=LineType.SeparateCode)
    buf = int(round(pad * PX_TO_MVT))

    features: list[dict] = []
    for level_ft in levels_ft:
        try:
            result = cg.lines(float(level_ft))
        except Exception:
            continue

        lines, codes = result
        if lines is None:
            continue

        # Each entry in `lines` is an ndarray of shape (N, 2) with (x, y) coords.
        for line in lines:
            if len(line) < 2:
                continue
            pts = np.round((line - pad) * PX_TO_MVT).astype(np.int32)
            pts[:, 0] = np.clip(pts[:, 0], -buf, MVT_EXTENT + buf)
            pts[:, 1] = np.clip(pts[:, 1], -buf, MVT_EXTENT + buf)
            coords = [(int(p[0]), int(p[1])) for p in pts]
            if len(coords) < 2:
                continue
            features.append({
                "geometry": {"type": "LineString", "coordinates": coords},
                "properties": {
                    "ele_ft": level_ft,
                    "index":  _is_index(level_ft, interval_ft),
                },
            })

    return features
