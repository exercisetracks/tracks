# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""What zoom levels the tile archives on disk actually cover.

A MapLibre source's ``maxzoom`` is a promise about the data, not a limit on the
camera: it names the deepest zoom that has tiles, and *past* it MapLibre stops
requesting and starts overzooming — scaling the last real tile up. For vector
data that is nearly free and looks fine, because the geometry is re-rendered at
the new scale rather than resampled; only the level of detail is frozen.

Overstate the promise and it fails in the worst possible way. MapLibre requests
tiles that cannot exist, and a 404 is not treated as an error to recover from —
it means "this tile is legitimately empty". An empty tile is still a renderable
tile, so the renderer stops falling back to the parent and draws *nothing*. Past
that zoom the map is the background colour with every layer, label and coastline
gone: it reads as a broken map rather than as a missing download.

That is what a stock install does today. ``master_basemap_detail.pmtiles``
(z13-15) and the region overlays only exist once a region has been downloaded,
while the style declares them unconditionally — so before any download every
zoom past 12 goes blank. The browser is accidentally immune: its ``pmt://``
protocol handler (``useMapInit.js``) turns the 404 into a generic exception, and
an *errored* tile does keep its parent on screen. A native client speaks plain
HTTP and gets the empty-tile path instead, which is why this only ever showed up
on the phone.

So the numbers are read from the archives themselves rather than assumed. A
PMTiles v3 header is a fixed 127-byte struct carrying the archive's own zoom
range, so this costs one open and one short read per archive, and it stays right
across a region download without anything having to invalidate anything.
"""

import logging
from pathlib import Path

log = logging.getLogger(__name__)

# PMTiles v3 spec: a fixed-size header, magic first, with min/max zoom as single
# bytes. Reading two bytes beats depending on a PMTiles library for two numbers.
_HEADER_BYTES = 127
_MAGIC = b"PMTiles"
_SPEC_VERSION_OFFSET = 7
_MIN_ZOOM_OFFSET = 100
_MAX_ZOOM_OFFSET = 101

# Tilesets whose URL name is not the name of a file on disk.
#
# `basemap` is a Caddy-level alias for two archives split at z13 — the static
# planet overview and the small per-region detail file — so that MapLibre sees
# one continuous source with no seam. See caddy/Caddyfile, which owns the split;
# the union of the two archives' ranges is what that one source really covers.
_TILESET_ARCHIVES: dict[str, tuple[str, ...]] = {
    "basemap": ("planet_basemap", "master_basemap_detail"),
}


def archive_zooms(path: Path) -> tuple[int, int] | None:
    """``(min_zoom, max_zoom)`` from a PMTiles header, or None if unusable.

    None covers every "we cannot promise anything about this file" case —
    absent, truncated, not PMTiles, or a spec version whose header this does not
    know how to read — because they all want the same treatment from the caller.
    """
    try:
        with path.open("rb") as f:
            header = f.read(_HEADER_BYTES)
    except OSError:
        return None

    if len(header) < _HEADER_BYTES or not header.startswith(_MAGIC):
        log.warning("Not a PMTiles archive, ignoring for coverage: %s", path)
        return None
    if header[_SPEC_VERSION_OFFSET] != 3:
        log.warning("PMTiles v%d header not understood: %s", header[_SPEC_VERSION_OFFSET], path)
        return None

    lo, hi = header[_MIN_ZOOM_OFFSET], header[_MAX_ZOOM_OFFSET]
    if hi < lo:
        log.warning("PMTiles header claims maxzoom %d below minzoom %d: %s", hi, lo, path)
        return None
    return lo, hi


def tileset_zooms(data_dir: Path, tileset: str) -> tuple[int, int] | None:
    """The zoom range a tile *URL name* can actually serve.

    A tileset can be backed by more than one archive (see [_TILESET_ARCHIVES]),
    in which case the answer spans whichever of them are present — a downloaded
    region extends the basemap's reach without changing anything else, and an
    absent one simply does not contribute.
    """
    ranges = [
        zooms
        for archive in _TILESET_ARCHIVES.get(tileset, (tileset,))
        if (zooms := archive_zooms(data_dir / f"{archive}.pmtiles")) is not None
    ]
    if not ranges:
        return None
    return min(lo for lo, _ in ranges), max(hi for _, hi in ranges)
