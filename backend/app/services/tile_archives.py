# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Low-level PMTiles operations the map store is built from.

Two things the `pmtiles` package does not do, and that every master rebuild
needs:

* **Read a directory without the tiles.** `all_tiles` hands back every tile's
  bytes; deciding which tiles to keep only needs their ids.
* **Merge in tile-id order.** A k-way merge over the sources' sorted
  directories writes a clustered archive by construction. The old merge copied
  source after source, produced an unclustered file, and then paid a full
  `pmtiles cluster` rewrite to put it in order.

Everything writes beside its output and renames over it, so a reader — the
tile server, a phone pack being cut — never sees a half-written archive, and
a failure leaves the previous file in service.
"""

from __future__ import annotations

import heapq
import logging
import os
from pathlib import Path
from typing import Callable, Iterable, Iterator

from pmtiles.reader import MmapSource, Reader
from pmtiles.tile import Compression, deserialize_directory, deserialize_header, tileid_to_zxy
from pmtiles.writer import write as pmtiles_write

logger = logging.getLogger(__name__)


def header(path: str | Path) -> dict:
    with open(path, "rb") as f:
        return deserialize_header(f.read(127))


def tile_count(path: str | Path) -> int:
    """Tiles an archive addresses, from its header — no read of the tiles."""
    try:
        return int(header(path).get("addressed_tiles_count") or 0)
    except Exception:
        return 0


def iter_entries(get_bytes, h: dict) -> Iterator[tuple[int, int, int]]:
    """(tile_id, absolute offset, length) for every tile, in tile-id order.

    Leaf directories are visited in the order the root lists them, which for
    any archive written by `pmtiles` or tippecanoe is tile-id order, so the
    stream is sorted — merge() relies on it and checks it.
    """
    def walk(offset: int, length: int):
        for e in deserialize_directory(get_bytes(offset, length)):
            if e.run_length == 0:
                yield from walk(h["leaf_directory_offset"] + e.offset, e.length)
            else:
                data_at = h["tile_data_offset"] + e.offset
                for i in range(e.run_length):
                    yield e.tile_id + i, data_at, e.length

    yield from walk(h["root_offset"], h["root_length"])


def tile_ids(path: str | Path) -> Iterator[int]:
    """Every tile id in an archive, in order, without reading any tile."""
    with open(path, "rb") as f:
        get = MmapSource(f)
        yield from (tid for tid, _, _ in iter_entries(get, deserialize_header(get(0, 127))))


class _Source:
    def __init__(self, path: str | Path, rank: int):
        self.path = str(path)
        self.rank = rank
        self.file = open(self.path, "rb")
        self.get = MmapSource(self.file)
        self.header = deserialize_header(self.get(0, 127))
        self.entries = iter_entries(self.get, self.header)

    def close(self):
        self.file.close()


def _usable(paths: Iterable[str | Path]) -> list[str]:
    return [str(p) for p in paths if p and Path(p).exists() and Path(p).stat().st_size > 0]


def merge(
    sources: list[str | Path],
    output: str | Path,
    *,
    keep: Callable[[int], bool] | None = None,
    min_zoom: int | None = None,
    max_zoom: int | None = None,
    prototype: str | Path | None = None,
) -> int:
    """Merge `sources` into `output`; on a tile two sources hold, the later wins.

    `keep(tile_id)` drops tiles it answers False for (an area being deleted);
    `min_zoom`/`max_zoom` slice by zoom. The header and metadata come from
    `prototype` (default: the last source) with bounds and zoom range widened
    to every source's. Returns the tiles written; with none, no file is
    produced and an existing `output` is removed, since an archive that would
    hold nothing must not keep serving what it held before.
    """
    paths = _usable(sources)
    output = Path(output)
    tmp = output.with_name(output.name + ".merging")
    tmp.unlink(missing_ok=True)
    if not paths:
        output.unlink(missing_ok=True)
        return 0

    opened = [_Source(p, rank) for rank, p in enumerate(paths)]
    try:
        proto = next((s for s in opened if prototype and s.path == str(prototype)), opened[-1])
        out_header = dict(proto.header)
        with open(proto.path, "rb") as f:
            metadata = Reader(MmapSource(f)).metadata()
        for s in opened:
            for key, pick in (("min_zoom", min), ("min_lon_e7", min), ("min_lat_e7", min),
                              ("max_zoom", max), ("max_lon_e7", max), ("max_lat_e7", max)):
                if key in s.header and key in out_header:
                    out_header[key] = pick(out_header[key], s.header[key])

        # Sorted by tile id; on a tie the higher rank (later source) first, so
        # it is the one written and the rest are skipped.
        def tagged(src: _Source):
            # A function, not a nested generator expression: the inner
            # expression would read `s` when iterated, after the list was
            # built, and tag every tile with the last source.
            for tid, off, length in src.entries:
                yield tid, -src.rank, off, length, src

        streams = [tagged(s) for s in opened]
        written = 0
        last_id = -1
        with pmtiles_write(str(tmp)) as writer:
            for tid, _neg_rank, off, length, src in heapq.merge(*streams, key=lambda t: (t[0], t[1])):
                if tid == last_id:
                    continue
                if tid < last_id:
                    raise ValueError(f"{src.path} is not in tile-id order")
                last_id = tid
                if min_zoom is not None or max_zoom is not None:
                    z = tileid_to_zxy(tid)[0]
                    if (min_zoom is not None and z < min_zoom) or (max_zoom is not None and z > max_zoom):
                        continue
                if keep is not None and not keep(tid):
                    continue
                writer.write_tile(tid, src.get(off, length))
                written += 1
            if written:
                writer.finalize(out_header, metadata)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    finally:
        for s in opened:
            s.close()

    if not written:
        tmp.unlink(missing_ok=True)
        output.unlink(missing_ok=True)
        return 0
    os.replace(tmp, output)
    return written


def copy_subset(source: str | Path, output: str | Path, wanted: Callable[[int], bool]) -> int:
    """`source` with only the tiles `wanted` — same header, same metadata."""
    return merge([source], output, keep=wanted)


def is_gzip_vector(path: str | Path) -> bool:
    try:
        return header(path).get("tile_compression") == Compression.GZIP
    except Exception:
        return False
