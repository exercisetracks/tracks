# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Route snapping API — delegates to BRouter for trail-aware routing."""

import json
import math
import re
from datetime import date as _date
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import FileResponse
from sqlalchemy import text

from app.auth import require_auth
from app.config import settings
from app.database import SessionLocal
from app.models.activity import User
from app.models.user_settings import UserSettings
from app.routes.regions.bbox import parse_bbox
from app.services import brouter_client, brouter_downloader, elevation_sampler, weather, wildfire
from app.services.route_profile import build_profile

router = APIRouter(prefix="/maps", tags=["maps"])


@router.post("/route/snap")
async def snap_route(data: dict):
    coordinates = data.get("coordinates")
    profile = data.get("profile", "trekking")

    if not coordinates or len(coordinates) < 2:
        raise HTTPException(400, "At least 2 coordinates required")

    # Ensure rd5 segments exist for the route area
    lngs = [c[0] for c in coordinates]
    lats = [c[1] for c in coordinates]
    bbox = [min(lngs), min(lats), max(lngs), max(lats)]
    brouter_downloader.ensure_segments(bbox)

    try:
        result = await brouter_client.snap_route(coordinates, profile)
        return result
    except Exception as e:
        raise HTTPException(500, f"BRouter routing failed: {e}")


# ── Offline routing data ─────────────────────────────────────────────────────
#
# A phone in a valley cannot POST /route/snap, and "plan a route" is exactly the
# thing you want there. BRouter's routing core is a ~300 KB Java library that
# reads the same rd5 segments this server already keeps, so the phone runs its
# own copy of the engine — these endpoints hand it the data, nothing more.
#
# The names are matched against a pattern rather than joined blindly: they end
# up as path components, and `..%2F..%2Fetc%2Fpasswd` is what a path parameter
# that opens files is for.
_SEGMENT_NAME = re.compile(r"^[EW]\d{1,3}_[NS]\d{1,2}\.rd5$")

# Segments are 5° cells, so a request this wide covers at most a 2×2 block.
# Without a cap, a bbox spanning a continent would have the server fetch tens of
# gigabytes from brouter.de before answering — the unbounded-work trap that
# `/route/snap` hits too, which is why its client caps the span at 3° as well.
MAX_OFFLINE_BBOX_DEGREES = 3.0


@router.get("/route/offline/manifest")
def offline_routing_manifest(bbox: str):
    """What a phone must download to route inside `bbox` with no network.

    Fetching any missing segments is part of answering: the phone asks for an
    area the server may never have routed through itself, and a manifest listing
    files that 404 on download would be worse than a slow first request.
    """
    west, south, east, north = parse_bbox(bbox)
    if (east - west) > MAX_OFFLINE_BBOX_DEGREES or (north - south) > MAX_OFFLINE_BBOX_DEGREES:
        raise HTTPException(
            400,
            f"Area too large for offline routing data "
            f"(max {MAX_OFFLINE_BBOX_DEGREES}° per side)",
        )

    brouter_downloader.ensure_segments([west, south, east, north])
    profiles = brouter_downloader.ensure_profiles()

    files = []
    profile_dir = brouter_downloader.profiles_dir()
    for name in profiles:
        path = profile_dir / name
        if path.exists():
            files.append({"kind": "profile", "name": name, "bytes": path.stat().st_size})

    segment_dir = brouter_downloader.segments_dir()
    for name in brouter_downloader.segment_names_for_bbox([west, south, east, north]):
        path = segment_dir / name
        # Ocean cells have no rd5 at all and never will. Silently omitting them
        # is correct — the phone should not be told to download a 404.
        if path.exists():
            files.append({"kind": "segment", "name": name, "bytes": path.stat().st_size})

    return {"files": files, "total_bytes": sum(f["bytes"] for f in files)}


@router.get("/route/offline/segments/{name}")
def offline_routing_segment(name: str):
    if not _SEGMENT_NAME.match(name):
        raise HTTPException(400, "Not a segment name")
    path = brouter_downloader.segments_dir() / name
    if not path.exists():
        raise HTTPException(404, "Segment not held")
    return FileResponse(path, media_type="application/octet-stream", filename=name)


@router.get("/route/offline/profiles/{name}")
def offline_routing_profile(name: str):
    if name not in brouter_downloader.OFFLINE_PROFILES:
        raise HTTPException(400, "Not an offline profile")
    path = brouter_downloader.profiles_dir() / name
    if not path.exists():
        # One more try before giving up: the profile dir is populated lazily and
        # a phone can reach this endpoint without having called the manifest.
        brouter_downloader.ensure_profiles()
    if not path.exists():
        raise HTTPException(404, "Profile unavailable")
    return FileResponse(path, media_type="application/octet-stream", filename=name)


