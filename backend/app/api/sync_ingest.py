# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
Ingest endpoints for sync agents (garmin-sync, a future mobile app) — see
the branch plan's "pluggable ingest + egest" section. Agents authenticate
with a per-agent bearer pairing token (require_sync_agent), never a global
secret (app.services.sync_agent_auth).

Ingestion never needs a live user session: agents seal FIT bytes locally
against the target user's public key (fetched via /sync/pubkey) before
sending them here, so the server only ever stores/queues ciphertext it can't
open itself — parsing happens later, once that user's session key is
available (app.services.fit_import.process_pending_imports_for_user, called
on login).
"""

import base64
import logging

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

from app.database import get_db
from app.services.fit_import import register_file
from app.models.imports import Import, PendingImport
from app.models.sync_agents import SyncAgent
from app.models.user_keys import UserKey
from app.services import object_storage
from app.services.device_resolution import get_or_create_device
from app.services.sync_agent_auth import require_sync_agent, resolve_sync_user_id

router = APIRouter(prefix="/sync", tags=["sync-ingest"])
log = logging.getLogger(__name__)


def _device_serial_header(x_garmin_device_serial: str = Header(default="")) -> str | None:
    return x_garmin_device_serial or None


@router.get("/pubkey")
def get_pubkey(
    device_serial: str | None = Depends(_device_serial_header),
    agent: SyncAgent = Depends(require_sync_agent),
    db: Session = Depends(get_db),
):
    """Returns the target user's ingestion public key — not secret, safe to
    hand to any authenticated sync agent. 404 if the device isn't claimed by
    anyone yet (see /sync/register-device)."""
    user_id = resolve_sync_user_id(db, agent, device_serial)
    if user_id is None:
        raise HTTPException(status_code=404, detail="Device not claimed by any user yet")

    uk = db.query(UserKey).filter_by(user_id=user_id).first()
    if uk is None:
        raise HTTPException(status_code=404, detail="User has no key material yet")

    return {"user_id": user_id, "public_key": base64.b64encode(uk.public_key).decode()}


class DeviceRegisterRequest(BaseModel):
    serial_number: str
    manufacturer: str | None = None
    manufacturer_id: int | None = None
    product_name: str | None = None
    product_id: int | None = None
    software_version: str | None = None


@router.post("/register-device")
def register_device(
    body: DeviceRegisterRequest,
    agent: SyncAgent = Depends(require_sync_agent),
    db: Session = Depends(get_db),
):
    """Registers device metadata only — no activity data — so a brand-new,
    not-yet-claimed device shows up in the Devices UI for the user to claim.
    Mirrors the old filesystem watcher's auto-register-on-first-import
    behavior without needing to parse (and therefore decrypt) anything
    first. Personal agents auto-claim on their own owner's behalf; household
    agents leave the device unclaimed for a human to sort out."""
    user_id = agent.user_id  # None for household agents — left unclaimed
    device_id = get_or_create_device(db, body.model_dump(), user_id)
    db.commit()
    return {"device_id": device_id}


class IngestRequest(BaseModel):
    device_serial: str | None = None
    filename: str
    content_hash: str   # sha256 of the PLAINTEXT bytes, computed by the agent
    sealed_b64: str      # libsodium sealed-box ciphertext, base64


# Bounds for the batch endpoints below, mirrored in /capabilities so a client
# clamps before sending. The file count keeps one request's commit small; the
# byte bound is the client's to keep (it knows the sizes before it seals), and
# is generous because the point is to amortise a round trip, not to stream.
INGEST_BATCH_FILES = 200
INGEST_BATCH_BYTES = 16 * 1024 * 1024
INGEST_MISSING_HASHES = 10_000


def _valid_hash(h: str) -> bool:
    return len(h) == 64 and all(c in "0123456789abcdef" for c in h)


def _ingest_one(db: Session, user_id: int, agent: SyncAgent, body: IngestRequest) -> str:
    """Queue one sealed file, or say why not. Leaves the commit to the caller,
    so a batch costs one commit rather than one per file.

    Dedup against both already-imported files and blobs still queued — an
    agent may legitimately retry an upload it's unsure landed. Both scoped to
    the user, and the first regardless of source: another household member
    holding the same file is not a duplicate, and the same user's file
    delivered once by USB and once from the phone is.
    """
    content_hash = body.content_hash.lower()
    if not _valid_hash(content_hash):
        return "invalid_hash"

    if db.query(Import).filter_by(user_id=user_id, source_id=content_hash).first():
        return "duplicate"
    if db.query(PendingImport).filter_by(user_id=user_id, content_hash=content_hash).first():
        return "duplicate"

    try:
        sealed = base64.b64decode(body.sealed_b64)
    except Exception:
        return "invalid_base64"

    blob_id = object_storage.store_blob(sealed)
    db.add(PendingImport(
        user_id=user_id, blob_id=blob_id, content_hash=content_hash,
        filename=body.filename,
    ))
    # Registered now, not at parse: the parse waits for the vault, and other
    # phones should learn the file exists (and fetch it) without waiting too.
    # It also flushes, which is what lets a batch carrying the same file twice
    # see the first copy and answer "duplicate" for the second.
    register_file(db, user_id, content_hash, body.filename, blob_id=blob_id, sealed=True)
    log.info("Queued sealed FIT %s for user %s (agent %s)", body.filename, user_id, agent.id)
    return "queued"


@router.post("/ingest")
def ingest_sealed_fit(
    body: IngestRequest,
    agent: SyncAgent = Depends(require_sync_agent),
    db: Session = Depends(get_db),
):
    user_id = resolve_sync_user_id(db, agent, body.device_serial)
    if user_id is None:
        raise HTTPException(status_code=404, detail="Device not claimed by any user yet")

    status = _ingest_one(db, user_id, agent, body)
    if status == "invalid_hash":
        raise HTTPException(status_code=400, detail="content_hash must be a hex SHA-256")
    if status == "invalid_base64":
        raise HTTPException(status_code=400, detail="sealed_b64 is not valid base64")
    db.commit()
    return {"status": status}


class MissingRequest(BaseModel):
    device_serial: str | None = None
    hashes: list[str] = Field(max_length=INGEST_MISSING_HASHES)


@router.post("/ingest/missing")
def ingest_missing(
    body: MissingRequest,
    agent: SyncAgent = Depends(require_sync_agent),
    db: Session = Depends(get_db),
):
    """Which of these files the server does not hold yet.

    Exists because a phone re-sends its whole library whenever it meets a
    server it has not uploaded to (a new install, a restore, a recreated
    server), and through /ingest every file the server already had still
    crossed the network in full — sealed, base64'd — only to be answered
    "duplicate". Asking first turns thousands of those round trips into one.

    Only hashes go up, never content, and they are answered for the agent's
    own user only, so this tells an agent nothing it could not learn by
    uploading the file.
    """
    user_id = resolve_sync_user_id(db, agent, body.device_serial)
    if user_id is None:
        raise HTTPException(status_code=404, detail="Device not claimed by any user yet")

    wanted = {h.lower() for h in body.hashes if _valid_hash(h.lower())}
    if not wanted:
        return {"missing": []}
    held = {h for (h,) in db.query(Import.source_id)
            .filter(Import.user_id == user_id, Import.source_id.in_(wanted))}
    held |= {h for (h,) in db.query(PendingImport.content_hash)
             .filter(PendingImport.user_id == user_id, PendingImport.content_hash.in_(wanted))}
    # In the order asked, so a client can zip the answer against its own list.
    seen: set[str] = set()
    missing = []
    for h in body.hashes:
        h = h.lower()
        if h in wanted and h not in held and h not in seen:
            seen.add(h)
            missing.append(h)
    return {"missing": missing}


class BatchIngestRequest(BaseModel):
    device_serial: str | None = None
    files: list[IngestRequest] = Field(max_length=INGEST_BATCH_FILES)


@router.post("/ingest/batch")
def ingest_sealed_batch(
    body: BatchIngestRequest,
    agent: SyncAgent = Depends(require_sync_agent),
    db: Session = Depends(get_db),
):
    """Many sealed files in one request — /ingest, amortised.

    A phone with years of history was sending one file per round trip, each
    paying the network's latency plus two commits here (the agent's
    last-seen stamp and the insert), which held a first upload to about two
    files a second however fast the link. One request now carries a batch
    under one auth check and one commit.

    Each file gets its own status, in order, and a bad one does not sink the
    rest: a client deciding which files to mark sent needs to know exactly
    which were taken. All files resolve to one user — the batch's
    `device_serial` — since a phone uploads for one person; a per-file serial
    is ignored rather than allowed to scatter one request across accounts.
    """
    user_id = resolve_sync_user_id(db, agent, body.device_serial)
    if user_id is None:
        raise HTTPException(status_code=404, detail="Device not claimed by any user yet")

    results = [
        {"content_hash": f.content_hash.lower(), "status": _ingest_one(db, user_id, agent, f)}
        for f in body.files
    ]
    db.commit()
    return {"results": results}
