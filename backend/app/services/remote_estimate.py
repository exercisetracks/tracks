# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""How much a new map area will add to this server, answered while it is drawn.

The estimate used to be `pmtiles extract --dry-run` against the remote planet
archives: 3-6 s per call, because every call fetched the archives' directories
again, and it counted the whole box — though the server now stores only ground
it does not already hold (services.map_store), and it left out the trails and
contours a build adds.

Here the remote archives are read like local ones: directories over HTTP
Range, kept between calls, so dragging the box around one place reads them
once. The box is covered, at each zoom, by aligned quadtree blocks — each one a
single contiguous run of tile ids (tile_archives.descendant_ranges) — and the
blocks wholly inside installed areas are skipped. Contours and the trail
overlay have no source archive to measure, so they are added in the
proportion they took for a real state-sized area (see _RATIOS).
"""

from __future__ import annotations

import logging
import threading
from collections import OrderedDict

import httpx
from pmtiles.tile import deserialize_directory, deserialize_header, zxy_to_tileid

logger = logging.getLogger(__name__)

# Measured on a 245,000 km² area (WA/ID/MT): contours were 0.34 of its terrain
# and the trail overlay 0.67 of its basemap detail.
_RATIOS = {"contours_per_dem": 0.34, "overlay_per_basemap": 0.67}
_DIR_CACHE_BYTES = 64 << 20


class RemoteArchive:
    """A PMTiles archive over HTTP, with its directories cached."""

    _lock = threading.Lock()
    _cache: "OrderedDict[tuple[str, int, int], list]" = OrderedDict()
    _cache_size = 0

    _headers: dict[str, dict] = {}

    def __init__(self, url: str, client: httpx.Client):
        self.url = url
        self.client = client
        h = self._headers.get(url)
        if h is None:
            h = self._headers[url] = deserialize_header(self._get(0, 127))
        self.header = h

    def _get(self, offset: int, length: int) -> bytes:
        r = self.client.get(self.url, headers={"Range": f"bytes={offset}-{offset + length - 1}"})
        r.raise_for_status()
        return r.content

    def directory(self, offset: int, length: int) -> list:
        key = (self.url, offset, length)
        with self._lock:
            d = self._cache.get(key)
            if d is not None:
                self._cache.move_to_end(key)
                return d
        d = deserialize_directory(self._get(offset, length))
        with self._lock:
            self._cache[key] = d
            RemoteArchive._cache_size += length
            while RemoteArchive._cache_size > _DIR_CACHE_BYTES and self._cache:
                (_, _, ln), _ = self._cache.popitem(last=False)
                RemoteArchive._cache_size -= ln
        return d

    def bytes_in(self, ranges: list[tuple[int, int]]) -> int:
        """Bytes of the tiles with ids in `ranges`, from directories alone."""
        import bisect
        h = self.header

        def walk(entries, lo, hi):
            total = 0
            ids = [e.tile_id for e in entries]
            i = max(bisect.bisect_right(ids, lo) - 1, 0)
            while i < len(entries):
                e = entries[i]
                if e.tile_id >= hi:
                    break
                if e.run_length == 0:
                    nxt = entries[i + 1].tile_id if i + 1 < len(entries) else float("inf")
                    if nxt > lo:
                        total += walk(self.directory(h["leaf_directory_offset"] + e.offset, e.length), lo, hi)
                else:
                    a, b = max(e.tile_id, lo), min(e.tile_id + e.run_length, hi)
                    if b > a:
                        total += e.length * (b - a)
                i += 1
            return total

        root = self.directory(h["root_offset"], h["root_length"])
        return sum(walk(root, lo, hi) for lo, hi in ranges)


def rect_ranges(bbox, z: int, skip=None, skip_tile=None) -> list[tuple[int, int]]:
    """Tile-id ranges covering the tiles of `bbox` at zoom `z`, as few as an
    aligned quadtree allows. `skip(w, s, e, n)` drops whole blocks (ground
    already held); `skip_tile` decides single tiles, which can be looser: a
    tile that merely touches held ground is already in the masters."""
    from app.services.map_packs import _lonlat_to_tile
    from app.services.map_store import tile_bounds
    from app.services.tile_archives import descendant_ranges
    w, s, e, n = bbox
    x0, y0 = _lonlat_to_tile(w, n, z)
    x1, y1 = _lonlat_to_tile(e, s, z)
    out: list[tuple[int, int]] = []

    def visit(level: int, bx: int, by: int):
        span = 1 << (z - level)
        ax0, ay0 = bx * span, by * span
        ax1, ay1 = ax0 + span - 1, ay0 + span - 1
        if ax1 < x0 or ax0 > x1 or ay1 < y0 or ay0 > y1:
            return
        if skip is not None:
            nw = tile_bounds(z, ax0, ay0)
            se = tile_bounds(z, ax1, ay1)
            if skip(nw[0], se[1], se[2], nw[3]):
                return
        inside = ax0 >= x0 and ax1 <= x1 and ay0 >= y0 and ay1 <= y1
        if inside and level < z and skip_tile is not None:
            # Partly on held ground: its tiles are decided one by one below.
            nw = tile_bounds(z, ax0, ay0)
            se = tile_bounds(z, ax1, ay1)
            inside = not skip_tile(nw[0], se[1], se[2], nw[3])
        if inside and level < z:
            out.extend(descendant_ranges(level, bx, by, z, z))
            return
        if level == z:
            if skip_tile is not None:
                w_, s_, e_, n_ = tile_bounds(z, bx, by)
                if skip_tile(w_, s_, e_, n_):
                    return
            tid = zxy_to_tileid(z, bx, by)
            out.append((tid, tid + 1))
            return
        for dx in (0, 1):
            for dy in (0, 1):
                visit(level + 1, bx * 2 + dx, by * 2 + dy)

    visit(0, 0, 0)
    out.sort()
    return out


_client: httpx.Client | None = None


def _http() -> httpx.Client:
    global _client
    if _client is None:
        _client = httpx.Client(timeout=httpx.Timeout(20.0, connect=10.0), follow_redirects=True,
                               limits=httpx.Limits(max_keepalive_connections=8))
    return _client


def estimate(bbox, sources: list[tuple[str, str, int, int]], covered=None) -> dict:
    """Bytes a build of `bbox` would add: {"basemap", "dem", "contours", "overlay", "bytes"}.

    `sources` is [(name, url, minzoom, maxzoom)] for "basemap" and "dem";
    `covered` is the installed areas' union, whose ground costs nothing new.
    """
    from shapely.geometry import box
    from shapely.prepared import prep
    held = prep(covered) if covered is not None and not covered.is_empty else None
    skip = (lambda w, s, e, n: held.contains(box(w, s, e, n))) if held else None
    # The masters hold every tile an installed area touches, so a single tile
    # that merely intersects held ground adds nothing.
    skip_tile = (lambda w, s, e, n: held.intersects(box(w, s, e, n))) if held else None
    out = {"basemap": 0, "dem": 0}
    for name, url, minz, maxz in sources:
        archive = RemoteArchive(url, _http())
        ranges = [r for z in range(minz, maxz + 1) for r in rect_ranges(bbox, z, skip, skip_tile)]
        out[name] = archive.bytes_in(ranges)
    out["contours"] = int(out["dem"] * _RATIOS["contours_per_dem"])
    out["overlay"] = int(out["basemap"] * _RATIOS["overlay_per_basemap"])
    out["bytes"] = sum(out.values())
    return out
