# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Normalise uploaded audio into something a Garmin watch will actually index.

Garmin documents a generous format list (.mp3 .m4a .m4b .aac .adts .wav) but the
device is much pickier in practice, and it fails *silently*: a file the watch
dislikes does not error, it simply never appears in the music list. Two causes
account for almost all of it.

**Embedded cover art.** An mp3 with artwork carries the picture as a second
stream, and a watch scanning for a single-audio-stream file skips the whole
track. This is the common one, because virtually every tagged library has art.
Stripping it needs no re-encode — dropping the non-audio streams and copying the
audio through is lossless and near-instant, so a clean library stays bit-exact.

**Anything that isn't mp3.** The other listed containers work for some people and
not others; mp3 is the format nobody reports trouble with. Non-mp3 input gets a
real encode.

Hence three paths, cheapest first: pass the bytes through untouched, remux to
drop art, or transcode. Only the last one loses anything.
"""

from __future__ import annotations

import json
import logging
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

log = logging.getLogger(__name__)

# Sample rates left alone. 44.1k is the safe default and what we encode to;
# 48k is accepted as-is rather than resampled, because resampling a perfectly
# playable file to satisfy a preference is a pointless quality loss.
ACCEPTED_SAMPLE_RATES = (44100, 48000)
TARGET_SAMPLE_RATE = 44100

# Garmin's own guidance tops out at 320 kbps. Anything above it gets re-encoded.
MAX_BITRATE = 320_000
TARGET_BITRATE = "192k"

# ID3v2.3 rather than the newer 2.4: embedded players are consistently better at
# 2.3, and a tag the watch cannot read shows the track as its bare filename.
ID3V2_VERSION = "3"

_TIMEOUT = 300


class TranscodeError(RuntimeError):
    """Audio could not be probed or converted."""


@dataclass(frozen=True)
class AudioInfo:
    codec: str | None
    sample_rate: int | None
    bitrate: int | None
    channels: int | None
    duration_s: float | None
    has_non_audio_stream: bool
    audio_stream_count: int
    #: Container-level tags, lowercased keys ("title", "artist", "album", ...).
    tags: dict[str, str]


@dataclass(frozen=True)
class NormalisedAudio:
    data: bytes
    info: AudioInfo
    #: "passthrough" | "stripped" | "transcoded" — which of the three paths ran.
    action: str
    #: Caller-supplied metadata where given, otherwise whatever the source file
    #: already carried. What the library row should be populated from.
    title: str | None
    artist: str | None
    album: str | None


def _tool(name: str) -> str:
    path = shutil.which(name)
    if path is None:
        raise TranscodeError(
            f"{name} is not installed; the backend image must provide ffmpeg"
        )
    return path


def _run(args: list[str]) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(
            args, capture_output=True, timeout=_TIMEOUT, check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise TranscodeError("Audio conversion timed out") from exc


def probe(path: Path) -> AudioInfo:
    """Inspect a file with ffprobe.

    Stream counts matter as much as the codec here — `has_non_audio_stream` is
    what identifies the embedded-artwork case that the watch chokes on.
    """
    proc = _run([
        _tool("ffprobe"), "-v", "error",
        "-show_streams", "-show_format", "-of", "json", str(path),
    ])
    if proc.returncode != 0:
        detail = proc.stderr.decode("utf-8", "replace").strip()
        raise TranscodeError(f"Not a readable audio file: {detail or 'unknown error'}")

    try:
        parsed = json.loads(proc.stdout.decode("utf-8", "replace"))
    except json.JSONDecodeError as exc:
        raise TranscodeError("Could not read audio metadata") from exc

    streams = parsed.get("streams") or []
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    if not audio:
        raise TranscodeError("File contains no audio stream")

    first = audio[0]
    fmt = parsed.get("format") or {}

    def _int(value) -> int | None:
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    def _float(value) -> float | None:
        try:
            return float(value)
        except (TypeError, ValueError):
            return None

    # Stream bitrate is absent for VBR mp3; the format-level figure is the
    # honest overall number in that case.
    bitrate = _int(first.get("bit_rate")) or _int(fmt.get("bit_rate"))

    raw_tags = {**(fmt.get("tags") or {}), **(first.get("tags") or {})}
    tags = {
        str(k).lower(): str(v)
        for k, v in raw_tags.items()
        if v is not None and str(v).strip()
    }

    return AudioInfo(
        codec=first.get("codec_name"),
        sample_rate=_int(first.get("sample_rate")),
        bitrate=bitrate,
        channels=_int(first.get("channels")),
        duration_s=_float(first.get("duration")) or _float(fmt.get("duration")),
        has_non_audio_stream=any(s.get("codec_type") != "audio" for s in streams),
        audio_stream_count=len(audio),
        tags=tags,
    )


def _needs_transcode(info: AudioInfo) -> bool:
    """True when copying the audio through cannot produce a conforming file."""
    if info.codec != "mp3":
        return True
    if info.sample_rate not in ACCEPTED_SAMPLE_RATES:
        return True
    if info.bitrate is not None and info.bitrate > MAX_BITRATE:
        return True
    # More than one audio stream (rare, but it happens with some rips) leaves no
    # single obvious track to keep, so re-encode the first and be done.
    return info.audio_stream_count > 1


def _metadata_args(title: str | None, artist: str | None, album: str | None) -> list[str]:
    """Tag arguments. Only set what we were given — writing an empty tag would
    replace whatever the file already carries with nothing."""
    args: list[str] = []
    for key, value in (("title", title), ("artist", artist), ("album", album)):
        if value:
            args += ["-metadata", f"{key}={value}"]
    return args


def normalise(
    raw: bytes,
    *,
    title: str | None = None,
    artist: str | None = None,
    album: str | None = None,
) -> NormalisedAudio:
    """Turn arbitrary uploaded audio into a watch-playable mp3.

    Returns the original bytes untouched when the input already conforms and
    needs no tag changes, so a well-prepared library survives upload bit-exact.
    """
    if not raw:
        raise TranscodeError("Uploaded file is empty")

    with tempfile.TemporaryDirectory(prefix="tracks-music-") as tmp:
        src = Path(tmp) / "in"
        src.write_bytes(raw)
        info = probe(src)

        # Fall back to the file's own tags for anything the caller left out, so
        # an ordinary tagged upload populates the library without the user
        # retyping what is already in the file. Only *explicit* values are
        # written back with -metadata — defaulting from the source and then
        # rewriting it would turn every clean upload into a needless remux.
        resolved_title = title or info.tags.get("title")
        resolved_artist = artist or info.tags.get("artist")
        resolved_album = album or info.tags.get("album")

        transcoding = _needs_transcode(info)
        retagging = bool(_metadata_args(title, artist, album))

        if not transcoding and not info.has_non_audio_stream and not retagging:
            return NormalisedAudio(
                data=raw, info=info, action="passthrough",
                title=resolved_title, artist=resolved_artist, album=resolved_album,
            )

        dst = Path(tmp) / "out.mp3"
        args = [_tool("ffmpeg"), "-nostdin", "-y", "-i", str(src)]

        # Keep only the first audio stream. `-vn` alone is not enough: cover art
        # rides as an attached_pic video stream and some inputs carry data or
        # subtitle streams too, so map explicitly rather than subtract.
        args += ["-map", "0:a:0", "-vn", "-sn", "-dn"]
        args += _metadata_args(title, artist, album)
        # Drop any artwork already in the tags as well as in the stream table.
        args += ["-map_metadata", "0", "-id3v2_version", ID3V2_VERSION, "-write_id3v1", "1"]

        if transcoding:
            args += [
                "-c:a", "libmp3lame",
                "-b:a", TARGET_BITRATE,
                "-ar", str(TARGET_SAMPLE_RATE),
                "-ac", "2",
            ]
            action = "transcoded"
        else:
            # Lossless: the audio is already a conforming mp3, we are only
            # dropping the extra streams and rewriting tags.
            args += ["-c:a", "copy"]
            action = "stripped"

        args.append(str(dst))

        proc = _run(args)
        if proc.returncode != 0 or not dst.exists() or dst.stat().st_size == 0:
            detail = proc.stderr.decode("utf-8", "replace").strip().splitlines()
            raise TranscodeError(
                f"Audio conversion failed: {detail[-1] if detail else 'unknown error'}"
            )

        data = dst.read_bytes()
        # Re-probe the result rather than predicting it: the stored duration and
        # bitrate should describe the file we actually keep.
        out_info = probe(dst)

    log.info(
        "music normalise: %s -> %s (%s, %d bytes)",
        info.codec, out_info.codec, action, len(data),
    )
    return NormalisedAudio(
        data=data, info=out_info, action=action,
        title=resolved_title, artist=resolved_artist, album=resolved_album,
    )
