# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Watch reconciliation for the music library.

Mirrors app.routes.courses_api.sync: the MusicTrack row is the source of truth,
the watch_* columns track real device state, and the upload/delete lists are
derived from the difference.

One deliberate difference from every other pushable: the upload item carries no
audio. A course FIT is a couple of kilobytes and rides as base64 inside the JSON
list; a music library is hundreds of megabytes and base64 would add a third on
top, all of it buffered in memory on both ends. Items name a `url` instead and
the client streams each track from it.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone

from sqlalchemy import and_, or_
from sqlalchemy.orm import Session

from app.models.music import MusicPlaylist, MusicPlaylistTrack, MusicTrack

#: Storage-root folder, a sibling of GARMIN/ rather than a child of it. Garmin
#: puts personal audio at the top level of the device and the watch's own
#: library scanner only looks there — writing into GARMIN/Music produces files
#: that exist on disk and never appear in the player.
MUSIC_FOLDER = "Music"

#: Garmin's documented ceiling across the whole device, not per folder.
DEVICE_FILE_LIMIT = 500

_UNSAFE = re.compile(r"[^A-Za-z0-9 ._()\-]+")


def _clean(value: str | None, fallback: str = "") -> str:
    """ASCII-safe fragment for an on-device filename.

    The watch indexes by ID3 tag, not by filename, so this only has to be
    stable, unique and readable when browsing the device over USB — it is not
    what anyone sees in the player.
    """
    text = _UNSAFE.sub(" ", (value or fallback))
    return re.sub(r"\s+", " ", text).strip()


def music_filename(track: MusicTrack) -> str:
    """Flat, collision-proof name for a track on the device.

    Flat rather than Artist/Album/ subfolders: the player groups by tag anyway,
    so nesting buys nothing on screen while making deletion, listing and the
    relative paths inside .m3u files all more fragile. The id prefix guarantees
    uniqueness without needing to consult the rest of the library.
    """
    artist = _clean(track.artist)
    title = _clean(track.title, "Untitled") or "Untitled"
    stem = f"{artist} - {title}" if artist else title
    # Keep well inside any filesystem's limit once the prefix and suffix are on.
    return f"{track.id:05d} {stem[:96]}.mp3"


def playlist_filename(playlist: MusicPlaylist) -> str:
    name = _clean(playlist.name, "Playlist") or "Playlist"
    return f"{playlist.id:04d} {name[:96]}.m3u"


#: Our transcode target is 192 kbps, so a second of audio is ~24 kB. Used to
#: size a Navidrome track that has not been fetched yet — see `_item_size`.
_BYTES_PER_SECOND = 192_000 / 8


def _ready(query):
    """Tracks that can actually be delivered.

    An upload needs its blob. A Navidrome track needs only its reference: the
    audio is fetched and cached the moment something asks for it (see
    app.services.music_source.ensure_audio), so requiring a blob here would hide
    every remote track from the very push that would materialise it.
    """
    return query.filter(
        or_(
            MusicTrack.blob_id.isnot(None),
            and_(MusicTrack.source == "subsonic", MusicTrack.source_ref.isnot(None)),
        )
    )


def _item_size(track: MusicTrack) -> int | None:
    """Bytes this track will cost, estimated when it is not yet cached.

    An estimate beats null: it is what the client checks the transfer size
    against before starting, and it is corrected to the real figure as soon as
    the audio is fetched.
    """
    if track.size_bytes:
        return track.size_bytes
    if track.duration_s:
        return int(track.duration_s * _BYTES_PER_SECOND)
    return None


def music_upload_items(db: Session, uid: int) -> list[dict]:
    """Tracks flagged for the watch that aren't on it yet."""
    tracks = _ready(
        db.query(MusicTrack).filter(
            MusicTrack.user_id == uid,
            MusicTrack.load_to_device.is_(True),
            MusicTrack.watch_uploaded_at.is_(None),
        )
    ).order_by(MusicTrack.artist, MusicTrack.album, MusicTrack.track_no, MusicTrack.id).all()

    return [
        {
            "id": t.id,
            "type": "music",
            "filename": music_filename(t),
            "folder": MUSIC_FOLDER,
            "url": f"/music/tracks/{t.id}/audio",
            "size": _item_size(t),
            "title": t.title,
            "artist": t.artist,
            "album": t.album,
            "duration_s": t.duration_s,
        }
        for t in tracks
    ]


