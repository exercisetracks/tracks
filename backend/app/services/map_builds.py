# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The one queue every map-area build and removal runs through.

## Why a queue

Area builds used to be a daemon thread per request, in whichever uvicorn
worker took the request, guarded by in-process locks. With two or four
workers that meant (audit F12-F16, F19, F23):

* a browser build and a phone build ran at once and rewrote the same master
  archives under each other;
* a restart lost every build in flight, and the one worker that resumed them
  could resume builds another worker was still running;
* "stalled" meant twenty quiet minutes, so a long tile build was given up on
  while it ran, and deleting it purged files under it;
* a resume started the whole build again from nothing.

Now a request only writes a row (`queued`, or `deleting`/`cancelling`), and
every worker runs a builder thread that competes for one PostgreSQL advisory
lock. Whoever holds it works the queue one job at a time, at the machine's
full parallelism; the others wait. The lock belongs to a database session, so
when the process holding it dies the lock goes with it and another worker
picks up — any row still in a build state then is by definition abandoned,
and is resumed rather than duplicated.

## A build

Each phase writes its output into the area's work directory and records
itself in `phases_done`, so an interrupted build resumes at the next phase:

1. `extract` — basemap detail and terrain, from the planet archives.
2. `overlay` — trails, water and land for the ground the area *owns* (its box
   minus what installed areas already cover; see map_store).
3. `dem` — terrain published into master_dem.
4. `contours` — traced from master_dem for the box and a ring around it, so
   the seam with neighbouring areas is traced from both sides; published.
5. `basemap` — published into master_basemap_detail.
6. `publish` — the overlay part moves into the area's directory and
   master_overlay is rebuilt.

Only then is the area `installed`. A failure stops the build with the phase
named in the error; whatever was published stays harmlessly (those tiles are
area-independent), and asking for the area again resumes it.

## A removal

`deleting` (or `cancelling`, for a build stopped part-way) drops from each
area-independent master the tiles no remaining area touches, rebuilds into a
remaining area any overlay ground it owned that is still covered, removes its
part, and marks the row deleted. Every step is idempotent, so a removal that
is interrupted simply runs again.
"""

from __future__ import annotations

import logging
import shutil
import threading
import time
from pathlib import Path

from sqlalchemy import text

from app.config import settings
from app.services import download_cancel, map_store, region_registry

logger = logging.getLogger(__name__)

# PostgreSQL advisory lock id for "the map writer" (ASCII "tmap").
LOCK_KEY = 0x746D6170
# How often an idle builder checks the queue; a new request also wakes the
# builder in its own process at once (kick).
POLL_S = 10.0
HEARTBEAT_S = 10.0
# A build that has started this many times without finishing stops trying: one
# that kills its process every time (out of memory, say) must not loop forever.
MAX_ATTEMPTS = 3

PHASES = ("extract", "overlay", "dem", "contours", "basemap", "publish")

# Bumped when the overlay builder changes what it puts in a part. Areas whose
# part was built by an older version get only their overlay rebuilt
# (upgrade_overlays), over the ground they own — nothing downloaded again.
#   2: public land reaching past the area's box is kept (boundary relations
#      completed in the OSM extract; see osmium_runner._extract_region).
OVERLAY_VERSION = 2
_BUILD_STATUSES = {"downloading", "downloading_dem", "merging", "trails", "contours"}
_TEARDOWN_STATUSES = {"deleting", "cancelling"}

_wake = threading.Event()
_started = False
_started_lock = threading.Lock()


def kick() -> None:
    """Wake this process's builder now rather than at its next poll."""
    _wake.set()


def start() -> None:
    """Start this process's builder thread. Called once per worker at startup."""
    global _started
    with _started_lock:
        if _started:
            return
        _started = True
    threading.Thread(target=_loop, daemon=True, name="map-builder").start()
    kick()


def _loop() -> None:
    while True:
        _wake.wait(timeout=POLL_S)
        _wake.clear()
        try:
            drain()
        except Exception:
            logger.exception("Map build queue pass failed")


