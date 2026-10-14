# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download, slim-filter, and bbox-clip OSM data via the ``osmium`` CLI.

This is the disk/subprocess layer shared by both region-build paths:
  * ``_run_osmium`` — run an osmium subcommand, optionally killable via a
    download cancel token.
  * ``ensure_source`` — on first use, download the covering Geofabrik extract and
    immediately ``osmium tags-filter`` it down to the slim feature set we render,
    caching that PBF (the raw download is discarded). Subsequent calls hit cache.
  * ``_extract_region`` — ``osmium extract`` the exact bbox from the slim source
    into a tiny temp PBF for downstream parsing/export.
"""

from __future__ import annotations

import os
import re
import subprocess
import tempfile
import threading
from pathlib import Path

import httpx

from app.services.osm_source.config import (
    TAGS_FILTER_EXPR, USER_AGENT, _osm_dir, logger, osmium_available,
)
from app.services.osm_source.geofabrik import find_extract

# One lock per cached extract key, so concurrent builders for the same region
# don't download/filter it twice while still allowing different extracts to run
# in parallel.
_source_locks: dict[str, threading.Lock] = {}
_source_locks_guard = threading.Lock()


def _lock_for(key: str) -> threading.Lock:
    with _source_locks_guard:
        lock = _source_locks.get(key)
        if lock is None:
            lock = _source_locks[key] = threading.Lock()
        return lock


def _run_osmium(args: list[str], timeout: int = 1800, cancel=None) -> bool:
    """Run an osmium subcommand. Returns True on success, False on failure.

    With ``cancel`` (a download_cancel.CancelToken) the run is killable: the
    process is registered so the cancel watcher can terminate it, and a
    cancel-kill raises DownloadCancelled instead of returning False.
    """
    if cancel is None:
        try:
            subprocess.run(["osmium", *args], check=True, timeout=timeout,
                           stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
            return True
        except subprocess.CalledProcessError as exc:
            logger.warning("osmium %s failed: %s", args[0] if args else "?",
                           (exc.stderr or b"").decode("utf-8", "replace")[:400])
        except Exception as exc:
            logger.warning("osmium %s error: %s", args[0] if args else "?", exc)
        return False

    from app.services.download_cancel import DownloadCancelled
    proc = subprocess.Popen(["osmium", *args], stdout=subprocess.DEVNULL,
                            stderr=subprocess.PIPE)
    cancel.add_proc(proc)
    try:
        _, err = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.communicate()
        logger.warning("osmium %s timed out", args[0] if args else "?")
        return False
    finally:
        cancel.discard_proc(proc)
    if cancel.cancelled():
        raise DownloadCancelled()
    if proc.returncode != 0:
        logger.warning("osmium %s failed: %s", args[0] if args else "?",
                       (err or b"").decode("utf-8", "replace")[:400])
        return False
    return True


# A cached extract older than this is refreshed in the background; builds keep
# using it meanwhile (OSM changes slowly, a download should not wait on it).
REFRESH_AFTER_S = 30 * 24 * 3600
_DOWNLOAD_TIMEOUT = httpx.Timeout(connect=30.0, read=120.0, write=60.0, pool=30.0)
_DOWNLOAD_TRIES = 4


def ensure_source(bbox: tuple[float, float, float, float], cancel=None,
                  progress=None) -> Path | None:
    """A slim, tag-filtered PBF covering ``bbox`` — one extract, or several
    merged when the box crosses a border (geofabrik.find_cover). None on any
    failure. ``progress(detail, fraction)`` hears about first-time downloads.
    """
    from app.services.osm_source.geofabrik import find_cover
    if not osmium_available():
        logger.warning("osmium not installed — falling back to Overpass")
        return None
    cover = find_cover(bbox)
    if not cover:
        return None
    slims = []
    for extract_id, url in cover:
        slim = ensure_extract(extract_id, url, cancel=cancel, progress=progress)
        if slim is None:
            return None
        slims.append(slim)
    return slims[0] if len(slims) == 1 else slims


def ensure_extract(extract_id: str, url: str, cancel=None, progress=None) -> Path | None:
    """One Geofabrik extract, downloaded and slimmed once, then cached."""
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", extract_id)
    slim = _osm_dir() / f"{safe}.filtered.osm.pbf"
    with _lock_for(safe):
        if slim.exists() and slim.stat().st_size > 0:
            import time
            if time.time() - slim.stat().st_mtime > REFRESH_AFTER_S:
                _refresh_later(extract_id, url, slim)
            return slim
        return _fetch_and_slim(extract_id, url, slim, cancel=cancel, progress=progress)


_refreshing: set[str] = set()


def _refresh_later(extract_id: str, url: str, slim: Path) -> None:
    """Fetch a fresh copy in the background; the old one serves until it lands."""
    if extract_id in _refreshing:
        return
    _refreshing.add(extract_id)

    def run():
        try:
            fresh = slim.with_name(slim.name.replace(".filtered.", ".refresh."))
            if _fetch_and_slim(extract_id, url, fresh) is not None:
                fresh.replace(slim)
                logger.info("Refreshed OSM extract %s", extract_id)
        except Exception:
            logger.exception("Refreshing OSM extract %s failed", extract_id)
        finally:
            _refreshing.discard(extract_id)

    threading.Thread(target=run, daemon=True, name=f"osm-refresh-{extract_id}").start()


def _fetch_and_slim(extract_id: str, url: str, slim: Path, cancel=None, progress=None) -> Path | None:
    from app.services.download_cancel import DownloadCancelled
    safe = slim.name.split(".")[0]
    raw = _osm_dir() / f"{safe}.raw.osm.pbf"
    try:
        _download_resumable(url, raw, extract_id, cancel=cancel, progress=progress)
    except DownloadCancelled:
        raise
    except Exception as exc:
        # The partial file stays: the next attempt resumes it.
        logger.warning("Geofabrik download failed (%s): %s", extract_id, exc)
        return None

    logger.info("Filtering %s (%.0f MB) → slim PBF …", extract_id, raw.stat().st_size / 1e6)
    if progress:
        progress(f"Preparing {extract_id} trail data…", 1.0)
    tmp = slim.with_suffix(".tmp.pbf")
    try:
        ok = _run_osmium(["tags-filter", "-O", "-o", str(tmp), str(raw),
                          *TAGS_FILTER_EXPR], cancel=cancel)
    except DownloadCancelled:
        tmp.unlink(missing_ok=True)
        raise
    raw.unlink(missing_ok=True)
    if not ok or not tmp.exists():
        tmp.unlink(missing_ok=True)
        return None
    tmp.replace(slim)
    logger.info("Cached slim extract %s (%.0f MB)", extract_id, slim.stat().st_size / 1e6)
    return slim


def _download_resumable(url: str, dest: Path, label: str, cancel=None, progress=None) -> None:
    """Download to ``dest`` (via ``dest.part``), resuming with Range after a
    dropped connection and across restarts.

    It used to be one stream with no timeout at all: a stalled connection hung
    the build forever where no cancel could reach it, and any failure threw
    away everything fetched so far (audit F21).
    """
    from app.services.download_cancel import DownloadCancelled
    part = dest.with_name(dest.name + ".part")
    last_error = None
    for attempt in range(_DOWNLOAD_TRIES):
        have = part.stat().st_size if part.exists() else 0
        headers = {"User-Agent": USER_AGENT}
        if have:
            headers["Range"] = f"bytes={have}-"
        try:
            with httpx.stream("GET", url, headers=headers, timeout=_DOWNLOAD_TIMEOUT,
                              follow_redirects=True) as r:
                if r.status_code == 416:      # already complete
                    break
                r.raise_for_status()
                if have and r.status_code != 206:
                    have = 0                   # server ignored the range: start over
                total = have + int(r.headers.get("content-length") or 0)
                logger.info("Downloading Geofabrik extract %s%s …", label,
                            f" (resuming at {have / 1e6:.0f} MB)" if have else "")
                with open(part, "ab" if have else "wb") as fh:
                    done = have
                    for chunk in r.iter_bytes(1 << 20):
                        if cancel is not None and cancel.cancelled():
                            raise DownloadCancelled()
                        fh.write(chunk)
                        done += len(chunk)
                        if progress and total:
                            progress(f"Downloading {label} trail data… "
                                     f"{done / 1e6:.0f} / {total / 1e6:.0f} MB", done / total)
            part.replace(dest)
            return
        except DownloadCancelled:
            raise
        except Exception as exc:
            last_error = exc
            logger.warning("Geofabrik download of %s interrupted (try %d): %s",
                           label, attempt + 1, exc)
    if part.exists() and not dest.exists():
        part.replace(dest)
        return
    raise RuntimeError(f"could not download {label}: {last_error}")


def _extract_region(bbox: tuple[float, float, float, float], source,
                    cancel=None) -> Path | None:
    """osmium-extract the exact bbox from the slim source → a tiny temp PBF.

    ``source`` may be several extracts (a box across a border): each is clipped
    and the clips are merged, which is far cheaper than reading their parent.
    """
    if isinstance(source, (list, tuple)):
        clips = []
        try:
            for one in source:
                clip = _extract_region(bbox, one, cancel=cancel)
                if clip is None:
                    return None
                clips.append(clip)
            fd, out = tempfile.mkstemp(suffix=".osm.pbf", dir=str(_osm_dir()))
            os.close(fd)
            Path(out).unlink(missing_ok=True)
            ok = _run_osmium(["merge", "-O", "-o", out, *map(str, clips)], timeout=600, cancel=cancel)
            if not ok or not Path(out).exists():
                Path(out).unlink(missing_ok=True)
                return None
            return Path(out)
        finally:
            for c in clips:
                c.unlink(missing_ok=True)
    w, s, e, n = bbox
    fd, out = tempfile.mkstemp(suffix=".osm.pbf", dir=str(_osm_dir()))
    Path(out).unlink(missing_ok=True)  # osmium writes it fresh
    os.close(fd)
    # `smart` completes relations that only partly fall in the box — but by
    # default only type=multipolygon. National forests, parks and wilderness
    # are mostly type=boundary relations, so for a box inside one (smaller than
    # the forest around it) the relation came out with most of its ring
    # missing, could not be assembled into an area, and the public land simply
    # was not on the map; only areas wholly inside the box survived. Measured on
    # Colville National Forest: 0 polygons without `boundary` here, 1 with.
    ok = _run_osmium(["extract", "-O", "-s", "smart", "-S", "types=multipolygon,boundary",
                      "-b", f"{w},{s},{e},{n}", "-o", out, str(source)], timeout=600, cancel=cancel)
    if not ok or not Path(out).exists():
        Path(out).unlink(missing_ok=True)
        return None
    return Path(out)
