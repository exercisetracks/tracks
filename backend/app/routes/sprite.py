# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Serve self-hosted sprite sheets for MapLibre POI icons.

Sprites are stored in /map-data/sprite/{name}.{json|png}.
If not present locally, falls back to the Protomaps CDN redirect.

A sheet is two files MapLibre fetches separately — a manifest of rectangles
and the image they index into — and the USGS sheet is rebuilt in place, under
the same names, whenever its art changes. Cached for a year as immutable, a
browser kept the half it had and fetched the other new: every icon on the
desktop map shifted one slot (parks with the public-works symbol, peaks with
the park's). So the web map asks `/sprite/version` first and fetches the sheet
as `usgs.json?v=<hash>`:

  * the current `v` is cached forever — those bytes never change under it;
  * a stale `v` is a 404, not today's file: a tab open across a rebuild may hold
    the old manifest already, and an empty icon is better than the wrong one;
  * no `v` (the phone, which mostly reads the sheet from its map pack) is
    revalidated on every load, a cheap 304 by ETag.
"""

from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse, RedirectResponse

from app.config import settings
from app.services.sprite_builder import SPRITE_NAME, sprite_version

router = APIRouter(prefix="/sprite", tags=["sprite"])

CDN_BASE = "https://protomaps.github.io/basemaps-assets/sprites/v4"

_REVALIDATE = "no-cache"
_FOREVER = "public, max-age=31536000, immutable"


@router.get("/version")
def get_sprite_version():
    """The USGS sheet's current version, for the `v` query of its URLs.

    Null while the sheet is still being built; the client then fetches it
    unversioned, which revalidates.
    """
    return {"version": sprite_version()}


@router.get("/{name}.{ext}")
def serve_sprite(name: str, ext: str, v: str | None = None):
    base = (Path(settings.map_data_dir) / "sprite").resolve()
    candidate = (base / f"{name}.{ext}").resolve()
    if candidate.is_relative_to(base) and candidate.is_file():
        cache = _REVALIDATE
        if v is not None and name.removesuffix("@2x") == SPRITE_NAME:
            if v != sprite_version():
                raise HTTPException(404, "Sprite version is no longer served")
            cache = _FOREVER
        mime = "application/json" if ext == "json" else "image/png"
        return FileResponse(str(candidate), media_type=mime,
                            headers={"Cache-Control": cache})
    return RedirectResponse(f"{CDN_BASE}/{name}.{ext}")