def drain() -> bool:
    """Take the writer lock if it is free and work the queue until it is empty.

    Returns whether this call held the lock. Safe to call from anywhere: when
    another process is building, it returns at once.
    """
    from app.database import engine
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as conn:
        if not conn.execute(text("SELECT pg_try_advisory_lock(:k)"), {"k": LOCK_KEY}).scalar():
            return False
        try:
            migrate_legacy_parts()
            recover_missing_masters()
            upgrade_overlays()
            while True:
                job = next_job()
                if job is None:
                    break
                run(job)
        finally:
            conn.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": LOCK_KEY})
    return True


def next_job() -> dict | None:
    """Teardowns first (they free disk), then abandoned builds, then the queue."""
    rows = region_registry.list_active()
    for wanted in (_TEARDOWN_STATUSES, _BUILD_STATUSES | {"combining"}, {"queued"}):
        for r in rows:
            if r["status"] in wanted:
                return r
    return None


def run(job: dict) -> None:
    status = job["status"]
    if status in _TEARDOWN_STATUSES:
        teardown(job)
    elif status == "combining":
        # Areas are no longer merged; a row an older version left mid-merge had
        # finished downloading, so it is settled rather than rebuilt.
        region_registry.update_status(job["id"], "installed", progress=100.0, detail=None)
    else:
        build(job)


# ── Build ────────────────────────────────────────────────────────────────────

class _Heartbeat:
    def __init__(self, region_id: int):
        self.region_id = region_id
        self._stop = threading.Event()

    def __enter__(self):
        region_registry.heartbeat(self.region_id)
        def beat():
            while not self._stop.wait(HEARTBEAT_S):
                try:
                    region_registry.heartbeat(self.region_id)
                except Exception:
                    logger.warning("Heartbeat for region %s failed", self.region_id, exc_info=True)
        threading.Thread(target=beat, daemon=True, name=f"heartbeat-{self.region_id}").start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        return False


