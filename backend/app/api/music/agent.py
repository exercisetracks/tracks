# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Music endpoints for the garmin-sync container — the `music_sync_router`.

NOT behind user auth: every route is guarded by a sync agent's bearer pairing
token, exactly like the course and workout sync routes it sits beside. This is
the cable path's half of music, so that a watch docked to the server gets the
same library the browser would push.

Two things differ from the browser's `/music/device-plan`:

**Audio is a separate fetch, not inline.** A course FIT rides as base64 inside
the list; a music library is hundreds of megabytes and base64 would add a third
on top, all of it buffered. The list names a URL and the agent streams each
track from it.

"""

from __future__ import annotations

import logging

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.api.training_plan import _get_sync_user
from app.database import get_db
from app.models.music import MusicTrack
from app.models.sync_agents import SyncAgent
from app.services import music_rotation, music_source, music_storage
from app.services.music_source import MusicSourceError
from app.services.sync_agent_auth import require_sync_agent

from .library import ranged_audio_response
from .sync import MUSIC_FOLDER, music_delete_items, music_upload_items, playlist_items

log = logging.getLogger(__name__)

music_sync_router = APIRouter(prefix="/music/sync", tags=["garmin-sync"])


class MarkUploaded(BaseModel):
    items: list[dict]


class MarkDeleted(BaseModel):
    ids: list[int]


def _user_or_400(db: Session, agent: SyncAgent, serial: str | None) -> int:
    uid = _get_sync_user(db, agent, serial or None)
    if uid is None:
        raise HTTPException(
            400,
            "Device not claimed by any user yet — provide X-Garmin-Device-Serial header",
        )
    return uid


@music_sync_router.get("/upload-list")
def music_upload_list(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """Tracks to write, plus the playlists to write alongside them.

    Rotation runs first when it is due, so a watch docked overnight wakes up
    carrying what the user has actually been listening to.
    """
    uid = _user_or_400(db, agent, x_garmin_device_serial)
    music_rotation.rotate_if_due(db, uid)
    return {
        "folder": MUSIC_FOLDER,
        "items": music_upload_items(db, uid),
        "playlists": playlist_items(db, uid),
    }


@music_sync_router.get("/delete-list")
def music_delete_list(
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    uid = _user_or_400(db, agent, x_garmin_device_serial)
    return {"folder": MUSIC_FOLDER, "items": music_delete_items(db, uid)}


@music_sync_router.get("/audio/{track_id}")
def music_audio(
    track_id: int,
    request: Request,
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    """One track's bytes, materialising a Navidrome reference on first ask."""
    uid = _user_or_400(db, agent, x_garmin_device_serial)
    track = db.get(MusicTrack, track_id)
    if track is None or track.user_id != uid:
        raise HTTPException(404, "No such track")
    try:
        blob_id = music_source.ensure_audio(db, track)
    except MusicSourceError as exc:
        raise HTTPException(404, str(exc))
    return ranged_audio_response(music_storage.blob_path(blob_id), request.headers.get("range"))


@music_sync_router.post("/mark-uploaded")
def mark_uploaded(
    body: MarkUploaded,
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    uid = _user_or_400(db, agent, x_garmin_device_serial)
    from .sync import apply_mark_music_uploaded
    return {"marked": apply_mark_music_uploaded(db, body.items, user_id=uid)}


@music_sync_router.post("/mark-deleted")
def mark_deleted(
    body: MarkDeleted,
    db: Session = Depends(get_db),
    x_garmin_device_serial: str = Header(default=""),
    agent: SyncAgent = Depends(require_sync_agent),
):
    uid = _user_or_400(db, agent, x_garmin_device_serial)
    from .sync import apply_mark_music_deleted
    return {"marked": apply_mark_music_deleted(db, body.ids, user_id=uid)}
