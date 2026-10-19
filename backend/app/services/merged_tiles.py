# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""One vector tile per z/x/y for the web map, instead of four.

The planning map draws from four vector archives -- the basemap (the z0-12
planet file and the z13-15 detail one), the trail and land overlay, the
long-distance routes and the contours -- and each was its own map source, so
every tile on screen was four requests, plus the DEM's. Panning a city
cost a hundred-odd requests, and a reverse proxy with request limits (Cosmos
Smart Shield on its "normal" preset, for one) banned the person using it.
Here the four are read straight from their archives and joined into one
tile, so a tile on screen is one request.

## Joining tiles

An MVT is a list of layers, and two tiles' bytes concatenated are a valid
tile with both lists. Two things stop it being only that:

- **Names collide.** The overlay has `water` and `landuse` as the basemap
  does, and a decoder keeps one layer per name. So every archive but the
  basemap has its layers renamed with a prefix ([PREFIXES]); the web style's
  merged source refers to them by those names (frontend style `mergeSources`).
- **Zoom ranges differ.** Routes and contours stop at z12, and the browser
  used to stretch their z12 tiles over z13-15 itself. One source has one zoom
  range, so here the z12 tile is cut down for a z13-15 one: decoded, scaled to
  the child's grid and clipped with a margin ([_overzoom]). Lines keep their
  full shape across the cut because the margin is wider than any stroke.

Places of interest come in the same tile (master_pois, prefix po_; see
poi_tiles), which also ended a GeoJSON request after every pan.

The phone keeps its separate sources: its offline packs answer tile requests
archive by archive (PackInterceptor), and a merged tile has no archive.

## Caching

Responses are versioned by the archives' mtimes (`version()`), so the same URL
is always the same bytes and the browser keeps them for good; a region download
changes the version and the web map refetches once. Joined tiles are kept in a
small in-memory cache too, because the cut-down route and contour tiles cost a
decode and an encode each.
"""
from __future__ import annotations

import gzip
import threading
from collections import OrderedDict
from pathlib import Path

import mapbox_vector_tile
from pmtiles.reader import MmapSource, Reader
from shapely import affinity
from shapely.geometry import (
    LineString, MultiLineString, MultiPoint, MultiPolygon, Point, Polygon, box, shape,
)

from app.config import settings

# (archive, layer prefix, first zoom, last zoom it has tiles for).
SOURCES: list[tuple[str, str, int, int]] = [
    ("planet_basemap", "", 0, 12),
    ("master_basemap_detail", "", 13, 15),
    ("master_overlay", "ov_", 6, 15),
    ("master_routes", "rt_", 3, 12),
    ("master_contours", "ct_", 9, 12),
    ("master_pois", "po_", 4, 13),
]
PREFIXES = {name: prefix for name, prefix, _, _ in SOURCES}

#: The merged source's zoom range: the basemap's.
MAX_ZOOM = 15

_EXTENT = 4096
#: How far past the tile edge an overzoomed tile keeps geometry, in tile units:
#: wider than any line or label halo drawn from it.
_BUFFER = 256

_CACHE_SIZE = 2048
_cache: OrderedDict[tuple, bytes] = OrderedDict()
_lock = threading.Lock()
_readers: dict[str, tuple[float, object, Reader]] = {}


def _path(name: str) -> Path:
    return Path(settings.map_data_dir) / f"{name}.pmtiles"


def version() -> str:
    """The archives' mtimes as one string: what the tile URLs carry."""
    parts = []
    for name, _, _, _ in SOURCES:
        p = _path(name)
        parts.append(str(int(p.stat().st_mtime)) if p.is_file() else "0")
    return "-".join(parts)


def _reader(name: str) -> Reader | None:
    """An open reader for an archive, reopened when the file is replaced."""
    p = _path(name)
    if not p.is_file():
        return None
    mtime = p.stat().st_mtime
    with _lock:
        held = _readers.get(name)
        if held and held[0] == mtime:
            return held[2]
        if held:
            try:
                held[1].close()
            except Exception:
                pass
        f = open(p, "rb")
        reader = Reader(MmapSource(f))
        _readers[name] = (mtime, f, reader)
        return reader


def _raw(name: str, z: int, x: int, y: int) -> bytes | None:
    reader = _reader(name)
    if reader is None:
        return None
    data = reader.get(z, x, y)
    if not data:
        return None
    if data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    return data


# ── Protobuf, just enough to rename a layer ──────────────────────────────────

def _varint(buf: bytes, i: int) -> tuple[int, int]:
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, i
        shift += 7


def _put_varint(n: int) -> bytes:
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _field(number: int, payload: bytes) -> bytes:
    return _put_varint((number << 3) | 2) + _put_varint(len(payload)) + payload


