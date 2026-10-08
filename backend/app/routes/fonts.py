# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Serve PBF glyph files for MapLibre vector label rendering.

Glyphs are stored in /map-data/fonts/{stack}/{range}.pbf. A range that is not
there yet is fetched once from the Protomaps CDN, kept, and served from disk
from then on.

This used to answer a missing range with a redirect to the CDN. The browser
stopped following it when the web app gained a Content-Security-Policy
(caddy/Caddyfile, `connect-src 'self'`), and every label on the map fell back
to glyphs drawn locally. Fetching here keeps the policy whole — the page still
talks to this origin only — and leaves an install that has been online once
able to draw labels offline.

Security: the endpoint is public (MapLibre fetches it without a token), so a
caller can make the server fetch from the CDN. It can only fetch from that one
fixed host, under a path built from a font-name pattern and a numeric range;
only a found glyph is written to disk, so a stream of made-up names stores
nothing.
"""

import logging
import os
import re
import tempfile
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse, Response

from app.config import settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/fonts", tags=["fonts"])

CDN_BASE = "https://protomaps.github.io/basemaps-assets/fonts"

# Font stack names are words and spaces ("Noto Sans Regular"); ranges are
# 256-codepoint blocks ("0-255").
_STACK = re.compile(r"^[A-Za-z0-9 _-]{1,64}$")
_RANGE = re.compile(r"^\d{1,5}-\d{1,5}$")

_CACHE_HEADERS = {"Cache-Control": "public, max-age=31536000, immutable"}


@router.get("/{stack}/{range}.pbf")
def serve_font(stack: str, range: str):
    if not _STACK.match(stack) or not _RANGE.match(range):
        raise HTTPException(404, "No such glyph range")
    base = (Path(settings.map_data_dir) / "fonts").resolve()
    candidate = (base / stack / f"{range}.pbf").resolve()
    if not candidate.is_relative_to(base):
        raise HTTPException(404, "No such glyph range")
    if candidate.is_file():
        return FileResponse(str(candidate), media_type="application/x-protobuf",
                            headers=_CACHE_HEADERS)

    data = _fetch(stack, range)
    if data is None:
        # MapLibre draws the block locally when a range is missing, so a 404
        # costs legibility, not the map. Not cached by the browser: the CDN
        # may well answer next time.
        raise HTTPException(404, "Glyph range unavailable")
    _store(candidate, data)
    return Response(data, media_type="application/x-protobuf", headers=_CACHE_HEADERS)


def _fetch(stack: str, range: str) -> bytes | None:
    url = f"{CDN_BASE}/{stack}/{range}.pbf"
    try:
        r = httpx.get(url, timeout=10, follow_redirects=True)
    except httpx.HTTPError as e:
        logger.warning("Glyph fetch failed for %s: %s", url, e)
        return None
    if r.status_code != 200:
        if r.status_code != 404:
            logger.warning("Glyph fetch for %s answered %s", url, r.status_code)
        return None
    return r.content


def _store(path: Path, data: bytes) -> None:
    """Write through a temp file, so a concurrent reader never sees half a file.
    A failure here only costs the next request another fetch."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
        with os.fdopen(fd, "wb") as f:
            f.write(data)
        os.replace(tmp, path)
    except OSError as e:
        logger.warning("Could not keep glyph range %s: %s", path, e)
