# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Map packs: the files a phone stores to have the map with no signal.

## Why packs

The phone used to fetch an area tile by tile into MapLibre's offline database:
tens of millions of requests for a state, every tile keyed by its URL (so a
version change orphaned them all — audit F1), and one SQLite file for
everything. That cannot reach "50 GB, whole states" (audit §2). Instead the
phone downloads whole PMTiles files, resumably, and answers MapLibre's tile
requests from them itself.

## Layout

A pack is one layer of one **cell**: a z8 tile, about 110 × 156 km at 45°N.
Cells never share a tile at z8 and above, so a cell pack holds exactly the
tiles under it and any number of them sit side by side on a phone.

| Layer | Zooms | Cut from |
|---|---|---|
| `basemap` | 8-15 | planet_basemap z8-12 + master_basemap_detail z13-15 |
| `overlay` | 8-15 | master_overlay |
| `contours` | 9-12 | master_contours |
| `dem` | 6-12 | planet_dem_z7 z6-7 (the cell's parents) + master_dem z8-12 |

Below z8 a tile spans many cells, so those zooms come as **shared** packs, one
of each per phone: `world` (basemap z0-7), `world-dem` (terrain z0-5),
`overlay-low` (overlay z6-7), `routes` (the long-trail overview), and
`assets` (glyphs and sprite, so labels and icons draw offline too).

## Versions and the cache

A pack's version is a hash of what went into it: for the cell layers, the
installed areas near the cell (id and last change); for the static ones, the
source file. A rebuild somewhere else does not invalidate a phone's packs for
the rest of the country. Packs are cut with `pmtiles extract` on first request
— seconds each — and kept in a size-capped cache (`/map-data/packs`, least
recently used first out), never as a permanent second copy of the map.
"""

from __future__ import annotations

import fcntl
import hashlib
import json
import logging
import math
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path

from app.config import settings
from app.services import map_store, region_registry, tile_archives

logger = logging.getLogger(__name__)

CELL_ZOOM = 8
CELL_LAYERS = ("basemap", "overlay", "contours", "dem")
SHARED = ("world", "world-dem", "overlay-low", "routes", "assets")
# The cache keeps at most this share of the masters' size, and never less than
# the floor — enough for a few states being saved at once.
CACHE_FRACTION = 0.10
CACHE_FLOOR = 2 << 30
# Shrinks a cell's box so `pmtiles extract`, which takes every tile touching
# the box, does not take the neighbouring cells' edge tiles too.
_EPS = 1e-7
# Areas this close to a cell can still have put tiles in it (contour rings,
# tippecanoe's tile buffers), so they count toward its version.
_NEAR_DEG = 0.1


def packs_dir() -> Path:
    return map_store.data_dir() / "packs"


def _planet(name: str) -> Path:
    return map_store.data_dir() / f"{name}.pmtiles"


# ── Cells ────────────────────────────────────────────────────────────────────

def cells_for(bbox: list[float]) -> list[tuple[int, int]]:
    """The z8 cells (x, y) a lon/lat box touches."""
    w, s, e, n = bbox
    x0, y0 = _lonlat_to_tile(w, n, CELL_ZOOM)
    x1, y1 = _lonlat_to_tile(e, s, CELL_ZOOM)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def _lonlat_to_tile(lon: float, lat: float, z: int) -> tuple[int, int]:
    n = 2 ** z
    lat = max(min(lat, 85.0511), -85.0511)
    x = int((lon + 180.0) / 360.0 * n)
    y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
    return min(max(x, 0), n - 1), min(max(y, 0), n - 1)


def cell_bounds(x: int, y: int) -> tuple[float, float, float, float]:
    return map_store.tile_bounds(CELL_ZOOM, x, y)


# ── Versions ─────────────────────────────────────────────────────────────────

def _file_identity(path: Path) -> str:
    try:
        st = path.stat()
        return f"{st.st_size}:{int(st.st_mtime)}"
    except FileNotFoundError:
        return "missing"


def _areas_near(x: int, y: int, areas: list[dict]) -> list[dict]:
    from shapely.geometry import box
    w, s, e, n = cell_bounds(x, y)
    near = box(w - _NEAR_DEG, s - _NEAR_DEG, e + _NEAR_DEG, n + _NEAR_DEG)
    return [a for a in areas if region_registry.region_geom(a).intersects(near)]


def cell_version(layer: str, x: int, y: int, areas: list[dict]) -> str:
    parts = [layer, str(x), str(y)]
    parts += [f"{a['id']}@{a.get('updated_at')}" for a in sorted(_areas_near(x, y, areas), key=lambda a: a["id"])]
    if layer == "basemap":
        parts.append(_file_identity(_planet("planet_basemap")))
    if layer == "dem":
        parts.append(_file_identity(_planet("planet_dem_z7")))
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]


def shared_version(name: str) -> str:
    if name == "assets":
        sources = sorted((map_store.data_dir() / "fonts").rglob("*.pbf")) + \
            sorted((map_store.data_dir() / "sprite").glob("*"))
        ident = "|".join(f"{p.name}:{_file_identity(p)}" for p in sources)
    else:
        ident = _file_identity(_shared_source(name))
    return hashlib.sha256(f"{name}|{ident}".encode()).hexdigest()[:16]


def _shared_source(name: str) -> Path:
    return {
        "world": _planet("planet_basemap"),
        "world-dem": _planet("planet_dem_z7"),
        "overlay-low": map_store.master_path(map_store.MASTER_OVERLAY),
        "routes": map_store.master_path("master_routes"),
    }[name]


# ── Manifest ─────────────────────────────────────────────────────────────────

def manifest(bbox: list[float]) -> dict:
    """What a phone needs for `bbox`: its cells' packs and the shared ones.

    A cell layer is offered only where the server has it: the basemap
    everywhere (z8-12 is the planet's), the rest only on installed ground. Sizes
    are those of the built pack when it is cached, else an estimate from the
    archives' directories — exact enough to budget storage with.
    """
    from shapely.geometry import box
    areas = [a for a in region_registry.list_active() if a["status"] == "installed"]
    covered = region_registry.coverage()
    dir_cache: dict = {}
    cells = []
    for x, y in cells_for(bbox):
        cb = box(*cell_bounds(x, y))
        on_ground = covered is not None and covered.intersects(cb)
        layers = {}
        for layer in CELL_LAYERS:
            if layer != "basemap" and not on_ground:
                continue
            if layer == "dem" and not map_store.master_path(map_store.MASTER_DEM).exists():
                continue
            if layer == "contours" and not map_store.master_path(map_store.MASTER_CONTOURS).exists():
                continue
            if layer == "overlay" and not map_store.master_path(map_store.MASTER_OVERLAY).exists():
                continue
            v = cell_version(layer, x, y, areas)
            layers[layer] = _entry(f"{CELL_ZOOM}/{x}/{y}/{layer}", v, lambda: _estimate_cell(layer, x, y, dir_cache))
        cells.append({"cell": f"{CELL_ZOOM}/{x}/{y}", "bounds": cell_bounds(x, y),
                      "covered": on_ground, "layers": layers})
    shared = {}
    for name in SHARED:
        if name != "assets" and not _shared_source(name).exists():
            continue
        shared[name] = _entry(f"shared/{name}", shared_version(name), lambda: _estimate_shared(name))
    total = sum(e["bytes"] for c in cells for e in c["layers"].values()) + \
        sum(e["bytes"] for e in shared.values())
    return {"cell_zoom": CELL_ZOOM, "cells": cells, "shared": shared, "total_bytes": total}


def _entry(key: str, version: str, estimate) -> dict:
    built = _cached(key, version)
    if built is not None:
        meta = json.loads(built.with_suffix(".json").read_text())
        return {"version": version, "bytes": meta["bytes"], "sha256": meta["sha256"],
                "url": f"/api/maps/packs/{key}?v={version}", "ready": True}
    return {"version": version, "bytes": estimate(), "sha256": None,
            "url": f"/api/maps/packs/{key}?v={version}", "ready": False}


def _estimate_cell(layer: str, x: int, y: int, cache: dict | None = None) -> int:
    """Bytes the cell's pack would take, from the sources' directories."""
    total = 0
    for src, minz, maxz in _cell_sources(layer):
        if src.exists():
            total += tile_archives.bytes_in_ranges(
                src, tile_archives.descendant_ranges(CELL_ZOOM, x, y, minz, maxz), cache)
    return total


def _estimate_shared(name: str) -> int:
    if name == "assets":
        return sum(p.stat().st_size for p in (map_store.data_dir() / "fonts").rglob("*.pbf")) + \
            sum(p.stat().st_size for p in (map_store.data_dir() / "sprite").glob("*"))
    if name == "routes":
        return _shared_source(name).stat().st_size
    src, minz, maxz = _shared_slice(name)
    return tile_archives.bytes_in_ranges(src, tile_archives.descendant_ranges(0, 0, 0, minz, maxz))


# ── Building ─────────────────────────────────────────────────────────────────

def _cell_sources(layer: str) -> list[tuple[Path, int, int]]:
    return {
        "basemap": [(_planet("planet_basemap"), 8, 12),
                    (map_store.master_path(map_store.MASTER_BASEMAP), 13, 15)],
        "overlay": [(map_store.master_path(map_store.MASTER_OVERLAY), 8, 15)],
        "contours": [(map_store.master_path(map_store.MASTER_CONTOURS), 9, 12)],
        "dem": [(_planet("planet_dem_z7"), 6, 7),
                (map_store.master_path(map_store.MASTER_DEM), 8, 12)],
    }[layer]


def _shared_slice(name: str) -> tuple[Path, int, int]:
    return {
        "world": (_planet("planet_basemap"), 0, 7),
        "world-dem": (_planet("planet_dem_z7"), 0, 5),
        "overlay-low": (map_store.master_path(map_store.MASTER_OVERLAY), 6, 7),
    }[name]


def _shrunk(b) -> tuple[float, float, float, float]:
    w, s, e, n = b
    return w + _EPS, s + _EPS, e - _EPS, n - _EPS


def _path_for(key: str, version: str) -> Path:
    suffix = ".zip" if key.endswith("/assets") else ".pmtiles"
    return packs_dir() / f"{key}.{version}{suffix}"


def _cached(key: str, version: str) -> Path | None:
    p = _path_for(key, version)
    return p if p.exists() and p.with_suffix(".json").exists() else None


class StaleVersion(Exception):
    """The pack asked for was replaced by a newer build; refetch the manifest."""


def pack_file(key: str, version: str) -> Path:
    """The pack for `key` at `version`, building it if it is not cached.

    Single-flight per pack (a file lock), so two phones asking at once build it
    once. Raises StaleVersion when `version` is no longer current, and
    LookupError for a pack the server cannot make.
    """
    current = _current_version(key)
    if current is None:
        raise LookupError(key)
    if current != version:
        raise StaleVersion(current)
    hit = _cached(key, version)
    if hit is not None:
        os.utime(hit)          # recently used: last out of the cache
        return hit
    out = _path_for(key, version)
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out.with_suffix(".lock"), "w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        hit = _cached(key, version)
        if hit is not None:
            return hit
        _build(key, out)
        digest = _sha256(out)
        out.with_suffix(".json").write_text(json.dumps({"bytes": out.stat().st_size, "sha256": digest}))
    _drop_older_versions(key, keep=out)
    evict()
    return out


def _current_version(key: str) -> str | None:
    parts = key.split("/")
    if parts[0] == "shared" and len(parts) == 2 and parts[1] in SHARED:
        return shared_version(parts[1])
    if len(parts) == 4 and parts[0] == str(CELL_ZOOM) and parts[3] in CELL_LAYERS:
        x, y = int(parts[1]), int(parts[2])
        if not (0 <= x < 2 ** CELL_ZOOM and 0 <= y < 2 ** CELL_ZOOM):
            return None
        areas = [a for a in region_registry.list_active() if a["status"] == "installed"]
        return cell_version(parts[3], x, y, areas)
    return None


def _build(key: str, out: Path) -> None:
    parts = key.split("/")
    tmpdir = Path(tempfile.mkdtemp(dir=packs_dir()))
    try:
        if parts[0] == "shared":
            name = parts[1]
            if name == "assets":
                _build_assets(out)
                return
            if name == "routes":
                _copy(_shared_source(name), out)
                return
            src, minz, maxz = _shared_slice(name)
            _extract(src, tmpdir / "a.pmtiles", None, minz, maxz)
            os.replace(tmpdir / "a.pmtiles", out)
            return
        x, y, layer = int(parts[1]), int(parts[2]), parts[3]
        pieces = []
        for i, (src, minz, maxz) in enumerate(_cell_sources(layer)):
            if not src.exists():
                continue
            piece = tmpdir / f"{i}.pmtiles"
            if _extract(src, piece, _shrunk(cell_bounds(x, y)), minz, maxz):
                pieces.append(piece)
        if not pieces:
            raise LookupError(f"nothing to put in {key}")
        if len(pieces) == 1:
            os.replace(pieces[0], out)
        else:
            tile_archives.merge(pieces, out)
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)


def _extract(src: Path, out: Path, bbox, minz: int, maxz: int) -> bool:
    cmd = ["pmtiles", "extract", str(src), str(out), "--minzoom", str(minz), "--maxzoom", str(maxz)]
    if bbox is not None:
        cmd.append("--bbox=" + ",".join(f"{c:.7f}" for c in bbox))
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=3600)
    if r.returncode != 0:
        raise RuntimeError(f"pmtiles extract failed: {(r.stderr or r.stdout).strip()[-300:]}")
    return out.exists() and tile_archives.tile_count(out) > 0


def _copy(src: Path, out: Path) -> None:
    import shutil
    tmp = out.with_name(out.name + ".tmp")
    shutil.copyfile(src, tmp)
    os.replace(tmp, out)


def _build_assets(out: Path) -> None:
    """Glyphs and sprite in one zip, at the paths the style asks for them."""
    root = map_store.data_dir()
    tmp = out.with_name(out.name + ".tmp")
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_STORED) as z:
        for p in sorted((root / "fonts").rglob("*.pbf")):
            z.write(p, "fonts/" + p.relative_to(root / "fonts").as_posix())
        for p in sorted((root / "sprite").glob("*")):
            if p.suffix in (".json", ".png"):
                z.write(p, "sprite/" + p.name)
    os.replace(tmp, out)


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _drop_older_versions(key: str, keep: Path) -> None:
    stem = Path(key).name
    for p in keep.parent.glob(f"{stem}.*"):
        if p.suffix in (".pmtiles", ".zip") and p != keep:
            for q in (p, p.with_suffix(".json"), p.with_suffix(".lock")):
                q.unlink(missing_ok=True)


def cache_cap() -> int:
    masters = sum(map_store.master_path(n).stat().st_size
                  for n in (*map_store.AREA_INDEPENDENT, map_store.MASTER_OVERLAY)
                  if map_store.master_path(n).exists())
    return max(CACHE_FLOOR, int(masters * CACHE_FRACTION))


def evict() -> None:
    """Least recently used packs out until the cache is under its cap."""
    root = packs_dir()
    if not root.exists():
        return
    files = [p for p in root.rglob("*") if p.suffix in (".pmtiles", ".zip") and p.is_file()]
    total = sum(p.stat().st_size for p in files)
    cap = cache_cap()
    if total <= cap:
        return
    for p in sorted(files, key=lambda p: p.stat().st_mtime):
        if total <= cap:
            break
        size = p.stat().st_size
        for q in (p, p.with_suffix(".json"), p.with_suffix(".lock")):
            q.unlink(missing_ok=True)
        total -= size
