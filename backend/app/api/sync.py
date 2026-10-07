# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Two-way sync for offline-first clients: push, pull, and FIT file bytes.

There is no file manifest endpoint: the pulled `fit_file` rows are the list of
files an account has, and a phone downloads each live one it lacks.

The wire format is spec/sync.yaml's; the merge is app.sync.merge; the database
side is app.sync.store. This module is only the HTTP edge.

Every endpoint here needs the crypto session, not just a token: rows carry
encrypted columns (injuries today) that can be neither written nor read with
the vault shut, and a pull that silently skipped them would look complete. A
phone reopens the vault itself with its device key on `session_expired`. The
one thing that works with the vault shut is still `POST /sync/ingest` — watch
sync on a mountain must never wait for a password.
"""
from __future__ import annotations

from fastapi import APIRouter, Body, Depends, HTTPException, Query, Response
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import User
from app.services import activity_status, fit_import
from app.services.crypto_context import UserKeyMaterial, require_crypto_session
from app.api.metrics.caching import invalidate_dashboard_cache, invalidate_training_load_cache
from app.sync import store

router = APIRouter(prefix="/sync", tags=["sync"])


# The body is spec/sync.yaml's push shape plus "epoch": <int> — the account
# epoch the phone last saw on a pull (absent = 0). A push from an older epoch
# is rejected whole with reason "wiped"; see app.sync.store.push.
@router.post("/push")
def sync_push(
    body: dict = Body(...),
    user: User = Depends(require_auth),
    _key: UserKeyMaterial = Depends(require_crypto_session),
    db: Session = Depends(get_db),
):
    if not isinstance(body.get("changes", []), list):
        raise HTTPException(status_code=422, detail="changes must be a list")
    # The phone's sync, on the sidebar (activity_status): every push, pull and
    # file download is a heartbeat, so it shows while the phone is talking to
    # the server and stops within seconds of it finishing.
    activity_status.mark(user.id, "phone")
    result = store.push(db, user.id, body)
    # The cached load and dashboard series are computed from these entities
    # (goals choose the MTB multiplier, settings the thresholds). A web edit
    # invalidates them where it is made; a phone's edit arrives here instead.
    if any(isinstance(c, dict) and c.get("entity") in _LOAD_INPUTS for c in body.get("changes", [])):
        invalidate_training_load_cache()
        invalidate_dashboard_cache()
    return result


_LOAD_INPUTS = frozenset({"goal", "settings", "activity", "device"})


@router.get("/pull")
def sync_pull(
    since: int = Query(0, ge=0),
    limit: int | None = Query(None, ge=1),
    user: User = Depends(require_auth),
    _key: UserKeyMaterial = Depends(require_crypto_session),
    db: Session = Depends(get_db),
):
    activity_status.mark(user.id, "phone")
    return store.pull(db, user.id, since, limit)


@router.get("/blobs/{sha256}")
def blob_download(
    sha256: str,
    user: User = Depends(require_auth),
    key: UserKeyMaterial = Depends(require_crypto_session),
    db: Session = Depends(get_db),
):
    """A FIT file's plaintext bytes.

    Looked up by (user, hash), never by hash alone: two family members can
    hold the same file, and each must reach only their own copy.
    """
    activity_status.mark(user.id, "phone")
    raw = _plaintext(db, user.id, sha256.lower(), key)
    if raw is None:
        raise HTTPException(status_code=404, detail="No such file")
    return Response(content=raw, media_type="application/octet-stream",
                    headers={"Cache-Control": "private, no-store"})


def _plaintext(db: Session, user_id: int, sha256: str, key: UserKeyMaterial) -> bytes | None:
    # Shared with the summary backfill, which reads the same at-rest copies.
    return fit_import.retained_plaintext(db, user_id, sha256, key)