def music_delete_items(db: Session, uid: int) -> list[dict]:
    """Tracks whose file must come off the watch — anything on the device that
    the user has since unloaded."""
    rows = db.query(MusicTrack).filter(
        MusicTrack.user_id == uid,
        MusicTrack.watch_filename.isnot(None),
        MusicTrack.watch_deleted_at.is_(None),
        MusicTrack.load_to_device.is_(False),
    ).all()
    return [
        {"id": t.id, "type": "music", "filename": t.watch_filename, "folder": MUSIC_FOLDER}
        for t in rows
    ]


def playlist_items(db: Session, uid: int) -> list[dict]:
    """Flagged playlists rendered as .m3u payloads.

    Entries are bare filenames: the .m3u sits in Music/ beside the tracks it
    names, so a relative path is just the name. Only tracks the watch is
    actually getting are listed — an .m3u line pointing at a file that was never
    pushed makes the whole playlist unusable on some firmware.
    """
    playlists = db.query(MusicPlaylist).filter(
        MusicPlaylist.user_id == uid,
        MusicPlaylist.load_to_device.is_(True),
    ).all()

    items = []
    for pl in playlists:
        rows = _ready(
            db.query(MusicTrack)
            .join(MusicPlaylistTrack, MusicPlaylistTrack.track_id == MusicTrack.id)
            .filter(
                MusicPlaylistTrack.playlist_id == pl.id,
                MusicTrack.user_id == uid,
                MusicTrack.load_to_device.is_(True),
            )
        ).order_by(MusicPlaylistTrack.position, MusicPlaylistTrack.id).all()
        if not rows:
            continue
        body = "\n".join(music_filename(t) for t in rows) + "\n"
        items.append({
            "id": pl.id,
            "type": "playlist",
            "filename": playlist_filename(pl),
            "folder": MUSIC_FOLDER,
            "content": body,
            "track_count": len(rows),
        })
    return items


def apply_mark_music_uploaded(db: Session, items: list[dict], user_id: int) -> int:
    """Record pushed tracks. Scoped to one user — every caller is a browser
    session acting for itself."""
    now = datetime.now(timezone.utc)
    marked = 0
    for item in items:
        t = db.get(MusicTrack, item.get("id"))
        if t is None or t.user_id != user_id:
            continue
        t.watch_filename = item.get("filename") or music_filename(t)
        t.watch_uploaded_at = now
        t.watch_deleted_at = None
        marked += 1
    db.commit()
    return marked


def apply_mark_music_deleted(db: Session, ids: list[int], user_id: int) -> int:
    """Record removed tracks, purging rows that were only kept alive so their
    file could be cleaned off the device first (see the library delete route)."""
    now = datetime.now(timezone.utc)
    marked = 0
    for tid in ids:
        t = db.get(MusicTrack, tid)
        if t is None or t.user_id != user_id:
            continue
        if t.purge_after_delete:
            # Drop the audio too; nothing references it once the row is gone.
            from app.services import music_storage
            if t.blob_id:
                music_storage.delete_blob(t.blob_id)
            db.delete(t)
        else:
            t.watch_deleted_at = now
            t.watch_uploaded_at = None
            t.watch_filename = None
        marked += 1
    db.commit()
    return marked


def device_plan(db: Session, uid: int) -> dict:
    """Everything the client needs for one music push.

    `on_device_after` lets the caller check Garmin's 500-file ceiling *before*
    starting a transfer that would silently overrun it, rather than discovering
    the limit part-way through a multi-megabyte push.
    """
    add = music_upload_items(db, uid)
    remove = music_delete_items(db, uid)
    playlists = playlist_items(db, uid)

    already = db.query(MusicTrack).filter(
        MusicTrack.user_id == uid,
        MusicTrack.watch_uploaded_at.isnot(None),
        MusicTrack.watch_deleted_at.is_(None),
    ).count()

    return {
        "add": add,
        "remove": remove,
        "playlists": playlists,
        "folder": MUSIC_FOLDER,
        "bytes_to_add": sum(i["size"] or 0 for i in add),
        "on_device_after": already - len(remove) + len(add) + len(playlists),
        "device_file_limit": DEVICE_FILE_LIMIT,
    }