def build(job: dict) -> None:
    rid, bbox = job["id"], job["bbox"]
    done = list(job.get("phases_done") or [])
    if done.count("attempt") >= MAX_ATTEMPTS:
        region_registry.update_status(
            rid, "error",
            error=f"Stopped after {MAX_ATTEMPTS} attempts — the server could not finish this area")
        return
    region_registry.record_attempt(rid)  # before the work, so a crash counts too
    done = set(done)
    downloaded = "extract" not in done   # a fresh build, not an overlay-only rebuild

    work = map_store.work_dir(rid)
    work.mkdir(parents=True, exist_ok=True)
    cancel = download_cancel.CancelToken(rid)
    cancel.start_watching()
    phase = "start"

    high = [0.0]

    def status(state, pct, detail):
        # One overall percentage for the whole build, which only moves
        # forward: each phase owns a slice of the bar (_SPAN), and reports
        # how far through its own slice it is.
        lo, hi = _SPAN[phase]
        overall = lo + (hi - lo) * max(0.0, min(100.0, float(pct))) / 100.0
        # Never backwards, whatever a tool's own counter does (tippecanoe
        # restarts its percentage for each pass over the data).
        overall = high[0] = max(high[0], overall)
        region_registry.update_status(rid, state, progress=round(overall, 1), detail=detail)

    def finish(name):
        region_registry.add_phase_done(rid, name)
        done.add(name)
        cancel.raise_if_cancelled()

    try:
        with _Heartbeat(rid):
            phase = "extract"
            if phase not in done:
                status("downloading", 0, "Checking disk space…")
                preflight_disk(bbox)
                from app.services import region_downloader
                region_downloader.extract_layers(
                    bbox, work, progress=lambda p, d: status("downloading", p, d), cancel=cancel)
                finish(phase)

            phase = "overlay"
            if phase not in done:
                status("trails", 0, "Fetching trail data…")
                # Ground already owned stays owned: an overlay rebuild must not
                # recompute it against areas built since, which were cut around it.
                owned = map_store.shape(job.get("owned")) or _ground_to_own(rid, bbox)
                part = work / "overlay.pmtiles"
                if owned is not None:
                    from app.routes.regions.thematic import build_overlay_part
                    build_overlay_part(owned, part, progress=lambda p, d: status("trails", p, d),
                                       cancel=cancel)
                region_registry.set_owned(rid, map_store.geojson(owned))
                finish(phase)

            size = sum(p.stat().st_size for p in work.glob("*.pmtiles"))

            phase = "dem"
            if phase not in done:
                if (work / "dem.pmtiles").exists():
                    status("merging", 0, "Adding terrain…")
                    map_store.publish(map_store.MASTER_DEM, work / "dem.pmtiles",
                                      progress=lambda f: status("merging", f * 100, f"Adding terrain… {f:.0%}"))
                finish(phase)

            phase = "contours"
            if phase not in done:
                if map_store.master_path(map_store.MASTER_DEM).exists():
                    status("contours", 0, "Tracing contour lines…")
                    _build_contours(rid, bbox, work, cancel,
                                    lambda d, t: status("contours", d / max(t, 1) * 100, f"{d} / {t} tiles"))
                finish(phase)

            phase = "basemap"
            if phase not in done:
                src = work / "source.pmtiles"
                if src.exists():
                    status("merging", 0, "Adding map detail…")
                    map_store.publish(map_store.MASTER_BASEMAP, src,
                                      progress=lambda f: status("merging", f * 90, f"Adding map detail… {f:.0%}"))
                    status("merging", 95, "Indexing places…")
                    _index_pois(src)
                finish(phase)

            phase = "publish"
            if phase not in done:
                status("merging", 0, "Adding trails…")
                part = work / "overlay.pmtiles"
                dest = map_store.area_dir(rid)
                dest.mkdir(parents=True, exist_ok=True)
                if part.exists():
                    # The new part covers all the ground this area owns, any
                    # handed over from removed neighbours included.
                    for old in dest.glob("overlay-from-*.pmtiles"):
                        old.unlink()
                    shutil.move(str(part), dest / "overlay.pmtiles")
                (dest / "overlay.version").write_text(str(OVERLAY_VERSION))
                map_store.rebuild_overlay(cancel=cancel,
                                          progress_cb=lambda pct, detail=None: status("merging", pct, detail or "Adding trails…"))
                finish(phase)

        region_registry.update_status(rid, "installed", progress=100.0, detail=None,
                                      size_bytes=(size or None) if downloaded else None)
        region_registry.reset_phases(rid)
        shutil.rmtree(work, ignore_errors=True)
        map_store.write_reload_trigger()
        logger.info("Area %s (%s) installed", rid, job["name"])
    except download_cancel.DownloadCancelled:
        logger.info("Area %s cancelled during %s", rid, phase)
        region_registry.update_status(rid, "cancelling", progress=0.0, detail="Cancelling…")
        teardown(region_registry.get(rid) or job)
    except Exception as e:
        logger.exception("Area %s failed during %s", rid, phase)
        region_registry.update_status(rid, "error", error=f"{_PHASE_LABEL.get(phase, phase)}: {e}")
        map_store.write_reload_trigger()
    finally:
        cancel.stop_watching()


# Each phase's slice of the overall progress bar, by how long it takes.
_SPAN = {
    "start": (0, 0), "extract": (0, 30), "overlay": (30, 70), "dem": (70, 76),
    "contours": (76, 86), "basemap": (86, 92), "publish": (92, 100),
}

_PHASE_LABEL = {
    "start": "Could not start",
    "extract": "Downloading the map failed",
    "overlay": "Building trails failed",
    "dem": "Adding terrain failed",
    "contours": "Tracing contours failed",
    "basemap": "Adding map detail failed",
    "publish": "Adding trails failed",
}


def _ground_to_own(rid: int, bbox: list[float]):
    """This area's box minus what installed areas already cover, or None."""
    from shapely.geometry import box
    mine = box(*bbox)
    others = region_registry.coverage(exclude_id=rid)
    owned = mine if others is None else mine.difference(others)
    # Slivers from floating-point edges are not ground anyone can see.
    if owned.is_empty or owned.area < 1e-8:
        return None
    return owned


# One z12 tile is ~0.088° wide; half of one reaches the tiles bordering the
# box, so the seam between this area and its neighbours is retraced with
# terrain on both sides (it was traced from one side when the neighbour was
# built alone).
_CONTOUR_RING_DEG = 0.05


