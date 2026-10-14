# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Registry for downloaded map areas. Uses the PostgreSQL DB via SQLAlchemy.

Each row is one user-drawn box. The tiles themselves live in the shared master
archives (services.map_store), not per area: areas may overlap freely, and an
area is what the lists show and what a delete removes. The one per-area file is
its overlay part (trails, water, land), cut to the ground it `owned` when it
was built — see map_store for why that layer alone keeps parts.

Builds and deletes run through one queue (services.map_builds); the statuses
below are its states.
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

# Waiting for the builder, being built, or being torn down. `downloading_dem`
# and `combining` are no longer entered (the DEM is a step of `downloading`,
# and areas are not merged any more); they stay so a row an older version left
# in them still reads as in progress until the queue settles it.
_IN_PROGRESS_STATUSES = {
    "queued", "downloading", "downloading_dem", "merging", "trails", "contours",
    "combining", "cancelling", "deleting",
}

# How long without a heartbeat before an area's build counts as abandoned. The
# builder beats every few seconds whatever the phase is doing, so this is about
# a dead process, not a slow phase: the old rule — twenty minutes with no
# progress update — gave up on long tile builds that were still running, and
# let a delete purge files under a live pipeline (audit F14).
HEARTBEAT_STALE_S = 120
# Rows from before heartbeats existed: fall back to the old quiet-period rule.
_LEGACY_STALL_S = 1200


def source_dir(region_id: int) -> Path:
    return Path(settings.map_data_dir) / "regions" / str(region_id)


def create(name: str, bbox: list[float]) -> dict:
    db = SessionLocal()
    try:
        r = MapRegion(name=name, bbox=bbox, status="queued", detail="Waiting to start…")
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
        # A deleted row stays deleted: a late progress callback from a build
        # that was cancelled must not bring it back to life.
        r = db.query(MapRegion).filter(MapRegion.id == region_id,
                                       MapRegion.deleted == False).first()  # noqa: E712
        if not r:
            return
        r.status = status
        if status == "installed":
            # Finished: the last phase's words and any earlier failure are
            # history, not a description of the area.
            r.detail = None
            r.error = None
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


def delete_region_files(region_id: int) -> None:
    d = source_dir(region_id)
    if d.exists():
        shutil.rmtree(d)


def region_geom(r: dict):
    """A shapely geometry for an area: its stored shape (older merged areas
    have one), else its box."""
    from shapely.geometry import box, shape
    if r.get("geometry"):
        return shape(r["geometry"])
    w, s, e, n = r["bbox"]
    return box(w, s, e, n)


# Phases after which an unfinished area has tiles in the masters.
_PUBLISHING_PHASES = {"dem", "contours", "basemap", "publish"}


def coverage(exclude_id: int | None = None, *, include_partial: bool = False):
    """The union of the installed areas, as one shapely geometry (or None).

    With `include_partial`, also every unfinished area that has already
    published tiles — a failed build that will be resumed still needs them, so
    removing a neighbour must not take them away.
    """
    from shapely.ops import unary_union
    geoms = []
    for r in list_active():
        if r["id"] == exclude_id:
            continue
        if r["status"] == "installed" or (
                include_partial and _PUBLISHING_PHASES & set(r.get("phases_done") or [])):
            geoms.append(region_geom(r))
    return unary_union(geoms) if geoms else None


def _set(region_id: int, **fields) -> None:
    db = SessionLocal()
    try:
        r = db.query(MapRegion).filter(MapRegion.id == region_id).first()
        if r:
            for k, v in fields.items():
                setattr(r, k, v)
            db.commit()
    finally:
        db.close()


def heartbeat(region_id: int) -> None:
    _set(region_id, heartbeat_at=datetime.now(timezone.utc))


def phases_done(region_id: int) -> list[str]:
    r = get(region_id)
    return list(r.get("phases_done") or []) if r else []


def record_attempt(region_id: int) -> int:
    """Count one more start of this area's build; returns the new count."""
    done = phases_done(region_id)
    _set(region_id, phases_done=done + ["attempt"])
    return done.count("attempt") + 1


def add_phase_done(region_id: int, phase: str) -> None:
    done = phases_done(region_id)
    if phase not in done:
        _set(region_id, phases_done=done + [phase])


def reset_phases(region_id: int) -> None:
    _set(region_id, phases_done=[], heartbeat_at=None)


def clear_attempts(region_id: int) -> None:
    """A person asked again: the finished phases stand, the count starts over."""
    _set(region_id, phases_done=[p for p in phases_done(region_id) if p != "attempt"])


def set_owned(region_id: int, owned_geojson: dict | None) -> None:
    _set(region_id, owned=owned_geojson)


def _to_dict(r: MapRegion) -> dict:
    geom = r.geometry
    if isinstance(geom, str):
        try:
            geom = json.loads(geom)
        except (ValueError, TypeError):
            geom = None
    stale = False
    # Waiting in the queue is not stalling: nothing has started yet.
    if r.status in _IN_PROGRESS_STATUSES and r.status != "queued":
        now = datetime.now(timezone.utc)
        beat = r.heartbeat_at or r.updated_at
        limit = HEARTBEAT_STALE_S if r.heartbeat_at else _LEGACY_STALL_S
        if beat is not None:
            if beat.tzinfo is None:
                beat = beat.replace(tzinfo=timezone.utc)
            stale = (now - beat).total_seconds() > limit
    return {
        "id": r.id, "name": r.name,
        "bbox": r.bbox if isinstance(r.bbox, list) else json.loads(r.bbox),
        "geometry": geom,
        "status": r.status, "progress": r.progress,
        "detail": r.detail, "error": r.error, "size_bytes": r.size_bytes,
        "created_at": r.created_at.isoformat() if r.created_at else None,
        "updated_at": r.updated_at.isoformat() if r.updated_at else None,
        "stale": stale,
        "phases_done": list(r.phases_done or []),
        "owned": r.owned,
    }
