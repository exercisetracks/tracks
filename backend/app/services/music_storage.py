# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Blob storage for music audio — deliberately *not* app.services.object_storage.

object_storage holds ingested health data and guarantees every blob it keeps is
opaque ciphertext: sealed by a sync agent's public key, or encrypted under the
user's DEK. Music cannot live under that rule. A watch has no key and no way to
get one — whichever transport delivers a track (MTP over USB, or a Connect IQ
app pulling over Wi-Fi), what arrives has to be a playable file. Storing music
sealed would mean decrypting it on every read purely to hand out the plaintext,
which buys nothing and quietly weakens the invariant the other store depends on.

So music gets its own root with the same tiny interface, and object_storage's
"never plaintext" promise stays literally true rather than approximately true.
Audio is also bulky, and keeping it out of the health-blob directory means a
music library cannot bury the FIT archive.
"""

import uuid
from pathlib import Path

from app.config import settings


def _root() -> Path:
    root = Path(settings.fit_files_dir) / "music"
    root.mkdir(parents=True, exist_ok=True)
    return root


def store_blob(data: bytes) -> str:
    """Persist audio bytes, return an opaque blob_id to retrieve them by."""
    blob_id = uuid.uuid4().hex
    (_root() / blob_id).write_bytes(data)
    return blob_id


def load_blob(blob_id: str) -> bytes:
    return (_root() / blob_id).read_bytes()


def blob_path(blob_id: str) -> Path:
    """Filesystem path for a blob, for streaming without reading it all in.

    A track is megabytes, not kilobytes, and both the browser push and the
    watch's ranged fetches want to read slices rather than whole files.
    """
    return _root() / blob_id


def blob_exists(blob_id: str) -> bool:
    return (_root() / blob_id).is_file()


def delete_blob(blob_id: str) -> None:
    (_root() / blob_id).unlink(missing_ok=True)