def _build_contours(rid, bbox, work: Path, cancel, progress) -> None:
    from app.services.contour_generator import build_contours_pmtiles
    w, s, e, n = bbox
    ring = _CONTOUR_RING_DEG
    out = work / "contours.pmtiles"
    out.unlink(missing_ok=True)
    build_contours_pmtiles(map_store.master_path(map_store.MASTER_DEM), out,
                           bbox=(w - ring, s - ring, e + ring, n + ring),
                           progress_cb=progress, cancel=cancel)
    if out.exists():
        map_store.publish(map_store.MASTER_CONTOURS, out)


def _index_pois(source: Path) -> None:
    try:
        from app.services.poi_indexer import MIN_TILE_ZOOM, index_pmtiles
        index_pmtiles(str(source), label="area POIs", skip_below_zoom=MIN_TILE_ZOOM)
    except Exception:
        logger.exception("POI index for %s failed (non-fatal)", source)


def preflight_disk(bbox: list[float]) -> None:
    """Refuse a build the disk cannot hold, before downloading any of it.

    A full disk halfway through a merge used to fail with whatever the tool
    printed, after minutes of work (audit F22). A publish writes the new master
    beside the old one, so the biggest master counts too.
    """
    data = map_store.data_dir()
    try:
        from app.routes.regions.crud import estimate_bytes
        need = int(estimate_bytes(bbox) * 1.6)
    except Exception:
        logger.info("Could not size the area before building; skipping the disk check")
        return
    largest = max((map_store.master_path(n).stat().st_size
                   for n in (*map_store.AREA_INDEPENDENT, map_store.MASTER_OVERLAY)
                   if map_store.master_path(n).exists()), default=0)
    need += largest + (1 << 30)
    free = shutil.disk_usage(data).free
    if free < need:
        raise RuntimeError(
            f"Not enough disk space on the server: this area needs about {need / 1e9:.1f} GB "
            f"free and {free / 1e9:.1f} GB is")


# ── Teardown ─────────────────────────────────────────────────────────────────

def teardown(job: dict) -> None:
    """Remove an area: its tiles no other area needs, and its overlay ground."""
    rid = job["id"]
    try:
        with _Heartbeat(rid):
            region_registry.update_status(rid, job["status"] if job["status"] in _TEARDOWN_STATUSES
                                          else "deleting", progress=10.0, detail="Removing…")
            area = region_registry.region_geom(job)
            remaining = region_registry.coverage(exclude_id=rid, include_partial=True)
            for name in map_store.AREA_INDEPENDENT:
                map_store.drop_area(name, area, remaining)

            parts = map_store.overlay_parts(rid)
            if parts:
                region_registry.update_status(rid, job["status"], progress=50.0,
                                              detail="Rebuilding trails for neighbouring areas…")
                _hand_over_ground(job, remaining)
            shutil.rmtree(map_store.area_dir(rid), ignore_errors=True)
            map_store.rebuild_overlay()
            shutil.rmtree(map_store.work_dir(rid), ignore_errors=True)
        region_registry.mark_deleted(rid)
        map_store.write_reload_trigger()
        logger.info("Area %s (%s) removed", rid, job.get("name"))
    except Exception as e:
        logger.exception("Removing area %s failed", rid)
        region_registry.update_status(
            rid, "error", error=f"Removing this area failed: {e} — try removing it again")


def _hand_over_ground(job: dict, remaining) -> None:
    """Rebuild the overlay for ground this area owned that others still cover.

    Its part held every trail on that ground; the areas that also cover it
    hold none (they were cut around it). The regained ground goes to the oldest
    of them, as an extra part, before this area's part is removed — so if the
    rebuild fails, nothing has been lost yet.
    """
    if remaining is None:
        return
    owned = map_store.shape(job.get("owned")) or region_registry.region_geom(job)
    regained = owned.intersection(remaining)
    if regained.is_empty or regained.area < 1e-8:
        return
    heirs = [r for r in region_registry.list_active()
             if r["status"] == "installed" and r["id"] != job["id"]
             and region_registry.region_geom(r).intersects(regained)]
    if not heirs:
        return
    heir = min(heirs, key=lambda r: r["id"])
    from app.routes.regions.thematic import build_overlay_part
    dest = map_store.area_dir(heir["id"])
    dest.mkdir(parents=True, exist_ok=True)
    part = dest / f"overlay-from-{job['id']}.pmtiles"
    build_overlay_part(regained, part)
    heir_owned = map_store.shape(heir.get("owned"))
    region_registry.set_owned(
        heir["id"], map_store.geojson(regained if heir_owned is None else heir_owned.union(regained)))


