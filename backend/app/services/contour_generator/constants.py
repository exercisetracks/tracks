# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Shared constants for contour generation.

Tile geometry, smoothing/stitching parameters, and the zoom→interval mapping
that the rest of the package relies on. Kept in one place so DEM decoding,
contour extraction, and the tile builder agree on the same numbers.
"""

from __future__ import annotations

import logging
import threading

logger = logging.getLogger("app.services.contour_generator")

# Serialise contour regeneration — the lifespan startup thread and the
# post-reindex call can otherwise race on the same .tmp output file.
_contour_lock = threading.Lock()

MVT_EXTENT = 4096
# DEM tiles from Mapterhorn/Terrarium are 512×512 pixels.
TILE_PX = 512
# One tile pixel in MVT units.
PX_TO_MVT = MVT_EXTENT / TILE_PX   # = 8.0

# Pixels of neighbouring-tile elevation stitched around each tile before
# smoothing + contouring. Without it, edge smoothing and contour crossings don't
# match at tile seams (visible breaks in the lines); the resulting geometry also
# overlaps slightly into neighbours so MapLibre renders the lines continuously.
CONTOUR_PAD_PX = 8
# Decoded-tile LRU cache: tiles are iterated in (roughly spatial) Hilbert order,
# so neighbours are usually fetched close together — caching avoids re-decoding
# each tile up to 9× as it serves as its own neighbours' buffer.
_DECODE_CACHE_MAX = 256

# Zoom → base contour interval in feet. Coarser intervals at low zoom keep the
# overview readable; z9 uses a sparse 500 ft interval so only major elevation
# bands show, then density increases as you zoom in.
INTERVAL_FT: dict[int, int] = {
    7:  1000,
    8:  1000,
    9:  500,
    10: 200,
    11: 100,
    12: 100,
    13: 100,
    14: 100,
    15: 100,
}
INDEX_EVERY = 5   # every Nth intermediate contour is an index contour
