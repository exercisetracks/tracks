# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Activities API package — assembles the per-concern sub-routers.

Split out of a single ~950-line module to keep each file focused and under the
200-line budget:
  helpers.py        shared query/metric helpers + owned-or-404 guard
  list.py           /sports, / (paginated listing)
  heatmap_cache.py  heatmap cache state, builders, invalidate
  heatmap.py        /heatmap, /tracks-geojson
  backfill.py       /backfill-metrics
  backfill_fit.py   FIT re-parse backfills (laps / sets / climbs / grades)
  subresources.py   /{id}/laps, /golf-holes, /sets, /climbs
  detail.py         /{id} (+ /track, PATCH, DELETE)

Sub-routers are included so every static path (/sports, /heatmap, …) registers
before the dynamic `/{activity_id}` routes in detail.py — FastAPI matches in
registration order, so detail.py MUST be included last.
"""

from fastapi import APIRouter

from . import backfill, backfill_fit, detail, heatmap, subresources
from . import list as list_routes
from .heatmap_cache import invalidate_heatmap_cache  # re-exported

router = APIRouter(prefix="/activities", tags=["activities"])
router.include_router(list_routes.router)
router.include_router(heatmap.router)
router.include_router(backfill.router)
router.include_router(backfill_fit.router)
router.include_router(subresources.router)
router.include_router(detail.router)   # owns /{activity_id} — include LAST

__all__ = ["router", "invalidate_heatmap_cache"]
