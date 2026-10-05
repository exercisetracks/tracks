# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Region teardown shared by user deletes and download-cancel cleanup.

Removing a region's tiles and rebuilding the master archives without it is the
same work whether a user deleted a finished area or an in-progress download was
aborted; both paths converge on `purge_region`.
"""

import logging

from app.config import settings
from app.services import region_merger, region_registry

logger = logging.getLogger(__name__)


def purge_region(region_id: int) -> None:
    """Delete a region's tile files, rebuild every master without it, then mark it
    deleted. Shared by user delete and download-cancel cleanup. Files are removed
    first so the rebuilds exclude them (_source_paths skips missing files);
    mark_installed=False so the rebuild never flips a *concurrently* downloading
    region to 'installed'. mark_deleted (in finally) retires the list entry/wheel.
    """
    region_registry.delete_region_files(region_id)
    try:
        region_merger.rebuild_master(mark_installed=False)
        if settings.dem_source_url:
            region_merger.rebuild_master_dem()
            region_merger.rebuild_master_contours()
        region_merger.rebuild_master_overlay()
    finally:
        region_registry.mark_deleted(region_id)


def purge_cancelled_region(region_id: int) -> None:
    """Clean up after an aborted download (runs in the pipeline thread, which has
    already stopped its work — so no rebuild races a live writer)."""
    region_registry.update_status(region_id, "deleting", progress=0.0,
                                  detail="Removing cancelled download…")
    try:
        purge_region(region_id)
    except Exception:
        logger.exception("Cleanup after cancel failed for region %s", region_id)
        region_registry.mark_deleted(region_id)   # at least drop it from the list
