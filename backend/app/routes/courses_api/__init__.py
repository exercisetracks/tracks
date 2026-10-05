# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Custom tracks (courses) API package — two routers, assembled per concern.

Split out of a single ~726-line module to keep each file focused:
  helpers.py   shared constants/palette, serializers, stat/turn recompute,
               owned-or-404 guard, activity-track loader
  tracks.py    static `/courses` paths — list / geojson / create / import /
               from-activity / check-turns
  merge.py     `/courses/merge` (one cohesive concatenation routine)
  detail.py    dynamic `/courses/{track_id}` paths — get / patch / delete /
               save-mine (included LAST so its wildcard never shadows statics)
  folders.py   `/folders` CRUD
  sync.py      the separate course_sync_router (prefix /maps/sync, sync-secret
               guarded) garmin-sync uses for watch reconciliation

Two distinct routers are exported (main.py includes them separately):
  router             prefix /maps, behind normal auth (main.py adds _auth_dep).
  course_sync_router prefix /maps/sync, NO global auth — every route is guarded
                     by the shared sync secret.

Sub-routers are included so every static `/courses/*` path registers before the
dynamic `/courses/{track_id}` routes in detail.py — FastAPI matches in
registration order, so detail.py MUST be included last.
"""
from fastapi import APIRouter

from . import detail, folders, merge, tracks
from .sync import course_sync_router  # re-exported, registered separately by main.py

router = APIRouter(prefix="/maps", tags=["courses"])
router.include_router(tracks.router)
router.include_router(merge.router)
router.include_router(folders.router)
router.include_router(detail.router)   # owns /courses/{track_id} — include LAST

__all__ = ["router", "course_sync_router"]
