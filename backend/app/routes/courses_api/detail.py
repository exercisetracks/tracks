# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Per-track routes — the dynamic `/courses/{track_id}` paths.

Get / update / delete a single track, plus save-mine (clone an external course
into a managed one). This router is included LAST so its `/{track_id}` wildcard
never shadows the static `/courses/*` paths in tracks.py / merge.py — FastAPI
matches in registration order.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.custom_track import CustomTrack, CustomTrackFolder

from .helpers import (
    _DEFAULT_COLOR, _MAX_NAME, _apply_stats, _detail, _owned_or_404, _refresh_turns,
)

router = APIRouter()


@router.get("/courses/{track_id}")
def get_course(track_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    return _detail(_owned_or_404(track_id, user, db))


class CourseUpdate(BaseModel):
    name: str | None = None
    color: str | None = None
    sport: str | None = None
    notes: str | None = None
    turn_by_turn: bool | None = None
    load_to_device: bool | None = None
    folder_id: int | None = None
    clear_folder: bool = False   # explicit "move out of any folder"
    hidden: bool | None = None
    # Replace the track geometry (e.g. after a client-side simplify). Recomputes
    # all stats/profile and, if the track is on the watch, forces a re-upload.
    coords: list | None = None


@router.patch("/courses/{track_id}")
def update_course(track_id: int, body: CourseUpdate,
                  user: User = Depends(require_auth), db: Session = Depends(get_db)):
    t = _owned_or_404(track_id, user, db)
    on_device = t.watch_uploaded_at is not None and t.watch_deleted_at is None

    if body.name is not None:
        t.name = body.name.strip()[:_MAX_NAME] or t.name
    if body.color is not None:
        t.color = body.color
    if body.sport is not None:
        t.sport = body.sport.lower()
    if body.notes is not None:
        t.notes = body.notes
    if body.hidden is not None:
        t.hidden = body.hidden
    if body.coords is not None:
        clean = [c for c in body.coords if c and c[0] is not None and c[1] is not None]
        if len(clean) < 2:
            raise HTTPException(400, "A track needs at least 2 coordinates")
        _apply_stats(t, clean)
        _refresh_turns(t)
        if on_device:           # geometry changed → re-upload the course FIT next sync
            t.watch_uploaded_at = None
    if body.turn_by_turn is not None and body.turn_by_turn != t.turn_by_turn:
        t.turn_by_turn = body.turn_by_turn
        _refresh_turns(t)
        # The FIT content changed; if it's already on the watch, force a re-upload
        # (same filename overwrites) so the device gets the new course-point set.
        if on_device:
            t.watch_uploaded_at = None
    if body.load_to_device is not None:
        t.load_to_device = body.load_to_device
    if body.clear_folder:
        t.folder_id = None
    elif body.folder_id is not None:
        folder = db.get(CustomTrackFolder, body.folder_id)
        if folder is None or folder.user_id != user.id:
            raise HTTPException(404, "Folder not found")
        t.folder_id = folder.id
        # Moving a track into a synced folder loads it to the watch too.
        if folder.load_to_device and not t.is_external:
            t.load_to_device = True

    db.commit()
    db.refresh(t)
    return _detail(t)


@router.delete("/courses/{track_id}", status_code=204)
def delete_course(track_id: int, user: User = Depends(require_auth),
                  db: Session = Depends(get_db)):
    t = _owned_or_404(track_id, user, db)
    on_device = t.watch_filename is not None and t.watch_deleted_at is None and (
        t.watch_uploaded_at is not None or t.is_external)
    if on_device:
        # Keep the row so the next sync removes the file; purge on confirmation.
        t.load_to_device = False
        t.purge_after_delete = True
        db.commit()
    else:
        db.delete(t)
        db.commit()


@router.post("/courses/{track_id}/save-mine", status_code=201)
def save_external_to_mine(track_id: int, user: User = Depends(require_auth),
                          db: Session = Depends(get_db)):
    """Clone a device-discovered (external) course into a managed track the user
    owns, so they can recolor / re-load it later."""
    src = _owned_or_404(track_id, user, db)
    if not src.is_external:
        raise HTTPException(400, "Track is already managed")
    t = CustomTrack(user_id=user.id, name=src.name[:_MAX_NAME], color=_DEFAULT_COLOR,
                    sport=src.sport, source="fit", geometry=src.geometry,
                    distance_m=src.distance_m, ascent_m=src.ascent_m,
                    descent_m=src.descent_m, bounds=src.bounds, profile=src.profile)
    db.add(t)
    db.commit()
    db.refresh(t)
    return _detail(t)
