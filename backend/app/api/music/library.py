# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Music library CRUD, upload, and audio delivery."""

from __future__ import annotations

import hashlib
import logging
import re
from typing import List

import anyio.to_thread
from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, Response, StreamingResponse
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.music import MusicPlaylist, MusicPlaylistTrack, MusicTrack
from app.services import music_rotation, music_source, music_storage
from app.services.music_source import MusicSourceError
from app.services.music_transcode import TranscodeError, normalise

from .sync import (
    apply_mark_music_deleted,
    apply_mark_music_uploaded,
    device_plan,
)

log = logging.getLogger(__name__)

router = APIRouter(prefix="/music", tags=["music"])

# Generous: a long lossless upload is legitimately large, and it is normalised
# down to mp3 before anything is stored.
_MAX_FILE_BYTES = 200 * 1024 * 1024


# ── shapes ───────────────────────────────────────────────────────────────────

class TrackPatch(BaseModel):
    title: str | None = None
    artist: str | None = None
    album: str | None = None
    track_no: int | None = None
    load_to_device: bool | None = None


class LoadRequest(BaseModel):
    ids: List[int]
    load: bool = True


class PlaylistBody(BaseModel):
    name: str | None = None
    load_to_device: bool | None = None


class PlaylistTracks(BaseModel):
    track_ids: List[int]


class MarkUploaded(BaseModel):
    items: List[dict]


class MarkDeleted(BaseModel):
    ids: List[int]


def _track_dict(t: MusicTrack) -> dict:
    return {
        "id": t.id,
        "title": t.title,
        "artist": t.artist,
        "album": t.album,
        "track_no": t.track_no,
        "duration_s": t.duration_s,
        "size_bytes": t.size_bytes,
        "codec": t.codec,
        "bitrate": t.bitrate,
        "sample_rate": t.sample_rate,
        "source": t.source,
        "cached": bool(t.blob_id),
        "load_to_device": t.load_to_device,
        "on_watch": t.watch_uploaded_at is not None and t.watch_deleted_at is None,
        "created_at": t.created_at.isoformat() if t.created_at else None,
    }


def _owned(db: Session, model, obj_id: int, user: User):
    obj = db.get(model, obj_id)
    if obj is None or obj.user_id != user.id:
        raise HTTPException(404, "Not found")
    return obj


# ── tracks ───────────────────────────────────────────────────────────────────

