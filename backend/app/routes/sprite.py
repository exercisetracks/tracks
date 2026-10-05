# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Serve self-hosted sprite sheets for MapLibre POI icons.

Sprites are stored in /map-data/sprite/{name}.{json|png}.
If not present locally, falls back to the Protomaps CDN redirect.
"""

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, RedirectResponse

from app.config import settings

router = APIRouter(prefix="/sprite", tags=["sprite"])

CDN_BASE = "https://protomaps.github.io/basemaps-assets/sprites/v4"


@router.get("/{name}.{ext}")
def serve_sprite(name: str, ext: str):
    base = (Path(settings.map_data_dir) / "sprite").resolve()
    candidate = (base / f"{name}.{ext}").resolve()
    if candidate.is_relative_to(base) and candidate.is_file():
        mime = "application/json" if ext == "json" else "image/png"
        # NOT immutable: the sheet's contents change when sprite_builder's
        # BUILD_VERSION bumps (same URL, new glyphs). Force revalidation so a
        # regenerated sheet is picked up — FileResponse's ETag makes this a cheap
        # 304 when unchanged, and a fresh fetch when the sprite was rebuilt.
        return FileResponse(
            str(candidate), media_type=mime,
            headers={"Cache-Control": "public, max-age=0, must-revalidate"},
        )
    if ext == "json":
        return RedirectResponse(f"{CDN_BASE}/{name}.{ext}")
    return RedirectResponse(f"{CDN_BASE}/{name}.{ext}")
