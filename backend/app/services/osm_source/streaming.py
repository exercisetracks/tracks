# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Single-pass streaming region-build path (osmium export, no dict parse).

The primary region-build path: clip the bbox ONCE, then ``osmium export`` (C++)
the clipped PBF to GeoJSONSeq and stream it one feature at a time. This replaces
the legacy two-pass pyosmium parse (which built millions of Python dicts and was
re-scanned 6×) — see the orchestrator's _build_thematic_streaming. Geometry is
assembled by osmium (multipolygon areas included), so the builders no longer need
their own polygon/relation assembly. Route relations (linear) are handled by a
separate light pyosmium pass — osmium export doesn't emit route-relation geometry.
"""

from __future__ import annotations

import json
from pathlib import Path

from app.services.osm_source.config import logger, osmium_available
from app.services.osm_source.osmium_runner import (
    _extract_region, _run_osmium, ensure_source,
)


def prepare_region_pbf(bbox: tuple[float, float, float, float],
                       cancel=None) -> Path | None:
    """Clip ``bbox`` from the cached slim source into ONE temp region PBF (or None).

    The single extract is shared by both the GeoJSON export and the route-relation
    parse, so the multi-GB slim source is read once per region — not once per
    chunk-cell as the legacy path did (the old per-cell cold-read cost).
    """
    if not osmium_available():
        return None
    source = ensure_source(bbox, cancel=cancel)
    if source is None:
        return None
    return _extract_region(bbox, source, cancel=cancel)


def export_geojson(region_pbf: Path, cancel=None) -> Path | None:
    """``osmium export`` a region PBF → a GeoJSONSeq file (point/line/polygon)."""
    out = region_pbf.with_suffix(".geojsonl")
    out.unlink(missing_ok=True)
    ok = _run_osmium([
        "export", str(region_pbf), "-f", "geojsonseq",
        "--geometry-types=point,linestring,polygon", "-O", "-o", str(out),
    ], timeout=1800, cancel=cancel)
    if not ok or not out.exists():
        out.unlink(missing_ok=True)
        return None
    return out


def iter_geojson_features(geojson_path: Path):
    """Yield ``(geometry_dict, tags_dict)`` for each feature in a GeoJSONSeq file.

    GeoJSONSeq lines are RS-delimited (RFC 8142) — the leading ``\\x1e`` is stripped.
    Streamed line-by-line so a continent-scale export never loads into memory.
    """
    with open(geojson_path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            try:
                feat = json.loads(line)
            except Exception:
                continue
            geom = feat.get("geometry")
            if geom:
                yield geom, (feat.get("properties") or {})


def parse_route_relations(region_pbf: Path) -> list[dict]:
    """Hiking/foot route relations in a region PBF, Overpass-shaped (tags + member
    geometry inlined), for ``trail_builder._route_features``.

    osmium export emits nodes/ways/areas but NOT route-relation geometry, so this
    light pyosmium pass collects just those relations. Two passes (way-id collect,
    then geometry resolve) keep peak memory bounded to the member ways only.
    """
    try:
        import osmium
    except Exception as exc:
        logger.warning("pyosmium import failed (routes): %s", exc)
        return []

    wanted_ways: set[int] = set()
    rel_meta: list[tuple[int, dict, list[tuple[int, str]]]] = []

    class _RelScan(osmium.SimpleHandler):
        def relation(self, r):
            tags = {t.k: t.v for t in r.tags}
            if tags.get("route") not in ("hiking", "foot"):
                return
            members = [(m.ref, m.role) for m in r.members if m.type == "w"]
            rel_meta.append((r.id, tags, members))
            for ref, _ in members:
                wanted_ways.add(ref)

    try:
        _RelScan().apply_file(str(region_pbf))
    except Exception as exc:
        logger.warning("route relation pre-scan failed: %s", exc)
        return []
    if not rel_meta:
        return []

    way_geom: dict[int, list[tuple[float, float]]] = {}

    class _WayGeom(osmium.SimpleHandler):
        def way(self, w):
            if w.id not in wanted_ways:
                return
            pts = []
            for nd in w.nodes:
                try:
                    if nd.location.valid():
                        pts.append((nd.location.lon, nd.location.lat))
                except Exception:
                    continue
            if len(pts) >= 2:
                way_geom[w.id] = pts

    try:
        _WayGeom().apply_file(str(region_pbf), locations=True)
    except Exception as exc:
        logger.warning("route way-geometry pass failed: %s", exc)
        return []

    out: list[dict] = []
    for rid, tags, members in rel_meta:
        mlist = []
        for ref, role in members:
            g = way_geom.get(ref)
            if g:
                mlist.append({"type": "way", "role": role,
                              "geometry": [{"lon": p[0], "lat": p[1]} for p in g]})
        out.append({"type": "relation", "id": rid, "tags": tags, "members": mlist})
    return out
