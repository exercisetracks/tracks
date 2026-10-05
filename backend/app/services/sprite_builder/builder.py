# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Orchestration: build both @1x/@2x sheets, version-gate, run off-thread.

A ``{SPRITE_NAME}.version`` sidecar holding :data:`BUILD_VERSION` makes re-runs
cheap — the sheet is rebuilt only when the symbol code version changes or an
output file is missing. ``build_sprite_async`` is the lifespan entry point.
"""

from __future__ import annotations

import json
import logging
import threading

from app.services.sprite_builder.atlas import (
    _load_base_icons, _pack, _render_glyph, _render_pattern,
)
from app.services.sprite_builder.palette import (
    BUILD_VERSION, ICON_PX, PATTERN_PX, SPRITE_NAME, _sprite_dir,
)
from app.services.sprite_builder.registry import PATTERNS, SYMBOLS

logger = logging.getLogger(__name__)


def _build_ratio(ratio: int) -> None:
    images = _load_base_icons(ratio)                 # base first (overridable)
    for name, fn in SYMBOLS.items():
        images[name] = _render_glyph(fn, ICON_PX * ratio)
    for name, fn in PATTERNS.items():
        images[name] = _render_pattern(fn, PATTERN_PX * ratio)

    sheet, manifest = _pack(images, ratio)
    sd = _sprite_dir()
    suffix = "" if ratio == 1 else "@2x"
    sheet.save(sd / f"{SPRITE_NAME}{suffix}.png", "PNG")
    (sd / f"{SPRITE_NAME}{suffix}.json").write_text(json.dumps(manifest))


def _is_current() -> bool:
    sd = _sprite_dir()
    ver = sd / f"{SPRITE_NAME}.version"
    needed = [f"{SPRITE_NAME}.json", f"{SPRITE_NAME}.png",
              f"{SPRITE_NAME}@2x.json", f"{SPRITE_NAME}@2x.png"]
    if not all((sd / n).is_file() for n in needed):
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
