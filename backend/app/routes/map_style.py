# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Serve the MapLibre style document — `GET /maps/style.json`.

The style is built by ~3400 lines of JavaScript in
`frontend/src/pages/maps/style/`. Rather than translate that into Python and
maintain two implementations of the same map, `frontend/scripts/build-map-style.mjs`
executes the real builders under Node and writes their output to
`app/static/map_style.json`. This module serves that artifact.

So there is one implementation of the style. The web app keeps importing the
builders directly, a native client fetches this endpoint, and a new layer is
added in exactly one place.

Two things have to happen at request time rather than build time:

**Tile version.** Cache busting uses each PMTiles archive's mtime, which changes
whenever a region merge rewrites it. Baked in at build time it would be stale
immediately, so the build emits a placeholder and it is substituted here.

**Absolute URLs.** The web app runs same-origin, so the built style uses paths
like `/api/tiles/...`. MapLibre Native has no origin to resolve those against
and needs absolute URLs. `?base_url=` rewrites them.

**Honest zoom ranges.** The style declares the sources a fully-downloaded
install has. What is on disk is whatever has been downloaded so far, and a
source that promises tiles it cannot serve makes a native client draw a blank
map past that zoom rather than overzoom the last real level. See
`services/tile_coverage.py` for why that failure is so much louder here than in
the browser.

