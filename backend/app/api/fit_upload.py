# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import hashlib
import json
import logging
from pathlib import Path
from typing import List

from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy.orm import Session

import anyio.to_thread

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.models.imports import Import
from app.services import fit_import
from app.services.crypto_context import require_crypto_session

router = APIRouter(prefix="/fit", tags=["fit"])
log = logging.getLogger(__name__)

# FIT files are normally well under a megabyte. Cap generously so a single
# upload can't exhaust memory — the whole file is read into RAM to hash it.
_MAX_FILE_BYTES = 50 * 1024 * 1024  # 50 MB

# Bulk uploads process one file at a time synchronously (see
# app.services.fit_import.import_uploaded_bytes) rather than fanning out
# across a thread pool the way the old filesystem-watcher path did — each
# call already does its own encrypt+parse+insert+commit, and running many
# of those concurrently against the same DB session/user devices invited
# races the old code specifically called out avoiding. A later stage moves
# this onto the Celery task queue for real concurrency.


def _hash_content(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


@router.post("/upload")
async def upload_fit_files(
    files: List[UploadFile] = File(...),
    user: User = Depends(require_auth),
    material=Depends(require_crypto_session),
):
    """
    Upload one or more .fit files. Each is encrypted under the current
    session's key before it's persisted anywhere, then parsed and inserted
    immediately — there's a live key right here, no need to queue and wait
    for a future login the way sync-agent ingestion does.
    """
    log.info("[UPLOAD] Request received - user_id=%s, files=%d", user.id, len(files) if files else 0)

    if not files:
        raise HTTPException(status_code=400, detail="No files provided")

    imported = []
    duplicates = []
    errors = []

    for upload in files:
        # Sanitise to a bare filename — never trust the client-supplied path.
        filename = Path(upload.filename or "unnamed").name
        if not filename.lower().endswith(".fit"):
            errors.append({"filename": filename, "error": "Not a .fit file"})
            continue
        if upload.size is not None and upload.size > _MAX_FILE_BYTES:
            errors.append({"filename": filename, "error": "File too large"})
            continue

        content = await upload.read()
        if len(content) > _MAX_FILE_BYTES:
            errors.append({"filename": filename, "error": "File too large"})
            continue

        try:
            # import_uploaded_bytes does real file/DB/CPU work — offload so
            # it doesn't block the event loop for the duration (it takes
            # `material` as an explicit argument, not the ambient
            # contextvar, so no run_with_key propagation is needed here).
            status = await anyio.to_thread.run_sync(
                fit_import.import_uploaded_bytes, user.id, content, filename, material,
            )
            if status == "duplicate":
                duplicates.append({"filename": filename, "sha256": _hash_content(content)[:16]})
            else:
                imported.append({"filename": filename, "sha256": _hash_content(content)[:16], "status": status})
        except Exception as e:
            log.exception("[UPLOAD] Failed to process %s", filename)
            errors.append({"filename": filename, "error": str(e)[:200]})

    log.info("[UPLOAD] Completed - imported=%d, duplicates=%d, errors=%d",
              len(imported), len(duplicates), len(errors))

    if not imported and errors:
        raise HTTPException(
            status_code=400,
            detail={"message": "No files imported", "errors": [e["error"] for e in errors]}
        )

    return {
        "saved": len(imported),
        "skipped": len(duplicates),
        "errors": len(errors),
        "total": len(files),
        "files": imported,
        "message": f"Imported {len(imported)} file(s).",
    }


class PrecheckFile(BaseModel):
    filename: str
    size: int | None = None


class PrecheckRequest(BaseModel):
    files: list[PrecheckFile] = Field(default_factory=list, max_length=10000)


@router.post("/precheck")
def precheck_fit_files(
    payload: PrecheckRequest,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """
    Given filenames+sizes visible on a device, report which have already been
    imported so browser-based device sync can skip transferring them.

    Matching is filename + size against past imports' extra JSON; a filename
    recorded without a size (legacy rows) matches on name alone. This is a
    transfer-saving heuristic only — anything uploaded anyway is still
    deduplicated by content hash.
    """
    known: dict[str, set] = {}
    rows = db.query(Import.extra).filter(Import.source.in_(["upload", "sync"])).all()
    for (extra,) in rows:
        if isinstance(extra, str):
            try:
                extra = json.loads(extra)
            except ValueError:
                continue
        if not isinstance(extra, dict):
            continue
        name = extra.get("filename")
        if name:
            known.setdefault(name, set()).add(extra.get("size"))

    known_names, new_names = [], []
    for f in payload.files:
        sizes = known.get(f.filename)
        if sizes is not None and (None in sizes or f.size is None or f.size in sizes):
            known_names.append(f.filename)
        else:
            new_names.append(f.filename)

    return {"known": known_names, "new": new_names}


@router.post("/upload-simple")
async def upload_fit_files_simple(
    files: List[UploadFile] = File(...),
    user: User = Depends(require_auth),
    material=Depends(require_crypto_session),
):
    """Simplified upload - same as /upload."""
    return await upload_fit_files(files, user, material)
