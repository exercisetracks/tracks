# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Rasterise glyphs and shelf-pack them into a MapLibre sprite sheet.

Steps: render each glyph to its own RGBA tile (supersampled for crisp edges, or
exact-size for seamless patterns), crop the existing ``light`` base sprite so its
icons survive, then shelf-pack everything into a near-square sheet plus a manifest
of ``{x, y, width, height, pixelRatio}`` boxes.
"""

from __future__ import annotations

import json
import logging
import math

from PIL import Image, ImageDraw

from app.services.sprite_builder.palette import (
    BASE_SPRITE, CLEAR, PAD, SS, _sprite_dir,
)

logger = logging.getLogger(__name__)


def _render_glyph(fn, px: int) -> Image.Image:
    """Supersample a glyph for crisp edges, then downscale to px."""
    big = Image.new("RGBA", (px * SS, px * SS), CLEAR)
    fn(ImageDraw.Draw(big), px * SS)
    return big.resize((px, px), Image.LANCZOS)


def _render_pattern(fn, px: int) -> Image.Image:
    """Patterns are drawn at exact tile px (no supersample) to stay seamless."""
    img = Image.new("RGBA", (px, px), CLEAR)
    fn(ImageDraw.Draw(img), px)
    return img


def _load_base_icons(ratio: int) -> dict[str, Image.Image]:
    """Crop every icon out of the existing `light` sprite at the given ratio."""
    sd = _sprite_dir()
    suffix = "" if ratio == 1 else "@2x"
    jpath = sd / f"{BASE_SPRITE}{suffix}.json"
    ipath = sd / f"{BASE_SPRITE}{suffix}.png"
    if not (jpath.is_file() and ipath.is_file()):
        return {}
    try:
        meta = json.loads(jpath.read_text())
        sheet = Image.open(ipath).convert("RGBA")
    except Exception as exc:
        logger.warning("Base sprite %s unreadable: %s", BASE_SPRITE, exc)
        return {}
    out: dict[str, Image.Image] = {}
    for name, m in meta.items():
        try:
            box = (m["x"], m["y"], m["x"] + m["width"], m["y"] + m["height"])
            out[name] = sheet.crop(box)
        except Exception:
            continue
    return out


def _pack(images: dict[str, Image.Image], ratio: int) -> tuple[Image.Image, dict]:
    """Shelf-pack icons into a near-square sheet; return (sheet, manifest)."""
    items = sorted(images.items(), key=lambda kv: kv[1].height, reverse=True)
    total = sum((im.width + PAD) * (im.height + PAD) for _, im in items)
    max_w = max(256, int(math.sqrt(total) * 1.25))

    manifest: dict[str, dict] = {}
    x = y = row_h = sheet_w = 0
    placements = []
    for name, im in items:
        if x + im.width + PAD > max_w and x > 0:
            x = 0
            y += row_h + PAD
            row_h = 0
        placements.append((name, im, x, y))
        manifest[name] = {"x": x, "y": y, "width": im.width, "height": im.height,
                          "pixelRatio": ratio}
        x += im.width + PAD
        row_h = max(row_h, im.height)
        sheet_w = max(sheet_w, x)
    sheet_h = y + row_h

    sheet = Image.new("RGBA", (max(1, sheet_w), max(1, sheet_h)), CLEAR)
    for _, im, px, py in placements:
        sheet.paste(im, (px, py), im)
    return sheet, manifest
