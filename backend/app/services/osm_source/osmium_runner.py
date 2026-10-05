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


def ensure_source(bbox: tuple[float, float, float, float], cancel=None) -> Path | None:
    """Path to a slim, tag-filtered PBF covering ``bbox``, downloading + caching
    the relevant Geofabrik extract on first use. None on any failure.

    ``cancel`` aborts a first-use Geofabrik download/filter: the multi-hundred-MB
    stream checks for cancel each chunk and the tags-filter osmium run is killable.
    """
    from app.services.download_cancel import DownloadCancelled
    if not osmium_available():
        logger.warning("osmium not installed — falling back to Overpass")
        return None
    found = find_extract(bbox)
    if not found:
        return None
    extract_id, url = found
    safe = re.sub(r"[^A-Za-z0-9_.-]", "_", extract_id)
    slim = _osm_dir() / f"{safe}.filtered.osm.pbf"

    with _lock_for(safe):
        if slim.exists() and slim.stat().st_size > 0:
            return slim
        raw = _osm_dir() / f"{safe}.raw.osm.pbf"
        try:
            logger.info("Downloading Geofabrik extract %s …", extract_id)
            with httpx.stream("GET", url, headers={"User-Agent": USER_AGENT},
                              timeout=None, follow_redirects=True) as r:
                r.raise_for_status()
                with open(raw, "wb") as fh:
                    for chunk in r.iter_bytes(1 << 20):
                        if cancel is not None and cancel.cancelled():
                            raise DownloadCancelled()
                        fh.write(chunk)
        except DownloadCancelled:
            raw.unlink(missing_ok=True)
            raise
        except Exception as exc:
            logger.warning("Geofabrik download failed (%s): %s", extract_id, exc)
            raw.unlink(missing_ok=True)
            return None

        logger.info("Filtering %s (%.0f MB) → slim PBF …", extract_id,
                    raw.stat().st_size / 1e6)
        tmp = slim.with_suffix(".tmp.pbf")
        try:
            ok = _run_osmium(["tags-filter", "-O", "-o", str(tmp), str(raw),
                              *TAGS_FILTER_EXPR], cancel=cancel)
        except DownloadCancelled:
            raw.unlink(missing_ok=True)
            tmp.unlink(missing_ok=True)
            raise
        raw.unlink(missing_ok=True)
        if not ok or not tmp.exists():
            tmp.unlink(missing_ok=True)
            return None
        tmp.replace(slim)
        logger.info("Cached slim extract %s (%.0f MB)", extract_id, slim.stat().st_size / 1e6)
        return slim


def _extract_region(bbox: tuple[float, float, float, float], source: Path,
                    cancel=None) -> Path | None:
    """osmium-extract the exact bbox from the slim source → a tiny temp PBF."""
    w, s, e, n = bbox
    fd, out = tempfile.mkstemp(suffix=".osm.pbf", dir=str(_osm_dir()))
    Path(out).unlink(missing_ok=True)  # osmium writes it fresh
    os.close(fd)
    ok = _run_osmium(["extract", "-O", "-s", "smart", "-b", f"{w},{s},{e},{n}",
                      "-o", out, str(source)], timeout=600, cancel=cancel)
    if not ok or not Path(out).exists():
        Path(out).unlink(missing_ok=True)
        return None
    return Path(out)
