# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Strength training API package — assembles the per-concern sub-routers.

Split out of a single ~530-line module so each file owns one resource:
  schemas.py           shared Pydantic models + equipment vocabulary
  library.py           /exercises (library + custom, enriched)
  progress.py          /progress, /history, /exercises/{name}/1rm, /weekly-summary
  equipment.py         /equipment (GET/PUT)
  preferences.py       /preferences/{name} (PUT/DELETE)
  custom_exercises.py  /custom-exercises CRUD

Route ordering: library.py owns the static GET /exercises; progress.py owns the
dynamic PUT /exercises/{name}/1rm. FastAPI matches in registration order, so
library is included before progress.

Public interface: only `router` is imported elsewhere (app.main).
"""
from fastapi import APIRouter

from . import custom_exercises, equipment, library, preferences, progress

router = APIRouter(prefix="/strength", tags=["strength"])
router.include_router(library.router)      # static /exercises — before dynamic /exercises/{...}
router.include_router(progress.router)
router.include_router(equipment.router)
router.include_router(preferences.router)
router.include_router(custom_exercises.router)

__all__ = ["router"]
