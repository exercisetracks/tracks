# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Garmin meta API package — assembles the per-concern sub-routers.

These endpoints power the four-state animation system that tells the
frontend whether a given exercise/stretch will animate on the user's watch.
Split out of a single ~510-line module into one focused file per concern:

  manifest.py        /animations, /animations/check — Garmin's published,
                     bundled animation manifest (read-only, offline-safe).
  devices.py         /devices, /devices/primary — watches detected from
                     synced FIT files + the user's primary-device selection.
  confirmations.py   /confirmations POST/DELETE — per-(product, cat, subtype)
                     "animates / doesn't animate on my watch" feedback,
                     keyed by product_id so it's shared across users that own
                     the same watch model.
  recap.py           /recap/* — post-sync bulk feedback: surface completed
                     workouts with un-confirmed animatable steps so the user
                     can mark them yes / no / I-don't-remember in one pass.

Sub-routers are included so every static path (/animations, /devices,
/recap/pending, …) registers before the dynamic `/recap/{workout_id}`
routes in recap.py — FastAPI matches in registration order, so recap.py
MUST be included last.
"""

from fastapi import APIRouter

from . import confirmations, devices, manifest, recap

router = APIRouter(prefix="/garmin", tags=["garmin"])
router.include_router(manifest.router)
router.include_router(devices.router)
router.include_router(confirmations.router)
router.include_router(recap.router)   # owns /recap/{workout_id} — include LAST

__all__ = ["router"]
