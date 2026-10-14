# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Generate vector tiles with tippecanoe — replaces the pure-Python MVT tiler.

The per-region thematic layers (trails/water/areas/infra/landuse) used to be
clipped + MVT-encoded + gzipped tile-by-tile in pure Python (one builder at a
time, serialised by a global lock), and emitted as five separate master
archives. This module instead:

  1. Streams each layer's features to a newline-delimited GeoJSON file
     (``features_to_ndjson``), carrying per-feature ``min_zoom`` as tippecanoe's
     native per-feature ``minzoom`` (so a feature first appears at its zoom).
  2. Runs ONE ``tippecanoe`` over all the layer files (``build_overlay``) to
     produce a single multi-layer ``overlay.pmtiles`` — far faster (C++, all
     cores) and one source instead of five (no per-source zoom-boundary pop).
  3. Stitches every region's ``overlay.pmtiles`` into ``master_overlay.pmtiles``
     with ``tile-join`` (``merge_overlays``).

tippecanoe emits standard MVT (MapLibre renders it directly); it preserves the
``-L`` layer names so the frontend keeps its ``source-layer`` values.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import subprocess
from pathlib import Path
from typing import Sequence

from shapely.geometry import mapping

logger = logging.getLogger(__name__)

# Overlay archive zoom span. Per-feature minzoom (carried from each builder's
# length/area grading) drives LOD — big public-land polygons are graded down to
# z6 (see landuse_builder._area_min_zoom) so they can fade in long before the
# detail band. Every zoom is assembled the same way (a real tile-join); see
# region_merger.rebuild_master_overlay.
OVERLAY_MIN_ZOOM = 6
OVERLAY_MAX_ZOOM = 15

# tippecanoe progress is printed to stderr as a "NN.N%" bar (carriage-returns,
# not newlines) — scrape the latest percentage to drive the download progress UI.
_PCT_RE = re.compile(r"(\d+(?:\.\d+)?)%")


def features_to_ndjson(feats, path: str | Path, max_zoom: int = OVERLAY_MAX_ZOOM) -> int:
    """Append ``[(geom, props, min_zoom), …]`` to a newline-delimited GeoJSON file.

    One GeoJSON Feature per line (the form tippecanoe reads fastest, in parallel
    with ``-P``). ``min_zoom`` is written as tippecanoe's per-feature
    ``{"tippecanoe":{"minzoom": mz}}`` (clamped to ``max_zoom``) so the feature
    first appears at that zoom; it stays in ``properties`` too (builders already
    include it) so existing zoom-based style filters keep working. Appends so a
    chunked (continent-scale) build can stream each cell's features into the same
    per-layer file. Returns the number of features written.
    """
    path = Path(path)
    n = 0
    with open(path, "a", encoding="utf-8") as fh:
        for geom, props, mz in feats:
            if geom is None or getattr(geom, "is_empty", False):
                continue
            feat = {
                "type": "Feature",
                "tippecanoe": {"minzoom": max(0, min(int(mz), max_zoom))},
                "properties": props,
                "geometry": mapping(geom),
            }
            fh.write(json.dumps(feat, separators=(",", ":")))
            fh.write("\n")
            n += 1
    return n


