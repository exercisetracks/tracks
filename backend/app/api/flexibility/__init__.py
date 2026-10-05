# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Flexibility and mobility API package — assembles the per-concern sub-routers.

Split out of a single ~616-line module into focused, loosely-coupled files:
  animation.py          Garmin animation-manifest checks (shared)
  library.py            /stretches (merged built-in + custom + preferences)
  preferences.py        /preferences/{name} (PUT/DELETE)
  custom_stretches.py   /custom-stretches CRUD
  flows.py              /flows CRUD (static /flows before dynamic /flows/{id})
  generation.py         generate_post_activity_stretch_flow (pure, no router)

Public interface (must stay importable as app.api.flexibility.<name>):
  router                                — included in app.main
  generate_post_activity_stretch_flow   — imported by api/training_plan.py

Authenticated routes: /flexibility/*
"""

from fastapi import APIRouter

from . import custom_stretches, flows, library, preferences
from .generation import generate_post_activity_stretch_flow  # re-exported

router = APIRouter(prefix="/flexibility", tags=["flexibility"])
router.include_router(library.router)
router.include_router(preferences.router)
router.include_router(custom_stretches.router)
router.include_router(flows.router)

__all__ = ["router", "generate_post_activity_stretch_flow"]
