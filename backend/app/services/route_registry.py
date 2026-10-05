# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Curated long-trail registry + per-route identity helpers.

`CURATED_TRAILS` is the single source of truth for famous thru-hikes: a canonical
display name, an OSM name regex that matches the parent AND its segment
sub-relations, and a fetch bbox. The build fetches each as a relation hierarchy
and MERGES every matched relation into ONE trail under the canonical name — so a
trail OSM models as dozens of disconnected segment relations (ADT, Senda
Pirenaica/GR11, North Country) renders as a single, properly-named route with its
segments as sections, instead of shattering. Anything not curated falls back to
the data-driven colour/abbr below (+ a generic prefix-grouping pass in the build).
"""

from __future__ import annotations

import colorsys
import hashlib
import re

# Every long-trail route line shares ONE min_zoom so they all fade in together at
# overview zoom (no trail "renders slightly after" the rest). Network class no
# longer gates the global overview — length/curation already qualified them. z3 =
# one zoom level earlier than the rest of the map detail (the archive is z3-12).
ROUTE_MIN_ZOOM = 3

# Default coverage for the broad US network sweep (contiguous US).
GLOBAL_ROUTES_BBOX = (-125.0, 24.0, -66.5, 49.5)
GLOBAL_ROUTE_CELL_DEG = 10.0
GLOBAL_ROUTE_NETWORKS = "iwn|nwn|rwn"  # international/national/regional (skip lwn)

# The broad sweep pulls in many short regional loops; require this total length so
# only genuine thru-hikes survive. Curated trails bypass it (hand-vetted).
LONG_TRAIL_MIN_M = 112000.0  # ~70 miles

# Badge fan-out slots so where several long trails share one tread (PCT + Tahoe
# Rim) their shields don't stack; and the along-trail spacing of anchored badges.
ROUTE_BADGE_SLOTS = 5
BADGE_INTERVAL_M = 12000.0   # one emblem every ~12 km of trail (anchored, tiered)

_ABBR_STOP = {"the", "trail", "track", "way", "path", "route", "de", "du", "of",
              "la", "le", "des", "national", "scenic", "da", "del", "grande",
              "sentiero", "camino", "und", "and"}

# Curated thru-hikes: (canonical_name, name_regex, (W, S, E, N)). The regex is
# deliberately broad (no tight `$`) so it also matches the trail's segment
# sub-relations ("ADT - …", "… Section A", "Senda Pirenaica - E01"); the build
# merges every match under canonical_name. US majors first (the user's home turf).
CURATED_TRAILS: list[tuple[str, str, tuple[float, float, float, float]]] = [
    # ── North America majors ──
    ("Pacific Crest Trail",        r"^Pacific Crest Trail",            (-125.0, 32.0, -116.0, 49.5)),
    ("Continental Divide Trail",   r"^Continental Divide",             (-114.5, 31.3, -104.0, 49.2)),
    ("Appalachian Trail",          r"^Appalachian Trail",              (-84.5, 33.5, -67.8, 46.0)),
    ("Arizona Trail",              r"^Arizona( National Scenic)? Trail", (-113.5, 31.3, -111.0, 37.2)),
    ("Colorado Trail",             r"^Colorado Trail",                 (-108.5, 37.2, -104.8, 39.7)),
    ("American Discovery Trail",   r"American Discovery Trail|^ADT[ -]", (-123.5, 36.5, -75.0, 42.0)),
    ("North Country Trail",        r"^North Country",                  (-104.5, 39.0, -73.0, 49.4)),
    ("Florida Trail",              r"^Florida( National Scenic)? Trail", (-87.5, 24.5, -80.0, 31.0)),
    ("Pacific Northwest Trail",    r"^Pacific Northwest",              (-124.9, 47.8, -104.0, 49.4)),
    ("Ice Age Trail",              r"^Ice Age",                        (-92.9, 42.4, -87.0, 45.9)),
    ("Potomac Heritage Trail",     r"^Potomac Heritage",               (-80.5, 38.0, -76.5, 41.0)),
    ("John Muir Trail",            r"^John Muir Trail",                (-119.6, 36.5, -118.2, 37.9)),
    ("Long Trail",                 r"^Long Trail",                     (-73.5, 42.7, -72.4, 45.1)),
    ("Superior Hiking Trail",      r"^Superior Hiking Trail",          (-92.1, 46.6, -89.4, 48.1)),
    ("New England Trail",          r"^New England( National Scenic)? Trail", (-73.1, 41.0, -72.3, 42.8)),
    # Ozark Trail (MO) and Ozark Highlands Trail (AR) are DIFFERENT trails — keep
    # the regexes precise so they don't merge into one disconnected blob.
    ("Ozark Trail",                r"^Ozark Trail",                    (-92.0, 36.5, -90.0, 38.7)),
    ("Ozark Highlands Trail",      r"^Ozark Highlands Trail",          (-94.1, 35.3, -92.4, 36.3)),
    # Hayduke is modelled as separate "Hayduke Trail #1…#14" relations with no
    # parent — curate it so they merge into one trail (and bypass the length filter).
    ("Hayduke Trail",              r"^Hayduke Trail",                  (-114.5, 35.8, -108.8, 39.0)),
    ("Benton MacKaye Trail",       r"^Benton MacKaye Trail",           (-84.8, 34.5, -83.5, 36.1)),
    ("Mountains-to-Sea Trail",     r"^Mountains[- ]to[- ]Sea Trail",   (-84.0, 34.9, -75.4, 36.6)),
    ("Sheltowee Trace",            r"^Sheltowee Trace",                (-84.6, 36.5, -83.0, 38.6)),
    ("Tahoe Rim Trail",            r"^Tahoe Rim Trail",                (-120.3, 38.7, -119.8, 39.4)),
    ("Oregon Coast Trail",         r"^Oregon Coast Trail",             (-124.6, 41.9, -123.6, 46.3)),
    # ── Europe ──
    ("Tour du Mont Blanc",         r"Tour du Mont.?Blanc",             (6.5, 45.6, 7.3, 46.1)),
    ("GR20",                       r"GR ?20",                          (8.4, 41.3, 9.6, 42.8)),
    ("GR10",                       r"GR ?10",                          (-2.0, 42.3, 3.3, 43.5)),
    ("Senda Pirenaica (GR11)",     r"GR ?11|Senda Pirenaica",          (-2.0, 42.2, 3.4, 43.0)),
    ("Haute Route",                r"Haute Route",                     (6.8, 45.7, 8.2, 46.4)),
    ("Camino de Santiago",         r"Camino de Santiago|Camino Franc|Way of Saint James|Jakobsweg",
                                                                       (-9.3, 41.8, -1.0, 43.9)),
    ("West Highland Way",          r"West Highland Way",               (-5.2, 55.8, -4.3, 56.9)),
    ("Pennine Way",                r"Pennine Way",                     (-2.7, 53.3, -1.9, 55.7)),
    ("South West Coast Path",      r"South West Coast Path",           (-6.5, 50.0, -2.4, 51.4)),
    ("Kungsleden",                 r"Kungsleden",                      (12.0, 65.5, 21.0, 69.2)),
    ("Laugavegur",                 r"Laugavegur",                      (-19.6, 63.6, -18.6, 64.4)),
    ("Alta Via 1",                 r"Alta Via 1",                      (11.6, 46.1, 12.6, 46.8)),
    ("GR5 (Grande Traversée des Alpes)", r"GR ?5|Grande Travers.e des Alpes", (5.8, 43.6, 7.6, 46.5)),
    ("Cape Wrath Trail",           r"Cape Wrath Trail",                (-5.6, 56.7, -4.6, 58.7)),
    ("Coast to Coast",             r"Coast to Coast",                  (-3.6, 54.2, -0.5, 54.8)),
    ("Camino del Norte",           r"Camino del Norte",                (-9.3, 43.0, -1.7, 43.6)),
    ("Rota Vicentina",             r"Rota Vicentina|Fishermen's Trail", (-9.0, 37.0, -8.5, 38.1)),
    # ── Asia / Middle East ──
    ("Israel National Trail",      r"Israel National Trail|Israel Trail", (34.0, 29.4, 35.9, 33.4)),
    ("Lycian Way",                 r"Lycian Way|Likya Yolu",           (28.8, 36.0, 30.6, 37.1)),
    ("Jordan Trail",               r"Jordan Trail",                    (34.9, 29.1, 36.4, 32.9)),
    ("Annapurna Circuit",          r"Annapurna Circuit",               (83.4, 28.2, 84.6, 29.0)),
    ("Everest Base Camp",          r"Everest Base Camp|Khumbu",        (86.4, 27.5, 87.1, 28.3)),
    ("Kumano Kodo",                r"Kumano Kodo",                     (135.3, 33.6, 136.3, 34.3)),
    ("Manaslu Circuit",            r"Manaslu",                         (84.4, 28.3, 85.2, 28.8)),
    ("Markha Valley",              r"Markha",                          (77.3, 33.6, 77.8, 34.1)),
    # ── Oceania ──
    ("Te Araroa",                  r"Te Araroa",                       (166.0, -47.5, 179.5, -34.0)),
    ("Milford Track",              r"Milford Track",                   (167.5, -45.1, 168.3, -44.4)),
    ("Routeburn Track",            r"Routeburn",                       (167.9, -45.1, 168.4, -44.5)),
    ("Overland Track",             r"Overland Track",                  (145.6, -42.3, 146.4, -41.4)),
    ("Larapinta Trail",            r"Larapinta",                       (132.4, -24.2, 134.2, -22.8)),
    ("Bibbulmun Track",            r"Bibbulmun",                       (115.4, -35.3, 116.7, -31.6)),
    ("Australian Alps Walking Track", r"Australian Alps Walking",      (146.0, -37.6, 148.9, -35.4)),
    ("Kepler Track",               r"Kepler Track",                    (167.4, -45.6, 167.9, -45.2)),
    ("Abel Tasman Coast Track",    r"Abel Tasman",                     (172.8, -41.1, 173.2, -40.7)),
    ("Tongariro Northern Circuit", r"Tongariro Northern Circuit|Tongariro Alpine Crossing", (175.4, -39.35, 175.85, -38.95)),
    ("Heaphy Track",               r"Heaphy",                          (172.0, -41.3, 172.7, -40.8)),
    ("Rakiura Track",              r"Rakiura",                         (167.8, -47.05, 168.35, -46.6)),
    ("Paparoa Track",              r"Paparoa",                         (171.2, -42.3, 171.8, -41.9)),
    ("Lake Waikaremoana Track",    r"Waikaremoana",                    (176.9, -38.95, 177.35, -38.55)),
    ("Three Capes Track",          r"Three Capes",                     (147.8, -43.35, 148.05, -42.95)),
    ("Cape to Cape Track",         r"Cape to Cape",                    (114.9, -34.4, 115.2, -33.5)),
    ("Heysen Trail",               r"Heysen",                          (138.4, -35.7, 139.2, -32.7)),
    ("Great Ocean Walk",           r"Great Ocean Walk",                (143.0, -38.9, 143.6, -38.6)),
    # ── South America ──
    ("Inca Trail",                 r"Inca Trail|Camino Inca",          (-72.7, -13.5, -71.9, -12.9)),
    ("Torres del Paine Circuit",   r"Torres del Paine|Paine Circuit",  (-73.3, -51.5, -72.6, -50.7)),
    ("Huayhuash Circuit",          r"Huayhuash",                       (-77.1, -10.5, -76.6, -9.9)),
    ("Ciudad Perdida",             r"Ciudad Perdida|Lost City|Teyuna", (-74.2, 10.9, -73.8, 11.3)),
    ("Choquequirao",               r"Choquequirao",                    (-73.1, -13.5, -72.5, -13.1)),
    # ── Africa ──
    ("Otter Trail",                r"Otter Trail",                     (23.3, -34.2, 24.3, -33.8)),
    ("Drakensberg Traverse",       r"Drakensberg",                     (28.7, -30.4, 29.8, -28.5)),
    ("Fish River Canyon",          r"Fish River Canyon",               (17.4, -28.1, 18.0, -27.4)),
    ("Toubkal Circuit",            r"Toubkal",                         (-8.2, 30.9, -7.6, 31.3)),
    ("Simien Traverse",            r"Simien|Semien",                   (37.8, 12.9, 38.5, 13.4)),
    # ── Central America / Canada ──
    ("Camino de Costa Rica",       r"Camino de Costa Rica|Camino Costa Rica", (-86.0, 8.0, -82.5, 11.2)),
    ("Great Divide Trail",         r"Great Divide Trail",              (-122.0, 48.9, -114.0, 54.6)),
    ("Sunshine Coast Trail",       r"Sunshine Coast Trail",            (-124.9, 49.5, -124.3, 50.2)),
    ("West Coast Trail",           r"West Coast Trail",                (-125.7, 48.5, -124.6, 49.0)),
]

# Pre-compiled curated matchers (Python side: sweep-skip + grouping).
_CURATED_RES = [(name, re.compile(rx, re.I)) for name, rx, _ in CURATED_TRAILS]


def canonical_for(name: str) -> str | None:
    """Canonical curated trail name a route name belongs to, or None."""
    for canon, rx in _CURATED_RES:
        if rx.search(name or ""):
            return canon
    return None


def _route_color(name: str) -> str:
    """Deterministic, pleasant per-route colour (stable hash of the name → hue)."""
    h = int(hashlib.md5((name or "route").encode("utf-8")).hexdigest()[:8], 16)
    r, g, b = colorsys.hls_to_rgb((h % 360) / 360.0, 0.42, 0.62)
    return "#%02X%02X%02X" % (int(r * 255), int(g * 255), int(b * 255))


def _route_abbr(name: str) -> str:
    """Short uppercase badge label derived from the route name."""
    words = [w for w in re.split(r"[^A-Za-z0-9]+", name or "") if w and w.lower() not in _ABBR_STOP]
    if not words:
        return (name[:3].upper() if name else "TR")
    if len(words) == 1:
        return words[0][:3].upper()
    return "".join(w[0] for w in words[:4]).upper()


def _route_slot(name: str) -> int:
    h = int(hashlib.md5((name or "route").encode("utf-8")).hexdigest()[:8], 16)
    return h % ROUTE_BADGE_SLOTS
