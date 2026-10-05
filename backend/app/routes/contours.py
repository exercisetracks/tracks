# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""API endpoint to trigger contour line regeneration.

Contour tiles are served automatically by go-pmtiles from
``master_contours.pmtiles`` in the map data directory.
"""

import logging

from fastapi import APIRouter

from app.services.contour_generator import regenerate_contours

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/contours", tags=["contours"])


@router.post("/regenerate")
def trigger_contour_regeneration():
    """Regenerate contour tiles from the master DEM archive.

    Reads ``master_dem.pmtiles``, generates contour lines at USGS-style
    intervals using contourpy, and writes MVT tiles into
    ``master_contours.pmtiles``.
    """
    ok = regenerate_contours()
    if not ok:
        return {"status": "error", "detail": "master_dem.pmtiles not found or generation failed"}
    return {"status": "ok"}
