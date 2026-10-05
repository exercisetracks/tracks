# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fill patterns — one ``pat_*(d, s)`` per seamless ``fill-pattern`` tile.

Patterns (marsh, orchard, sand, …) are drawn at the exact tile size so they tile
without seams, so coordinates here are again fractions of the tile side ``s``.
The ``PATTERNS`` registry in :mod:`registry` maps fill-pattern names to these.
"""

from __future__ import annotations

from app.services.sprite_builder.palette import BROWN, GREEN, TEAL, WATER
from app.services.sprite_builder.primitives import _circle


def _tuft(d, s, cx, cy, col, scale=1.0):
    """A three-blade grass tuft rooted at (cx, cy)."""
    h = 0.22 * scale
    w = 0.10 * scale
    lw = max(1, round(s * 0.05))
    for dx in (-w, 0.0, w):
        d.line([((cx) * s, (cy) * s), ((cx + dx) * s, (cy - h) * s)],
               fill=col, width=lw)


def pat_marsh(d, s):
    _tuft(d, s, 0.30, 0.42, TEAL)
    _tuft(d, s, 0.70, 0.78, TEAL)
    d.line([(0.46 * s, 0.50 * s), (0.66 * s, 0.50 * s)], fill=WATER, width=max(1, round(s * 0.04)))


def pat_swamp(d, s):
    _tuft(d, s, 0.34, 0.46, TEAL)
    _tuft(d, s, 0.72, 0.84, TEAL)
    lw = max(1, round(s * 0.04))
    d.line([(0.10 * s, 0.62 * s), (0.30 * s, 0.62 * s)], fill=WATER, width=lw)
    d.line([(0.62 * s, 0.30 * s), (0.86 * s, 0.30 * s)], fill=WATER, width=lw)


def pat_wooded_swamp(d, s):
    _tuft(d, s, 0.30, 0.48, TEAL)
    _circle(d, s, 0.70, 0.30, 0.09, fill=GREEN)
    _circle(d, s, 0.30, 0.82, 0.09, fill=GREEN)
    d.line([(0.52 * s, 0.66 * s), (0.74 * s, 0.66 * s)], fill=WATER, width=max(1, round(s * 0.04)))


def pat_mangrove(d, s):
    for cx, cy in ((0.26, 0.40), (0.62, 0.32), (0.44, 0.74), (0.80, 0.78)):
        _tuft(d, s, cx, cy, TEAL, scale=0.85)


def pat_orchard(d, s):              # regular dot grid (offset rows tile cleanly)
    for cy in (0.25, 0.75):
        for cx in (0.25, 0.75):
            _circle(d, s, cx, cy, 0.10, fill=GREEN)


def pat_vineyard(d, s):            # short vertical ticks in rows
    lw = max(1, round(s * 0.06))
    for cy in (0.30, 0.70):
        for cx in (0.20, 0.50, 0.80):
            d.line([(cx * s, (cy - 0.12) * s), (cx * s, (cy + 0.12) * s)], fill=GREEN, width=lw)


def pat_sand(d, s):               # brown stipple
    pts = [(0.18, 0.22), (0.55, 0.16), (0.80, 0.40), (0.30, 0.58),
           (0.66, 0.66), (0.20, 0.82), (0.48, 0.86), (0.86, 0.84)]
    r = max(1, round(s * 0.035))
    for cx, cy in pts:
        d.ellipse([cx * s - r, cy * s - r, cx * s + r, cy * s + r], fill=BROWN)


def pat_gravel(d, s):             # mixed-size brown dots
    pts = [(0.20, 0.24, 0.06), (0.58, 0.20, 0.04), (0.78, 0.46, 0.06),
           (0.34, 0.60, 0.05), (0.66, 0.72, 0.04), (0.22, 0.82, 0.05),
           (0.50, 0.40, 0.04), (0.86, 0.80, 0.05)]
    for cx, cy, rf in pts:
        r = max(1, round(s * rf))
        d.ellipse([cx * s - r, cy * s - r, cx * s + r, cy * s + r], fill=BROWN)


def pat_scrub(d, s):              # small olive scrub marks (v shapes)
    olive = (120, 130, 70, 255)
    lw = max(1, round(s * 0.05))
    for cx, cy in ((0.28, 0.40), (0.70, 0.66)):
        d.line([((cx - 0.10) * s, cy * s), (cx * s, (cy - 0.14) * s),
                ((cx + 0.10) * s, cy * s)], fill=olive, width=lw, joint="curve")


def pat_mud(d, s):               # foreshore / tidal flat — fine blue stipple + dashes
    r = max(1, round(s * 0.028))
    for cx, cy in ((0.18, 0.26), (0.46, 0.18), (0.74, 0.30), (0.30, 0.52),
                   (0.62, 0.56), (0.20, 0.78), (0.50, 0.82), (0.82, 0.72)):
        d.ellipse([cx * s - r, cy * s - r, cx * s + r, cy * s + r], fill=WATER)
    lw = max(1, round(s * 0.035))
    d.line([(0.34 * s, 0.38 * s), (0.52 * s, 0.38 * s)], fill=WATER, width=lw)
    d.line([(0.56 * s, 0.68 * s), (0.74 * s, 0.68 * s)], fill=WATER, width=lw)


def pat_reef(d, s):              # rock/coral reef — scattered blue plus marks
    lw = max(1, round(s * 0.05))
    for cx, cy in ((0.28, 0.30), (0.66, 0.42), (0.42, 0.70), (0.80, 0.78)):
        d.line([((cx - 0.07) * s, cy * s), ((cx + 0.07) * s, cy * s)], fill=WATER, width=lw)
        d.line([(cx * s, (cy - 0.07) * s), (cx * s, (cy + 0.07) * s)], fill=WATER, width=lw)


def pat_tailings(d, s):          # dense orange/brown stipple
    orange = (190, 120, 60, 255)
    pts = [(0.16, 0.20), (0.40, 0.14), (0.64, 0.24), (0.84, 0.18),
           (0.26, 0.44), (0.52, 0.40), (0.76, 0.50), (0.14, 0.66),
           (0.40, 0.70), (0.62, 0.64), (0.84, 0.74), (0.30, 0.86), (0.66, 0.86)]
    r = max(1, round(s * 0.03))
    for cx, cy in pts:
        d.ellipse([cx * s - r, cy * s - r, cx * s + r, cy * s + r], fill=orange)
