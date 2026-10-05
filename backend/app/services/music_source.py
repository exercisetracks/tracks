# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Where a track's audio actually comes from.

Two sources, one interface. An uploaded track owns its bytes outright. A
Navidrome track is a reference until something asks for it, at which point the
audio is fetched, normalised and cached as a blob like any other.

That laziness is the point. Mirroring a whole music server into Tracks storage
would duplicate a library the user already has; fetching on demand means only
the handful of tracks actually going to a watch are ever stored, and the cache
is disposable — delete a blob and the next request refetches it.

Both device paths converge here. The USB push and the Connect IQ app both read
audio from `/music/tracks/{id}/audio`, so neither needs to know a track came
from Navidrome, and neither needs credentials for it: the watch cannot
authenticate to a music server, and that server is usually not reachable from
outside the LAN anyway.
"""

from __future__ import annotations

import hashlib
import logging

from sqlalchemy.orm import Session

from app.models.music import MusicTrack
from app.models.user_settings import UserSettings
from app.services import music_storage
from app.services.encryption import decrypt
from app.services.music_transcode import TranscodeError, normalise
from app.services.subsonic import SubsonicClient, SubsonicError, SubsonicSong

log = logging.getLogger(__name__)


class MusicSourceError(RuntimeError):
    """Audio could not be obtained for a track."""


def client_for(us: UserSettings | None) -> SubsonicClient | None:
    """Build a client from stored settings, or None when none is configured."""
    if us is None or not us.music_server_url or not us.music_server_username:
        return None
    password = decrypt(us.music_server_password_enc) if us.music_server_password_enc else None
    if password is None:
        log.warning("music server password could not be decrypted for user %s", us.user_id)
        return None
    return SubsonicClient(us.music_server_url, us.music_server_username, password)


def track_from_song(user_id: int, song: SubsonicSong) -> MusicTrack:
    """A library row for a remote song. No audio yet — see `ensure_audio`."""
    return MusicTrack(
        user_id=user_id,
        title=song.title,
        artist=song.artist,
        album=song.album,
        track_no=song.track_no,
        duration_s=song.duration_s,
        source="subsonic",
        source_ref=song.id,
    )


def ensure_audio(db: Session, track: MusicTrack) -> str:
    """Return a blob id holding this track's playable audio, fetching it first
    if the track is a remote reference that has not been cached yet.

    Idempotent and safe to call on every request: an already-cached track is a
    single existence check.
    """
    if track.blob_id and music_storage.blob_exists(track.blob_id):
        return track.blob_id

    if track.source != "subsonic" or not track.source_ref:
        raise MusicSourceError("This track has no audio stored")

    us = db.query(UserSettings).filter_by(user_id=track.user_id).first()
    client = client_for(us)
    if client is None:
        raise MusicSourceError("No music server is configured")

    try:
        raw = client.stream(track.source_ref)
    except SubsonicError as exc:
        raise MusicSourceError(str(exc)) from exc

    # The server transcoded to mp3 for us, but Subsonic servers embed cover art
    # and the watch silently skips any file carrying a second stream. Normalise
    # regardless — a clean file passes through untouched.
    try:
        result = normalise(raw)
    except TranscodeError as exc:
        raise MusicSourceError(f"Downloaded audio was unusable: {exc}") from exc

    # A blob that exists but is no longer on disk (cache cleared) leaves a
    # dangling id; overwrite rather than orphan a second copy.
    if track.blob_id:
        music_storage.delete_blob(track.blob_id)

    track.blob_id = music_storage.store_blob(result.data)
    track.size_bytes = len(result.data)
    track.codec = result.info.codec
    track.bitrate = result.info.bitrate
    track.sample_rate = result.info.sample_rate
    track.content_hash = hashlib.sha256(result.data).hexdigest()
    if track.duration_s is None:
        track.duration_s = result.info.duration_s
    db.commit()

    log.info("music: cached subsonic track %s (%d bytes)", track.id, track.size_bytes)
    return track.blob_id


def evict_audio(db: Session, track: MusicTrack) -> None:
    """Drop the cached bytes for a remote track, keeping the reference.

    Only meaningful for `subsonic` tracks — evicting an upload would destroy the
    only copy, so this refuses rather than being clever about it.
    """
    if track.source != "subsonic":
        raise MusicSourceError("Uploaded tracks have no re-fetchable source")
    if track.blob_id:
        music_storage.delete_blob(track.blob_id)
        track.blob_id = None
        track.size_bytes = None
        db.commit()
