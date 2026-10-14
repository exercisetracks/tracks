# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Region REST endpoints: name suggestion, size estimate, download kickoff,
listing, status/progress, and deletion.

Route ordering matters — FastAPI matches in registration order, so the static
`/regions/*` paths (suggest-name, estimate, download) are declared before the
dynamic `/regions/{region_id}` routes that would otherwise swallow them.
"""

import logging
from concurrent.futures import ThreadPoolExecutor

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import text

from app.auth import require_auth
from app.models.activity import User

from app.config import settings
from app.services import (
    geocode, map_builds, map_hosts, map_store, region_downloader, region_registry,
)
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import resolve_source_url

from .bbox import MAX_NAME_LEN, parse_bbox

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/maps", tags=["maps"])

# Queued or being built — a DELETE on one of these cancels the build rather
# than removing a finished area.
_IN_PROGRESS_STATUSES = {
    "queued", "downloading", "downloading_dem", "merging", "trails", "contours", "combining",
}
# Serialises "is it already here? no — create it" across workers, so two
# requests for one box at the same moment make one area, not two (audit F23).
_CREATE_LOCK_KEY = 0x746D6171


@router.get("/regions/suggest-name")
def suggest_name(bbox: str):
    return {"name": geocode.suggest_region_name(parse_bbox(bbox))}


@router.get("/regions/estimate")
def estimate_region(bbox: str):
    """Precise download-size estimate for a bbox: the exact byte size of the
    basemap (z13-15 detail) + DEM extracts, via `pmtiles extract --dry-run`."""
    parts = parse_bbox(bbox)
    if not settings.pmtiles_source_url:
        raise HTTPException(400, "PMTILES_SOURCE_URL is not configured")
    _refuse_if_dns_blocked()
    # A failure is a 502 carrying pmtiles' reason, not a size of 0: the download
    # this estimate precedes reads the same sources and would fail the same way.
    try:
        return estimate_parts(parts)
    except region_downloader.DryRunFailed as e:
        raise HTTPException(502, str(e))


def estimate_bytes(bbox: list[float]) -> int:
    """Bytes a build of `bbox` would add to this server (services.remote_estimate)."""
    return estimate_parts(bbox)["bytes"]


def estimate_parts(bbox: list[float]) -> dict:
    from app.services import remote_estimate
    sources = []
    if settings.pmtiles_source_url:
        sources.append(("basemap", resolve_source_url(settings.pmtiles_source_url),
                        region_downloader._basemap_region_minzoom(), 15))
    if settings.dem_source_url:
        sources.append(("dem", settings.dem_source_url,
                        region_downloader._dem_region_minzoom(),
                        region_downloader._dem_region_maxzoom()))
    try:
        return remote_estimate.estimate(bbox, sources, region_registry.coverage())
    except Exception as e:
        logger.warning("Size estimate failed for %s: %s", bbox, e)
        raise region_downloader.DryRunFailed(f"could not read the map source: {e}")


def _refuse_if_dns_blocked() -> None:
    """Answer a blocked map host before anything is sized or downloaded.

    A DNS filter (AdGuard Home, Pi-hole) blocking a map host used to surface
    as a bare 502 from the estimate, and a download that failed without
    saying why; the person running the filter then had to work out which
    names to allow. This names what is blocked and everything to allow.
    """
    try:
        map_hosts.check_download_hosts()
    except map_hosts.MapHostsBlocked as e:
        logger.warning("Map hosts blocked by DNS: %s", e.blocked)
        raise HTTPException(502, str(e))


# How far a requested box may poke outside an existing one and still count as
# covered by it — about 11 m, which is smaller than a user can aim a drag.
_COVER_SLACK = 1e-4

def _covers(outer: list[float], inner: list[float]) -> bool:
    """True when `outer` contains `inner` (west, south, east, north)."""
    return (outer[0] <= inner[0] + _COVER_SLACK
            and outer[1] <= inner[1] + _COVER_SLACK
            and outer[2] >= inner[2] - _COVER_SLACK
            and outer[3] >= inner[3] - _COVER_SLACK)


def _already_have(bbox: list[float]) -> dict | None:
    """An existing area that answers this request, if there is one.

    * A queued or building area whose box covers this one: join it.
    * Installed areas that together cover this box: nothing to build. Areas
      overlap freely now, so coverage is their union, not one box — a request
      straddling two installed neighbours is already here. The answer names the
      area covering most of it.
    * A failed area with this same box: it is queued again and resumes from
      its last finished phase instead of a fresh area starting from nothing.

    Without this, asking for ground you already had downloaded it again, and
    the list filled with identically named copies.
    """
    from shapely.geometry import box
    want = box(*bbox)
    rows = region_registry.list_active()
    for r in rows:
        if r["status"] in _IN_PROGRESS_STATUSES and not r.get("stale") and _covers(r["bbox"], bbox):
            return r
    covered = region_registry.coverage()
    if covered is not None and covered.buffer(_COVER_SLACK).covers(want):
        installed = [r for r in rows if r["status"] == "installed"]
        return max(installed, key=lambda r: region_registry.region_geom(r).intersection(want).area)
    for r in rows:
        if r["status"] == "error" and _covers(r["bbox"], bbox) and _covers(bbox, r["bbox"]):
            region_registry.clear_attempts(r["id"])
            region_registry.update_status(r["id"], "queued", progress=0.0, detail="Waiting to start…")
            return region_registry.get(r["id"])
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

    from app.database import engine
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as conn:
        conn.execute(text("SELECT pg_advisory_lock(:k)"), {"k": _CREATE_LOCK_KEY})
        try:
            # Answer with what's already here before building anything. Every
            # answer hands back an area the caller can watch exactly as if it
            # had just been started.
            existing = _already_have(bbox)
            if existing is not None:
                status = "exists" if existing["status"] == "installed" else "downloading"
                map_builds.kick()
                return {"status": status, "region": existing}

            # Refused before a row exists, so a blocked host leaves no failed
            # area behind to delete — just the reason, where the box was drawn.
            _refuse_if_dns_blocked()

            if not name or name == "Untitled":
                name = geocode.suggest_region_name(bbox)
            region = region_registry.create(name=_distinct_name(name), bbox=bbox)
        finally:
            conn.execute(text("SELECT pg_advisory_unlock(:k)"), {"k": _CREATE_LOCK_KEY})

    # The build runs in the queue (map_builds), in whichever worker holds it.
    map_builds.kick()
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
def delete_region(region_id: int, user: User = Depends(require_auth)):
    # Admin only. A region is shared by every account and device on the
    # server; any signed-in user — or a phone's "Cancel" on a row it was only
    # saving locally — used to delete it for all of them (audit F4).
    if not user.is_admin:
        raise HTTPException(403, "Only an admin can remove map areas from the server")
    r = region_registry.get(region_id)
    if not r:
        raise HTTPException(404, "Region not found")
    status = r["status"]

    # Already being removed: the queue will finish it.
    if status in ("deleting", "cancelling"):
        return {"status": status}

    # Queued or building → cancel: the build sees the status and stops at its
    # next check, then removes what it published. Anything else → remove. The
    # queue does both (map_builds.teardown), one at a time with every other
    # map write, so a removal can never run under a live build.
    new = "cancelling" if status in _IN_PROGRESS_STATUSES else "deleting"
    region_registry.update_status(region_id, new, progress=0.0,
                                  detail="Cancelling…" if new == "cancelling" else "Removing…")
    map_builds.kick()
    return {"status": new}


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


# ── Start the basemap over ────────────────────────────────────────────────────

# The tile archives that make up the basemap: the global overviews and the
# masters merged from the regions. Fonts, sprites and trail logos are seeded
# assets the style needs, and routing segments and POI extracts are not the
# basemap; none of those are touched.
_BASEMAP_FILES = (
    "planet_basemap.pmtiles", "planet_z7.pmtiles", "planet_dem_z7.pmtiles",
    "master_basemap_detail.pmtiles", "master_dem.pmtiles", "master_overlay.pmtiles",
    "master_contours.pmtiles", "master_routes.pmtiles",
)


@router.post("/basemap/reset")
def reset_basemap(user=Depends(require_auth)):
    """Delete every basemap tile on this server, and fetch it again if map
    downloads are on.

    For a basemap that has gone wrong — a truncated archive, a region whose
    tiles no longer match its record — where deleting areas one by one does not
    reach the files that are broken. Admin-only, because the tiles are the
    instance's, shared by every account on it.

    Refused while anything is downloading: the pipelines write these files as
    they go, and deleting underneath one would leave it writing into a file
    that no longer exists. Each region keeps its record and is rebuilt from
    its own bounding box, so the areas come back as they were; with downloads
    off they are marked for re-download instead, which is what startup
    recovery does for a region whose files are missing.
    """
    import shutil
    from pathlib import Path

    from app.database import SessionLocal
    from app.models.user_settings import UserSettings
    from app.services.global_dem import start_dem_download
    from app.services.global_overview import start_map_download
    from app.services.global_routes import start_global_routes_build

    if not getattr(user, "is_admin", False):
        raise HTTPException(403, "Only an admin can delete the map data")

    active = region_registry.list_active()
    busy = [r for r in active if r["status"] in _IN_PROGRESS_STATUSES | {"cancelling", "deleting"}
            and r["status"] != "queued" and not r.get("stale")]
    busy_global = [d for d in global_download_tracker.get_all() if d.get("status") == "downloading"]
    if busy or busy_global:
        raise HTTPException(409, "A map download is still running — wait for it to finish or cancel it first")

    data_dir = Path(settings.map_data_dir)
    removed = 0
    for name in _BASEMAP_FILES:
        f = data_dir / name
        if f.exists():
            f.unlink()
            removed += 1
    for r in active:
        region_registry.delete_region_files(r["id"])
    shutil.rmtree(data_dir / "work", ignore_errors=True)

    db = SessionLocal()
    try:
        us = db.query(UserSettings).first()
        enabled = bool(us and us.map_enabled)
    finally:
        db.close()

    if enabled:
        start_map_download()
        if settings.dem_source_url:
            start_dem_download()
        # The long-trail overview is one of the files deleted above, and
        # nothing else rebuilds it short of a restart.
        start_global_routes_build()
        # Every area goes back in the queue and is built again from its own
        # box, one at a time, as any download is.
        for r in active:
            region_registry.reset_phases(r["id"])
            region_registry.set_owned(r["id"], None)
            region_registry.update_status(r["id"], "queued", progress=0.0, detail="Waiting to download again…")
        map_builds.kick()
    else:
        for r in active:
            region_registry.update_status(
                r["id"], "error", error="Map data was deleted — re-download this area")
    # Let go-pmtiles drop its handles on the files that are gone.
    map_store.write_reload_trigger()
    logger.warning("Basemap reset by user %s: %d archive(s), %d region(s); re-download=%s",
                   user.id, removed, len(active), enabled)
    return {"removed_files": removed, "regions": len(active), "redownloading": enabled}
