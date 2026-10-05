# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""HTTP client for the BRouter trail-snapping routing engine.

Sends a GET request to the BRouter standalone server with waypoints
and returns the snapped route as a GeoJSON FeatureCollection.
"""

from __future__ import annotations

import logging

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


async def snap_route(
    coordinates: list[list[float]],
    profile: str = "trekking",
) -> dict:
    """Snap a list of [lng, lat] waypoints to trails/roads via BRouter.

    Returns a GeoJSON FeatureCollection with the snapped LineString.
    """
    lonlats = "|".join(f"{lng},{lat}" for lng, lat in coordinates)
    params = {
        "lonlats": lonlats,
        "profile": profile,
        "format": "geojson",
        "alternativeidx": 0,
    }

    async with httpx.AsyncClient(timeout=30) as client:
        resp = await client.get(
            f"{settings.brouter_url}/brouter",
            params=params,
        )
        resp.raise_for_status()
        data = resp.json()

    _log_route_stats(data)
    return data


def _log_route_stats(geojson: dict) -> None:
    features = geojson.get("features", [])
    if not features:
        return
    coords = features[0].get("geometry", {}).get("coordinates", [])
    if len(coords) < 2:
        return

    dist_km = _haversine_distance(coords)
    logger.info("BRouter snapped route: %d points, %.1f km", len(coords), dist_km)


def _haversine_distance(coords: list[list[float]]) -> float:
    import math
    total = 0.0
    for i in range(len(coords) - 1):
        lon1, lat1 = math.radians(coords[i][0]), math.radians(coords[i][1])
        lon2, lat2 = math.radians(coords[i + 1][0]), math.radians(coords[i + 1][1])
        dlon = lon2 - lon1
        dlat = lat2 - lat1
        a = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
        total += 6371 * 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return total
