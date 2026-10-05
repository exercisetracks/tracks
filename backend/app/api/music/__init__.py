# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Music API package.

  library.py    /music library CRUD, upload + normalisation, ranged audio
  sync.py       watch reconciliation helpers, shared by every transport
  navidrome.py  /music/server — connecting a Subsonic music server
  agent.py      /music/sync — what the garmin-sync container talks to

Three routers, split by *who is calling* rather than by resource: library and
navidrome answer to a browser session, agent to the cable-sync container
holding a pairing token. Mixing those auth models in one module is how a token
ends up trusted somewhere it should not be.

The on-watch Connect IQ app has no router here at all. It talks to the user's
music server directly (see watchapp/); the only thing Tracks contributes is the
credentials, handed to the phone by navidrome.py's /music/server/watch-config.

`music_sync_router` is exported separately rather than mounted here, because
app.main registers the agent-guarded routers on their own — they must not pick
up the JWT dependency the others carry.
"""

from fastapi import APIRouter

from . import agent, library, navidrome
from .agent import music_sync_router
from .library import ranged_audio_response
from .sync import (
    DEVICE_FILE_LIMIT,
    MUSIC_FOLDER,
    apply_mark_music_deleted,
    apply_mark_music_uploaded,
    device_plan,
    music_delete_items,
    music_filename,
    music_upload_items,
    playlist_filename,
    playlist_items,
)

router = APIRouter()
router.include_router(library.router)     # /music/...
router.include_router(navidrome.router)   # /music/server/...

__all__ = [
    "router",
    "music_sync_router",
    "MUSIC_FOLDER",
    "DEVICE_FILE_LIMIT",
    "music_filename",
    "playlist_filename",
    "music_upload_items",
    "music_delete_items",
    "playlist_items",
    "apply_mark_music_uploaded",
    "apply_mark_music_deleted",
    "device_plan",
    "ranged_audio_response",
]
