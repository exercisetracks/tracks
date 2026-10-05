# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download a region from a remote PMTiles source via go-pmtiles extract.

Spawns go-pmtiles as a subprocess to extract vector tiles (z8-15) for
a given bounding box. Progress is tracked via stdout parsing.
"""

from __future__ import annotations

import logging
import os
import re
import subprocess
import tempfile
from pathlib import Path

from app.config import settings
from app.services import region_registry
from app.services.pmtiles_extract import resolve_source_url, run_pmtiles_with_progress

logger = logging.getLogger(__name__)

# go-pmtiles `extract --dry-run` prints e.g. "... for an archive size of 293 kB".
_SIZE_RE = re.compile(r"archive size of ([\d.]+)\s*([kMGT]?i?B)", re.IGNORECASE)
_UNIT = {"b": 1, "kb": 1_000, "mb": 1_000_000, "gb": 1_000_000_000}


def dry_run_size(source_url: str, bbox: str, minzoom: int, maxzoom: int) -> int:
    """Return the exact archive byte size a bbox extract WOULD produce, without
    downloading. Uses `pmtiles extract --dry-run`, which reads only the source
    directory and sums the matching tiles' lengths. 0 on failure.

    The output path is required by the CLI but never written (``--dry-run``); a
    unique temp name avoids any clash between concurrent estimate requests.
    """
    fd, out_path = tempfile.mkstemp(suffix=".pmtiles", prefix="_estimate_dryrun_")
    os.close(fd)
    Path(out_path).unlink(missing_ok=True)   # CLI wants a free path to (not) write
    cmd = [
        "pmtiles", "extract", source_url, out_path,
        f"--bbox={bbox}", "--minzoom", str(minzoom), "--maxzoom", str(maxzoom),
        "--dry-run",
    ]
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=90)
    except Exception:
        logger.exception("dry-run size failed for %s", source_url)
        return 0
    finally:
        Path(out_path).unlink(missing_ok=True)
    m = _SIZE_RE.search((r.stderr or "") + "\n" + (r.stdout or ""))
    if not m:
        return 0
    return int(float(m.group(1)) * _UNIT.get(m.group(2).lower().replace("ib", "b"), 1))


def _basemap_region_minzoom() -> int:
    """Regional basemap extracts start where the global overview ends.

    With the z0-12 global basemap present, a region only needs z13-15 —
    the overview already covers z0-12, so z12 tiles would be discarded
    during merge (``_build_master`` applies ``--minzoom=13``).
    Before that upgrade — when only the legacy z0-7 overview exists — regions
    must still cover z8-15 or there'd be a z8-11 gap in the downloaded area.
    """
    if (Path(settings.map_data_dir) / "planet_basemap.pmtiles").exists():
        return 13
    return 8


def download_region(region_id: int, bbox: list[float], cancel=None) -> None:
    """Download vector basemap tiles for a region bbox.

    Runs go-pmtiles extract for the basemap source.
    Updates the registry status throughout. Does NOT trigger a merge —
    that's the caller's responsibility. ``cancel`` (a CancelToken) makes the
    extract abortable; a user abort re-raises DownloadCancelled instead of
    marking the region errored.
    """
    from app.services.download_cancel import DownloadCancelled

    src_dir = region_registry.source_dir(region_id)
    src_dir.mkdir(parents=True, exist_ok=True)

    west, south, east, north = bbox
    bbox_str = f"{west},{south},{east},{north}"

    region_registry.update_status(region_id, "downloading", progress=0.0,
                                  detail="Starting download…")
    try:
        _extract_source(
            resolve_source_url(settings.pmtiles_source_url),
            str(src_dir / "source.pmtiles"),
            bbox_str,
            minzoom=_basemap_region_minzoom(), maxzoom=15,
            on_progress=lambda pct, detail: region_registry.update_status(
                region_id, "downloading", progress=float(pct), detail=detail),
            cancel=cancel,
        )
    except DownloadCancelled:
        raise
    except Exception as e:
        logger.exception("Basemap extract failed for %s", region_id)
        region_registry.update_status(region_id, "error", error=str(e))
        return

    region_registry.update_status(
        region_id, "downloading", progress=100.0,
        size_bytes=_dir_size(src_dir), detail="Download complete",
    )


def _dem_region_minzoom() -> int:
    """DEM extracts start above the global DEM overview's max zoom (z7).

    The ``planet_dem_z7`` overview covers z0-7, so z7 region tiles would
    be discarded during merge (``_build_master`` applies ``--minzoom=8``).
    Skip downloading them entirely.
    """
    # The overview is only present when global downloads have been enabled.
    if (Path(settings.map_data_dir) / "planet_dem_z7.pmtiles").exists():
        return 8
    return 7


def _dem_region_maxzoom() -> int:
    """DEM regional extract max zoom capped to the source's actual max.

    Mapterhorn's global DEM archive tops out at z12, so extracting beyond
    z12 wastes HTTP range requests (each 404/204 = a round-trip).
    Cache the value so the check runs once per process lifetime.
    """
    global _cached_dem_maxzoom
    if _cached_dem_maxzoom is not None:
        return _cached_dem_maxzoom
    if not settings.dem_source_url:
        _cached_dem_maxzoom = 12
        return 12
    try:
        r = subprocess.run(
            ["pmtiles", "show", settings.dem_source_url],
            capture_output=True, text=True, timeout=30,
        )
        for line in (r.stdout or "").splitlines():
            if line.startswith("max zoom:"):
                _cached_dem_maxzoom = int(line.split(":")[1].strip())
                return _cached_dem_maxzoom
    except Exception:
        pass
    _cached_dem_maxzoom = 12
    return 12

_cached_dem_maxzoom: int | None = None


def download_region_dem(region_id: int, bbox: list[float], cancel=None) -> None:
    """Download DEM elevation tiles for a region bbox.

    Downloads from settings.dem_source_url for the same geographic
    area.  Min zoom starts above the global overview; max zoom is
    capped to the source archive's actual max (z12 for Mapterhorn)
    to avoid wasted 404 round-trips.  Does NOT trigger a merge.
    ``cancel`` makes the extract abortable.
    """
    from app.services.download_cancel import DownloadCancelled

    if not settings.dem_source_url:
        return

    src_dir = region_registry.source_dir(region_id)
    src_dir.mkdir(parents=True, exist_ok=True)

    west, south, east, north = bbox
    bbox_str = f"{west},{south},{east},{north}"

    region_registry.update_status(region_id, "downloading_dem", size_bytes=0,
                                  progress=0.0, detail="Starting terrain download…")

    try:
        _extract_source(
            settings.dem_source_url,
            str(src_dir / "dem.pmtiles"),
            bbox_str,
            minzoom=_dem_region_minzoom(), maxzoom=_dem_region_maxzoom(),
            on_progress=lambda pct, detail: region_registry.update_status(
                region_id, "downloading_dem", progress=float(pct), detail=detail),
            cancel=cancel,
        )
    except DownloadCancelled:
        raise
    except Exception as e:
        logger.exception("DEM extract failed for %s", region_id)
        region_registry.update_status(region_id, "error", error=str(e))
        return

    region_registry.update_status(
        region_id, "downloading_dem", progress=100.0,
        size_bytes=_dir_size(src_dir), detail="Terrain download complete",
    )


# How long a go-pmtiles extract may go without a word before it is presumed dead.
_STALL_SECONDS = 900


def _extract_source(source_url: str, output_path: str, bbox: str,
                    minzoom: int, maxzoom: int,
                    on_progress=None, cancel=None) -> None:
    cmd = [
        "pmtiles", "extract",
        source_url,
        output_path,
        f"--bbox={bbox}",
        "--minzoom", str(minzoom),
        "--maxzoom", str(maxzoom),
    ]
    logger.info("Running: %s", " ".join(cmd))
    if on_progress is None:
        on_progress = lambda pct, detail: None
    # Fifteen minutes of *silence*, not of work — see run_pmtiles_with_progress.
    # A state-sized extract streams for over an hour and says something every
    # few seconds while it does; a connection that has died says nothing, and
    # that is what this catches.
    run_pmtiles_with_progress(cmd, on_progress, timeout=_STALL_SECONDS,
                              output_path=output_path, cancel=cancel)
    logger.info("Extract complete: %s", output_path)


def _dir_size(path: Path) -> int:
    total = 0
    for f in path.rglob("*"):
        if f.is_file():
            total += f.stat().st_size
    return total
