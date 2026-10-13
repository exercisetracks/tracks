# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Custom-track folders — `/folders` CRUD. The app calls them collections.

Folders group tracks and saved places, and carry a `load_to_device` flag;
flipping it cascades the sync state to every (non-external) member track and
every member place, and deleting a folder un-groups its members rather than
deleting them.
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import func
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.custom_track import CustomTrack, CustomTrackFolder
from app.models.waypoint import Waypoint

from .helpers import _MAX_NAME

router = APIRouter()


def _folder_dict(f: CustomTrackFolder, count: int, places: int = 0) -> dict:
    return {"id": f.id, "name": f.name, "color": f.color,
            "load_to_device": f.load_to_device, "track_count": count, "place_count": places}


@router.get("/folders")
def list_folders(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    folders = (db.query(CustomTrackFolder).filter(CustomTrackFolder.user_id == user.id)
               .order_by(CustomTrackFolder.name).all())
    counts = dict(
        db.query(CustomTrack.folder_id, func.count(CustomTrack.id))
        .filter(CustomTrack.user_id == user.id, CustomTrack.folder_id.isnot(None))
        .group_by(CustomTrack.folder_id).all()
    ) if folders else {}
    place_counts = dict(
        db.query(Waypoint.folder_id, func.count(Waypoint.id))
        .filter(Waypoint.user_id == user.id, Waypoint.folder_id.isnot(None))
        .group_by(Waypoint.folder_id).all()
    ) if folders else {}
    return [_folder_dict(f, counts.get(f.id, 0), place_counts.get(f.id, 0)) for f in folders]


class FolderBody(BaseModel):
    name: str | None = None
    color: str | None = None
    load_to_device: bool | None = None


@router.post("/folders", status_code=201)
def create_folder(body: FolderBody, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = CustomTrackFolder(
        user_id=user.id,
        name=(body.name or "Folder").strip()[:_MAX_NAME] or "Folder",
        color=body.color or "#64748b",
        load_to_device=bool(body.load_to_device),
    )
    db.add(f)
    db.commit()
    db.refresh(f)
    return _folder_dict(f, 0)


@router.patch("/folders/{folder_id}")
def update_folder(folder_id: int, body: FolderBody,
                  user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = db.get(CustomTrackFolder, folder_id)
    if f is None or f.user_id != user.id:
        raise HTTPException(404, "Folder not found")
    if body.name is not None:
        f.name = body.name.strip()[:_MAX_NAME] or f.name
    if body.color is not None:
        f.color = body.color
    if body.load_to_device is not None:
        f.load_to_device = body.load_to_device
        # Cascade the folder's sync state to every (non-external) member track.
        # Row by row, not a bulk UPDATE, so each track's change is stamped and
        # reaches phones.
        for t in (db.query(CustomTrack)
                    .filter(CustomTrack.user_id == user.id, CustomTrack.folder_id == f.id,
                            CustomTrack.is_external.is_(False))
                    .all()):
            t.load_to_device = f.load_to_device
        for w in (db.query(Waypoint)
                    .filter(Waypoint.user_id == user.id, Waypoint.folder_id == f.id).all()):
            w.load_to_device = f.load_to_device
    db.commit()
    db.refresh(f)
    count = (db.query(func.count(CustomTrack.id))
             .filter(CustomTrack.folder_id == f.id).scalar() or 0)
    places = (db.query(func.count(Waypoint.id))
              .filter(Waypoint.folder_id == f.id).scalar() or 0)
    return _folder_dict(f, count, places)


@router.delete("/folders/{folder_id}", status_code=204)
def delete_folder(folder_id: int, user: User = Depends(require_auth), db: Session = Depends(get_db)):
    f = db.get(CustomTrackFolder, folder_id)
    if f is None or f.user_id != user.id:
        raise HTTPException(404, "Folder not found")
    # Members are un-grouped, not deleted. Row by row rather than leaving it
    # to the foreign key's SET NULL: a change the database makes on its own is
    # never stamped, so phones would go on filing the members under a
    # collection that no longer exists.
    for t in db.query(CustomTrack).filter(CustomTrack.folder_id == f.id).all():
        t.folder_id = None
    for w in db.query(Waypoint).filter(Waypoint.folder_id == f.id).all():
        w.folder_id = None
    db.flush()
    db.delete(f)
    db.commit()
