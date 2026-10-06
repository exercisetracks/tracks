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

import itertools
import logging
import os
import re
import subprocess
import threading
import time
from datetime import date, timedelta
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

import httpx

from app.services.global_download_tracker import DownloadId, global_download_tracker

logger = logging.getLogger(__name__)

_PCT = re.compile(r"(\d+)\s*%")
_DETAIL = re.compile(r"\(([^)]*)\)")

_PROTOMAPS_HOST = "build.protomaps.com"
_DATED = re.compile(r"/(\d{8})\.pmtiles$")


def _reachable(url: str) -> bool | None:
    """Is the pmtiles archive fetchable? (range request for the first byte).

    None means the server could not be reached at all, as opposed to False, it
    answered and the file is not there: only the second is a reason to go
    looking for another build.
    """
    try:
        r = httpx.get(url, headers={"Range": "bytes=0-0"}, timeout=30,
                      follow_redirects=True)
        return r.status_code in (200, 206)
    except httpx.TransportError:
        return None
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
    reachable = _reachable(url)
    if reachable is None:
        # The host itself is unreachable (DNS blocked, offline). Every dated
        # candidate is on the same host, and walking 21 of them through a DNS
        # timeout each is ten minutes spent learning nothing. The extract
        # fails with SourceUnreachable, which says what to do about it.
        return url
    if reachable:
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

class SourceUnreachable(RuntimeError):
    """go-pmtiles could not connect to the tile server at all.

    Distinct from a failure partway through a download: nothing about the
    archive or this install is wrong, something between here and ``host`` is
    refusing the connection, and it will work as soon as that stops. The
    message is written for the person who has to stop it, because the raw Go
    error ("dial tcp 0.0.0.0:443: connect: connection refused") tells them
    nothing — it is what a network-wide ad blocker such as AdGuard Home or
    Pi-hole looks like from inside the container, and that is the most common
    cause seen so far.
    """

    def __init__(self, host: str, raw: str, blocked: bool):
        self.host, self.raw, self.blocked = host, raw, blocked
        if blocked:
            msg = (f"Couldn't download from {host}: this network's DNS answered "
                   f"with a null address, which is how ad blockers and DNS filters "
                   f"(AdGuard Home, Pi-hole, NextDNS) block a site. "
                   f"Allow {host} in that filter.")
        else:
            msg = (f"Couldn't connect to {host}. Check this machine's internet "
                   f"connection, and whether an ad blocker or DNS filter (AdGuard "
                   f"Home, Pi-hole), a VPN or a firewall is blocking it — if so, "
                   f"allow {host}.")
        super().__init__(msg)


# Go's wording for a connection that never got as far as HTTP: a refused or
# unroutable dial, a failed DNS lookup, or a TLS handshake broken by something
# intercepting it. A failure *after* connecting (a reset stream, a stall) is a
# flaky download, not a blocked one, and keeps its own message.
_UNREACHABLE = re.compile(
    r"dial tcp|lookup \S+( on \S+)?: |no such host|server misbehaving"
    r"|network is unreachable|no route to host|tls: |x509: ")
# The address a DNS filter answers with for a blocked name. A real server is
# never at any of these.
_NULL_DIAL = re.compile(r"dial tcp (0\.0\.0\.0|127\.\d+\.\d+\.\d+|\[::1?\]):\d+")
_GET_HOST = re.compile(r'Get "https?://([^/":]+)')


def _unreachable_error(cmd: list[str], lines: list[str]) -> SourceUnreachable | None:
    """The SourceUnreachable that go-pmtiles' output describes, if it does."""
    text = "\n".join(lines)
    if not _UNREACHABLE.search(text):
        return None
    m = _GET_HOST.search(text)
    host = m.group(1) if m else next(
        (urlparse(a).hostname for a in cmd if a.startswith(("http://", "https://"))),
        "the tile server")
    raw = next((ln for ln in reversed(lines) if _UNREACHABLE.search(ln)), text)
    return SourceUnreachable(host, raw, blocked=bool(_NULL_DIAL.search(text)))


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
    the captured output tail on a stall or non-zero exit after the final attempt,
    or SourceUnreachable when the server could not be connected to at all.

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
        unreachable = _unreachable_error(cmd, err_tail)
        if unreachable is not None:
            logger.warning("go-pmtiles could not reach %s: %s",
                           unreachable.host, unreachable.raw)
            raise unreachable
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


# How long a global download waits before trying an unreachable server again:
# soon at first, because the usual cause is someone who has just allowed the
# host in their DNS filter and is watching the map page, then every quarter
# hour. One failed connection is all a retry costs, so it never gives up — the
# alternative was a download that stayed failed until the container restarted.
_UNREACHABLE_BACKOFF = (60, 120, 300, 600, 900)


def _in_words(seconds: int) -> str:
    minutes = round(seconds / 60)
    return "1 minute" if minutes == 1 else f"{minutes} minutes"


def is_complete_archive(path: str | Path) -> bool:
    """Has this archive finished downloading?

    go-pmtiles extract creates the output at its full size up front, fills in
    the tiles, and writes the header last — so an extract that was killed
    (a restart mid-download, an upgrade) leaves a file of the right size with
    a zeroed header. ``exists()`` called that finished, and the download was
    never tried again while every tile request to it failed. The magic number
    is the last thing written, so its presence means the rest is there too.
    """
    try:
        with open(path, "rb") as f:
            return f.read(7) == b"PMTiles"
    except OSError:
        return False


def extract_global(resolve_source: Callable[[], str], output: str | Path,
                   maxzoom: int, download_id: DownloadId, timeout: int = 7200,
                   sleep: Callable[[float], None] = time.sleep) -> None:
    """Extract a global archive, waiting out an unreachable server for as long as it takes.

    ``resolve_source`` is called before every attempt rather than once, so a
    basemap build that could not be resolved while the network was blocked is
    resolved properly once it is not. Any other failure is raised as before.

    The extract is written beside ``output`` and renamed into place only once
    it is complete, so the served name never points at a half-written archive
    (see is_complete_archive).
    """
    output = Path(output)
    partial = output.with_name(output.stem + ".downloading.pmtiles")
    for attempt in itertools.count():
        source = resolve_source()
        global_download_tracker.start(download_id)
        partial.unlink(missing_ok=True)
        try:
            extract_with_progress(source, partial, maxzoom, download_id, timeout=timeout)
            os.replace(partial, output)
            return
        except SourceUnreachable as e:
            partial.unlink(missing_ok=True)
            wait = _UNREACHABLE_BACKOFF[min(attempt, len(_UNREACHABLE_BACKOFF) - 1)]
            logger.warning("%s — retrying in %ds", e, wait)
            global_download_tracker.fail(
                download_id, f"{e} The download will retry by itself in {_in_words(wait)}.")
            sleep(wait)
        except BaseException:
            partial.unlink(missing_ok=True)
            raise
