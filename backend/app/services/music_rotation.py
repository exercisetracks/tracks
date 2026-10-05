# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Keep the watch's music fresh from what the user actually listens to.

The idea is that nobody should have to curate a watch playlist. The music
server already knows what is being played; a rotation run reads that, marks
those tracks as the ones to carry, and unmarks the rest. The sync that triggered
it — over USB, or the watch's own Wi-Fi via the Connect IQ app — then moves the
difference.

Two rules keep this from being annoying:

**Uploads are never touched.** A track the user uploaded and ticked by hand is
an explicit choice; an inference from play counts does not get to overrule it.
Rotation only ever adds and removes `subsonic` tracks.

**Tracks already on the watch that are still in rotation are left alone.** The
reconciliation is on the desired-state flag, not on the files, so a track that
survives a rotation is not re-transferred — which matters when re-sending it
costs megabytes over a cable.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone

from sqlalchemy.orm import Session

from app.models.music import MusicPlaylist, MusicPlaylistTrack, MusicTrack
from app.models.user_settings import UserSettings
from app.services.music_source import client_for, track_from_song
from app.services.subsonic import SMART_IDS, SubsonicError, rotation_songs, smart_songs

log = logging.getLogger(__name__)


def run_rotation(db: Session, user_id: int, *, limit: int | None = None) -> dict:
    """Recompute which Navidrome tracks the watch should carry.

    Returns a summary; raises SubsonicError if the server cannot be reached, so
    a manual run can report why while a scheduled one can log and move on.
    """
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    client = client_for(us)
    if client is None:
        raise SubsonicError("No music server is configured")

    count = limit or (us.music_rotate_count if us else 40) or 40
    songs = rotation_songs(client, count)
    if not songs:
        log.info("music rotation: server returned nothing for user %s", user_id)
        return {"carried": 0, "added": 0, "dropped": 0, "created": 0}

    wanted_refs = {s.id for s in songs}

    existing = {
        t.source_ref: t
        for t in db.query(MusicTrack).filter(
            MusicTrack.user_id == user_id,
            MusicTrack.source == "subsonic",
        ).all()
        if t.source_ref
    }

    created = 0
    added = 0
    for song in songs:
        track = existing.get(song.id)
        if track is None:
            track = track_from_song(user_id, song)
            db.add(track)
            existing[song.id] = track
            created += 1
        else:
            # Refresh metadata; a tag fixed on the server should show here too.
            track.title = song.title
            track.artist = song.artist
            track.album = song.album
            if song.duration_s is not None:
                track.duration_s = song.duration_s
        if not track.load_to_device:
            track.load_to_device = True
            added += 1

    dropped = 0
    for ref, track in existing.items():
        if ref not in wanted_refs and track.load_to_device:
            track.load_to_device = False
            dropped += 1

    if us is not None:
        us.music_rotate_last_run = datetime.now(timezone.utc)
    db.commit()

    log.info(
        "music rotation user=%s carried=%d added=%d dropped=%d new_rows=%d",
        user_id, len(wanted_refs), added, dropped, created,
    )
    return {
        "carried": len(wanted_refs),
        "added": added,
        "dropped": dropped,
        "created": created,
    }


#: Songs per smart playlist. Small enough that several fit inside the device's
#: 500-file ceiling alongside anything picked by hand.
SMART_LIMIT = 50


def refresh_smart_playlists(db: Session, user_id: int, client=None) -> int:
    """Recompute the contents of every derived playlist this user carries.

    A smart playlist is a saved *query*, not a saved list — "recently played"
    has to mean recently played now, not whenever it was first imported. So its
    membership is rebuilt from the server each time rather than stored once.

    Member tracks inherit the playlist's carry flag, so a smart playlist the
    user is carrying pulls its current contents onto the watch and lets the ones
    that dropped out go.
    """
    client = client or client_for(db.query(UserSettings).filter_by(user_id=user_id).first())
    if client is None:
        return 0

    playlists = db.query(MusicPlaylist).filter(
        MusicPlaylist.user_id == user_id,
        MusicPlaylist.source == "smart",
    ).all()

    refreshed = 0
    for pl in playlists:
        if pl.source_ref not in SMART_IDS:
            continue
        try:
            songs = smart_songs(client, pl.source_ref, SMART_LIMIT)
        except SubsonicError as exc:
            log.warning("smart playlist %s could not refresh: %s", pl.source_ref, exc)
            continue

        tracks = adopt_songs(db, user_id, songs)
        db.query(MusicPlaylistTrack).filter(
            MusicPlaylistTrack.playlist_id == pl.id
        ).delete(synchronize_session=False)
        for position, track in enumerate(tracks):
            db.add(MusicPlaylistTrack(playlist_id=pl.id, track_id=track.id, position=position))
            if pl.load_to_device:
                track.load_to_device = True
        refreshed += 1

    db.commit()
    return refreshed


def adopt_songs(db: Session, user_id: int, songs) -> list:
    """Find-or-create library rows for remote songs, preserving input order.

    No audio is fetched: a row is a reference until a device asks for it.
    """
    refs = [s.id for s in songs]
    existing = {
        t.source_ref: t
        for t in db.query(MusicTrack).filter(
            MusicTrack.user_id == user_id,
            MusicTrack.source == "subsonic",
            MusicTrack.source_ref.in_(refs),
        ).all()
    }
    out = []
    for song in songs:
        track = existing.get(song.id)
        if track is None:
            track = track_from_song(user_id, song)
            db.add(track)
            db.flush()
            existing[song.id] = track
        else:
            track.title = song.title
            track.artist = song.artist
            track.album = song.album
        out.append(track)
    return out


def rotate_if_due(db: Session, user_id: int, *, max_age_hours: int = 6) -> bool:
    """Refresh the carried set if it is stale, on the way to answering a sync.

    Rotation is demand-driven rather than scheduled, and deliberately so. A
    periodic job would need a beat scheduler and a container to run it, and
    would spend most of its life recomputing a plan for a watch that is not
    listening. Doing it when the watch actually asks costs no new
    infrastructure and produces a fresher answer: the plan is computed from
    listening history as of moments ago, not as of the last cron tick.

    `max_age_hours` keeps a chatty client from hammering the music server —
    several syncs in an afternoon share one rotation.

    Never raises. A music server that is down should mean the watch syncs what
    was already chosen, not that the sync fails.
    """
    us = db.query(UserSettings).filter_by(user_id=user_id).first()
    if us is None or not us.music_auto_rotate or not us.music_server_url:
        return False

    last = us.music_rotate_last_run
    if last is not None:
        if last.tzinfo is None:
            last = last.replace(tzinfo=timezone.utc)
        age_hours = (datetime.now(timezone.utc) - last).total_seconds() / 3600
        if age_hours < max_age_hours:
            return False

    try:
        # Smart playlists are saved queries, so they go stale on exactly the
        # same schedule the carried set does.
        refresh_smart_playlists(db, user_id)
        run_rotation(db, user_id)
        return True
    except SubsonicError as exc:
        log.warning("music rotation skipped for user %s: %s", user_id, exc)
    except Exception:
        log.exception("music rotation failed for user %s", user_id)
    return False