@router.post("/route/elevation")
def route_elevation(data: dict):
    """Sample DEM elevation (metres) at each [lng, lat] — for un-snapped routes
    whose geometry has no elevation (BRouter-snapped routes already carry it)."""
    coordinates = data.get("coordinates")
    if not coordinates or len(coordinates) < 2:
        raise HTTPException(400, "At least 2 coordinates required")
    return {"elevations": elevation_sampler.sample_elevations(coordinates)}


@router.get("/route/{route_id}")
def route_detail(route_id: str, section: str | None = Query(None)):
    """Long-trail detail: parent identity, its sections (name + distance), and an
    elevation profile for the selected section (or the longest one by default).

    Geometry/distances come from the per-route index written by the global-routes
    build (``map-data/routes/{id}.json``); elevation is DEM-sampled on demand."""
    if not route_id.isdigit():
        raise HTTPException(400, "Invalid route id")
    path = Path(settings.map_data_dir) / "routes" / f"{route_id}.json"
    if not path.exists():
        raise HTTPException(404, "Route not found")
    try:
        idx = json.loads(path.read_text())
    except Exception:
        raise HTTPException(500, "Route index unreadable")

    secs = idx.get("sections", [])
    by_id = {s["id"]: s for s in secs}
    chosen = by_id.get(section)
    if chosen is None:
        # Default to the longest section (most interesting profile).
        chosen = max(secs, key=lambda s: s.get("distance_m", 0)) if secs else None

    # Profiles are precomputed at startup (precompute_route_profiles); fall back to
    # an on-demand build if this section hasn't been cached yet.
    profile = None
    if chosen is not None:
        profile = chosen.get("profile")
        if profile is None:
            profile = build_profile(chosen["coords"], chosen.get("distance_m"))

    return {
        "id": idx.get("id"),
        "name": idx.get("name"),
        "abbr": idx.get("abbr"),
        "color": idx.get("color"),
        "network": idx.get("network"),
        "distance_m": idx.get("distance_m"),
        "sections": [{"id": s["id"], "name": s["name"],
                      "distance_m": s.get("distance_m", 0)} for s in secs],
        "selected_section": chosen["id"] if chosen else None,
        "selected_name": chosen["name"] if chosen else None,
        "profile": profile,
    }


def _require_wildfire_enabled(user_id: int) -> None:
    """403 unless the user has opted in to the live wildfire/smoke feeds —
    the explicit gate that keeps third-party queries strictly opt-in."""
    db = SessionLocal()
    try:
        us = db.query(UserSettings).filter_by(user_id=user_id).first()
        if us is None or not us.wildfire_enabled:
            raise HTTPException(
                403, "Live wildfire data is disabled — enable it in Settings")
    finally:
        db.close()


@router.get("/wildfires")
def wildfires(user: User = Depends(require_auth)):
    """Active wildfire incident points + current perimeters (NIFC WFIGS,
    nationwide — no location is sent upstream). Cached server-side 10 min."""
    _require_wildfire_enabled(user.id)
    return {
        "fires": wildfire.fetch_incidents(),
        "perimeters": wildfire.fetch_perimeters(),
    }


@router.get("/wildfires/smoke")
def wildfire_smoke(user: User = Depends(require_auth)):
    """NOAA HMS smoke plume polygons (nationwide daily analysis). Cached 30 min."""
    _require_wildfire_enabled(user.id)
    return wildfire.fetch_smoke()


def _weather_enabled(user_id: int) -> bool:
    """Whether the user allows point forecasts — the privacy toggle that says
    their coordinates may be sent to Open-Meteo.

    Unlike wildfire, which only ever sends nationwide feed requests, every
    forecast carries the tapped (or trained-at) location, so this gates every
    endpoint that calls the provider. No settings row yet means the column
    default, which is on.
    """
    db = SessionLocal()
    try:
        us = db.query(UserSettings).filter_by(user_id=user_id).first()
        return us is None or bool(us.weather_enabled)
    finally:
        db.close()


def _nearest_poi(lat: float, lon: float, radius_deg: float = 0.03) -> dict | None:
    """Nearest named POI within ~radius_deg of (lat, lon), or None."""
    db = SessionLocal()
    try:
        row = db.execute(text("""
            SELECT name, kind, kind_detail, ele_ft, lat, lng,
                   ((lat - :lat) * (lat - :lat) +
                    (lng - :lon) * (lng - :lon) * :coslat2) AS d2
            FROM poi_search
            WHERE name <> ''
              -- Containment, so idx_poi_search_geo can serve it; see the note
              -- on the same rewrite in routes/poi/endpoints.py.
              AND point(lng, lat) <@ box(
                    point(:lon - :r, :lat - :r), point(:lon + :r, :lat + :r))
            ORDER BY d2 ASC
            LIMIT 1
        """), {
            "lat": lat, "lon": lon, "r": radius_deg,
            # longitude degrees shrink with latitude; weight so distance is fair
            "coslat2": max(0.05, math.cos(math.radians(lat)) ** 2),
        }).fetchone()
        if not row:
            return None
        m = row._mapping
        # metres ≈ sqrt(d2) * 111320 (close enough for a "how far" hint)
        dist_m = (m["d2"] ** 0.5) * 111320
        return {
            "name": m["name"], "kind": m["kind"], "kind_detail": m["kind_detail"],
            "ele_ft": m["ele_ft"], "lat": m["lat"], "lng": m["lng"],
            "distance_m": round(dist_m),
        }
    except Exception:
        return None
    finally:
        db.close()


