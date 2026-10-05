# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Serve self-hosted PBF glyph files for MapLibre vector label rendering.

Glyphs are stored in /map-data/fonts/{stack}/{range}.pbf.
If not present locally, falls back to the Protomaps CDN redirect.
"""

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse, RedirectResponse

from app.config import settings

router = APIRouter(prefix="/fonts", tags=["fonts"])

CDN_BASE = "https://protomaps.github.io/basemaps-assets/fonts"


@router.get("/{stack}/{range}.pbf")
def serve_font(stack: str, range: str):
    base = (Path(settings.map_data_dir) / "fonts").resolve()
    candidate = (base / stack / f"{range}.pbf").resolve()
    if candidate.is_relative_to(base) and candidate.is_file():
        return FileResponse(
            str(candidate), media_type="application/x-protobuf",
            headers={"Cache-Control": "public, max-age=31536000, immutable"},
        )
    return RedirectResponse(f"{CDN_BASE}/{stack}/{range}.pbf")
