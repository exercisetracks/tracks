# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Orchestration: build both @1x/@2x sheets, version-gate, run off-thread.

A ``{SPRITE_NAME}.version`` sidecar holding :data:`BUILD_VERSION` makes re-runs
cheap — the sheet is rebuilt only when the symbol code version changes or an
output file is missing. ``build_sprite_async`` is the lifespan entry point.
"""

from __future__ import annotations

import hashlib
import json
import logging
import threading

from app.services.sprite_builder.atlas import (
    _load_base_icons, _pack, _render_glyph, _render_pattern,
)
from app.services.sprite_builder.palette import (
    BUILD_VERSION, ICON_PX, PATTERN_PX, SPRITE_NAME, _sprite_dir,
)
from app.services.sprite_builder.registry import FIXED_SIZE, PATTERNS, SYMBOLS

logger = logging.getLogger(__name__)


def _build_ratio(ratio: int) -> None:
    images = _load_base_icons(ratio)                 # base first (overridable)
    for name, fn in SYMBOLS.items():
        images[name] = _render_glyph(fn, ICON_PX * ratio, normalise=name not in FIXED_SIZE)
    for name, fn in PATTERNS.items():
        images[name] = _render_pattern(fn, PATTERN_PX * ratio)

    sheet, manifest = _pack(images, ratio)
    sd = _sprite_dir()
    suffix = "" if ratio == 1 else "@2x"
    sheet.save(sd / f"{SPRITE_NAME}{suffix}.png", "PNG")
    (sd / f"{SPRITE_NAME}{suffix}.json").write_text(json.dumps(manifest))


# The files that make one sheet. MapLibre takes a manifest and an image apart,
# so they are versioned as one: a manifest from one build laid over the image
# of another puts every icon one slot over (a park drawn with the public-works
# symbol, a trail with the hospital's).
SHEET_FILES = (f"{SPRITE_NAME}.json", f"{SPRITE_NAME}.png",
               f"{SPRITE_NAME}@2x.json", f"{SPRITE_NAME}@2x.png")

_version_cache: tuple[tuple, str | None] = ((), None)


def sprite_version() -> str | None:
    """A short hash of the sheet's four files, or None while one is missing.

    Content rather than :data:`BUILD_VERSION`: a sheet can change without the
    code changing (the base ``light`` sprite it merges, a sheet seeded by hand),
    and only the bytes say whether a cached copy is still this one. Hashed once
    per change of the files' size and mtime, not per request.
    """
    global _version_cache
    sd = _sprite_dir()
    try:
        stats = tuple((n, (sd / n).stat().st_mtime_ns, (sd / n).stat().st_size)
                      for n in SHEET_FILES)
    except OSError:
        return None
    if _version_cache[0] != stats:
        h = hashlib.sha256()
        for n in SHEET_FILES:
            h.update((sd / n).read_bytes())
        _version_cache = (stats, h.hexdigest()[:12])
    return _version_cache[1]


def _is_current() -> bool:
    sd = _sprite_dir()
    ver = sd / f"{SPRITE_NAME}.version"
    if not all((sd / n).is_file() for n in SHEET_FILES):
        return False
    return ver.is_file() and ver.read_text().strip() == BUILD_VERSION


def build_sprite(force: bool = False) -> None:
    """Generate the USGS sprite sheet (idempotent)."""
    if not force and _is_current():
        return
    try:
        _build_ratio(1)
        _build_ratio(2)
        (_sprite_dir() / f"{SPRITE_NAME}.version").write_text(BUILD_VERSION)
        logger.info("Built USGS sprite (%d symbols, %d patterns)",
                    len(SYMBOLS), len(PATTERNS))
    except Exception:
        logger.exception("USGS sprite build failed (non-fatal)")


def build_sprite_async() -> None:
    threading.Thread(target=build_sprite, daemon=True, name="usgs-sprite").start()
