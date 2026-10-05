# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Coaching API package — assembles the per-concern sub-routers.

Split out of a single ~1500-line module so each file covers one concern:
  helpers.py       shared user/device scoping, TSS/CTL-ATL load model, result cache
  daily.py         /today, /readiness, /ai-enhance, /plan, /history
  goals.py         /goals CRUD (static /goals before dynamic /goals/{id})
  race_helpers.py  athlete metrics, triathlon splits, weather, plan serializer
  race_plan.py     /goals/{id}/predicted-time + /race-plan(/generate|/course|/fit)

The only externally-imported name is `router` (used by app.main). Sub-routers
are included so every static path registers before the dynamic /goals/{id}
routes — FastAPI matches in registration order, so goals.py and race_plan.py
(both owning /goals…) come after the static daily endpoints.
"""

from fastapi import APIRouter

from . import daily, goals, race_plan

router = APIRouter(prefix="/coaching", tags=["coaching"])
router.include_router(daily.router)       # static paths: /today, /readiness, /plan…
router.include_router(goals.router)       # /goals + /goals/{id}
router.include_router(race_plan.router)   # /goals/{id}/race-plan… (nested, include after goals)

__all__ = ["router"]
