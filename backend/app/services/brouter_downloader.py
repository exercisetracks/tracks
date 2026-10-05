# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Download BRouter rd5 routing segments for a given bounding box.

BRouter splits the world into 5°×5° grid cells. File naming uses:
  E{lon} for east longitudes, W{abs(lon)} for west
  N{lat} for north latitudes, S{abs(lat)} for south

The same files serve two readers. The `brouter` container routes out of
`segments4/` for the browser and for any phone with signal; the *phone* reads
its own copy of the very same rd5 when it has none, because BRouter's routing
core is a small Java library that runs on Android as happily as it does in the
container. That is why this module also knows about the profile directory: an
rd5 is unreadable without the `lookups.dat` that names its tags, and a route is
unplannable without a `.brf` to weigh the ways, so all three travel together.
"""

from __future__ import annotations

import logging
from pathlib import Path

import httpx

from app.config import settings

logger = logging.getLogger(__name__)

SEGMENTS_URL = "https://brouter.de/brouter/segments4"

# Profiles come from the BRouter source tree at the release tag the server
# container runs (brouter/Dockerfile) and the phone vendors
# (mobile/routing-brouter/VENDORED.md), so all three weigh trails identically.
# Not master: master moves ahead of any release, and a lookups.dat newer than
# the engine reading it is how a phone stops routing after an unrelated push
# upstream. Bump all three together.
BROUTER_VERSION = "1.7.10"
PROFILES_URL = f"https://raw.githubusercontent.com/abrensch/brouter/v{BROUTER_VERSION}/misc/profiles2"

# What a phone needs to route offline, and no more. `lookups.dat` is the tag
# dictionary the rd5 files are encoded against and is not optional; `trekking`
# is the only profile mobile asks for. Adding a profile here is a one-line
# change, but each one is a download the user pays for.
OFFLINE_PROFILES = ("lookups.dat", "trekking.brf")


def segments_dir() -> Path:
    return Path(settings.map_data_dir) / "brouter" / "segments4"


def profiles_dir() -> Path:
    return Path(settings.map_data_dir) / "brouter" / "profiles2"


def segment_names_for_bbox(bbox: list[float]) -> list[str]:
    """The rd5 filenames covering `bbox`, whether or not they exist on disk."""
    return [_cell_filename(lon, lat) for lon, lat in _cells_for_bbox(bbox)]


def ensure_profiles() -> list[str]:
    """Fetch the profile files the phone routes with, once, into map-data.

    Returns the names that are present afterwards — a name missing from the
    result means upstream could not be reached and the caller has nothing to
    offer, which is a better answer than serving a truncated `lookups.dat` that
    would make every offline route fail with a decoding error instead.
    """
    dest = profiles_dir()
    dest.mkdir(parents=True, exist_ok=True)

    present = []
    for name in OFFLINE_PROFILES:
        fpath = dest / name
        if fpath.exists() and fpath.stat().st_size > 0:
            present.append(name)
            continue
        try:
            resp = httpx.get(f"{PROFILES_URL}/{name}", timeout=60)
            resp.raise_for_status()
            # Written whole. A half-written lookups.dat left by a dropped
            # connection would look valid to the `exists()` check above forever.
            tmp = fpath.with_suffix(fpath.suffix + ".part")
            tmp.write_bytes(resp.content)
            tmp.replace(fpath)
            present.append(name)
            logger.info("Downloaded BRouter profile: %s", name)
        except (httpx.HTTPError, OSError):
            logger.warning("Could not fetch BRouter profile: %s", name)
    return present


def ensure_segments(bbox: list[float]) -> list[str]:
    dest = segments_dir()
    dest.mkdir(parents=True, exist_ok=True)

    cells = _cells_for_bbox(bbox)
    downloaded = []

    for lon, lat in cells:
        fname = _cell_filename(lon, lat)
        fpath = dest / fname
        if fpath.exists():
            continue
        url = f"{SEGMENTS_URL}/{fname}"
        try:
            resp = httpx.get(url, timeout=60)
            resp.raise_for_status()
            fpath.write_bytes(resp.content)
            downloaded.append(fname)
            logger.info("Downloaded rd5: %s", fname)
        except httpx.HTTPError:
            logger.debug("rd5 not available (might be ocean): %s", fname)

    return downloaded


def _cell_filename(lon: int, lat: int) -> str:
    ew = f"W{abs(lon)}" if lon < 0 else f"E{lon}"
    ns = f"S{abs(lat)}" if lat < 0 else f"N{lat}"
    return f"{ew}_{ns}.rd5"


def _cells_for_bbox(bbox: list[float]) -> list[tuple[int, int]]:
    west, south, east, north = bbox
    cells = []
    for lon in range(int(west // 5) * 5, int(east // 5) * 5 + 5, 5):
        for lat in range(int(south // 5) * 5, int(north // 5) * 5 + 5, 5):
            cells.append((lon, lat))
    return cells
