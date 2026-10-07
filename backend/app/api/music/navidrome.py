# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Connecting a music server (Navidrome and anything else speaking Subsonic).

Credentials are verified before they are stored: a saved-but-wrong password
would otherwise fail later, in a background rotation, where nobody is watching.
"""

from __future__ import annotations

import logging

import anyio.to_thread
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.music import MusicPlaylist, MusicPlaylistTrack, MusicTrack
from app.models.user_settings import UserSettings
from app.services import music_rotation
from app.services.encryption import decrypt, encrypt
from app.services.music_source import client_for, track_from_song
from app.services.subsonic import SMART_KINDS, SubsonicClient, SubsonicError

log = logging.getLogger(__name__)

router = APIRouter(prefix="/music/server", tags=["music"])


class ServerBody(BaseModel):
    url: str
    username: str
    # Omitted on an edit that only changes the URL or username — the stored
    # password is kept rather than being wiped by a form that did not show it.
    password: str | None = None


class RotateBody(BaseModel):
    limit: int | None = None


class AutoBody(BaseModel):
    auto_rotate: bool | None = None
    rotate_count: int | None = None


def _settings(db: Session, user: User) -> UserSettings:
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None:
        raise HTTPException(400, "User settings are missing")
    return us


def _client_or_400(db: Session, user: User) -> SubsonicClient:
    client = client_for(_settings(db, user))
    if client is None:
        raise HTTPException(400, "No music server is configured")
    return client


async def _call(fn, *args, **kwargs):
    """Run a blocking Subsonic call off the event loop, mapping its failures to
    502 — the fault is an upstream server's, not the caller's request."""
    try:
        return await anyio.to_thread.run_sync(lambda: fn(*args, **kwargs))
    except SubsonicError as exc:
        raise HTTPException(502, str(exc))


@router.get("")
def get_server(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    us = _settings(db, user)
    return {
        "url": us.music_server_url,
        "username": us.music_server_username,
        # Never the password itself, just whether one is on file.
        "configured": bool(us.music_server_url and us.music_server_password_enc),
        "auto_rotate": us.music_auto_rotate,
        "rotate_count": us.music_rotate_count,
        "last_run": us.music_rotate_last_run.isoformat() if us.music_rotate_last_run else None,
    }


class ProbeBody(BaseModel):
    url: str


@router.post("/probe")
async def probe_server(body: ProbeBody, user: User = Depends(require_auth)):
    """Find a music server from a partial address — scheme and default port
    filled in (services/subsonic.discover, which says why this is safe to
    offer). The form shows the login fields only once this has found one."""
    from app.services.subsonic import discover

    found = await anyio.to_thread.run_sync(lambda: discover(body.url))
    if found is None:
        raise HTTPException(404, "No music server answered at that address")
    return {"url": found}


@router.put("")
async def set_server(
    body: ServerBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Save a music server, after proving the credentials work."""
    us = _settings(db, user)

    password = body.password
    if password is None:
        password = decrypt(us.music_server_password_enc) if us.music_server_password_enc else None
    if not password:
        raise HTTPException(400, "A password is required")

    url = body.url.rstrip("/")
    if not url.startswith(("http://", "https://")):
        raise HTTPException(400, "The server URL must start with http:// or https://")

    probe = SubsonicClient(url, body.username, password)
    server_type = await _call(probe.ping)

    us.music_server_url = url
    us.music_server_username = body.username
    us.music_server_password_enc = encrypt(password)
    db.commit()

    return {"connected": True, "server": server_type, "url": url}


