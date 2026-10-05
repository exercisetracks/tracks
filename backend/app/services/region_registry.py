# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Registry for downloaded map regions. Uses the PostgreSQL DB via SQLAlchemy.

Each region represents a user-drawn bbox whose tiles have been extracted
from the Protomaps planet build.
"""

from __future__ import annotations

import json
import logging
import shutil
from datetime import datetime, timezone
from pathlib import Path

from sqlalchemy import and_

from app.config import settings
from app.database import SessionLocal
from app.models.map_region import MapRegion

logger = logging.getLogger(__name__)

# Per-type source archives stored under each region's directory. A merged area
# combines each of these (overlap-tolerant) from all its constituents. `overlay`
# is the multi-layer tippecanoe archive (trails/water/areas/infra/landuse) and is
# tile-joined (not custom-merged) so overlapping regions concatenate features.
PART_FILES = ["source.pmtiles", "dem.pmtiles", "overlay.pmtiles", "contours.pmtiles"]
# overlay is tile-joined (feature union) — two regions share every tile along
# their common edge, and a last-wins merge would drop one side of each. basemap
# and dem are tile-id custom-merged: their tiles are copies of whole planet
# tiles, identical whichever region supplied them. Contours are custom-merged
# too, but only as a fallback — merge_regions retraces them afterwards.
TILEJOIN_PARTS = {"overlay.pmtiles"}

# Friendly per-part labels for the overlap-merge progress bar (one slice each).
_MERGE_PART_LABELS = {
    "source.pmtiles": "Merging basemap…",
    "dem.pmtiles": "Merging terrain…",
    "overlay.pmtiles": "Merging trails…",
    "contours.pmtiles": "Merging contours…",
}

# Non-terminal statuses that a live download_and_merge()/purge run cycles
# through; every one of them touches update_status() at least every few
# seconds while actually progressing (per-cell/per-tile/per-feature progress
# callbacks). If `updated_at` hasn't moved in _STALL_SECONDS while still in
# one of these, the driving thread is gone (crashed, container restart, killed
# subprocess) — there's no live process left to ever finish it. Surfaced as
# `stale` so the UI can offer delete/retry instead of an eternal progress bar.
_IN_PROGRESS_STATUSES = {
    "downloading", "downloading_dem", "merging", "trails", "contours",
    "combining", "cancelling", "deleting",
}
_STALL_SECONDS = 1200


def source_dir(region_id: int) -> Path:
    return Path(settings.map_data_dir) / "regions" / str(region_id)


def create(name: str, bbox: list[float]) -> dict:
    db = SessionLocal()
    try:
        r = MapRegion(name=name, bbox=bbox, status="downloading")
        db.add(r)
        db.commit()
        db.refresh(r)
        return _to_dict(r)
    finally:
        db.close()


def get(region_id: int) -> dict | None:
    db = SessionLocal()
    try:
        r = db.query(MapRegion).filter(
            and_(MapRegion.id == region_id, MapRegion.deleted == False)
        ).first()
        return _to_dict(r) if r else None
    finally:
        db.close()


def list_active() -> list[dict]:
    db = SessionLocal()
    try:
        rows = db.query(MapRegion).filter(
            MapRegion.deleted == False
        ).order_by(MapRegion.created_at).all()
        return [_to_dict(r) for r in rows]
    finally:
        db.close()


def update_status(region_id: int, status: str, *, error: str | None = None,
                  progress: float | None = None, size_bytes: int | None = None,
                  detail: str | None = None) -> None:
    db = SessionLocal()
    try:
        r = db.query(MapRegion).filter(MapRegion.id == region_id).first()
        if not r:
            return
        r.status = status
        if error is not None:
            r.error = error
        if progress is not None:
            r.progress = progress
        if size_bytes is not None:
            r.size_bytes = size_bytes
        if detail is not None:
            r.detail = detail
        db.commit()
    finally:
        db.close()


def mark_deleted(region_id: int) -> None:
    db = SessionLocal()
    try:
        r = db.query(MapRegion).filter(MapRegion.id == region_id).first()
        if r:
            r.deleted = True
            r.status = "deleted"
            db.commit()
    finally:
        db.close()


def find_overlapping(bbox: list[float], exclude_id: int | None = None) -> list[dict]:
    active = list_active()
    result = []
    for r in active:
        if exclude_id is not None and r["id"] == exclude_id:
            continue
        if _bboxes_intersect(bbox, r["bbox"]):
            result.append(r)
    return result


def delete_region_files(region_id: int) -> None:
    d = source_dir(region_id)
    if d.exists():
        shutil.rmtree(d)


def _region_geom(r: dict):
    """A shapely geometry for a region: its stored union polygon, else its bbox."""
    from shapely.geometry import box, shape
    if r.get("geometry"):
        return shape(r["geometry"])
    w, s, e, n = r["bbox"]
    return box(w, s, e, n)


def _update_geometry(region_id: int, bbox: list[float], geometry: dict) -> None:
    db = SessionLocal()
    try:
        r = db.query(MapRegion).filter(MapRegion.id == region_id).first()
        if r:
            r.bbox = bbox
            r.geometry = geometry
            db.commit()
    finally:
        db.close()


def merge_regions(constituent_ids: list[int], progress_cb=None) -> dict | None:
    """Fold several overlapping installed areas into one.

    Combines each per-type source archive (basemap/DEM/trails/…) of the
    constituents into the oldest constituent's directory, sets that area's
    geometry to the union polygon (bbox to the envelope), and deletes the rest.

    Then rebuilds what depends on those archives, which is not optional:

      * **Contours are regenerated** from the merged DEM. Contour lines are
        traced tile by tile from a ring of neighbouring DEM pixels, and a region
        that ends at its own boundary has no neighbour there — the ring is nodata
        and the lines stop short. Each constituent's contours therefore carry a
        ragged edge along the shared border. Only now, with both DEMs in one
        archive, can those tiles be traced with real elevation on both sides. The
        staleness check in ``regenerate_contours`` cannot do this for us: it
        compares mtimes, and the combine loop writes contours *after* the DEM, so
        the merged contours always look newer than the DEM they no longer match.
      * **The masters are reassembled.** This used to be skipped, on the argument
        that the union of the constituents' archives holds exactly the same set
        of tiles, so the served map could not have changed. The tile *set* is the
        same; the tile *contents* are not. The overlay is combined with a
        feature-level tile-join here, which is precisely what the last-wins
        assembly could not do — leaving the served archive short of every feature
        it had dropped along the boundary, permanently, with nothing to trigger
        another attempt.

    Returns the surviving merged area, or None if <2 remain.

    ``progress_cb(pct: float, detail: str)`` (optional) receives overall 0-100
    progress: each part archive is an equal slice, as are the contour and master
    rebuilds, and the heavy overlay tile-join contributes smooth sub-progress
    within its own slice.
    """
    from shapely.geometry import mapping
    from shapely.ops import unary_union
    from app.services import custom_merge, tippecanoe_writer

    regions = [r for r in (get(i) for i in constituent_ids) if r]
    if len(regions) < 2:
        return None
    regions.sort(key=lambda r: r["id"])
    primary, others = regions[0], regions[1:]
    primary_dir = source_dir(primary["id"])
    primary_dir.mkdir(parents=True, exist_ok=True)

    union = unary_union([_region_geom(r) for r in regions])
    minx, miny, maxx, maxy = union.bounds
    envelope = [minx, miny, maxx, maxy]

    # The part archives, then the contour retrace, then the master assembly.
    total = len(PART_FILES) + 2

    def _step(idx: int, sub: float, label: str) -> None:
        """Report overall merge progress: part ``idx`` is the slice
        [idx/total, (idx+1)/total]; ``sub`` (0-1) advances within it."""
        if not progress_cb:
            return
        frac = (idx + max(0.0, min(sub, 1.0))) / total
        try:
            progress_cb(round(frac * 100, 1), label)
        except Exception:
            pass

    # Combine each per-type archive into the primary's directory. The multi-layer
    # overlay is tile-joined (unions features per tile) so neither region loses
    # its side of a shared tile; basemap/DEM use the tile-id custom merge, which
    # is safe for them because their tiles are copied whole out of the planet
    # archives rather than derived from one region's clipped data.
    #
    # Contours are combined here and then retraced from scratch below. The
    # retrace is the one that removes the seam, but it is allowed to fail, and
    # this leaves a merged-but-ragged archive to fall back on rather than only
    # the primary's own half.
    for idx, fname in enumerate(PART_FILES):
        label = _MERGE_PART_LABELS.get(fname, "Merging…")
        _step(idx, 0.0, label)
        inputs = [str(source_dir(r["id"]) / fname)
                  for r in regions if (source_dir(r["id"]) / fname).exists()]
        if len(inputs) >= 2:
            if fname in TILEJOIN_PARTS:
                # tile-join is the slow part — stream its 0-100 into this slice.
                tippecanoe_writer.merge_overlays(
                    inputs, str(primary_dir / fname),
                    progress_cb=lambda pct, _i=idx, _l=label: _step(_i, pct / 100.0, _l))
            else:
                custom_merge.merge_archives(inputs, str(primary_dir / fname))
        elif len(inputs) == 1 and Path(inputs[0]).resolve() != (primary_dir / fname).resolve():
            shutil.copy(inputs[0], primary_dir / fname)

    _update_geometry(primary["id"], envelope, mapping(union))

    # Before anything is rebuilt: the assembly steps below read `list_active()`,
    # and a constituent still listed there would have its (now-absorbed) archives
    # merged in a second time.
    for r in others:
        delete_region_files(r["id"])
        mark_deleted(r["id"])

    # Retrace the contours across the old border — see the docstring. Also
    # reassembles master_contours, so the master step below leaves it alone.
    contours_idx = len(PART_FILES)
    _step(contours_idx, 0.0, "Redrawing contours…")
    try:
        from app.services.contour_generator import build_region_contours
        build_region_contours(
            primary["id"],
            progress_cb=lambda done, n: _step(contours_idx, done / max(n, 1),
                                              "Redrawing contours…"))
    except Exception:
        # Non-fatal, and deliberately so: stale contours are a cosmetic seam,
        # while an abandoned merge leaves two areas half folded into one.
        logger.exception("Contour retrace after merge failed for %d", primary["id"])

    _step(total - 1, 0.0, "Publishing…")
    from app.services import region_merger
    region_merger.rebuild_master(write_trigger=False, mark_installed=False)
    region_merger.rebuild_master_dem(write_trigger=False)
    region_merger.rebuild_master_overlay(write_trigger=False)
    region_merger.write_reload_trigger()

    logger.info("Merged areas %s → %d (union of %d boxes)",
                [r["id"] for r in others], primary["id"], len(regions))
    return get(primary["id"])


def _bboxes_intersect(a: list[float], b: list[float]) -> bool:
    return not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])


def _to_dict(r: MapRegion) -> dict:
    geom = r.geometry
    if isinstance(geom, str):
        try:
            geom = json.loads(geom)
        except (ValueError, TypeError):
            geom = None
    stale = False
    if r.status in _IN_PROGRESS_STATUSES and r.updated_at:
        updated_at = r.updated_at
        if updated_at.tzinfo is None:
            updated_at = updated_at.replace(tzinfo=timezone.utc)
        age = (datetime.now(timezone.utc) - updated_at).total_seconds()
        stale = age > _STALL_SECONDS
    return {
        "id": r.id, "name": r.name,
        "bbox": r.bbox if isinstance(r.bbox, list) else json.loads(r.bbox),
        "geometry": geom,
        "status": r.status, "progress": r.progress,
        "detail": r.detail, "error": r.error, "size_bytes": r.size_bytes,
        "created_at": r.created_at.isoformat() if r.created_at else None,
        "updated_at": r.updated_at.isoformat() if r.updated_at else None,
        "stale": stale,
    }