@router.get("/point/hourly")
def point_hourly(lat: float = Query(..., ge=-90, le=90),
                 lon: float = Query(..., ge=-180, le=180),
                 date: str = Query(..., pattern=r"^\d{4}-\d{2}-\d{2}$"),
                 user: User = Depends(require_auth)):
    """The hour-by-hour forecast for one day at one point.

    Split from ``/point`` rather than folded into it because the two are asked
    at very different rates: every tap on the map fetches point info, and almost
    none of those taps go on to open a day. Carrying 168 hourly readings in that
    response would multiply the cost of the common case several times over to
    pre-answer a question usually never asked.

    ``forecast_days`` is stretched to reach the requested date because
    Open-Meteo counts days forward from today rather than taking a range, and
    the daily strip this is opened from runs a week out.

    The phone now asks Open-Meteo itself (mobile core/weather/OpenMeteo.kt);
    this stays for phones on releases from before that.
    """
    try:
        wanted = _date.fromisoformat(date)
    except ValueError:
        raise HTTPException(400, "Invalid date")

    # Local dates, so "today" means what it means where the user is standing.
    # A day ahead of the requested one is enough slack for the point being in a
    # timezone whose date has already rolled over relative to the server's.
    days = (wanted - _date.today()).days + 2
    if days < 1 or days > 16:
        raise HTTPException(400, "That day is outside the forecast window")

    # Turned off reads as "nothing to show" rather than an error: the clients
    # already render an empty day, and the toggle is the user's own choice.
    if not _weather_enabled(user.id):
        return {"date": date, "hours": [], "weather_disabled": True}

    forecast = weather.fetch_point_forecast(lat, lon, days=days, include_hourly=True)
    if not forecast:
        return {"date": date, "hours": []}

    # Open-Meteo timestamps are local wall-clock (timezone=auto), so a prefix
    # match on the date is the whole filter — no conversion, and no risk of
    # slicing somebody's evening into the next day.
    hours = [h for h in forecast.get("hourly", []) if str(h.get("time", "")).startswith(date)]
    return {"date": date, "timezone": forecast.get("timezone"), "hours": hours}


@router.get("/point/weather")
def point_weather(lat: float = Query(..., ge=-90, le=90),
                  lon: float = Query(..., ge=-180, le=180),
                  user: User = Depends(require_auth)):
    """Just the forecast for a point.

    Separate from ``/point`` so a client can put a panel on screen before this
    has answered. The forecast is the one part of point info that leaves the
    building — Open-Meteo, over the internet, from a server that may itself be
    on a slow link — and bundling it meant the elevation and the place name,
    both of which are local lookups measured in milliseconds, arrived at the
    speed of the slowest thing in the response.

    Only phones on older releases call this; current ones ask Open-Meteo
    directly.
    """
    if not _weather_enabled(user.id):
        return {"weather": None, "weather_disabled": True}
    return {"weather": weather.fetch_point_forecast(lat, lon)}


@router.get("/point")
def point_info(lat: float = Query(..., ge=-90, le=90),
               lon: float = Query(..., ge=-180, le=180),
               weather_included: bool = Query(True, alias="weather"),
               user: User = Depends(require_auth)):
    """Info about an arbitrary clicked map point: elevation, weather forecast,
    and the nearest named POI. (Nearby trails are read client-side from the
    rendered vector tiles, so they aren't duplicated here.)

    ``weather=false`` drops the forecast, for callers that fetch it separately
    from ``/point/weather`` so their panel can open immediately. It defaults on,
    because the web app asks for this in one request and a default that changed
    its behaviour would be a silent regression there.
    """
    # The rest of the panel is local lookups, so a disabled forecast drops only
    # that part; `weather_disabled` lets the panel say why it is missing rather
    # than reporting the provider as unavailable.
    weather_disabled = weather_included and not _weather_enabled(user.id)
    elev_m = None
    try:
        vals = elevation_sampler.sample_elevations([[lon, lat]])
        elev_m = vals[0] if vals else None
    except Exception:
        elev_m = None

    return {
        "lat": lat,
        "lon": lon,
        "elevation_m": round(elev_m, 1) if elev_m is not None else None,
        "elevation_ft": round(elev_m * 3.280839895) if elev_m is not None else None,
        "nearest_poi": _nearest_poi(lat, lon),
        # Land type is derived client-side from the already-downloaded basemap
        # tiles (usePointInfo), so no runtime OSM/Overpass call is made here.
        "weather": (weather.fetch_point_forecast(lat, lon)
                    if weather_included and not weather_disabled else None),
        "weather_disabled": weather_disabled,
    }