Public, like the other endpoints MapLibre fetches (`/fonts`, `/sprite`,
`/tiles/version`) — a style document is layer styling, and a client that can't
authenticate can't fetch a single tile through it anyway.
"""

import json
import logging
import re
from pathlib import Path
from typing import Literal
from urllib.parse import urlparse

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import Response

from app.config import settings
from app.routes.tiles import TILESETS, tiles_version
from app.services.tile_coverage import tileset_zooms

router = APIRouter(prefix="/maps", tags=["maps"])
log = logging.getLogger(__name__)

_STATIC_DIR = Path(__file__).resolve().parent.parent / "static"
_STYLE_PATH = _STATIC_DIR / "map_style.json"

# Must match build-map-style.mjs.
_VERSION_PLACEHOLDER = "__TILE_VERSION__"

# The tileset name inside a tile URL template, e.g. /api/tiles/master_overlay/{z}/…
_TILESET_IN_URL = re.compile(r"/tiles/([^/]+)/")

# Paths the style references that a native client has to resolve absolutely.
# Everything the style points at lives under /api, so one prefix covers tiles,
# glyphs, and the sprite.
_ORIGIN_RELATIVE_PREFIX = "/api"


def _tile_version() -> str:
    """One version string for the whole style.

    The frontend cache-busts each tileset with its own mtime, but the style
    document is a single artifact, so it takes the newest mtime across the
    archives. A merge into any one of them then busts the lot — slightly
    broader than necessary, and much simpler than per-source substitution for
    an archive set that is rebuilt together anyway.
    """
    versions = tiles_version()
    newest = max((versions.get(name) or 0) for name in TILESETS) if TILESETS else 0
    return str(newest)


def _fit_to_available_tiles(style: dict) -> None:
    """Narrow the style's promises to the archives that are actually present.

    Two rules, and the direction of each matters:

    **maxzoom only ever comes down**, never up. Some sources are deliberately
    capped below their archive — `basemap_overview` declares maxzoom 7 against a
    z0-12 file precisely so MapLibre overzooms the z7 landcover across the whole
    detail band (see sources.js). Raising it to the archive's 12 would silently
    undo that. Taking the minimum keeps the cartographer's intent and only
    corrects the case where the data is shallower than the claim.

    **minzoom only ever goes up**, for the mirror-image reason: it exists to stop
    MapLibre requesting levels below the archive, and lowering it would create
    exactly the 404s this is here to remove.

    A source with no archive at all is dropped along with every layer that reads
    it. Those layers cannot draw anything either way, so keeping them buys only
    a request storm — and MapLibre refuses to load a style whose layer names a
    source that is not there, which makes dropping them mandatory rather than
    tidy.

    Untouched: `geojson` sources, which are fed by the client at runtime and
    have no archive to check.
    """
    data_dir = Path(settings.map_data_dir)
    sources: dict = style.get("sources", {})
    dropped: set[str] = set()

    for name, source in sources.items():
        urls = source.get("tiles")
        if not urls:
            continue
        found = _TILESET_IN_URL.search(urls[0])
        if not found:
            log.warning("Source %r has a tile URL this cannot read: %s", name, urls[0])
            continue

        available = tileset_zooms(data_dir, found.group(1))
        if available is None:
            dropped.add(name)
            continue

        low, high = available
        if "maxzoom" in source:
            source["maxzoom"] = min(source["maxzoom"], high)
        if "minzoom" in source:
            source["minzoom"] = max(source["minzoom"], low)

    if not dropped:
        return

    style["sources"] = {n: s for n, s in sources.items() if n not in dropped}
    style["layers"] = [l for l in style.get("layers", []) if l.get("source") not in dropped]
    log.info(
        "Style trimmed to downloaded data: dropped %s (no archive in %s)",
        ", ".join(sorted(dropped)),
        data_dir,
    )


def _validate_base_url(base_url: str) -> str:
    """Accept only a scheme + host, and never a path or query.

    This value is interpolated into URLs the client will fetch, so an
    unvalidated one would let a caller hand out a style pointing at a host of
    their choosing. Anyone can call this endpoint, and the resulting document
    could be passed to someone else.
    """
    parsed = urlparse(base_url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise HTTPException(
            status_code=422,
            detail="base_url must be an absolute http(s) URL, e.g. https://tracks.example.com",
        )
    if parsed.path.rstrip("/") or parsed.query or parsed.fragment:
        raise HTTPException(
            status_code=422,
            detail="base_url must not include a path, query, or fragment",
        )
    return f"{parsed.scheme}://{parsed.netloc}"


def _serve(path: Path, base_url: str | None) -> Response:
    """One built style artifact, with the three request-time rewrites applied."""
    try:
        raw = path.read_text()
    except FileNotFoundError:
        # The artifact is generated by the frontend build, so a backend running
        # against a tree that was never built has none. Say so plainly rather
        # than 500 — the fix is a build step, not a bug report.
        log.error("Map style artifact missing at %s", path)
        raise HTTPException(
            status_code=503,
            detail="Map style has not been built — run frontend/scripts/build-map-style.mjs",
        )

    raw = raw.replace(_VERSION_PLACEHOLDER, _tile_version())

    # Parsed rather than text-substituted, unlike the two rewrites around it:
    # this one has to reason about the document's shape, not match a token in it.
    style = json.loads(raw)
    _fit_to_available_tiles(style)
    raw = json.dumps(style)

    if base_url:
        origin = _validate_base_url(base_url)
        # Substituting on the JSON text rather than walking the parsed document
        # keeps this independent of the style's shape: URLs appear in `sources`,
        # in `glyphs`, and in `sprite`, and a future layer type could introduce
        # another. The prefix is distinctive enough that a false positive would
        # have to be a literal "/api..." inside a label expression.
        raw = raw.replace(f'"{_ORIGIN_RELATIVE_PREFIX}', f'"{origin}{_ORIGIN_RELATIVE_PREFIX}')

    return Response(
        content=raw,
        media_type="application/json",
        # The style changes only when the frontend is rebuilt or an archive is
        # merged, and the tile version inside it already busts tile caches.
        # Short cache: long enough to skip a refetch on every map open, short
        # enough that a region download shows up promptly.
        headers={"Cache-Control": "public, max-age=300"},
    )


_BASE_URL_DESC = (
    "Absolute origin to prefix onto /api paths, for clients "
    "with no page origin to resolve them against "
    "(e.g. https://tracks.example.com). Omit for same-origin use."
)


@router.get("/style.json")
def map_style(base_url: str | None = Query(None, description=_BASE_URL_DESC)):
    return _serve(_STYLE_PATH, base_url)


@router.get("/backdrop.json")
def backdrop_style(
    theme: Literal["light", "dark"] = Query("light"),
    base_url: str | None = Query(None, description=_BASE_URL_DESC),
):
    """The plain basemap that sits behind GPS overlays, in a light or dark palette.

    The web builds this in the browser (`components/map/basemapStyle.js`) for
    its dashboard heatmap and activity maps. The phone's dashboard heatmap
    needs the same thing: the planning style above is a single light document,
    so a phone in dark mode drew a white map in the middle of a dark screen.
    Built from the same module, so the two clients cannot drift apart.
    """
    return _serve(_STATIC_DIR / f"backdrop_style_{theme}.json", base_url)
