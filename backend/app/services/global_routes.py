# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Build the region-independent long-route overview (master_routes.pmtiles).

Curated famous thru-hikes are fetched BY NAME as relation hierarchies and merged
to one trail each (route_hierarchy); the broad US network sweep fills in the rest,
with a generic prefix-grouping pass so any still-fragmented trail is unified under
its common name. Lets long trails show everywhere, not only inside downloaded
regions. Each route's per-section geometry is persisted for click-to-detail.
"""

from __future__ import annotations

import json
import logging
import re
import shutil
import time
from pathlib import Path


from app.config import settings
from app.services.overpass_client import POLITE_DELAY_S, _split_bbox
from app.services.route_geometry import _assemble_coords, _coords_length_m
from app.services.route_hierarchy import (
    _relation_to_trail, _trail_to_features, fetch_route_hierarchy, merge_trails,
)
from app.services.route_registry import (
    CURATED_TRAILS, GLOBAL_ROUTE_CELL_DEG, GLOBAL_ROUTE_NETWORKS, GLOBAL_ROUTES_BBOX,
    LONG_TRAIL_MIN_M, canonical_for,
)
from app.services.trail_fetch import fetch_routes

logger = logging.getLogger(__name__)


def _routes_index_dir() -> Path:
    return Path(settings.map_data_dir) / "routes"


def write_route_index(entry: dict) -> None:
    """Persist one route's section geometry/distances for the detail endpoint."""
    d = _routes_index_dir()
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{entry['id']}.json").write_text(json.dumps(entry, separators=(",", ":")))


def _name_prefix(name: str) -> str:
    """Common trail name before a segment marker ("ADT - …" → "ADT",
    "Hayduke Trail #12" → "Hayduke Trail")."""
    n = re.sub(r"\s+#\s*\d+.*$", "", name or "")   # strip a trailing "#N …"
    for sep in (" - ", " ("):
        if sep in n:
            return n.split(sep)[0].strip()
    return n.strip()


def build_global_routes(output_path, bbox=GLOBAL_ROUTES_BBOX,
                        min_zoom: int = 3, max_zoom: int = 12, progress_cb=None) -> int:
    """Fetch + tile long routes across `bbox` (z3-12). Returns feature count."""
    def _report(pct, detail):
        if progress_cb is not None:
            try:
                progress_cb(pct, detail)
            except Exception:
                pass

    feats: list[tuple] = []
    seen: set = set()
    shutil.rmtree(_routes_index_dir(), ignore_errors=True)

    def _emit(trail: dict) -> int:
        line_feats, point_feats, entry = _trail_to_features(trail)
        if not line_feats:
            return 0
        feats.extend(line_feats)
        feats.extend(point_feats)
        write_route_index(entry)
        return len(line_feats)

    # ── Curated famous thru-hikes FIRST, each merged to one trail under its
    #    canonical name. Mark every matched relation id seen so the sweep skips it. ──
    for j, (canonical, regex, tb) in enumerate(CURATED_TRAILS):
        if j:
            time.sleep(POLITE_DELAY_S)
        try:
            trails = fetch_route_hierarchy(regex, tb)
        except Exception as exc:
            logger.warning("Curated trail '%s' failed: %s", canonical, exc)
            continue
        if not trails:
            continue
        for t in trails:
            _seen_add(seen, t["id"])
            for sec in t["sections"]:
                _seen_add(seen, sec["id"])
        added = _emit(merge_trails(trails, canonical))
        logger.info("Curated %d/%d '%s': %d roots, +%d ways",
                    j + 1, len(CURATED_TRAILS), canonical, len(trails), added)
        _report(round((j + 1) / len(CURATED_TRAILS) * 40), f"famous trails {j + 1}")

    # ── Broad network sweep (iwn/nwn/rwn), length-filtered. Collect survivors,
    #    then prefix-group so any still-fragmented trail unifies under its name. ──
    cells = _split_bbox(bbox, GLOBAL_ROUTE_CELL_DEG)
    survivors: list[dict] = []
    for i, cell in enumerate(cells):
        time.sleep(POLITE_DELAY_S)
        try:
            els = fetch_routes(cell, networks=GLOBAL_ROUTE_NETWORKS)
        except Exception as exc:
            logger.warning("Sweep cell %d/%d failed: %s", i + 1, len(cells), exc)
            continue
        for el in els:
            rid = el.get("id")
            if el.get("type") != "relation" or rid in seen:
                continue
            name = (el.get("tags") or {}).get("name", "")
            if canonical_for(name):
                continue   # belongs to a curated trail — don't re-leak as a fragment
            seen.add(rid)
            trail = _relation_to_trail(el)
            coords = _assemble_coords(trail["sections"][0]["ways"])
            if _coords_length_m(coords) < LONG_TRAIL_MIN_M:
                continue
            survivors.append(trail)
        _report(40 + round((i + 1) / len(cells) * 40), f"trail networks {i + 1}/{len(cells)}")

    for canonical, group in _group_by_prefix(survivors):
        _emit(merge_trails(group, canonical) if len(group) > 1 else group[0])

    if not feats:
        logger.warning("No global route features fetched")
        return 0

    _report(80, "writing tiles…")
    _write_tiles(feats, output_path, min_zoom, max_zoom, _report)
    _report(100, "done")
    return len(feats)


def _group_by_prefix(trails: list[dict]) -> list[tuple[str, list[dict]]]:
    """Group sweep survivors by common name prefix; merge groups of ≥3 under that
    prefix (a fragmented un-curated trail), keep the rest individually."""
    groups: dict[str, list[dict]] = {}
    for t in trails:
        groups.setdefault(_name_prefix(t["name"]), []).append(t)
    out: list[tuple[str, list[dict]]] = []
    for prefix, ts in groups.items():
        if len(ts) >= 3:
            out.append((prefix, ts))
        else:
            out.extend((t["name"], [t]) for t in ts)
    return out


def _write_tiles(feats, output_path, min_zoom, max_zoom, report) -> None:
    from app.services import tippecanoe_writer
    output_path = Path(output_path)
    nd = output_path.with_suffix(".routes.ndjson")
    nd.unlink(missing_ok=True)
    try:
        tippecanoe_writer.features_to_ndjson(feats, nd, max_zoom=max_zoom)
        tippecanoe_writer.build_overlay(
            {"routes": nd}, output_path, min_zoom=min_zoom, max_zoom=max_zoom,
            progress_cb=lambda pct: report(80 + round(pct * 0.2), "writing tiles…"))
    finally:
        nd.unlink(missing_ok=True)


def _seen_add(seen: set, val) -> None:
    try:
        seen.add(int(val))
    except (TypeError, ValueError):
        pass
