# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Region REST endpoints: name suggestion, size estimate, download kickoff,
listing, status/progress, and deletion.

Route ordering matters — FastAPI matches in registration order, so the static
`/regions/*` paths (suggest-name, estimate, download) are declared before the
dynamic `/regions/{region_id}` routes that would otherwise swallow them.
"""

import logging
import threading
from concurrent.futures import ThreadPoolExecutor

from fastapi import APIRouter, HTTPException

from app.config import settings
from app.services import (
    download_cancel, geocode, region_downloader, region_registry,
)
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import resolve_source_url

from .bbox import MAX_NAME_LEN, parse_bbox
from .download import download_and_merge
from .purge import purge_region

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/maps", tags=["maps"])

# Statuses that mean a download pipeline is still running for the region — a
# DELETE on one of these aborts the download (cancel) rather than removing a
# finished area.
_IN_PROGRESS_STATUSES = {
    "downloading", "downloading_dem", "merging", "trails", "contours", "combining",
}


@router.get("/regions/suggest-name")
def suggest_name(bbox: str):
    return {"name": geocode.suggest_region_name(parse_bbox(bbox))}


@router.get("/regions/estimate")
def estimate_region(bbox: str):
    """Precise download-size estimate for a bbox: the exact byte size of the
    basemap (z13-15 detail) + DEM extracts, via `pmtiles extract --dry-run`."""
    parts = parse_bbox(bbox)
    bbox_str = ",".join(str(p) for p in parts)

    jobs = []
    if settings.pmtiles_source_url:
        jobs.append((resolve_source_url(settings.pmtiles_source_url),
                     region_downloader._basemap_region_minzoom(), 15))
    if settings.dem_source_url:
        jobs.append((settings.dem_source_url,
                     region_downloader._dem_region_minzoom(),
                     region_downloader._dem_region_maxzoom()))

    # Run the basemap + DEM dry-runs concurrently (each is a few seconds of
    # remote range requests).
    with ThreadPoolExecutor(max_workers=len(jobs) or 1) as ex:
        sizes = ex.map(
            lambda j: region_downloader.dry_run_size(j[0], bbox_str, j[1], j[2]),
            jobs,
        )
    return {"bytes": sum(sizes)}


# How far a requested box may poke outside an existing one and still count as
# covered by it — about 11 m, which is smaller than a user can aim a drag.
_COVER_SLACK = 1e-4

# Statuses that make an existing region a real answer to a fresh request.
# `error`, `cancelling` and `deleting` are deliberately absent: a failed or
# half-removed area is exactly when someone asks again, and the answer they
# want is a new attempt, not a pointer at the wreckage.
_MATCHABLE_STATUSES = _IN_PROGRESS_STATUSES | {"installed"}


def _covers(outer: list[float], inner: list[float]) -> bool:
    """True when `outer` contains `inner` (west, south, east, north)."""
    return (outer[0] <= inner[0] + _COVER_SLACK
            and outer[1] <= inner[1] + _COVER_SLACK
            and outer[2] >= inner[2] - _COVER_SLACK
            and outer[3] >= inner[3] - _COVER_SLACK)


def _already_have(bbox: list[float]) -> dict | None:
    """An existing region that makes this download unnecessary, if there is one.

    Without this, every request created a row and started an extraction, so
    asking for an area you already had downloaded it a second time into a second
    directory — which is what "the server is trying to download it again" looked
    like from the map. Worse, the duplicates all get the same suggested name, so
    the region list filled up with identically-labelled copies of one state.

    A merged area is matched only on an exact box. Its stored bbox is the
    ENVELOPE of the areas it combines, and an L-shaped merge has a large corner
    that envelope claims but no tile covers; treating that as coverage would
    tell someone they already had a place they had never downloaded.
    """
    for region in region_registry.list_active():
        if region["status"] not in _MATCHABLE_STATUSES or region.get("stale"):
            continue
        existing = region.get("bbox")
        if not isinstance(existing, list) or len(existing) != 4:
            continue
        if not _covers(existing, bbox):
            continue
        if region.get("geometry") is not None and not _covers(bbox, existing):
            continue
        return region
    return None


def _distinct_name(name: str) -> str:
    """Keep region names unique among the areas that still exist.

    `suggest_region_name` answers with the state or city a box falls in, so two
    different corners of one state both come back as that state — indistinguishable
    in a list whose whole job is telling downloaded areas apart.
    """
    taken = {r["name"] for r in region_registry.list_active()}
    if name not in taken:
        return name
    for suffix in range(2, 100):
        candidate = f"{name} ({suffix})"
        if candidate not in taken:
            return candidate[:MAX_NAME_LEN]
    return name


@router.post("/regions/download")
def download_region(data: dict):
    bbox = parse_bbox(data.get("bbox"))
    name = (data.get("name") or "").strip()[:MAX_NAME_LEN]

    if not settings.pmtiles_source_url:
        raise HTTPException(400, "PMTILES_SOURCE_URL is not configured")

    # Answer with what's already here before building anything. Both cases hand
    # back a region the caller can watch exactly as if it had just been started,
    # so a client needs no new branch to benefit — the phone half of the download
    # follows on from an "exists" region the same way it follows a fresh one.
    existing = _already_have(bbox)
    if existing is not None:
        status = "exists" if existing["status"] == "installed" else "downloading"
        return {"status": status, "region": existing}

    if not name or name == "Untitled":
        name = geocode.suggest_region_name(bbox)
    name = _distinct_name(name)

    region = region_registry.create(name=name, bbox=bbox)

    thread = threading.Thread(
        target=download_and_merge, args=(region["id"], bbox), daemon=True
    )
    thread.start()

    return {"status": "downloading", "region": region}


@router.get("/regions")
def list_regions():
    return region_registry.list_active()


@router.get("/regions/{region_id}")
def get_region(region_id: int):
    r = region_registry.get(region_id)
    if not r:
        raise HTTPException(404, "Region not found")
    return r


@router.delete("/regions/{region_id}")
def delete_region(region_id: int):
    r = region_registry.get(region_id)
    if not r:
        raise HTTPException(404, "Region not found")
    status = r["status"]

    # Already being removed (delete or cancel cleanup in flight) — don't kick a
    # second rebuild that would race the first.
    if status == "deleting":
        return {"status": "deleting"}

    # A stalled build (region_registry.stale — no status update in a long
    # while) has no live worker left to notice a cancel request, so the
    # request_cancel() path below would leave it stuck in "cancelling"
    # forever too. Nothing is writing to its files anymore, so it's safe to
    # purge directly instead of waiting for a worker that's already gone.
    if (status in _IN_PROGRESS_STATUSES or status == "cancelling") and r.get("stale"):
        region_registry.update_status(region_id, "deleting", progress=0.0)
        threading.Thread(target=purge_region, args=(region_id,), daemon=True).start()
        return {"status": "deleting"}

    # In-progress download (or a cancel already requested) → abort it. We only set
    # the cross-worker marker + UI state; the worker running the pipeline notices,
    # stops the work, and runs cleanup itself — doing it here would race that live
    # pipeline's file writes.
    if status in _IN_PROGRESS_STATUSES or status == "cancelling":
        download_cancel.request_cancel(region_id)
        region_registry.update_status(region_id, "cancelling", progress=0.0,
                                      detail="Cancelling…")
        return {"status": "cancelling"}

    # Installed / errored → remove now. Mark 'deleting' so it stays in the list
    # with a wheel until the async master rebuild finishes; _purge_region then
    # marks it deleted, which retires the wheel.
    region_registry.update_status(region_id, "deleting", progress=0.0)
    threading.Thread(target=purge_region, args=(region_id,), daemon=True).start()
    return {"status": "deleting"}


@router.get("/regions/{region_id}/progress")
def region_progress(region_id: int):
    r = region_registry.get(region_id)
    if not r:
        raise HTTPException(404, "Region not found")
    return {"status": r["status"], "progress": r["progress"],
            "detail": r.get("detail"), "error": r["error"]}


@router.get("/global-downloads")
def global_downloads():
    """Return status of the two global downloads (basemap overview + DEM).

    Used by the frontend to show a progress indicator when the user first
    enables maps in Settings and the ~120 MB / ~3.1 GB archives are being
    downloaded in the background.
    """
    return {"downloads": global_download_tracker.get_all()}