def build_overlay(layer_files: dict[str, str | Path], output_path: str | Path,
                  min_zoom: int = OVERLAY_MIN_ZOOM, max_zoom: int = OVERLAY_MAX_ZOOM,
                  progress_cb=None, cancel=None,
                  clip_bbox: "Sequence[float] | None" = None) -> int:
    """Run tippecanoe over per-layer NDJSON files → one multi-layer pmtiles.

    ``layer_files`` maps layer name → its NDJSON path; empty/missing files are
    skipped. No features are dropped (``--no-tile-size-limit``/``--no-feature-limit``)
    so fidelity matches the old tiler — per-feature ``minzoom`` controls density.
    Returns the number of layers built (0 if nothing to do). ``progress_cb(pct)``
    receives tippecanoe's 0-100 progress.

    ``clip_bbox`` (w, s, e, n) cuts every feature to the region's own box, and is
    what makes two regions' archives *partition* space rather than overlap.
    Without it a region's archive spills well past its bbox — Overpass returns
    whole ways and relations that merely intersect the query box, so a trail or a
    national-forest polygon crossing the edge is carried in complete. Two regions
    that merely touch then hold thousands of identical features, and the two ways
    of combining archives both get it wrong: ``tile-join`` concatenates them
    (every feature drawn twice, semi-transparent fills compositing twice), and a
    last-wins tile merge drops one archive's whole tile. Clipping removes the
    overlap that both failure modes need. See region_merger.rebuild_master_overlay.
    """
    layers = {name: Path(p) for name, p in layer_files.items()
              if Path(p).exists() and Path(p).stat().st_size > 0}
    if not layers:
        logger.info("No overlay features — skipping tippecanoe")
        return 0

    output_path = Path(output_path)
    # The temp path MUST keep the .pmtiles suffix: tippecanoe picks its output
    # format from the extension, so writing to "*.tmp" silently emits MBTiles.
    tmp = output_path.with_suffix(".building" + output_path.suffix)
    tmp.unlink(missing_ok=True)
    cmd = [
        "tippecanoe", "-o", str(tmp), "--force",
        "-Z", str(min_zoom), "-z", str(max_zoom), "-P",
        "--simplification=10",
        "--no-tile-size-limit", "--no-feature-limit", "--no-tile-stats",
    ]
    if clip_bbox is not None:
        w, s, e, n = clip_bbox
        cmd.append(f"--clip-bounding-box={w},{s},{e},{n}")
    for name, p in layers.items():
        cmd += ["-L", f"{name}:{p}"]
    try:
        _run(cmd, "tippecanoe overlay", progress_cb=progress_cb, cancel=cancel)
        os.replace(str(tmp), str(output_path))
    finally:
        tmp.unlink(missing_ok=True)
    logger.info("Built overlay %s (%d layers: %s)", output_path.name,
                len(layers), ", ".join(layers))
    return len(layers)


def merge_overlays(sources: list[str], output_path: str | Path, progress_cb=None,
                   cancel=None) -> int:
    """Stitch per-region overlay archives into one master via tile-join.

    A single source is copied directly (no re-tiling); multiple sources are
    tile-joined, which unions their features tile by tile. That is the only
    correct way to combine them: two regions share every tile along their common
    edge, and a tile-id merge would keep one archive's version of such a tile and
    silently drop the other's — the whole tile, every layer in it, not just the
    colliding feature. On screen that is a hard seam down the boundary.

    tile-join concatenates rather than de-duplicating, so it is only safe on
    archives that do not hold the same feature twice; ``build_overlay``'s
    ``clip_bbox`` is what guarantees that. ``progress_cb(pct)`` receives
    tile-join's 0-100 progress (multi-source path only). ``cancel`` lets the
    watcher kill the tile-join. Returns the source count.
    """
    srcs = [s for s in sources if Path(s).exists()]
    if not srcs:
        return 0
    output_path = Path(output_path)
    # Keep the .pmtiles suffix on the temp path — tile-join, like tippecanoe,
    # picks its output format from the extension (a "*.tmp" path emits MBTiles).
    tmp = output_path.with_suffix(".building" + output_path.suffix)
    tmp.unlink(missing_ok=True)
    try:
        if len(srcs) == 1:
            shutil.copy2(srcs[0], str(tmp))
        else:
            _join_shared_only(srcs, tmp, progress_cb=progress_cb, cancel=cancel)
        os.replace(str(tmp), str(output_path))
    finally:
        tmp.unlink(missing_ok=True)
    return len(srcs)


