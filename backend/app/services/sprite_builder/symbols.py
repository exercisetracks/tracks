# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Point and line glyphs — one ``sym_*(d, s)`` per USGS map symbol.

Three groups live here, all drawn in fractional coordinates of the square ``s``:
  * topographic point glyphs — campground, spring, mine, benchmark, peak, …
  * recreation & services    — restrooms, parking, food, culture, parks (quad-
    style monochrome icons that replace stray Protomaps "light" base icons so
    every POI shares one hand-drawn look)
  * line symbols             — rail_tie, pylon, levee_tick (placed along a line)

The ``SYMBOLS`` registry in :mod:`registry` maps icon-image names to these.
"""

from __future__ import annotations

import math

from app.services.sprite_builder.palette import (
    BROWN, CLEAR, GREEN, INK, RED, WATER,
)
from app.services.sprite_builder.primitives import (
    _circle, _line, _lw, _poly, _thick,
)


# ── Point glyphs ────────────────────────────────────────────────────────────────

def sym_campground(d, s):           # tent triangle — USGS recreation red
    _poly(d, s, [(0.5, 0.16), (0.14, 0.84), (0.86, 0.84)], fill=RED)
    _line(d, s, [(0.5, 0.16), (0.5, 0.84)], fill=CLEAR, width=_lw(s, 0.06))


def sym_picnic(d, s):               # table: top bar + splayed legs (red)
    lw = _lw(s, 0.09)
    _line(d, s, [(0.18, 0.42), (0.82, 0.42)], fill=RED, width=lw)
    _line(d, s, [(0.30, 0.42), (0.18, 0.82)], fill=RED, width=lw)
    _line(d, s, [(0.70, 0.42), (0.82, 0.82)], fill=RED, width=lw)
    _line(d, s, [(0.24, 0.60), (0.76, 0.60)], fill=RED, width=lw)


def sym_trailhead(d, s):            # "TH›" trailhead sign — red block letters + chevron
    # Trailhead signs in the field are a small placard reading "TH" — matched
    # directly rather than an abstract pictogram, so it reads unmistakably as
    # "trailhead" at a glance instead of resembling a shoe store icon. Red ink
    # (not the usual black) so it pops as a way-marker against the other
    # culture symbols. Letters are narrower than a full-width wordmark —
    # left-aligned — so a trailing "›" chevron fits beside them without
    # crowding the tile edges. Fit to a 0.10-0.90 square (like every other
    # glyph here) rather than the letters' natural wide/short proportions.
    # Transparent background, no backing tile. One uniform stroke width (the
    # weight of the T's crossbar) for every bar AND the chevron, so nothing
    # in the glyph reads as thicker or thinner than the rest.
    W = 0.10
    def _bar(x0, y0, x1, y1):
        d.rectangle([x0 * s, y0 * s, x1 * s, y1 * s], fill=RED)

    # T — top row
    _bar(0.10, 0.10, 0.52, 0.10 + W)          # crossbar
    _bar(0.31 - W / 2, 0.10, 0.31 + W / 2, 0.46)  # stem, centered under crossbar
    # H — bottom row
    _bar(0.10, 0.50, 0.10 + W, 0.90)          # left upright
    _bar(0.52 - W, 0.50, 0.52, 0.90)          # right upright
    _bar(0.10, 0.70 - W / 2, 0.52, 0.70 + W / 2)  # crossbar, vertically centered

    # › chevron — points toward the trail, spanning the same height as TH
    _thick(d, s, (0.58, 0.14), (0.90, 0.50), W, fill=RED)
    _thick(d, s, (0.90, 0.50), (0.58, 0.86), W, fill=RED)


def sym_ranger_station(d, s):       # flag on a pole (USGS ranger/forest HQ)
    lw = _lw(s, 0.08)
    _line(d, s, [(0.28, 0.14), (0.28, 0.86)], width=lw)
    _poly(d, s, [(0.28, 0.16), (0.78, 0.30), (0.28, 0.44)], fill=INK)


def sym_cemetery(d, s):             # box with a cross
    lw = _lw(s, 0.08)
    d.rectangle([0.14 * s, 0.14 * s, 0.86 * s, 0.86 * s], outline=INK, width=lw)
    _line(d, s, [(0.5, 0.26), (0.5, 0.62)], width=lw)
    _line(d, s, [(0.36, 0.38), (0.64, 0.38)], width=lw)


def sym_mine(d, s):                 # crossed pick & hammer → bold X with heads
    lw = _lw(s, 0.12)
    _line(d, s, [(0.20, 0.20), (0.80, 0.80)], width=lw)
    _line(d, s, [(0.80, 0.20), (0.20, 0.80)], width=lw)
    _circle(d, s, 0.5, 0.5, 0.10, fill=INK)


def sym_mine_shaft(d, s):           # small filled diamond
    _poly(d, s, [(0.5, 0.24), (0.76, 0.5), (0.5, 0.76), (0.24, 0.5)], fill=INK)


def sym_quarry(d, s):               # open-pit X + dot
    lw = _lw(s, 0.11)
    _line(d, s, [(0.22, 0.22), (0.78, 0.78)], width=lw)
    _line(d, s, [(0.78, 0.22), (0.22, 0.78)], width=lw)


def sym_cave(d, s):                 # cave entrance — open arch
    lw = _lw(s, 0.10)
    d.arc([0.18 * s, 0.24 * s, 0.82 * s, 0.96 * s], start=180, end=360, fill=INK, width=lw)
    _line(d, s, [(0.18, 0.60), (0.18, 0.84)], width=lw)
    _line(d, s, [(0.82, 0.60), (0.82, 0.84)], width=lw)


def sym_spring(d, s):               # circle + wavy tail (water)
    lw = _lw(s, 0.09)
    _circle(d, s, 0.5, 0.36, 0.18, outline=WATER, width=lw)
    _line(d, s, [(0.5, 0.54), (0.5, 0.70), (0.66, 0.78), (0.5, 0.88)], fill=WATER, width=lw)


def sym_well(d, s):                 # circle + center dot (water)
    lw = _lw(s, 0.09)
    _circle(d, s, 0.5, 0.5, 0.30, outline=WATER, width=lw)
    _circle(d, s, 0.5, 0.5, 0.07, fill=WATER)


def sym_drinking_water(d, s):       # faucet + falling drop (potable), blue, no bg
    lw = _lw(s, 0.10)
    _line(d, s, [(0.32, 0.16), (0.32, 0.44)], fill=WATER, width=lw)   # riser
    _line(d, s, [(0.32, 0.28), (0.62, 0.28)], fill=WATER, width=lw)   # spout
    _line(d, s, [(0.62, 0.28), (0.62, 0.42)], fill=WATER, width=lw)   # spout tip
    _poly(d, s, [(0.62, 0.52), (0.71, 0.66), (0.53, 0.66)], fill=WATER)  # drop
    _circle(d, s, 0.62, 0.625, 0.075, fill=WATER)


def sym_peak(d, s):                 # summit triangle (USGS topo) — open brown line, no bg
    _poly(d, s, [(0.5, 0.18), (0.83, 0.80), (0.17, 0.80)], outline=BROWN, width=_lw(s, 0.11))


def sym_tank(d, s):                 # filled square (storage tank)
    d.rectangle([0.24 * s, 0.24 * s, 0.76 * s, 0.76 * s], fill=INK)


def sym_gaging_station(d, s):       # circle + top tick (gauge)
    lw = _lw(s, 0.09)
    _circle(d, s, 0.5, 0.54, 0.28, outline=WATER, width=lw)
    _line(d, s, [(0.5, 0.10), (0.5, 0.30)], fill=WATER, width=lw)


def sym_boat_ramp(d, s):            # ramp line + boat hull (water)
    lw = _lw(s, 0.09)
    _line(d, s, [(0.16, 0.78), (0.84, 0.50)], fill=WATER, width=lw)
    _poly(d, s, [(0.42, 0.30), (0.72, 0.30), (0.57, 0.50)], fill=WATER)


def sym_winter_rec(d, s):           # snowflake — winter recreation (icy blue)
    SNOW = (62, 116, 162, 255)
    lw = _lw(s, 0.065)
    cx, cy, R = 0.5, 0.5, 0.37
    for a in range(0, 360, 60):
        r = math.radians(a)
        ex, ey = cx + R * math.cos(r), cy + R * math.sin(r)
        _line(d, s, [(cx, cy), (ex, ey)], fill=SNOW, width=lw)
        # paired V-branches part-way out each spoke, like a real ice crystal
        for frac, blen in ((0.55, 0.13), (0.80, 0.10)):
            bx, by = cx + R * frac * math.cos(r), cy + R * frac * math.sin(r)
            for da in (-60, 60):
                rr = math.radians(a + da)
                _line(d, s, [(bx, by), (bx + blen * math.cos(rr), by + blen * math.sin(rr))],
                      fill=SNOW, width=lw)


def sym_school(d, s):               # building + flag
    lw = _lw(s, 0.07)
    d.rectangle([0.22 * s, 0.40 * s, 0.78 * s, 0.82 * s], fill=INK)
    _line(d, s, [(0.5, 0.40), (0.5, 0.14)], width=lw)
    _poly(d, s, [(0.5, 0.14), (0.74, 0.22), (0.5, 0.30)], fill=INK)


def sym_place_of_worship(d, s):     # building + cross
    lw = _lw(s, 0.08)
    d.rectangle([0.26 * s, 0.42 * s, 0.74 * s, 0.82 * s], fill=INK)
    _line(d, s, [(0.5, 0.10), (0.5, 0.42)], width=lw)
    _line(d, s, [(0.38, 0.20), (0.62, 0.20)], width=lw)


def sym_aerodrome(d, s):            # airplane silhouette
    lw = _lw(s, 0.10)
    _line(d, s, [(0.5, 0.14), (0.5, 0.86)], width=lw)         # fuselage
    _line(d, s, [(0.14, 0.50), (0.86, 0.50)], width=lw)       # wings
    _line(d, s, [(0.36, 0.80), (0.64, 0.80)], width=lw)       # tailplane


def sym_tower(d, s):                # A-frame tower + top dot
    lw = _lw(s, 0.08)
    _line(d, s, [(0.5, 0.18), (0.22, 0.84)], width=lw)
    _line(d, s, [(0.5, 0.18), (0.78, 0.84)], width=lw)
    _line(d, s, [(0.34, 0.55), (0.66, 0.55)], width=lw)
    _circle(d, s, 0.5, 0.18, 0.08, fill=INK)


def sym_substation(d, s):           # square outline + diagonal
    lw = _lw(s, 0.08)
    d.rectangle([0.20 * s, 0.20 * s, 0.80 * s, 0.80 * s], outline=INK, width=lw)
    _line(d, s, [(0.20, 0.80), (0.80, 0.20)], width=lw)


def sym_pumping_plant(d, s):        # circle + filled inner circle
    lw = _lw(s, 0.08)
    _circle(d, s, 0.5, 0.5, 0.30, outline=INK, width=lw)
    _circle(d, s, 0.5, 0.5, 0.13, fill=INK)


def sym_falls(d, s):                # waterfall — stacked blue ticks
    lw = _lw(s, 0.10)
    for y in (0.34, 0.52, 0.70):
        _line(d, s, [(0.24, y), (0.76, y)], fill=WATER, width=lw)
    _line(d, s, [(0.5, 0.20), (0.5, 0.84)], fill=WATER, width=max(1, lw // 2))


def sym_benchmark(d, s):            # horizontal-control monument — filled triangle
    _poly(d, s, [(0.5, 0.18), (0.84, 0.80), (0.16, 0.80)], fill=INK)
    _circle(d, s, 0.5, 0.62, 0.07, fill=CLEAR)


def sym_spot_elevation(d, s):       # bare × cross
    lw = _lw(s, 0.11)
    _line(d, s, [(0.24, 0.24), (0.76, 0.76)], width=lw)
    _line(d, s, [(0.76, 0.24), (0.24, 0.76)], width=lw)


def sym_monument(d, s):             # open triangle (location monument)
    lw = _lw(s, 0.09)
    _poly(d, s, [(0.5, 0.18), (0.84, 0.80), (0.16, 0.80)], outline=INK, width=lw)


# ── Recreation & services ───────────────────────────────────────────────────────
# Quad-style monochrome glyphs for the amenity/recreation points the basemap
# carries (restrooms, parking, food, culture, parks). These replace the stray
# Protomaps "light" base icons so every POI on the map shares one hand-drawn look.

def sym_toilets(d, s):              # restroom — line-drawn man + woman + divider
    lw = _lw(s, 0.08)
    _line(d, s, [(0.5, 0.20), (0.5, 0.80)], width=max(1, lw // 2))      # divider
    # man (left): head + torso + splayed legs
    _circle(d, s, 0.30, 0.26, 0.085, outline=INK, width=lw)
    _line(d, s, [(0.30, 0.345), (0.30, 0.58)], width=lw)               # torso
    _line(d, s, [(0.30, 0.58), (0.22, 0.80)], width=lw)               # left leg
    _line(d, s, [(0.30, 0.58), (0.38, 0.80)], width=lw)               # right leg
    # woman (right): head + dress (open triangle) + legs
    _circle(d, s, 0.70, 0.26, 0.085, outline=INK, width=lw)
    _line(d, s, [(0.70, 0.345), (0.59, 0.62)], width=lw)             # dress left
    _line(d, s, [(0.70, 0.345), (0.81, 0.62)], width=lw)             # dress right
    _line(d, s, [(0.59, 0.62), (0.81, 0.62)], width=lw)             # hem
    _line(d, s, [(0.655, 0.62), (0.635, 0.80)], width=lw)           # left leg
    _line(d, s, [(0.745, 0.62), (0.765, 0.80)], width=lw)           # right leg


def sym_parking(d, s):              # "P" on a square (USGS wayfinding)
    lw = _lw(s, 0.08)
    d.rectangle([0.16 * s, 0.16 * s, 0.84 * s, 0.84 * s], outline=INK, width=lw)
    pw = _lw(s, 0.11)
    _line(d, s, [(0.40, 0.26), (0.40, 0.76)], width=pw)
    d.arc([0.24 * s, 0.26 * s, 0.56 * s, 0.58 * s], start=270, end=90, fill=INK, width=pw)


def sym_hospital(d, s):             # medical cross (Greek, equal arms) — red
    t = 0.11
    d.rectangle([(0.5 - t) * s, 0.20 * s, (0.5 + t) * s, 0.80 * s], fill=RED)
    d.rectangle([0.20 * s, (0.5 - t) * s, 0.80 * s, (0.5 + t) * s], fill=RED)


def sym_fuel(d, s):                 # fuel pump
    lw = _lw(s, 0.08)
    d.rectangle([0.22 * s, 0.24 * s, 0.54 * s, 0.82 * s], outline=INK, width=lw)
    _line(d, s, [(0.28, 0.36), (0.48, 0.36)], width=max(1, lw // 2))    # gauge window
    _line(d, s, [(0.54, 0.34), (0.66, 0.34), (0.66, 0.58)], width=lw)   # hose arm
    _circle(d, s, 0.66, 0.62, 0.05, fill=INK)                          # nozzle tip


def sym_viewpoint(d, s):            # scenic overlook — binoculars
    lw = _lw(s, 0.09)
    _circle(d, s, 0.34, 0.58, 0.17, outline=INK, width=lw)
    _circle(d, s, 0.66, 0.58, 0.17, outline=INK, width=lw)
    _line(d, s, [(0.30, 0.42), (0.34, 0.30)], width=lw)                # left eyepiece
    _line(d, s, [(0.70, 0.42), (0.66, 0.30)], width=lw)                # right eyepiece
    _line(d, s, [(0.34, 0.30), (0.66, 0.30)], width=lw)                # bridge


def sym_information(d, s):          # "i" in a circle
    lw = _lw(s, 0.08)
    _circle(d, s, 0.5, 0.5, 0.34, outline=INK, width=lw)
    _circle(d, s, 0.5, 0.30, 0.045, fill=INK)
    _line(d, s, [(0.5, 0.42), (0.5, 0.70)], width=_lw(s, 0.10))


def sym_shelter(d, s):              # trail lean-to: single-slope roof on posts
    lw = _lw(s, 0.08)
    _line(d, s, [(0.16, 0.34), (0.84, 0.52)], width=lw)                # sloped roof
    _line(d, s, [(0.20, 0.36), (0.20, 0.80)], width=lw)                # tall post
    _line(d, s, [(0.80, 0.50), (0.80, 0.80)], width=lw)                # short post
    _line(d, s, [(0.20, 0.80), (0.80, 0.80)], width=lw)                # floor


def sym_alpine_hut(d, s):           # mountain hut — house outline + gable roof
    lw = _lw(s, 0.08)
    d.rectangle([0.28 * s, 0.46 * s, 0.72 * s, 0.82 * s], outline=INK, width=lw)
    _line(d, s, [(0.20, 0.48), (0.50, 0.22), (0.80, 0.48)], width=lw)


def sym_park(d, s):                 # city park — single shade tree
    _circle(d, s, 0.5, 0.40, 0.25, fill=GREEN)
    _line(d, s, [(0.5, 0.62), (0.5, 0.84)], fill=BROWN, width=_lw(s, 0.09))


def sym_forest(d, s):               # conifer stand
    _poly(d, s, [(0.32, 0.18), (0.16, 0.64), (0.48, 0.64)], fill=GREEN)
    _poly(d, s, [(0.66, 0.30), (0.52, 0.70), (0.80, 0.70)], fill=GREEN)
    _line(d, s, [(0.32, 0.64), (0.32, 0.80)], fill=BROWN, width=_lw(s, 0.06))
    _line(d, s, [(0.66, 0.70), (0.66, 0.84)], fill=BROWN, width=_lw(s, 0.06))


def sym_restaurant(d, s):           # fork + knife
    lw = _lw(s, 0.07)
    _line(d, s, [(0.33, 0.32), (0.33, 0.82)], width=lw)                # fork handle
    for cx in (0.27, 0.33, 0.39):
        _line(d, s, [(cx, 0.18), (cx, 0.34)], width=lw)               # tines
    _line(d, s, [(0.27, 0.34), (0.39, 0.34)], width=lw)
    _line(d, s, [(0.65, 0.18), (0.65, 0.82)], width=lw)                # knife back
    _poly(d, s, [(0.65, 0.18), (0.74, 0.34), (0.65, 0.48)], fill=INK)  # blade


def sym_cafe(d, s):                 # coffee cup + saucer + steam
    lw = _lw(s, 0.08)
    d.rectangle([0.28 * s, 0.42 * s, 0.60 * s, 0.70 * s], outline=INK, width=lw)
    d.arc([0.56 * s, 0.44 * s, 0.78 * s, 0.66 * s], start=300, end=60, fill=INK, width=lw)
    _line(d, s, [(0.22, 0.76), (0.66, 0.76)], width=lw)                # saucer
    _line(d, s, [(0.37, 0.26), (0.37, 0.36)], width=max(1, lw // 2))   # steam
    _line(d, s, [(0.49, 0.26), (0.49, 0.36)], width=max(1, lw // 2))


def sym_fast_food(d, s):            # takeaway cup + straw
    lw = _lw(s, 0.08)
    _poly(d, s, [(0.34, 0.42), (0.66, 0.42), (0.62, 0.84), (0.38, 0.84)], outline=INK, width=lw)
    _line(d, s, [(0.30, 0.42), (0.70, 0.42)], width=lw)                # lid rim
    _line(d, s, [(0.33, 0.34), (0.67, 0.34)], width=lw)                # lid top
    _line(d, s, [(0.57, 0.34), (0.63, 0.18)], width=lw)               # straw


def sym_bar(d, s):                  # cocktail glass
    lw = _lw(s, 0.08)
    _poly(d, s, [(0.24, 0.24), (0.76, 0.24), (0.5, 0.54)], outline=INK, width=lw)
    _line(d, s, [(0.5, 0.54), (0.5, 0.78)], width=lw)                  # stem
    _line(d, s, [(0.34, 0.80), (0.66, 0.80)], width=lw)               # foot


def sym_supermarket(d, s):          # shopping cart
    lw = _lw(s, 0.08)
    _poly(d, s, [(0.30, 0.34), (0.80, 0.34), (0.71, 0.62), (0.38, 0.62)], outline=INK, width=lw)
    _line(d, s, [(0.30, 0.34), (0.20, 0.24)], width=lw)               # handle
    _circle(d, s, 0.44, 0.74, 0.055, fill=INK)
    _circle(d, s, 0.66, 0.74, 0.055, fill=INK)


def sym_convenience(d, s):          # shopping bag
    lw = _lw(s, 0.08)
    d.rectangle([0.30 * s, 0.38 * s, 0.70 * s, 0.82 * s], outline=INK, width=lw)
    d.arc([0.37 * s, 0.24 * s, 0.63 * s, 0.52 * s], start=180, end=360, fill=INK, width=lw)


def sym_library(d, s):              # open book
    lw = _lw(s, 0.08)
    _poly(d, s, [(0.5, 0.32), (0.18, 0.40), (0.18, 0.74), (0.5, 0.66)], outline=INK, width=lw)
    _poly(d, s, [(0.5, 0.32), (0.82, 0.40), (0.82, 0.74), (0.5, 0.66)], outline=INK, width=lw)


def sym_museum(d, s):               # classical building — pediment + columns
    lw = _lw(s, 0.07)
    _poly(d, s, [(0.5, 0.18), (0.14, 0.40), (0.86, 0.40)], fill=INK)
    d.rectangle([0.16 * s, 0.74 * s, 0.84 * s, 0.82 * s], fill=INK)
    for cx in (0.26, 0.42, 0.58, 0.74):
        _line(d, s, [(cx, 0.44), (cx, 0.72)], width=lw)


def sym_theatre(d, s):              # performing-arts mask
    lw = _lw(s, 0.08)
    _circle(d, s, 0.5, 0.5, 0.30, outline=INK, width=lw)
    _circle(d, s, 0.40, 0.44, 0.035, fill=INK)
    _circle(d, s, 0.60, 0.44, 0.035, fill=INK)
    d.arc([0.36 * s, 0.46 * s, 0.64 * s, 0.70 * s], start=20, end=160, fill=INK, width=lw)


# ── Line symbols (placed repeatedly along a line) ───────────────────────────────

def sym_rail_tie(d, s):             # single crosstie perpendicular to the rail
    d.rectangle([0.44 * s, 0.08 * s, 0.56 * s, 0.92 * s], fill=INK)


def sym_pylon(d, s):                # transmission tower — vertical mast + 2 crossarms
    lw = _lw(s, 0.09)
    _line(d, s, [(0.5, 0.10), (0.5, 0.90)], width=lw)          # mast
    _line(d, s, [(0.22, 0.34), (0.78, 0.34)], width=lw)        # upper crossarm
    _line(d, s, [(0.28, 0.56), (0.72, 0.56)], width=lw)        # lower crossarm


def sym_pipeline_marker(d, s):      # hollow bead for the pipeline "string of beads"
    lw = _lw(s, 0.16)
    _circle(d, s, 0.5, 0.5, 0.26, outline=BROWN, width=lw)


def sym_levee_tick(d, s):           # short perpendicular tick (levee hatch)
    d.rectangle([0.46 * s, 0.10 * s, 0.54 * s, 0.55 * s], fill=BROWN)