# ── One-time: per-area parts → masters only ──────────────────────────────────

def migrate_legacy_parts() -> None:
    """Delete the per-area copies of the area-independent layers.

    Before masters-only, every area kept source/dem/contours files beside the
    masters that were assembled from them — a full second copy of the map. The
    masters already hold those tiles, so the copies are simply removed. Areas
    keep their overlay part, and get `owned` set to their own shape (they never
    overlapped: older versions merged overlapping areas into one).
    Idempotent; cheap when there is nothing left to do.
    """
    root = map_store.data_dir() / "regions"
    if not root.exists():
        return
    masters_present = all(map_store.master_path(n).exists()
                          for n in (map_store.MASTER_BASEMAP,))
    rows = {r["id"]: r for r in region_registry.list_active()}
    for d in root.iterdir():
        if not d.is_dir() or not d.name.isdigit():
            continue
        rid = int(d.name)
        row = rows.get(rid)
        if row is None:
            # A directory no area owns (a cancel used to leave these behind).
            if not any(d.glob("overlay*.pmtiles")):
                shutil.rmtree(d, ignore_errors=True)
            continue
        if row["status"] != "installed":
            continue
        legacy = [d / f for f in ("source.pmtiles", "dem.pmtiles", "contours.pmtiles")]
        if any(p.exists() for p in legacy) and masters_present:
            for p in legacy:
                p.unlink(missing_ok=True)
            logger.info("Area %s: removed per-area copies now held only by the masters", rid)
        if row.get("owned") is None:
            region_registry.set_owned(rid, map_store.geojson(region_registry.region_geom(row)))


def upgrade_overlays() -> None:
    """Queue an overlay-only rebuild for installed areas built before OVERLAY_VERSION."""
    for r in region_registry.list_active():
        if r["status"] != "installed":
            continue
        marker = map_store.area_dir(r["id"]) / "overlay.version"
        try:
            built = int(marker.read_text())
        except (OSError, ValueError):
            built = 1
        if built >= OVERLAY_VERSION:
            continue
        if r.get("owned") is None:
            region_registry.set_owned(r["id"], map_store.geojson(region_registry.region_geom(r)))
        region_registry.reset_phases(r["id"])
        for phase in ("extract", "dem", "contours", "basemap"):
            region_registry.add_phase_done(r["id"], phase)
        region_registry.update_status(r["id"], "queued", progress=0.0,
                                      detail="Updating trails and public lands…")
        logger.info("Area %s: rebuilding its overlay (built by version %s, now %s)",
                    r["id"], built, OVERLAY_VERSION)


def recover_missing_masters() -> None:
    """Areas whose tiles are gone from disk go back in the queue.

    The masters are the only copy now, so a volume restored without them (or a
    master deleted by hand) leaves installed areas with nothing behind them.
    They are rebuilt from their boxes — the old answer, marking them failed for
    someone to notice and re-download one by one, assumed per-area copies the
    masters could be reassembled from. Contours alone missing are retraced from
    master_dem without re-downloading anything.
    """
    installed = [r for r in region_registry.list_active() if r["status"] == "installed"]
    if not installed:
        return
    if not map_store.master_path(map_store.MASTER_BASEMAP).exists():
        logger.warning("Map detail archive missing — queuing %d area(s) to build again", len(installed))
        for r in installed:
            region_registry.reset_phases(r["id"])
            region_registry.update_status(r["id"], "queued", progress=0.0,
                                          detail="Map files were missing — downloading again…")
        return
    dem = map_store.master_path(map_store.MASTER_DEM)
    contours = map_store.master_path(map_store.MASTER_CONTOURS)
    if dem.exists() and not contours.exists():
        logger.warning("Contours missing — tracing them again from the terrain")
        from app.services.contour_generator import build_contours_pmtiles
        build_contours_pmtiles(dem, contours)
        map_store.write_reload_trigger()


def wait_idle(timeout: float = 30.0) -> bool:
    """For tests and the reset route: drain the queue in this thread."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        drain()
        if next_job() is None:
            return True
        time.sleep(0.2)
    return False