def _join_shared_only(srcs: list[str], output: Path, progress_cb=None, cancel=None) -> None:
    """tile-join only the tiles two or more regions share; byte-copy the rest.

    A tile only one region has needs no union — its bytes already are the
    answer — and that is nearly every tile: regions share only the band along a
    common edge and the coarse zooms where one tile spans several. tile-joining
    everything decoded and re-encoded every tile of every region on every
    download, so adding a 20 km square beside a state-sized area spent 6.4 of its
    7.7 minutes re-joining the state (measured 2026-10-14). Now the shared tiles
    are pulled into small per-region archives, tile-joined, and laid over a
    byte-copy merge of everything, last so their union wins.
    """
    from pmtiles.reader import MmapSource, Reader, all_tiles
    from pmtiles.tile import zxy_to_tileid
    from pmtiles.writer import write as pmtiles_write

    from app.services import custom_merge

    owners: dict[int, int] = {}
    shared: set[int] = set()
    for src in srcs:
        with open(src, "rb") as f:
            reader = Reader(MmapSource(f))
            for (z, x, y), _ in all_tiles(reader.get_bytes):
                tid = zxy_to_tileid(z, x, y)
                owners[tid] = owners.get(tid, 0) + 1
                if owners[tid] == 2:
                    shared.add(tid)
    del owners
    if cancel is not None:
        cancel.raise_if_cancelled()

    work = output.with_name(output.stem + ".shared")
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    try:
        parts: list[str] = []
        for i, src in enumerate(srcs):
            part = work / f"part{i}.pmtiles"
            wrote = 0
            with open(src, "rb") as f:
                reader = Reader(MmapSource(f))
                header, metadata = reader.header(), reader.metadata()
                with pmtiles_write(str(part)) as writer:
                    for (z, x, y), data in all_tiles(reader.get_bytes):
                        tid = zxy_to_tileid(z, x, y)
                        if tid in shared:
                            writer.write_tile(tid, data)
                            wrote += 1
                    # finalize() needs at least one tile.
                    if wrote:
                        writer.finalize(header, metadata)
            if wrote:
                parts.append(str(part))
            else:
                part.unlink(missing_ok=True)

        layers = list(srcs)
        if len(parts) >= 2:
            joined = work / "joined.pmtiles"
            _run(["tile-join", "-o", str(joined), "--force", "--no-tile-size-limit", *parts],
                 "tile-join shared overlay tiles", progress_cb=progress_cb, cancel=cancel)
            layers.append(str(joined))
        logger.info("Overlay merge: %d sources, %d shared tiles tile-joined", len(srcs), len(shared))
        custom_merge.merge_archives(layers, str(output), prototype=srcs[-1])
    finally:
        shutil.rmtree(work, ignore_errors=True)


def _run(cmd: list[str], label: str, progress_cb=None, timeout: int = 7200,
         cancel=None) -> None:
    """Run a subprocess, optionally scraping a stderr percentage into progress_cb.

    When ``cancel`` (a download_cancel.CancelToken) is given the process is run
    via Popen and registered so the cancel watcher can kill it; a cancel-kill is
    re-raised as DownloadCancelled rather than a generic failure.
    """
    logger.info("Running: %s", " ".join(str(c) for c in cmd))
    if progress_cb is None and cancel is None:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        if r.returncode != 0:
            raise RuntimeError(f"{label} failed (exit {r.returncode}): "
                               f"{((r.stderr or '') + (r.stdout or '')).strip()[:600]}")
        return

    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL,
                            stderr=subprocess.PIPE, bufsize=0)
    if cancel is not None:
        cancel.add_proc(proc)
    tail = b""
    last = -1.0
    try:
        while True:
            chunk = proc.stderr.read(256) if proc.stderr else b""
            if not chunk:
                break
            tail = (tail + chunk)[-512:]
            if progress_cb is not None:
                for m in _PCT_RE.finditer(tail.decode("utf-8", "replace")):
                    pct = float(m.group(1))
                    if pct != last:
                        last = pct
                        try:
                            progress_cb(pct)
                        except Exception:
                            pass
    finally:
        proc.wait(timeout=timeout)
        if cancel is not None:
            cancel.discard_proc(proc)
    if cancel is not None and cancel.cancelled():
        from app.services.download_cancel import DownloadCancelled
        raise DownloadCancelled()
    if proc.returncode != 0:
        raise RuntimeError(f"{label} failed (exit {proc.returncode})")
