# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A user's music library and what of it belongs on the watch.

Watch reconciliation mirrors CustomTrack and Waypoint exactly (see
app.models.custom_track): ``load_to_device`` is the DESIRED state and the
watch_* columns record the actual one, so the sync endpoints derive work from
the difference rather than from a queue that can drift. That matters more here
than elsewhere — a music push is megabytes per track, so a queue that
double-counts costs real minutes on a USB cable.

  upload-list = load_to_device AND watch_uploaded_at IS NULL
  delete-list = watch_filename NOT NULL AND watch_deleted_at IS NULL
                AND load_to_device = False

Unlike courses and workouts, audio is never carried inline in the sync list.
A course FIT is a couple of kilobytes and rides as base64 in JSON; a track is
several megabytes and would inflate by a third doing the same. The list carries
metadata and the client fetches bytes per track from /music/tracks/{id}/audio.
"""

from sqlalchemy import (
    Boolean, Column, DateTime, Float, ForeignKey, Integer, String, UniqueConstraint,
)
from sqlalchemy.sql import func

from app.database import Base


class MusicTrack(Base):
    __tablename__ = "music_tracks"

    id      = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)

    title    = Column(String, nullable=False, default="Untitled")
    artist   = Column(String, nullable=True)
    album    = Column(String, nullable=True)
    track_no = Column(Integer, nullable=True)

    # Where the audio came from. "upload" owns a blob of its own; "subsonic"
    # (Navidrome) names a remote id we re-fetch, so the row can exist before any
    # audio has been cached locally.
    source     = Column(String, nullable=False, default="upload")
    source_ref = Column(String, nullable=True)

    # Post-normalisation audio (see app.services.music_transcode). blob_id is
    # nullable so a row can be created while its transcode job is still running.
    blob_id      = Column(String, nullable=True)
    size_bytes   = Column(Integer, nullable=True)
    duration_s   = Column(Float, nullable=True)
    codec        = Column(String, nullable=True)
    bitrate      = Column(Integer, nullable=True)
    sample_rate  = Column(Integer, nullable=True)

    # Hash of the *normalised* bytes, so re-uploading the same source file twice
    # is recognised even when the two uploads carried different tags.
    content_hash = Column(String, nullable=True, index=True)

    # ── Watch reconciliation (mirrors CustomTrack) ───────────────────────────
    load_to_device     = Column(Boolean, nullable=False, default=False)
    watch_filename     = Column(String, nullable=True)
    watch_uploaded_at  = Column(DateTime(timezone=True), nullable=True)
    watch_deleted_at   = Column(DateTime(timezone=True), nullable=True)
    purge_after_delete = Column(Boolean, nullable=False, default=False)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class MusicPlaylist(Base):
    """A named ordering of tracks, written to the watch as an .m3u.

    ``load_to_device`` cascades to member tracks the way a CustomTrackFolder's
    does: flagging a playlist is how most people will choose what to carry,
    rather than ticking tracks one at a time.
    """

    __tablename__ = "music_playlists"

    id      = Column(Integer, primary_key=True)
    user_id = Column(Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)

    name           = Column(String, nullable=False, default="Playlist")
    load_to_device = Column(Boolean, nullable=False, default=False)

    # Set for playlists mirrored from an external source (Navidrome), so a
    # re-sync updates the same row instead of duplicating it.
    source     = Column(String, nullable=False, default="local")
    source_ref = Column(String, nullable=True)

    # Filename last written to the watch, so a rename can clean up the old .m3u.
    watch_filename = Column(String, nullable=True)

    created_at = Column(DateTime(timezone=True), server_default=func.now())
    updated_at = Column(DateTime(timezone=True), server_default=func.now(), onupdate=func.now())


class MusicPlaylistTrack(Base):
    __tablename__ = "music_playlist_tracks"
    __table_args__ = (
        UniqueConstraint("playlist_id", "track_id", name="uq_music_playlist_track"),
    )

    id          = Column(Integer, primary_key=True)
    playlist_id = Column(Integer, ForeignKey("music_playlists.id", ondelete="CASCADE"), nullable=False, index=True)
    track_id    = Column(Integer, ForeignKey("music_tracks.id", ondelete="CASCADE"), nullable=False, index=True)
    position    = Column(Integer, nullable=False, default=0)
