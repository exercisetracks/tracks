# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Kept for the modules that only need the reload trigger.

Assembling the masters from per-area parts is gone: the masters are the map
now, written only by services.map_store under the build queue.
"""

from app.services.map_store import (  # noqa: F401
    MASTER_BASEMAP as MASTER_BASEMAP_DETAIL_NAME,
    MASTER_CONTOURS as MASTER_CONTOURS_NAME,
    MASTER_DEM as MASTER_DEM_NAME,
    MASTER_OVERLAY as MASTER_OVERLAY_NAME,
    write_reload_trigger,
)