@router.get("/tracks")
def list_tracks(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    rows = (
        db.query(MusicTrack)
        .filter(MusicTrack.user_id == user.id)
        .order_by(MusicTrack.artist, MusicTrack.album, MusicTrack.track_no, MusicTrack.id)
        .all()
    )
    return {"tracks": [_track_dict(t) for t in rows]}


@router.post("/tracks")
async def upload_tracks(
    files: List[UploadFile] = File(...),
    load_to_device: bool = Form(False),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Upload audio, normalise it, and add it to the library.

    Each file is converted to a form the watch will actually index before it is
    stored — see app.services.music_transcode for why that is not optional.
    Duplicates are recognised by the hash of the *normalised* bytes, so the same
    song uploaded twice in different source formats still collapses to one row.
    """
    if not files:
        raise HTTPException(400, "No files provided")

    added, duplicates, failed = [], [], []

    for upload in files:
        raw = await upload.read()
        if not raw:
            failed.append({"filename": upload.filename, "error": "File was empty"})
            continue
        if len(raw) > _MAX_FILE_BYTES:
            failed.append({"filename": upload.filename, "error": "File is too large"})
            continue

        # ffmpeg is a blocking subprocess; keep it off the event loop.
        try:
            result = await anyio.to_thread.run_sync(lambda r=raw: normalise(r))
        except TranscodeError as exc:
            failed.append({"filename": upload.filename, "error": str(exc)})
            continue
        except Exception:
            log.exception("music upload: normalise crashed for %s", upload.filename)
            failed.append({"filename": upload.filename, "error": "Could not process audio"})
            continue

        digest = hashlib.sha256(result.data).hexdigest()
        existing = (
            db.query(MusicTrack)
            .filter(MusicTrack.user_id == user.id, MusicTrack.content_hash == digest)
            .first()
        )
        if existing is not None:
            duplicates.append({"filename": upload.filename, "id": existing.id})
            continue

        # Last resort for an untagged file: its own name, minus the extension.
        fallback = re.sub(r"\.[A-Za-z0-9]{1,5}$", "", upload.filename or "").strip()

        track = MusicTrack(
            user_id=user.id,
            title=result.title or fallback or "Untitled",
            artist=result.artist,
            album=result.album,
            source="upload",
            blob_id=music_storage.store_blob(result.data),
            size_bytes=len(result.data),
            duration_s=result.info.duration_s,
            codec=result.info.codec,
            bitrate=result.info.bitrate,
            sample_rate=result.info.sample_rate,
            content_hash=digest,
            load_to_device=load_to_device,
        )
        db.add(track)
        db.commit()
        added.append({**_track_dict(track), "action": result.action})

    return {"added": added, "duplicates": duplicates, "failed": failed}


@router.patch("/tracks/{track_id}")
def patch_track(
    track_id: int,
    body: TrackPatch,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    track = _owned(db, MusicTrack, track_id, user)
    for field, value in body.model_dump(exclude_unset=True).items():
        setattr(track, field, value)
    db.commit()
    return _track_dict(track)


@router.post("/tracks/load")
def set_load(
    body: LoadRequest,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Bulk flag/unflag tracks for the watch — how the UI's checkboxes commit."""
    rows = (
        db.query(MusicTrack)
        .filter(MusicTrack.user_id == user.id, MusicTrack.id.in_(body.ids))
        .all()
    )
    for track in rows:
        track.load_to_device = body.load
    db.commit()
    return {"updated": len(rows)}


@router.delete("/tracks/{track_id}")
def delete_track(
    track_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Remove a track from the library.

    A track currently on the watch is not deleted outright: it is unloaded and
    flagged, so the row survives long enough for the next sync to take the file
    off the device, then it is purged. Deleting the row immediately would strand
    the file on the watch with nothing left to describe it.
    """
    track = _owned(db, MusicTrack, track_id, user)

    on_watch = track.watch_filename is not None and track.watch_deleted_at is None
    if on_watch:
        track.load_to_device = False
        track.purge_after_delete = True
        db.commit()
        return {"deleted": False, "pending_device_removal": True}

    if track.blob_id:
        music_storage.delete_blob(track.blob_id)
    db.delete(track)
    db.commit()
    return {"deleted": True, "pending_device_removal": False}


# Digits are bounded rather than left open: `int()` on a few million digits is
# not free, and past 4300 of them CPython refuses outright and the handler 500s.
# 19 digits is past any conceivable file size, so nothing legitimate is lost.
_RANGE = re.compile(r"^bytes=(\d{0,19})-(\d{0,19})$")

# Read size for a ranged response. The range itself is whatever the client asked
# for — possibly the entire track — so it is served in pieces rather than read
# into memory first.
_CHUNK = 64 * 1024


def ranged_audio_response(path, range_header: str | None) -> Response:
    """Serve an mp3, honouring a Range header.

    Shared by both device paths because both want it: the browser push resumes
    an interrupted transfer rather than restarting a multi-megabyte file, and
    Connect IQ fetches media in ranged chunks by default.
    """
    size = path.stat().st_size
    headers = {"Accept-Ranges": "bytes", "Cache-Control": "private, max-age=3600"}

    match = _RANGE.match(range_header or "")
    if not match:
        return FileResponse(path, media_type="audio/mpeg", headers=headers)

    start_raw, end_raw = match.groups()
    if start_raw:
        start = int(start_raw)
        end = int(end_raw) if end_raw else size - 1
    else:
        # A suffix range ("bytes=-500") asks for the final N bytes.
        if not end_raw:
            raise HTTPException(416, "Invalid range")
        start = max(size - int(end_raw), 0)
        end = size - 1

    end = min(end, size - 1)
    if start > end or start >= size:
        return Response(
            status_code=416,
            headers={**headers, "Content-Range": f"bytes */{size}"},
        )

    # Streamed, not buffered. `bytes=0-` is a legal range meaning "all of it",
    # so reading the range up front would pull a whole track into memory per
    # request — and the watch's sync asks for several tracks at once.
    length = end - start + 1

    def body():
        with path.open("rb") as fh:
            fh.seek(start)
            left = length
            while left > 0:
                block = fh.read(min(_CHUNK, left))
                if not block:
                    break
                left -= len(block)
                yield block

    return StreamingResponse(
        body(),
        status_code=206,
        media_type="audio/mpeg",
        headers={
            **headers,
            "Content-Range": f"bytes {start}-{end}/{size}",
            "Content-Length": str(length),
        },
    )


@router.get("/tracks/{track_id}/audio")
def track_audio(
    track_id: int,
    request: Request,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """The normalised mp3, materialising a Navidrome reference on first ask."""
    track = _owned(db, MusicTrack, track_id, user)
    # A Navidrome track is a reference until something asks for it. This is that
    # moment: fetch, normalise and cache, then serve like any other. Already
    # cached is a single existence check.
    try:
        blob_id = music_source.ensure_audio(db, track)
    except MusicSourceError as exc:
        raise HTTPException(404, str(exc))

    return ranged_audio_response(music_storage.blob_path(blob_id), request.headers.get("range"))


# ── playlists ────────────────────────────────────────────────────────────────

@router.get("/playlists")
def list_playlists(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    rows = db.query(MusicPlaylist).filter(MusicPlaylist.user_id == user.id).all()

    # Every playlist's members in one query rather than one query per playlist.
    # Ordered by playlist and then position, so grouping is a single pass and
    # each list comes out already in playlist order.
    members: dict[int, list[int]] = {pl.id: [] for pl in rows}
    if members:
        for playlist_id, track_id in (
            db.query(MusicPlaylistTrack.playlist_id, MusicPlaylistTrack.track_id)
            .filter(MusicPlaylistTrack.playlist_id.in_(members.keys()))
            .order_by(
                MusicPlaylistTrack.playlist_id,
                MusicPlaylistTrack.position,
                MusicPlaylistTrack.id,
            )
            .all()
        ):
            members[playlist_id].append(track_id)

    return {
        "playlists": [
            {
                "id": pl.id,
                "name": pl.name,
                "load_to_device": pl.load_to_device,
                "source": pl.source,
                "track_ids": members[pl.id],
            }
            for pl in rows
        ]
    }


@router.post("/playlists")
def create_playlist(
    body: PlaylistBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    pl = MusicPlaylist(
        user_id=user.id,
        name=body.name or "Playlist",
        load_to_device=bool(body.load_to_device),
    )
    db.add(pl)
    db.commit()
    return {"id": pl.id, "name": pl.name, "load_to_device": pl.load_to_device, "track_ids": []}


@router.patch("/playlists/{playlist_id}")
def patch_playlist(
    playlist_id: int,
    body: PlaylistBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    pl = _owned(db, MusicPlaylist, playlist_id, user)
    data = body.model_dump(exclude_unset=True)
    for field, value in data.items():
        setattr(pl, field, value)

    # Flagging a playlist is how most people choose what to carry, so it
    # cascades to its members the way a CustomTrackFolder's switch does —
    # otherwise the playlist arrives on the watch naming files that were never
    # pushed, which some firmware treats as a broken playlist rather than a
    # short one.
    if data.get("load_to_device") is True:
        member_ids = [
            r.track_id
            for r in db.query(MusicPlaylistTrack).filter(
                MusicPlaylistTrack.playlist_id == pl.id
            ).all()
        ]
        if member_ids:
            db.query(MusicTrack).filter(
                MusicTrack.user_id == user.id, MusicTrack.id.in_(member_ids)
            ).update({MusicTrack.load_to_device: True}, synchronize_session=False)

    db.commit()
    return {"id": pl.id, "name": pl.name, "load_to_device": pl.load_to_device}


@router.put("/playlists/{playlist_id}/tracks")
def set_playlist_tracks(
    playlist_id: int,
    body: PlaylistTracks,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Replace the playlist's contents, in the order given."""
    pl = _owned(db, MusicPlaylist, playlist_id, user)

    owned = {
        t.id
        for t in db.query(MusicTrack.id)
        .filter(MusicTrack.user_id == user.id, MusicTrack.id.in_(body.track_ids))
        .all()
    }
    db.query(MusicPlaylistTrack).filter(
        MusicPlaylistTrack.playlist_id == pl.id
    ).delete(synchronize_session=False)

    position = 0
    for track_id in body.track_ids:
        if track_id not in owned:
            continue
        db.add(MusicPlaylistTrack(playlist_id=pl.id, track_id=track_id, position=position))
        position += 1

    if pl.load_to_device and owned:
        db.query(MusicTrack).filter(
            MusicTrack.user_id == user.id, MusicTrack.id.in_(owned)
        ).update({MusicTrack.load_to_device: True}, synchronize_session=False)

    db.commit()
    return {"id": pl.id, "track_count": position}


@router.delete("/playlists/{playlist_id}")
def delete_playlist(
    playlist_id: int,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    pl = _owned(db, MusicPlaylist, playlist_id, user)
    db.delete(pl)
    db.commit()
    return {"deleted": True}


# ── device sync ──────────────────────────────────────────────────────────────

@router.get("/device-plan")
def music_device_plan(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Everything one music push needs: files to add, files to remove, the
    playlists to write, and the counts to sanity-check against the device's
    500-file ceiling before starting."""
    # Refresh from listening history first when it is stale and switched on, so
    # a cable sync carries the same fresh set the Wi-Fi path would.
    music_rotation.rotate_if_due(db, user.id)
    return device_plan(db, user.id)


@router.post("/mark-uploaded")
def music_mark_uploaded(
    body: MarkUploaded,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    return {"marked": apply_mark_music_uploaded(db, body.items, user_id=user.id)}


@router.post("/mark-deleted")
def music_mark_deleted(
    body: MarkDeleted,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    return {"marked": apply_mark_music_deleted(db, body.ids, user_id=user.id)}


__all__ = ["router", "ranged_audio_response"]
