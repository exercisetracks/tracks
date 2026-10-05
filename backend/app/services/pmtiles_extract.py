# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Run `pmtiles extract` while streaming its progress into the download tracker.

go-pmtiles prints a carriage-return progress bar to stderr, e.g.
    fetching chunks  13% |██  | (312 kB/2.3 MB, 2.1 MB/s) [0s:0s]
This parses the percent + size detail off that bar and pushes it to
global_download_tracker so the UI can show a real progress wheel instead of an
indeterminate spinner. Shared by the basemap (global_overview) and DEM
(global_dem) downloads.
"""

from __future__ import annotations

import logging
import os
import re
import subprocess
import threading
import time
from datetime import date, timedelta
from pathlib import Path
from typing import Callable

import httpx

from app.services.global_download_tracker import DownloadId, global_download_tracker

logger = logging.getLogger(__name__)

_PCT = re.compile(r"(\d+)\s*%")
_DETAIL = re.compile(r"\(([^)]*)\)")

_PROTOMAPS_HOST = "build.protomaps.com"
_DATED = re.compile(r"/(\d{8})\.pmtiles$")


def _reachable(url: str) -> bool:
    """Is the pmtiles archive fetchable? (range request for the first byte)."""
    try:
        r = httpx.get(url, headers={"Range": "bytes=0-0"}, timeout=30,
                      follow_redirects=True)
        return r.status_code in (200, 206)
    except Exception:
        return False


def resolve_source_url(url: str) -> str:
    """Return a usable pmtiles URL, falling forward to the newest Protomaps build.

    Protomaps publishes dated daily builds (build.protomaps.com/YYYYMMDD.pmtiles)
    and only keeps roughly the last week, so a hard-coded date in
    PMTILES_SOURCE_URL goes 404 after a few days. Non-Protomaps (e.g.
    self-hosted) URLs are returned unchanged. For an expired
    build.protomaps.com/YYYYMMDD.pmtiles, probe today backwards for the latest
    available daily build.
    """
    if _PROTOMAPS_HOST not in url or not _DATED.search(url):
        return url
    if _reachable(url):
        return url
    logger.warning("Configured basemap build is unavailable (%s) — resolving latest", url)
    base = url[: _DATED.search(url).start() + 1]  # ".../"
    for days_back in range(0, 21):
        d = (date.today() - timedelta(days=days_back)).strftime("%Y%m%d")
        candidate = f"{base}{d}.pmtiles"
        if _reachable(candidate):
            logger.info("Resolved latest basemap build: %s", candidate)
            return candidate
    logger.error("No available Protomaps build found in the last 21 days")
    return url  # let extract fail with a clear error

# Large multi-range extracts (the ~3 GB DEM / basemap overviews) intermittently
# get their HTTP/2 stream reset by the origin/CDN ("stream error … INTERNAL_ERROR;
# received from peer"). Disabling Go's HTTP/2 client makes go-pmtiles fall back to
# HTTP/1.1, which doesn't multiplex streams and avoids those resets.
_GODEBUG_HTTP1 = "http2client=0"

# How often the watchdog checks whether the subprocess has gone quiet. Well
# below any sensible stall timeout, and idle in between.
_WATCHDOG_TICK = 5.0

# Called with (percent:int, detail:str|None) on each progress tick. ``detail`` is
# the size summary off the go-pmtiles bar, e.g. "312 kB / 2.3 MB".
ProgressCb = Callable[[int, "str | None"], None]


def run_pmtiles_with_progress(cmd: list[str], on_progress: ProgressCb,
                              timeout: int = 7200, retries: int = 3,
                              output_path: str | Path | None = None,
                              cancel=None) -> None:
    """Run a go-pmtiles command, parsing its `fetching … NN%` bar to on_progress.

    Shared by the global basemap/DEM downloads and per-region extracts. Forces
    HTTP/1.1 (see ``_GODEBUG_HTTP1``) and retries transient network failures up to
    ``retries`` times — go-pmtiles extract is not resumable, so ``output_path`` (if
    given) is removed before each retry to restart clean. Raises RuntimeError with
    the captured output tail on a stall or non-zero exit after the final attempt.

    ``timeout`` is **silence**, not duration: the watchdog measures how long the
    subprocess has gone without saying anything, and every progress tick resets
    it. It used to be a wall clock, which punished exactly the downloads it was
    least able to help — a large region extract that was streaming along
    perfectly got killed at the hour mark, and because extracts are not
    resumable the retry loop then deleted the file and started it again, twice.
    Hours of somebody's bandwidth to arrive at "timed out after 3600s". A stalled
    connection is still caught, and quickly, because a stalled connection is
    silent and this is what silence means.

    ``cancel`` (a download_cancel.CancelToken) makes the run abortable: its
    subprocess is registered so the cancel watcher can kill it, and a cancel
    short-circuits the retry loop with DownloadCancelled (not a network retry).
    """
    from app.services.download_cancel import DownloadCancelled

    last_err: Exception | None = None
    for attempt in range(1, retries + 1):
        if cancel is not None and cancel.cancelled():
            raise DownloadCancelled()
        if output_path is not None and attempt > 1:
            Path(output_path).unlink(missing_ok=True)  # not resumable — start fresh
        try:
            _run_pmtiles_once(cmd, on_progress, timeout, cancel=cancel)
            return
        except DownloadCancelled:
            raise                                   # user abort — never retry
        except RuntimeError as e:
            last_err = e
            if attempt < retries:
                logger.warning("go-pmtiles failed (attempt %d/%d): %s — retrying",
                               attempt, retries, e)
                time.sleep(min(30, 5 * attempt))
    assert last_err is not None
    raise last_err


def _run_pmtiles_once(cmd: list[str], on_progress: ProgressCb, timeout: int,
                      cancel=None) -> None:
    from app.services.download_cancel import DownloadCancelled

    # go-pmtiles writes its progress bar AND log lines to stdout (stderr stays
    # empty), so capture stdout and fold stderr into it.
    env = {**os.environ, "GODEBUG": _GODEBUG_HTTP1}
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, env=env)
    if cancel is not None:
        cancel.add_proc(proc)               # let the watcher kill it on cancel

    # Watchdog: kill the process once it has gone quiet for `timeout` seconds
    # (the manual read loop below can otherwise block indefinitely on a stalled
    # connection). Silence rather than elapsed time — see the note above.
    timed_out = {"v": False}
    last_output = {"at": time.monotonic()}
    finished = threading.Event()

    def _watch():
        while not finished.wait(_WATCHDOG_TICK):
            if time.monotonic() - last_output["at"] > timeout:
                timed_out["v"] = True
                proc.kill()
                return

    watchdog = threading.Thread(target=_watch, daemon=True, name="pmtiles-watchdog")
    watchdog.start()

    buf, last_pct, err_tail = "", -1, []
    try:
        while True:
            ch = proc.stdout.read(1)
            if ch == "":
                break
            if ch in "\r\n":
                line, buf = buf.strip(), ""
                # Any output at all, progress bar or log line, is proof the
                # process is alive — which is the only question the watchdog is
                # asking.
                last_output["at"] = time.monotonic()
                if not line:
                    continue
                err_tail.append(line)
                if len(err_tail) > 25:
                    err_tail.pop(0)
                m = _PCT.search(line)
                if m and "fetching" in line:
                    pct = int(m.group(1))
                    if pct != last_pct:
                        last_pct = pct
                        detail = None
                        dm = _DETAIL.search(line)
                        if dm:
                            detail = dm.group(1).split(",")[0].strip().replace("/", " / ")
                        try:
                            on_progress(pct, detail)
                        except Exception:
                            pass
            else:
                buf += ch
        proc.wait()
    finally:
        finished.set()
        if cancel is not None:
            cancel.discard_proc(proc)

    # A cancel-kill also yields a non-zero exit, so check it before the generic
    # failure cases — the user aborted, this isn't an error or a timeout.
    if cancel is not None and cancel.cancelled():
        raise DownloadCancelled()
    if timed_out["v"]:
        raise RuntimeError(f"stalled — no output for {timeout}s")
    if proc.returncode != 0:
        tail = "\n".join(err_tail[-5:]) or f"exit code {proc.returncode}"
        raise RuntimeError(tail)


def extract_with_progress(source: str, output: str | Path, maxzoom: int,
                          download_id: DownloadId, timeout: int = 7200) -> None:
    """Run `pmtiles extract`, reporting progress to the global download tracker."""
    cmd = ["pmtiles", "extract", source, str(output), f"--maxzoom={maxzoom}"]
    run_pmtiles_with_progress(
        cmd,
        lambda pct, detail: global_download_tracker.set_progress(download_id, pct, detail),
        timeout=timeout,
        output_path=output,
    )
