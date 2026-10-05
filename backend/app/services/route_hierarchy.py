# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Resolve OSM route relation hierarchies into one unified trail + its sections.

OSM models a long trail as a parent super-relation whose members are per-section
sub-relations — sometimes nested several levels deep (national → state → segment),
sometimes with NO unifying parent at all (the segments are flat). This module
fetches the whole tree, flattens it to leaf sections, and MERGES everything a
curated regex matched into one trail under a canonical name, so a trail never
renders shattered. It also turns a unified trail into map features: per-section
lines, section-start markers, and emblem badges anchored at fixed along-trail
intervals (so badges don't drift as you zoom).
"""

from __future__ import annotations

import re

from shapely.geometry import LineString, Point

from app.services.overpass_client import _overpass
from app.services.route_geometry import (
    _assemble_coords, _coords_length_m, _section_distance_m, _ways_to_lines,
)
from app.services.route_registry import (
    BADGE_INTERVAL_M, ROUTE_MIN_ZOOM, _route_abbr, _route_color, _route_slot,
)


def fetch_route_hierarchy(name_regex: str, bbox, timeout: int = 220) -> list[dict]:
    """Fetch matched route relations + their full descendant tree + all ways.

    Returns one dict per top-level (root) relation::
        {"id", "name", "network", "sections": [{"id","name","ways":[…]}, …]}
    Descends ~4 nesting levels (enough for national → state → segment → ways).
    """
    w, s, e, n = bbox
    q = (
        '[out:json][timeout:220];'
        f'rel["route"~"^(hiking|foot)$"]["name"~"{name_regex}",i]({s},{w},{n},{e})->.p0;'
        '.p0 out body;'
        'rel(r.p0)->.p1; .p1 out body;'
        'rel(r.p1)->.p2; .p2 out body;'
        'rel(r.p2)->.p3; .p3 out body;'
        '(way(r.p0); way(r.p1); way(r.p2); way(r.p3);); out geom;'
    )
    els = _overpass(q, timeout)
    rels = {el["id"]: el for el in els if el.get("type") == "relation"}
    ways = {el["id"]: el for el in els if el.get("type") == "way"}
    child_ids = {m.get("ref") for r in rels.values() for m in (r.get("members") or [])
                 if m.get("type") == "relation"}
    used_ways: set = set()
    trails = []
    for rid, rel in rels.items():
        if rid in child_ids:
            continue   # not a root — it's a member of another matched relation
        secs = _flatten_sections(rid, rels, ways, used_ways, set())
        if not secs:
            continue
        tags = rel.get("tags") or {}
        trails.append({"id": str(rid), "name": tags.get("name", ""),
                       "network": tags.get("network", ""), "sections": secs})
    return trails


def _flatten_sections(rid, rels, ways, used_ways, visited) -> list[dict]:
    """DFS a relation tree → leaf sections (each relation that directly holds
    ways is one section, named by that relation). Cycle-guarded; ways deduped."""
    if rid in visited:
        return []
    visited.add(rid)
    rel = rels.get(rid)
    if not rel:
        return []
    out, direct = [], []
    for m in rel.get("members") or []:
        if m.get("type") == "way":
            ref = m.get("ref")
            if ref in ways and ref not in used_ways:
                used_ways.add(ref)
                direct.append(ways[ref])
    if direct:
        out.append({"id": str(rid), "name": (rel.get("tags") or {}).get("name", ""), "ways": direct})
    for m in rel.get("members") or []:
        if m.get("type") == "relation":
            out += _flatten_sections(m.get("ref"), rels, ways, used_ways, visited)
    return out


def merge_trails(trails: list[dict], canonical: str) -> dict:
    """Fold every matched top-level trail into ONE trail under `canonical`.

    Sections deduped by id; a flat "segment-as-parent" (no named section) takes
    its trail's name so the section reads distinctly (e.g. "Senda Pirenaica - E01")."""
    sections, seen, pid, net = [], set(), None, ""
    for t in trails:
        if pid is None:
            pid = t["id"]
        net = net or t.get("network", "")
        for sec in t["sections"]:
            sid = sec["id"] or t["id"]
            if sid in seen:
                continue
            seen.add(sid)
            name = sec["name"] or (t["name"] if t["name"] != canonical else "")
            sections.append({"id": sid, "name": name, "ways": sec["ways"]})
    return {"id": pid or "0", "name": canonical, "network": net, "sections": sections}


def _relation_to_trail(el: dict) -> dict:
    """A single (non-hierarchical) sweep relation → the trail dict shape (one
    "" section = the whole route), so it flows through the same feature path."""
    tags = el.get("tags") or {}
    ways = [m for m in (el.get("members") or [])
            if m.get("type") == "way" and m.get("geometry")]
    return {"id": str(el["id"]), "name": tags.get("name", ""),
            "network": tags.get("network", ""),
            "sections": [{"id": "", "name": "", "ways": ways}]}


def _section_label(parent_name: str, section_name: str) -> str:
    """Short human label for a section, with the redundant trail prefix stripped.

    "PCT - California Section A" → "California Section A". Empty → "" (caller
    falls back to "Section N")."""
    s = (section_name or "").strip()
    if not s:
        return ""
    m = re.match(r"^(\S{1,6})\s*[-:–—]\s+(.+)$", s)
    if m:
        return m.group(2).strip()
    for pre in (f"{parent_name} - ", f"{parent_name}: ", f"{parent_name} "):
        if s.startswith(pre):
            return s[len(pre):].strip() or s
    return s


def _badge_min_zoom(i: int) -> int:
    """Tiered zoom so anchored badges densify with zoom but never move."""
    if i % 8 == 0:
        return max(ROUTE_MIN_ZOOM, 5)
    if i % 4 == 0:
        return 7
    if i % 2 == 0:
        return 9
    return 11


def _badge_points(runs: list[list[list[float]]], base: dict) -> list[tuple]:
    """Emblem Point features at fixed ~12 km intervals along the trail (anchored).

    Each badge also carries its zoom tier + stable index (`tier`/`bidx`) as
    PROPERTIES: the frontend sorts symbol collisions by them so the sparse
    low-tier skeleton always wins placement — zooming in only ever ADDS badges
    between the existing ones, instead of the surviving shield jumping along
    the trail as the collision winner changes."""
    out, idx = [], 0

    def _feat(pt):
        nonlocal idx
        tier = _badge_min_zoom(idx)
        props = {**base, "marker": "badge", "tier": tier, "bidx": idx}
        out.append((pt, props, tier))
        idx += 1

    for run in runs:
        if not run:
            continue
        _feat(Point(run[0][0], run[0][1]))
        acc = 0.0
        for k in range(1, len(run)):
            acc += _coords_length_m([run[k - 1], run[k]])
            if acc >= BADGE_INTERVAL_M:
                acc = 0.0
                _feat(Point(run[k][0], run[k][1]))
    return out


def _trail_to_features(trail: dict):
    """Unified trail → (line_feats, point_feats, index_entry).

    Lines carry ONE parent identity + section_id + feat_key; markers break the
    trail into its sections; badges are anchored along it. Every long-trail line
    shares ROUTE_MIN_ZOOM so all trails fade in together."""
    pname = trail["name"]
    mz = ROUTE_MIN_ZOOM
    rid = trail["id"]
    base = {
        "kind": "route", "use": "foot", "route": 1, "name": pname,
        "network": trail.get("network", ""), "surface": "", "paved": -1,
        "sac_scale": "", "trail_visibility": "", "tracktype": "", "bridge": 0,
        "oneway": 0, "min_zoom": mz, "color": _route_color(pname),
        "abbr": _route_abbr(pname), "slot": _route_slot(pname), "route_id": rid,
    }
    line_feats, point_feats, index_sections, runs, total_m = [], [], [], [], 0.0
    for i, sec in enumerate(trail["sections"]):
        sec_id = sec["id"]
        # Number sections that have no distinct name OR just repeat the parent name
        # (OSM names many sub-relations identically, e.g. every AT state relation is
        # "Appalachian Trail") so each section reads distinctly.
        sec_label = _section_label(pname, sec["name"])
        if not sec_label or sec_label.strip().lower() == pname.strip().lower():
            sec_label = f"Section {i + 1}"
        props = {**base, "section_id": sec_id, "section": sec_label,
                 "feat_key": f"{rid}:{sec_id}"}
        for ls in _ways_to_lines(sec["ways"]):
            line_feats.append((ls, props, mz))
        coords = _assemble_coords(sec["ways"])
        if not coords:
            continue
        dist_m = _section_distance_m(sec["ways"])
        total_m += dist_m
        runs.append(coords)
        if len(coords) > 2:
            coords = [[round(x, 6), round(y, 6)]
                      for x, y in LineString(coords).simplify(0.0002).coords]
        index_sections.append({"id": sec_id, "name": sec_label,
                               "distance_m": round(dist_m), "coords": coords})
        point_feats.append((Point(coords[0][0], coords[0][1]),
                            {**props, "marker": "section"}, max(mz, 10)))
    point_feats += _badge_points(runs, base)
    index_entry = {"id": rid, "name": pname, "abbr": base["abbr"], "color": base["color"],
                   "network": base["network"], "distance_m": round(total_m),
                   "sections": index_sections}
    return line_feats, point_feats, index_entry
