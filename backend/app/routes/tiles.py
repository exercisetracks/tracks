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
# planet_dem_z7 is listed so the browser's z0-7 relief URL carries a version
# too and is cached for good, instead of revalidated tile by tile.
TILESETS = ["master_dem", "master_contours", "master_overlay", "master_routes", "planet_dem_z7",
            "master_pois"]


@router.get("/merged/{z}/{x}/{y}.mvt")
def merged_tile(z: int, x: int, y: int, v: str | None = None):
    """One tile with every vector archive's layers in it: the web map's single
    vector source (app.services.merged_tiles explains why). Versioned URLs are
    the same bytes forever, so they are cached forever; an unversioned one is
    revalidated.

    No sign-in, like every other /api/tiles path (Caddy serves those straight
    from go-pmtiles): the archives are OpenStreetMap and public-domain data the
    map shows anyone who can open the page. A tile not yet joined costs some
    tens of milliseconds of decoding, which is why joined tiles are cached and
    the zoom is bounded (merged_tiles.MAX_ZOOM)."""
    from fastapi.responses import Response
    from app.services import merged_tiles

    body = merged_tiles.merged_tile(z, x, y)
    cache = "public, max-age=31536000, immutable" if v else "no-cache"
    if body is None:
        return Response(status_code=204, headers={"Cache-Control": cache})
    return Response(
        content=body,
        media_type="application/vnd.mapbox-vector-tile",
        headers={"Content-Encoding": "gzip", "Cache-Control": cache},
    )


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
    # The web map's merged source (merged_tiles.version): all its archives' mtimes.
    from app.services import merged_tiles
    versions["merged"] = merged_tiles.version()
    return versions
