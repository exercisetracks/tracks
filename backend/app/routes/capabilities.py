# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Public capability negotiation — what this server is and what it can do.

Deliberately unauthenticated, like `/auth/status`: a client needs to know
whether it can talk to this deployment at all *before* it has a token, and
needs a way to say "this server is too old for me" instead of discovering it
one 404 at a time.

Everything here is either a constant of the build or cheaply derived from
what's actually deployed. Nothing in this response should require a database
query, so a client can poll it on a bad connection without cost.

Note the split between `features` and per-user settings: this endpoint answers
"does this server implement X", not "has this user enabled X". Map layers,
wildfire overlays, and AI coaching are all user preferences living in
`UserSettings` — read those from `/users/me/settings` after login.
"""

from pathlib import Path

from fastapi import APIRouter
from pydantic import BaseModel

from app.api.sync_ingest import INGEST_BATCH_BYTES, INGEST_BATCH_FILES, INGEST_MISSING_HASHES
from app.config import settings
from app.version import API_VERSION, MIN_CLIENT_API_VERSION, SERVER_VERSION

router = APIRouter(tags=["capabilities"])


class CapabilityLimits(BaseModel):
    """Bounds a client should clamp to before sending, not after being refused."""
    track_max_points: int
    activities_page_size: int
    fit_upload_bytes: int
    fit_precheck_entries: int
    ingest_batch_files: int
    ingest_batch_bytes: int
    ingest_missing_hashes: int


class CapabilitiesOut(BaseModel):
    """The negotiation contract, declared rather than implied.

    This endpoint returned a bare dict until a mobile-side shape test went
    looking for its schema and found `{}`. That is worse here than anywhere
    else in the API: every other endpoint is called by a client that already
    decided it can talk to this server, and this is the one that makes that
    decision. An undeclared response means no generated client can check it and
    no contract test can catch it drifting — precisely the skew that appears
    once an app ships through a store and update timing stops being ours.
    """
    app: str
    server_version: str
    api_version: int
    min_client_api_version: int
    features: list[str]
    limits: CapabilityLimits

# Feature strings a client may check for before using an endpoint. Add one when
# you add a capability a client can reasonably be expected to detect rather than
# assume. Removing one is a breaking change — bump MIN_CLIENT_API_VERSION.
_FEATURES = frozenset({
    "device_sync",       # /device-sync/* — push FIT to a watch via a client
    "sync_agents",       # /sync-agents/* pairing + /sync/ingest sealed upload
    "pending_imports",   # sealed blobs queued while the vault is locked
    "sync_ingest_batch", # /sync/ingest/missing + /sync/ingest/batch
    "track_max_points",  # /activities/{id}/track?max_points=
    "heatmap_viewport",  # /activities/heatmap?bbox=&zoom=
    "sync_v1",           # /sync/push + /sync/pull + /sync/blobs (spec/sync.yaml)
    "map_style",         # /maps/style.json (?base_url= for native clients)
    "refresh_tokens",    # /auth/refresh + /auth/sessions
    "device_keys",       # /auth/device-keys + /auth/device-unlock — silent
                         # vault re-unlock, so no weekly password prompt
    "recovery_key",      # BIP39 recovery phrase at setup
    "vault_lock",        # 401 session_expired is distinct from unauthenticated
})


def _has_basemap() -> bool:
    """True when a planet basemap archive is actually on disk.

    Maps are optional: the archives are ~20 GB and a deployment can legitimately
    run without them. A client that hides its map tab when this is false gives a
    better answer than one that shows an empty viewport.
    """
    return (Path(settings.map_data_dir) / "planet_basemap.pmtiles").is_file()


@router.get("/capabilities", response_model=CapabilitiesOut)
def capabilities():
    return {
        "app": "tracks",
        "server_version": SERVER_VERSION,
        "api_version": API_VERSION,
        "min_client_api_version": MIN_CLIENT_API_VERSION,
        "features": sorted(_FEATURES | ({"maps"} if _has_basemap() else set())),
        "limits": {
            # Mirrors the bounds enforced in the endpoints themselves, so a
            # client can clamp before sending rather than eat a 422.
            "track_max_points": 50_000,
            "activities_page_size": 100,
            "fit_upload_bytes": 50 * 1024 * 1024,
            "fit_precheck_entries": 10_000,
            "ingest_batch_files": INGEST_BATCH_FILES,
            "ingest_batch_bytes": INGEST_BATCH_BYTES,
            "ingest_missing_hashes": INGEST_MISSING_HASHES,
        },
    }