@router.get("/watch-config")
def watch_config(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """What the phone hands the on-watch music app: the server, and how to log in.

    This is the one place the music-server password leaves Tracks in the clear,
    and it is deliberate. The watch talks to the music server itself — over its
    own Wi-Fi, with no Tracks server in the path — and Navidrome's own API,
    which the watch needs because Subsonic's cannot page a playlist into pieces
    a watch can hold, is only reachable through a login. The phone forwards
    this straight to the watch over Bluetooth and keeps nothing.

    Only the session that owns the settings can ask, over the same TLS channel
    that carried the password in.
    """
    us = _settings(db, user)
    if not (us.music_server_url and us.music_server_password_enc):
        raise HTTPException(400, "No music server is configured")
    return {
        "url": us.music_server_url,
        "username": us.music_server_username,
        "password": decrypt(us.music_server_password_enc),
    }


@router.delete("")
def clear_server(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """Forget the server. Tracks already imported from it stay in the library —
    they keep whatever audio was cached, and simply cannot be re-fetched."""
    us = _settings(db, user)
    us.music_server_url = None
    us.music_server_username = None
    us.music_server_password_enc = None
    us.music_auto_rotate = False
    db.commit()
    return {"connected": False}


@router.patch("")
def set_auto(
    body: AutoBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    us = _settings(db, user)
    data = body.model_dump(exclude_unset=True)
    if "auto_rotate" in data:
        if data["auto_rotate"] and not us.music_server_url:
            raise HTTPException(400, "Connect a music server first")
        us.music_auto_rotate = bool(data["auto_rotate"])
    if data.get("rotate_count") is not None:
        us.music_rotate_count = max(1, min(int(data["rotate_count"]), 500))
    db.commit()
    return {"auto_rotate": us.music_auto_rotate, "rotate_count": us.music_rotate_count}


@router.get("/playlists")
async def remote_playlists(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    client = _client_or_400(db, user)
    playlists = await _call(client.playlists)
    # Counts for the watch's two built-ins, so the phone can show them beside
    # the real playlists. A count that cannot be had is left out, not an error.
    try:
        starred = len(await _call(client.starred_songs))
    except HTTPException:
        starred = None
    recent = await anyio.to_thread.run_sync(client.recently_played_count)
    return {
        "playlists": [
            {"id": p.id, "name": p.name, "song_count": p.song_count, "duration_s": p.duration_s}
            for p in playlists
        ],
        "starred_count": starred,
        "recent_count": recent,
    }


@router.get("/search")
async def remote_search(
    q: str = Query(min_length=1),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    client = _client_or_400(db, user)
    songs = await _call(client.search_songs, q)
    return {
        "songs": [
            {"id": s.id, "title": s.title, "artist": s.artist, "album": s.album,
             "album_id": s.album_id, "duration_s": s.duration_s}
            for s in songs
        ]
    }


@router.post("/playlists/{playlist_id}/import")
async def import_playlist(
    playlist_id: str,
    load_to_device: bool = Query(False),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Mirror a remote playlist into the library as a local playlist.

    Re-importing updates the same local playlist rather than making a second
    one, so this doubles as "refresh from the server".
    """
    client = _client_or_400(db, user)
    remote = await _call(client.playlists)
    meta = next((p for p in remote if p.id == playlist_id), None)
    if meta is None:
        raise HTTPException(404, "That playlist is not on the music server")

    songs = await _call(client.playlist_songs, playlist_id)
    tracks = music_rotation.adopt_songs(db, user.id, songs)

    pl = db.query(MusicPlaylist).filter_by(
        user_id=user.id, source="subsonic", source_ref=playlist_id
    ).first()
    if pl is None:
        pl = MusicPlaylist(user_id=user.id, source="subsonic", source_ref=playlist_id)
        db.add(pl)
        db.flush()
    pl.name = meta.name
    if load_to_device:
        pl.load_to_device = True

    db.query(MusicPlaylistTrack).filter(
        MusicPlaylistTrack.playlist_id == pl.id
    ).delete(synchronize_session=False)
    for position, track in enumerate(tracks):
        db.add(MusicPlaylistTrack(playlist_id=pl.id, track_id=track.id, position=position))
        if pl.load_to_device:
            track.load_to_device = True

    db.commit()
    return {"playlist_id": pl.id, "name": pl.name, "tracks": len(tracks)}


@router.post("/rotate")
async def rotate_now(
    body: RotateBody,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Recompute what the watch carries from listening history, right now."""
    try:
        return await anyio.to_thread.run_sync(
            lambda: music_rotation.run_rotation(db, user.id, limit=body.limit)
        )
    except SubsonicError as exc:
        raise HTTPException(502, str(exc))


# ── smart playlists ──────────────────────────────────────────────────────────

@router.get("/smart")
def smart_kinds(user: User = Depends(require_auth), db: Session = Depends(get_db)):
    """The derived playlists on offer, and which are already carried.

    Subsonic has no smart playlists of its own — these are query shapes Tracks
    resolves against the server, so the list is fixed rather than fetched.
    """
    carried = {
        pl.source_ref: pl
        for pl in db.query(MusicPlaylist).filter(
            MusicPlaylist.user_id == user.id, MusicPlaylist.source == "smart"
        ).all()
    }
    return {
        "kinds": [
            {
                **kind,
                "imported": kind["id"] in carried,
                "load_to_device": bool(carried[kind["id"]].load_to_device) if kind["id"] in carried else False,
            }
            for kind in SMART_KINDS
        ]
    }


@router.post("/smart/{kind}/import")
async def import_smart(
    kind: str,
    load_to_device: bool = Query(True),
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Start carrying a derived playlist.

    Unlike a mirrored playlist this is a standing query: its contents are
    recomputed from the server every time the carried set is refreshed, so
    "recently played" keeps meaning recently played rather than freezing at
    whatever was playing the day it was added.
    """
    if kind not in {k["id"] for k in SMART_KINDS}:
        raise HTTPException(404, "No such smart playlist")

    client = _client_or_400(db, user)
    label = next(k["label"] for k in SMART_KINDS if k["id"] == kind)

    pl = db.query(MusicPlaylist).filter_by(
        user_id=user.id, source="smart", source_ref=kind
    ).first()
    if pl is None:
        pl = MusicPlaylist(user_id=user.id, source="smart", source_ref=kind, name=label)
        db.add(pl)
        db.flush()
    pl.name = label
    pl.load_to_device = load_to_device
    db.commit()

    count = await _call(music_rotation.refresh_smart_playlists, db, user.id, client)
    return {"playlist_id": pl.id, "name": pl.name, "refreshed": count}


@router.delete("/smart/{kind}")
def drop_smart(
    kind: str,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Stop carrying a derived playlist.

    Its member tracks are unloaded too — they were only there because the
    playlist chose them — but tracks that some *other* carried playlist also
    names are left alone.
    """
    pl = db.query(MusicPlaylist).filter_by(
        user_id=user.id, source="smart", source_ref=kind
    ).first()
    if pl is None:
        raise HTTPException(404, "That smart playlist is not set up")

    member_ids = [
        r.track_id for r in db.query(MusicPlaylistTrack).filter_by(playlist_id=pl.id).all()
    ]
    still_wanted = {
        r.track_id
        for r in db.query(MusicPlaylistTrack)
        .join(MusicPlaylist, MusicPlaylist.id == MusicPlaylistTrack.playlist_id)
        .filter(
            MusicPlaylist.user_id == user.id,
            MusicPlaylist.id != pl.id,
            MusicPlaylist.load_to_device.is_(True),
            MusicPlaylistTrack.track_id.in_(member_ids or [0]),
        ).all()
    }
    orphans = [t for t in member_ids if t not in still_wanted]
    if orphans:
        db.query(MusicTrack).filter(
            MusicTrack.user_id == user.id, MusicTrack.id.in_(orphans)
        ).update({MusicTrack.load_to_device: False}, synchronize_session=False)

    db.delete(pl)
    db.commit()
    return {"removed": True, "unloaded": len(orphans)}
