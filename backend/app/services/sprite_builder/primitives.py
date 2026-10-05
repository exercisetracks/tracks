# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Low-level Pillow drawing helpers used by every symbol/pattern routine.

All coordinates are fractions of the square side ``s`` (0..1), so the same glyph
code renders identically at any supersampled size. ``d`` is a ``PIL.ImageDraw``.
"""

from __future__ import annotations

from app.services.sprite_builder.palette import INK


def _lw(s: int, frac: float = 0.10) -> int:
    return max(1, round(s * frac))


def _poly(d, s, pts, fill=None, outline=None, width=1):
    d.polygon([(x * s, y * s) for x, y in pts], fill=fill, outline=outline, width=width)


def _line(d, s, pts, fill=INK, width=2):
    d.line([(x * s, y * s) for x, y in pts], fill=fill, width=width, joint="curve")


def _circle(d, s, cx, cy, r, fill=None, outline=None, width=1):
    d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s],
              fill=fill, outline=outline, width=width)


def _thick(d, s, p0, p1, wfrac, fill=INK):
    """A solid stroke with rounded ends (line + end-cap circles), for chunky
    pictogram limbs (no native round-cap in Pillow lines)."""
    lw = max(1, round(s * wfrac))
    d.line([(p0[0] * s, p0[1] * s), (p1[0] * s, p1[1] * s)], fill=fill, width=lw)
    r = lw / 2
    for x, y in (p0, p1):
        d.ellipse([x * s - r, y * s - r, x * s + r, y * s + r], fill=fill)
