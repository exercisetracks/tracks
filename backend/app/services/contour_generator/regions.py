# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Whole-archive contour regeneration.

An area's contours are traced as a step of its build (services.map_builds),
from master_dem for its box and a ring around it. This rebuilds the whole of
master_contours from master_dem instead: for a server whose contours are
missing (a fresh volume restored without them), and for the admin endpoint.
The new archive is written beside the old and renamed over it, so the
contours already served stay up until it is done.
"""

from __future__ import annotations

from .builder import build_contours_pmtiles
from .constants import _contour_lock, logger


def regenerate_contours(progress_cb=None) -> bool:
    """Rebuild master_contours from master_dem. False when there is no DEM.

    Takes the map writer lock (services.map_builds) so it can never run while
    an area build is publishing into the same archive; returns False if the
    queue is busy rather than waiting behind a build that may take an hour.
    """
    from sqlalchemy import text

    from app.database import engine
    from app.services import map_builds, map_store

    dem = map_store.master_path(map_store.MASTER_DEM)
    if not dem.exists():
        return False
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as conn:
        if not conn.execute(text("SELECT pg_try_advisory_lock(:k)"), {"k": map_builds.LOCK_KEY}).scalar():
            logger.info("Map writer busy — contour regeneration not started")
            return False
        try:
            with _contour_lock:
                build_contours_pmtiles(dem, map_store.master_path(map_store.MASTER_CONTOURS),
                                       progress_cb=progress_cb)
        finally:
            conn.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": map_builds.LOCK_KEY})
    map_store.write_reload_trigger()
    return True
