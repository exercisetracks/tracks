# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Metrics API package — assembles the per-concern sub-routers.

Split out of a single ~970-line module to keep each file focused:
  helpers.py        shared query/settings/threshold helpers + TSS→CTL/ATL/TSB
  caching.py        per-user training-load + dashboard caches; warm/invalidate
  training_load.py  /training-load (CTL/ATL/TSB fitness series)
  dashboard.py      /summary, /by-sport, /activity-calendar, /vo2max-history,
                    /weekly-volume (cache-backed dashboard slices)
  trends.py         /trends (week/month/year volume + TSS + monotony/strain)
  health.py         /daily, /activity-load, /readiness-history
  performance.py    /power-curve, /pace-curve, /race-predictions

Every route here is a static path, so registration order is not a correctness
concern (there are no dynamic `/{...}` routes to shadow).

Re-exports for the public interface used elsewhere:
  - warm_/invalidate_ cache fns (app.main, app.api.devices)
"""

from fastapi import APIRouter

from . import dashboard, health, performance, trends, training_load
from .caching import (
    invalidate_dashboard_cache,
    invalidate_training_load_cache,
    warm_dashboard_cache,
    warm_training_load_cache,
)

router = APIRouter(prefix="/metrics", tags=["metrics"])
router.include_router(training_load.router)
router.include_router(dashboard.router)
router.include_router(trends.router)
router.include_router(health.router)
router.include_router(performance.router)

__all__ = [
    "router",
    "warm_dashboard_cache",
    "warm_training_load_cache",
    "invalidate_dashboard_cache",
    "invalidate_training_load_cache",
]