def rename_layers(tile: bytes, prefix: str) -> bytes:
    """Every layer of [tile] with [prefix] on its name (Tile.layers = 3, Layer.name = 1)."""
    if not prefix:
        return tile
    out = bytearray()
    i = 0
    while i < len(tile):
        key, i = _varint(tile, i)
        number, wire = key >> 3, key & 7
        if wire != 2:
            raise ValueError("unexpected wire type in a tile")
        length, i = _varint(tile, i)
        payload = tile[i:i + length]
        i += length
        if number != 3:
            out += _field(number, payload)
            continue
        layer = bytearray()
        j = 0
        while j < len(payload):
            lkey, j2 = _varint(payload, j)
            lwire = lkey & 7
            if lwire == 2:
                llen, j3 = _varint(payload, j2)
                body = payload[j3:j3 + llen]
                if lkey >> 3 == 1:
                    body = (prefix + body.decode("utf-8")).encode("utf-8")
                layer += _put_varint(lkey) + _put_varint(len(body)) + body
                j = j3 + llen
            elif lwire == 0:
                _, j3 = _varint(payload, j2)
                layer += payload[j:j3]
                j = j3
            else:
                size = 8 if lwire == 1 else 4
                layer += payload[j:j2 + size]
                j = j2 + size
        out += _field(3, bytes(layer))
    return bytes(out)


# ── Overzoom ─────────────────────────────────────────────────────────────────

def _overzoom(tile: bytes, prefix: str, dz: int, cx: int, cy: int) -> bytes:
    """A tile [dz] zooms below [tile], at offset (cx, cy) among its children.

    Geometry is scaled up to the child's grid and clipped to the child plus a
    margin. Properties and ids are kept, so a route's `feat_key` still drives
    its hover state.
    """
    decoded = mapbox_vector_tile.decode(tile, default_options={"y_coord_down": True})
    scale = 2 ** dz
    clip = box(-_BUFFER, -_BUFFER, _EXTENT + _BUFFER, _EXTENT + _BUFFER)
    layers = []
    for name, layer in decoded.items():
        extent = layer.get("extent", _EXTENT)
        k = _EXTENT / extent * scale
        features = []
        for f in layer["features"]:
            try:
                g = shape(f["geometry"])
            except Exception:
                continue
            g = affinity.affine_transform(g, [k, 0, 0, k, -cx * _EXTENT, -cy * _EXTENT])
            if not g.intersects(clip):
                continue
            g = _same_kind(g.intersection(clip), g) if g.geom_type != "Point" else g
            if g is None or g.is_empty:
                continue
            out = {"geometry": g, "properties": f.get("properties", {})}
            if f.get("id") is not None:
                out["id"] = f["id"]
            features.append(out)
        if features:
            layers.append({"name": prefix + name, "features": features})
    if not layers:
        return b""
    return mapbox_vector_tile.encode(
        layers, default_options={"extents": _EXTENT, "y_coord_down": True},
    )


def _same_kind(clipped, original):
    """[clipped] as the kind of geometry [original] was, or None.

    Clipping a polygon along an edge it shares with the clip box gives back a
    GeometryCollection of its area plus a stray line or point, which no MVT
    can hold: the encoder raised, and the whole tile -- every layer of it --
    failed with a 500. Only the parts of the original's dimension are kept.
    """
    if clipped.geom_type != "GeometryCollection":
        return clipped
    dim = 2 if "Polygon" in original.geom_type else 1 if "Line" in original.geom_type else 0
    kinds = {2: (Polygon, MultiPolygon), 1: (LineString, MultiLineString), 0: (Point, MultiPoint)}[dim]
    parts = []
    for g in clipped.geoms:
        if isinstance(g, kinds):
            parts.extend(getattr(g, "geoms", [g]))
    if not parts:
        return None
    multi = {2: MultiPolygon, 1: MultiLineString, 0: MultiPoint}[dim]
    return parts[0] if len(parts) == 1 else multi(parts)


def _part(name: str, prefix: str, lo: int, hi: int, z: int, x: int, y: int) -> bytes:
    if z < lo:
        return b""
    if z <= hi:
        raw = _raw(name, z, x, y)
        return rename_layers(raw, prefix) if raw else b""
    # Above the archive's last zoom: cut its tile down.
    dz = z - hi
    parent = _raw(name, hi, x >> dz, y >> dz)
    if not parent:
        return b""
    cx = x - ((x >> dz) << dz)
    cy = y - ((y >> dz) << dz)
    return _overzoom(parent, prefix, dz, cx, cy)


def merged_tile(z: int, x: int, y: int) -> bytes | None:
    """The joined tile, gzip-compressed; None when no archive has anything there."""
    if z < 0 or z > MAX_ZOOM:
        return None
    key = (version(), z, x, y)
    with _lock:
        hit = _cache.get(key)
        if hit is not None:
            _cache.move_to_end(key)
            return hit or None
    parts = []
    for name, prefix, lo, hi in SOURCES:
        if name == "planet_basemap" and z > hi:
            # Past z12 the basemap is the detail archive's, which only covers
            # downloaded regions. Elsewhere the planet's z12 is cut down so the
            # map is not blank there -- never both, or roads would draw twice.
            detail = _raw("master_basemap_detail", z, x, y)
            if detail:
                continue
        parts.append(_part(name, prefix, lo, hi, z, x, y))
    body = b"".join(parts)
    out = gzip.compress(body, compresslevel=6) if body else b""
    with _lock:
        _cache[key] = out
        if len(_cache) > _CACHE_SIZE:
            _cache.popitem(last=False)
    return out or None
