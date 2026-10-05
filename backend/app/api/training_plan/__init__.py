# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Training plan API package — assembles the per-concern sub-routers.

Split out of a single ~1850-line module so each file covers one concern:
  helpers.py     goal guard, pace-bests/history, effective FTP-HR, orphan-delete queue
  matching.py    activity → workout matching + fitness/strength fingerprint advance
  injectors.py   strength / stretch / field-test workout-list builders
  generation.py  /goals/{id}/plan(/generate), /workouts/upcoming, workout CRUD + bg refresh
  ics.py         ICS token CRUD (authed) + public no-auth .ics feeds (ics_router)
  sync.py        Garmin device sync service endpoints (sync_router) + FIT builders

Three routers are exported and mounted separately by app.main:
  router      authenticated /coaching paths (generation + ICS token CRUD)
  ics_router  public no-auth /ics/... feeds
  sync_router /training-plan/sync (sync-agent bearer token guarded, no JWT
              — see app.services.sync_agent_auth)

generation.router and ics.router both own /goals/{id}/... paths but each has a
distinct static suffix, so registration order between them is unambiguous; we
keep generation first to mirror the original module ordering.
"""

from fastapi import APIRouter

from . import generation, ics
from .generation import (
    refresh_plans_for_user,
    refresh_plans_for_user_force,
    _regenerate_future_workouts,
)
from .helpers import _get_pace_bests
from .ics import ics_router
from .matching import match_activity_to_workout
from .sync import _get_sync_user, sync_router

router = APIRouter(prefix="/coaching", tags=["training-plan"])
router.include_router(generation.router)   # /goals/{id}/plan…, /workouts…, /plan/workouts…
router.include_router(ics.router)          # /goals/{id}/ics-token, /ics-token

__all__ = [
    "router",
    "sync_router",
    "ics_router",
    "_get_sync_user",
    "_get_pace_bests",
    "refresh_plans_for_user",
    "refresh_plans_for_user_force",
    "_regenerate_future_workouts",
    "match_activity_to_workout",
]
