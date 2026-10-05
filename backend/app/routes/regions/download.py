# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Region download orchestration — the background pipeline launched by
POST /maps/regions/download.

`download_and_merge` runs in a daemon thread and drives a region through every
phase: parallel basemap + DEM extracts → master merges → thematic overlay build →
contours → install → auto-merge of overlapping areas. A user can abort at any
stage; cancellation is checked at each phase boundary and surfaced via a
cross-worker marker file (see app.services.download_cancel).
"""

import logging
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

from app.config import settings
from app.services import (
    download_cancel, region_downloader, region_merger, region_registry,
)

from .purge import purge_cancelled_region, purge_region
from .thematic import build_thematic

logger = logging.getLogger(__name__)


def download_and_merge(region_id: int, bbox: list[float]) -> None:
    # A user can abort the download at any stage. `cancel` carries the flag the
    # pipeline checks at each phase boundary (raise_if_cancelled) and registers
    # the live subprocesses / pools so the watcher can kill them. The watcher
    # polls a shared marker file, so a cancel from any uvicorn worker reaches the
    # one running this thread.
    cancel = download_cancel.CancelToken(region_id)
    cancel.start_watching()
    try:
        cancel.raise_if_cancelled()
        # ── Parallel basemap + DEM extracts ──────────────────────────────
        with ThreadPoolExecutor(max_workers=2) as ex:
            futures = {ex.submit(region_downloader.download_region, region_id, bbox, cancel): "basemap"}
            if settings.dem_source_url:
                futures[ex.submit(region_downloader.download_region_dem, region_id, bbox, cancel)] = "dem"

            errors = []
            for future in as_completed(futures):
                try:
                    future.result()
                except download_cancel.DownloadCancelled:
                    raise                       # abort — unwind to the cleanup handler
                except Exception as e:
                    errors.append(f"{futures[future]}: {e}")
                    logger.exception("Extract failed for region %s (%s)", region_id, futures[future])

            if errors:
                region_registry.update_status(region_id, "error", error="; ".join(errors))
                return

        cancel.raise_if_cancelled()
        r = region_registry.get(region_id)
        if r and r["status"] == "error":
            return

        # ── Basemap & DEM merges (serialised by merge lock) ──────────────
        # mark_installed=False / defer_contours=True keep the region in-progress
        # so the trail + contour phases below report their own progress before
        # the region flips to "installed".
        region_registry.update_status(region_id, "merging", progress=0.0,
                                      detail="Assembling tiles…")
        src = region_registry.source_dir(region_id) / "source.pmtiles"
        region_merger.rebuild_master(index_source=str(src), write_trigger=False,
                                     mark_installed=False)
        if settings.dem_source_url:
            region_merger.rebuild_master_dem(write_trigger=False)

        cancel.raise_if_cancelled()
        # ── Thematic builders (chunked, parallel) ────────────────────────
        # A continent-scale region won't fit its features in memory at once, so it
        # is built in spatial cells (a single cell for normal regions). Each cell's
        # OSM data is extracted once and shared by all builders, then freed before
        # the next cell — peak memory is bounded by cell size, not region size.
        region_registry.update_status(region_id, "trails", progress=0.0,
                                      detail="Fetching trail data…")
        build_thematic(region_id, bbox, cancel)

        # Basemap + trails are ready — show them now while contours generate.
        region_merger.write_reload_trigger()

        cancel.raise_if_cancelled()
        # ── Contour generation (tracked phase) ───────────────────────────
        # Built from THIS region's small dem.pmtiles (no full-planet DEM scan, no
        # whole-archive copy), then master_contours is reassembled.
        if settings.dem_source_url:
            region_registry.update_status(region_id, "contours", progress=0.0,
                                          detail="Generating contours…")
            try:
                from app.services.contour_generator import build_region_contours
                build_region_contours(
                    region_id,
                    progress_cb=lambda done, total: region_registry.update_status(
                        region_id, "contours",
                        progress=round(done / max(total, 1) * 100, 1),
                        detail=f"{done} / {total} tiles"),
                    cancel=cancel)
            except download_cancel.DownloadCancelled:
                raise
            except Exception:
                logger.exception("Region contours failed (non-fatal) for %s", region_id)

        cancel.raise_if_cancelled()
        # ── Mark installed ───────────────────────────────────────────────
        region_registry.update_status(region_id, "installed", progress=100.0,
                                      detail=None)

        # ── Auto-merge overlapping areas ─────────────────────────────────
        # Past this point the download is done; cancellation no longer applies
        # (a DELETE now takes the normal delete path). When the new area overlaps
        # existing ones they fold into one (the newest is absorbed into the
        # oldest). Drive a tracked "combining" phase on THIS region's card; it
        # retires when the merge deletes this region, and the surviving area then
        # reappears with the union outline.
        try:
            new = region_registry.get(region_id)
            overlap = []
            if new and new["status"] == "installed":
                overlap = [r for r in region_registry.find_overlapping(
                               new["bbox"], exclude_id=region_id)
                           if r["status"] == "installed"]
            if overlap:
                region_registry.update_status(region_id, "combining", progress=0.0,
                                              detail="Merging overlapping areas…")
                region_registry.merge_regions(
                    [region_id] + [r["id"] for r in overlap],
                    progress_cb=lambda pct, detail: region_registry.update_status(
                        region_id, "combining", progress=pct, detail=detail))
        except Exception:
            logger.exception("Region auto-merge failed for %s (non-fatal)", region_id)
        finally:
            # Never leave this region stuck on the merge wheel: if it wasn't
            # absorbed (no overlap, or the merge errored before deleting it),
            # settle it back to 'installed'. On a successful merge it's already
            # gone (get → None), so this is a no-op.
            cur = region_registry.get(region_id)
            if cur and cur["status"] == "combining":
                region_registry.update_status(region_id, "installed",
                                              progress=100.0, detail=None)

    except download_cancel.DownloadCancelled:
        logger.info("Region %s download cancelled — cleaning up", region_id)
        purge_cancelled_region(region_id)
    except Exception as e:
        # A subprocess killed by cancel can surface as a generic error before the
        # next checkpoint — treat it as a cancellation, not a failed download.
        if cancel.cancelled():
            logger.info("Region %s aborted mid-stage — cleaning up", region_id)
            purge_cancelled_region(region_id)
        else:
            region_registry.update_status(region_id, "error", error=str(e))
    finally:
        cancel.stop_watching()
        download_cancel.clear(region_id)


# Statuses whose work has a live thread behind it — or had one, until something
# ended the process it was running in.
_RESUMABLE = {"downloading", "downloading_dem", "merging", "trails", "contours"}


def resume_interrupted() -> int:
    """Restart region builds that a restart abandoned. Returns how many.

    ## Why this is needed

    `download_and_merge` runs in a daemon thread. A container restart, a deploy,
    an OOM kill or `docker compose up -d` on any other service takes that thread
    with it — and nothing else in the system was ever told. The row stays in
    `downloading` at whatever percent it had reached, the progress bar keeps
    describing work that has not existed for hours, and twenty minutes later
    `region_registry` marks it stale so the client can offer a delete. The only
    way forward was to throw away a build that may have been most of the way
    through gigabytes of extraction and start it again by hand.

    Restarting is safe because every phase is write-through: the extract
    overwrites the region's `source.pmtiles`, the merges rebuild the masters
    from whatever regions have files, and the thematic and contour builders are
    both derived from those. Nothing accumulates, so re-running a phase that
    already finished costs time and nothing else.

    ## What is not resumed

    `combining` is a post-download optimisation that folds overlapping areas
    together; the download itself is finished by then, so the region is simply
    settled back to `installed` rather than re-run. `cancelling` and `deleting`
    are teardowns whose worker is equally gone — they are finished here, because
    a half-purged area is the one state that really cannot be left alone.

    Called from startup recovery in exactly one worker (see `_is_primary_worker`),
    so two processes cannot restart the same build.
    """
    from app.services import download_cancel, region_registry

    resumed: list[dict] = []
    for region in region_registry.list_active():
        status = region["status"]
        region_id = region["id"]
        if status in _RESUMABLE:
            resumed.append(region)
        elif status == "combining":
            logger.info("Region %s was mid-merge at restart — settling it", region_id)
            region_registry.update_status(region_id, "installed", progress=100.0, detail=None)
        elif status == "cancelling":
            logger.info("Region %s was mid-cancel at restart — cleaning up", region_id)
            threading.Thread(target=purge_cancelled_region, args=(region_id,),
                             daemon=True).start()
        elif status == "deleting":
            logger.info("Region %s was mid-delete at restart — cleaning up", region_id)
            threading.Thread(target=purge_region, args=(region_id,), daemon=True).start()

    if not resumed:
        return 0

    logger.warning(
        "Resuming %d interrupted region build(s): %s",
        len(resumed), ", ".join(r["name"] for r in resumed),
    )

    def _run_all() -> None:
        for region in resumed:
            region_id = region["id"]
            # A cancel marker left behind by the dead run would abort the new
            # one at its first checkpoint, which would look exactly like the
            # resume having failed.
            download_cancel.clear(region_id)
            region_registry.update_status(
                region_id, "downloading", progress=0.0,
                detail="Resuming after a server restart…",
            )
            try:
                download_and_merge(region_id, region["bbox"])
            except Exception:
                logger.exception("Resumed region %s failed", region_id)

    # One thread for all of them, in sequence. Each build is already parallel
    # inside itself and competes for the same disk and the same merge lock;
    # starting five at once on a restart would be slower than starting them one
    # after another, and far more likely to run the box out of memory.
    threading.Thread(target=_run_all, daemon=True, name="region-resume").start()
    return len(resumed)
