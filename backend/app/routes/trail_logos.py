# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Source + serve long-trail emblem images.

At launch we download a small set of trail logos from Wikimedia Commons /
Wikipedia (licence-tracked) into map-data/trail_logos/ and serve them to the map
(public, like the sprite/fonts). Any trail not sourced here falls back to a
client-generated shield badge (see frontend trailLogos.js), so this is purely
additive — missing or failed downloads never break the map.
"""

from __future__ import annotations

import io
import logging
import threading
from pathlib import Path

import httpx
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from PIL import Image

from app.config import settings

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/maps", tags=["maps"])

# Wikimedia requires a descriptive User-Agent or it returns 403.
_UA = "Tracks/1.0 (self-hosted personal fitness map; long-trail route emblems)"
_COMMONS = "https://commons.wikimedia.org/wiki/Special:FilePath/"
_ENWIKI = "https://en.wikipedia.org/wiki/Special:FilePath/"

# id → candidate image URLs (tried in order). Special:FilePath rasterises SVGs to
# PNG via ?width and follows the redirect to upload.wikimedia.org. Only files
# confirmed to be the trail's emblem are listed; everything else uses the badge.
#
# Coverage note: these are the only US long trails with a freely-licensed emblem
# on Wikimedia (mostly National Scenic Trails). Most other trails in the frontend
# registry (adt, pnt, iat, net, pht, jmt, lt, sht) have copyrighted org logos that
# are NOT on Commons, so they intentionally fall back to the generated shield
# badge. The Colorado Trail (`ct`) instead ships a bundled, hand-provided
# emblem in backend/trail_logo_defaults/ct.png (seeded into map-data/trail_logos/
# by entrypoint.sh), so it isn't sourced here. Drop a 96px PNG into
# map-data/trail_logos/<id>.png to override any trail with a hand-provided emblem.
LOGO_SOURCES: dict[str, list[str]] = {
    "cdt": [_COMMONS + "ContinentalDivideTrailLogo.png?width=128"],
    "pct": [_COMMONS + "Pct-logo.svg?width=128"],
    "at":  [_ENWIKI + "Appalachian_Trail_logo.png?width=128",
            _COMMONS + "Appalachian_Trail_logo.png?width=128"],
    "azt": [_COMMONS + "Arizona_Trail_logo.svg?width=128"],
    "nct": [_COMMONS + "NCT_logo.png?width=128"],
    "ft":  [_COMMONS + "Florida_Trail.png?width=128"],
}


def _logo_dir() -> Path:
    d = Path(settings.map_data_dir) / "trail_logos"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _fit_square(img: Image.Image, size: int = 96) -> Image.Image:
    """Fit the logo into a transparent square so all emblems scale uniformly."""
    img = img.convert("RGBA")
    img.thumbnail((size, size), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.paste(img, ((size - img.width) // 2, (size - img.height) // 2), img)
    return canvas


def source_logos() -> None:
    """Download known trail emblems once into map-data/trail_logos/. Idempotent."""
    out = _logo_dir()
    for tid, urls in LOGO_SOURCES.items():
        dest = out / f"{tid}.png"
        if dest.exists() and dest.stat().st_size > 0:
            continue
        for url in urls:
            try:
                r = httpx.get(url, headers={"User-Agent": _UA}, follow_redirects=True, timeout=30)
                r.raise_for_status()
                _fit_square(Image.open(io.BytesIO(r.content))).save(dest, "PNG")
                logger.info("Sourced trail logo: %s", tid)
                break
            except Exception as exc:
                logger.warning("Trail logo %s from %s failed: %s", tid, url, exc)


def source_logos_async() -> None:
    threading.Thread(target=source_logos, daemon=True).start()


@router.get("/trail-logos")
def list_trail_logos():
    """Manifest of which trail emblems were successfully sourced."""
    return {"ids": sorted(p.stem for p in _logo_dir().glob("*.png"))}


@router.get("/trail-logos/{logo_id}.png")
def get_trail_logo(logo_id: str):
    if not logo_id.isalnum():
        raise HTTPException(404)
    p = _logo_dir() / f"{logo_id}.png"
    if not p.exists():
        raise HTTPException(404, "logo not sourced")
    return FileResponse(str(p), media_type="image/png")
