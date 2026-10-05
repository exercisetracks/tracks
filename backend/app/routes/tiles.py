# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""API endpoint for tile version info — used by the frontend for cache busting.

Returns the mtime (Unix timestamp) of each PMTiles archive so the frontend
can append ?v={mtime} to tile URLs.  When the underlying file changes
(after a region merge), the mtime changes → new URL → cache miss.
"""

from pathlib import Path

from fastapi import APIRouter

from app.config import settings

router = APIRouter(prefix="/tiles", tags=["tiles"])

# The basemap is intentionally NOT here: z0-12 is the static planet_basemap and
# z13-15 the small master_basemap_detail, served as one source split by zoom in
# Caddy. Neither is cache-busted on a region download — new z13-15 detail is
# fetched fresh on the next zoom-in, so the basemap never visibly reloads.
TILESETS = ["master_dem", "master_contours", "master_overlay", "master_routes"]


@router.get("/version")
def tiles_version():
    data_dir = Path(settings.map_data_dir)
    versions = {}
    for name in TILESETS:
        p = data_dir / f"{name}.pmtiles"
        if p.is_file():
            versions[name] = int(p.stat().st_mtime)
        else:
            versions[name] = 0
    return versions
