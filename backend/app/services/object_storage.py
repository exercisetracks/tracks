# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Blob storage for ingested FIT data — local disk only for now. A pluggable
S3-compatible backend is a stage-6/follow-up concern (see the branch plan);
this interface is deliberately storage-agnostic so that can be swapped in
later without touching callers.

Blobs stored here are always opaque ciphertext: either sealed (libsodium
sealed-box, from a sync agent that only ever holds a public key) or
DEK-encrypted (from an authenticated browser session) — this module never
sees plaintext and never needs to. Unlike the old filesystem-watcher layout
(a permanent plaintext archive under a per-username folder), blobs are named
by random id, not by anything meaningful, and there's no per-user directory
structure to reason about or accidentally expose.
"""

import uuid
from pathlib import Path

from app.config import settings


def _root() -> Path:
    root = Path(settings.fit_files_dir) / "blobs"
    root.mkdir(parents=True, exist_ok=True)
    return root


def store_blob(data: bytes) -> str:
    """Persist opaque bytes, return an opaque blob_id to retrieve them by."""
    blob_id = uuid.uuid4().hex
    (_root() / blob_id).write_bytes(data)
    return blob_id


def load_blob(blob_id: str) -> bytes:
    return (_root() / blob_id).read_bytes()


def delete_blob(blob_id: str) -> None:
    (_root() / blob_id).unlink(missing_ok=True)
