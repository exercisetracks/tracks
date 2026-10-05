# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Audio normalisation for the watch.

The behaviour that matters here is not "does ffmpeg run" but *which of the three
paths* runs, because the cheap ones are what keep a clean library bit-exact and
the expensive one is what rescues files the watch would otherwise ignore in
silence.
"""

import shutil
import subprocess

import pytest

from app.services.music_transcode import TranscodeError, normalise, probe

pytestmark = pytest.mark.skipif(
    shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
    reason="ffmpeg/ffprobe not available",
)


def _ffmpeg(*args):
    subprocess.run(["ffmpeg", "-nostdin", "-y", *args], capture_output=True, check=True)


@pytest.fixture
def clean_mp3(tmp_path):
    """A conforming 44.1 kHz stereo mp3 with tags — the happy case."""
    path = tmp_path / "clean.mp3"
    _ffmpeg(
        "-f", "lavfi", "-i", "sine=frequency=440:duration=1",
        "-ar", "44100", "-ac", "2", "-b:a", "192k",
        "-metadata", "title=Song One", "-metadata", "artist=Someone",
        "-metadata", "album=Record", "-id3v2_version", "3", str(path),
    )
    return path


@pytest.fixture
def mp3_with_cover_art(tmp_path, clean_mp3):
    """The silent-failure case: artwork rides as a second stream and the watch
    skips the whole track."""
    art = tmp_path / "art.png"
    _ffmpeg("-f", "lavfi", "-i", "color=c=blue:s=64x64:d=1", "-frames:v", "1", str(art))
    path = tmp_path / "withart.mp3"
    _ffmpeg(
        "-i", str(clean_mp3), "-i", str(art),
        "-map", "0:a", "-map", "1:v", "-c", "copy", "-id3v2_version", "3", str(path),
    )
    return path


def test_conforming_file_passes_through_byte_exact(clean_mp3):
    raw = clean_mp3.read_bytes()
    result = normalise(raw)

    assert result.action == "passthrough"
    # Not merely equivalent — identical. Re-encoding a file that already works
    # would cost quality for nothing.
    assert result.data == raw


def test_tags_are_read_from_the_file_when_not_supplied(clean_mp3):
    result = normalise(clean_mp3.read_bytes())

    assert (result.title, result.artist, result.album) == ("Song One", "Someone", "Record")


def test_cover_art_is_stripped_without_re_encoding(mp3_with_cover_art):
    assert probe(mp3_with_cover_art).has_non_audio_stream is True

    result = normalise(mp3_with_cover_art.read_bytes())

    # "stripped", not "transcoded": the audio was already fine, so only the
    # extra stream is dropped and the samples are copied through.
    assert result.action == "stripped"
    assert result.info.has_non_audio_stream is False
    assert result.info.audio_stream_count == 1
    assert result.info.codec == "mp3"


def test_non_mp3_input_is_transcoded_to_stereo_44k_mp3(tmp_path):
    flac = tmp_path / "mono48.flac"
    _ffmpeg("-f", "lavfi", "-i", "sine=frequency=330:duration=1",
            "-ar", "48000", "-ac", "1", str(flac))

    result = normalise(flac.read_bytes())

    assert result.action == "transcoded"
    assert result.info.codec == "mp3"
    assert result.info.sample_rate == 44100
    assert result.info.channels == 2


def test_explicit_metadata_overrides_the_file_and_keeps_the_rest(clean_mp3):
    result = normalise(clean_mp3.read_bytes(), title="New Title")

    assert result.title == "New Title"
    assert result.info.tags.get("title") == "New Title"
    # Untouched fields survive rather than being blanked by the rewrite.
    assert result.info.tags.get("artist") == "Someone"


def test_duration_survives_normalisation(clean_mp3):
    result = normalise(clean_mp3.read_bytes())

    assert result.info.duration_s == pytest.approx(1.0, abs=0.3)


def test_empty_upload_is_rejected():
    with pytest.raises(TranscodeError):
        normalise(b"")


def test_non_audio_upload_is_rejected():
    with pytest.raises(TranscodeError):
        normalise(b"this is not audio, it is a text file" * 100)
