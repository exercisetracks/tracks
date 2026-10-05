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
from pydantic import BaseModel
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


@router.post("/ingest")
def ingest_sealed_fit(
    body: IngestRequest,
    agent: SyncAgent = Depends(require_sync_agent),
    db: Session = Depends(get_db),
):
    user_id = resolve_sync_user_id(db, agent, body.device_serial)
    if user_id is None:
        raise HTTPException(status_code=404, detail="Device not claimed by any user yet")

    content_hash = body.content_hash.lower()
    if len(content_hash) != 64 or any(c not in "0123456789abcdef" for c in content_hash):
        raise HTTPException(status_code=400, detail="content_hash must be a hex SHA-256")

    # Dedup against both already-imported files and blobs still queued — an
    # agent may legitimately retry an upload it's unsure landed. Both scoped to
    # the user, and the first regardless of source: another household member
    # holding the same file is not a duplicate, and the same user's file
    # delivered once by USB and once from the phone is.
    if db.query(Import).filter_by(user_id=user_id, source_id=content_hash).first():
        return {"status": "duplicate"}
    if db.query(PendingImport).filter_by(user_id=user_id, content_hash=content_hash).first():
        return {"status": "duplicate"}

    try:
        sealed = base64.b64decode(body.sealed_b64)
    except Exception:
        raise HTTPException(status_code=400, detail="sealed_b64 is not valid base64")

    blob_id = object_storage.store_blob(sealed)
    db.add(PendingImport(
        user_id=user_id, blob_id=blob_id, content_hash=content_hash,
        filename=body.filename,
    ))
    # Registered now, not at parse: the parse waits for the vault, and other
    # phones should learn the file exists (and fetch it) without waiting too.
    register_file(db, user_id, content_hash, body.filename, blob_id=blob_id, sealed=True)
    db.commit()
    log.info("Queued sealed FIT %s for user %s (agent %s)", body.filename, user_id, agent.id)
    return {"status": "queued"}
