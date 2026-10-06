# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Browser device-sync API (prefix /device-sync).

User-authenticated counterparts of the garmin-sync container's secret-guarded
push endpoints, so the in-browser WebUSB/File-System-Access sync can push
courses, workouts, race plans, and the training-calendar schedule to a watch
plugged into the *user's* machine. All heavy lifting (queries, FIT encoding,
state marking) is shared with the cable path via the per-user helpers in
training_plan.sync and courses_api.sync.

Each pushable item carries the on-device destination folder so the frontend
stays protocol-dumb:
  workouts / race plans / schedule -> GARMIN/NewFiles (watch ingests on reboot
                                      or activity start)
  courses                          -> GARMIN/Courses
  waypoints                        -> GARMIN/Locations (one Locations.fit for
                                      every saved place, not one per point)
  workout deletions                <- GARMIN/Workouts (watch moves ingested
                                      workouts there)
  course deletions                 <- GARMIN/Courses
"""
import base64
import logging
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from app.auth import require_auth
from app.database import get_db
from app.models.activity import Activity, DataPoint, User
from app.models.user_settings import UserSettings
from app.services import public_fetch, weather
from app.services.crypto_context import require_crypto_session
from app.api.training_plan.sync import (
    _apply_mark_deleted,
    _apply_mark_uploaded,
    _delete_items_for_user,
    _schedule_bundle_for_user,
    _schedule_fit_for_user,
    _upload_items_for_user,
)
from app.routes.courses_api.sync import (
    _apply_mark_course_deleted,
    _apply_mark_course_uploaded,
    _course_delete_items,
    _course_upload_items,
)
from app.routes.waypoints_sync import (
    LOCATIONS_FOLDER,
    _apply_mark_waypoint_deleted,
    _apply_mark_waypoint_uploaded,
    _waypoint_clear_item,
    _waypoint_delete_items,
    _waypoint_upload_items,
)

router = APIRouter(prefix="/device-sync", tags=["device-sync"])


# WMO codes (Open-Meteo) to the OpenWeatherMap ids the watch protocol speaks.
#
# Not a lossless mapping and does not need to be: Garmin renders one of a dozen
# glyphs, so what has to survive is the *category* — clear, cloud, rain, snow,
# storm — and roughly how hard it is coming down. Anything unrecognised becomes
# "cloudy", which is the least wrong thing to draw when you do not know.
_WMO_TO_OWM = {
    0: 800, 1: 801, 2: 802, 3: 804,
    45: 741, 48: 741,
    51: 300, 53: 301, 55: 302, 56: 511, 57: 511,
    61: 500, 63: 501, 65: 502, 66: 511, 67: 511,
    71: 600, 73: 601, 75: 602, 77: 601,
    80: 520, 81: 521, 82: 522,
    85: 620, 86: 622,
    95: 200, 96: 201, 99: 202,
}

_WMO_LABEL = {
    0: "Clear", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast",
    45: "Fog", 48: "Fog", 51: "Light drizzle", 53: "Drizzle",
    55: "Heavy drizzle", 56: "Freezing drizzle", 57: "Freezing drizzle",
    61: "Light rain", 63: "Rain", 65: "Heavy rain",
    66: "Freezing rain", 67: "Freezing rain",
    71: "Light snow", 73: "Snow", 75: "Heavy snow", 77: "Snow grains",
    80: "Showers", 81: "Showers", 82: "Heavy showers",
    85: "Snow showers", 86: "Snow showers",
    95: "Thunderstorm", 96: "Thunderstorm", 99: "Thunderstorm",
}


@router.get("/weather")
def watch_weather(
    db: Session = Depends(get_db),
    user: User = Depends(require_auth),
    _key=Depends(require_crypto_session),
):
    """The forecast to push to the watch, for a phone that has no other source.

    The watch asks for weather roughly once a minute and Tracks had nothing to
    answer with unless a separate weather app was configured to broadcast to it
    — an arrangement that is invisible when it is not set up, since the watch
    simply shows an empty glance forever.

    Location is where the phone last was, as it synced it into the settings
    row — the same position the phone forecasts for when it asks Open-Meteo
    itself. Failing that (no phone has been opened with location permission
    yet), the start of the most recent activity with GPS: very nearly the
    right answer for "where does this person train", and a stale-by-one-trip
    forecast still beats an empty glance.

    404 rather than an empty body when there is nothing to say, so the client can
    tell "no forecast for you" from "a forecast of nothing".

    Current phones locate and fetch this forecast themselves (mobile
    core/weather/OpenMeteo.kt), so it works where the phone has signal but no
    route to this server. They call this only as a fallback, when none of the
    phone's own files has a position; older releases call it every time.
    """
    # The location sent is where this person trains, so the same privacy toggle
    # that gates race and map forecasts gates this one. 403, as for wildfire:
    # distinct from "nothing to locate by" and "provider down", and the phone
    # treats any failure as "keep what it had".
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is not None and not us.weather_enabled:
        raise HTTPException(
            status_code=403, detail="Weather is disabled — enable it in Settings")

    point = _synced_phone_point(us) or _latest_activity_point(user, db)
    if point is None:
        raise HTTPException(
            status_code=404,
            detail="No activity with GPS yet, so there is no location to forecast for",
        )

    lat, lon = point
    forecast = weather.fetch_point_forecast(lat, lon, days=5, include_hourly=True)
    if not forecast or not forecast.get("current"):
        raise HTTPException(status_code=503, detail="Weather provider is unavailable")

    current = forecast["current"]
    if current.get("temperature_c") is None:
        # `fetch_point_forecast` renames Open-Meteo's fields, and reading the
        # provider's names here produced a confident 0 C in August rather than
        # an error. A missing temperature is the one field worth refusing on.
        raise HTTPException(status_code=503, detail="Weather provider returned no temperature")
    daily = forecast.get("daily") or []
    today = daily[0] if daily else {}
    code = int(current.get("weather_code") or 0)

    return {
        "location": _place_name(lat, lon),
        # The watch attaches conditions to a position; a forecast without one
        # leaves its glance on "waiting for weather" no matter how many times
        # the data is accepted. See GarminWeatherEncoder.
        "lat": round(lat, 5),
        "lon": round(lon, 5),
        "timestamp": int(datetime.now(timezone.utc).timestamp()),
        "current_temp_c": round(current.get("temperature_c") or 0),
        "today_min_temp_c": round(today.get("temp_min_c") or 0),
        "today_max_temp_c": round(today.get("temp_max_c") or 0),
        "current_condition": _WMO_LABEL.get(code, "Cloudy"),
        "current_condition_code": _WMO_TO_OWM.get(code, 804),
        # Open-Meteo is asked for m/s (see services/weather.py); the watch
        # protocol carries km/h.
        "wind_speed_kmh": round((current.get("wind_mps") or 0) * 3.6, 1),
        "wind_direction_degrees": round(current.get("wind_direction") or 0),
        "humidity_percent": round(current.get("humidity_pct") or 0),
        # The next half-day, hour by hour. The watch's weather glance has an
        # hourly strip and had nothing to fill it: Tracks only ever sent current
        # conditions and daily highs, so the strip stayed blank while the rest of
        # the screen worked.
        "hourly": _hourly_rows(forecast, _HOURLY_HOURS),
        "forecasts": [
            {
                "min_temp_c": round(day.get("temp_min_c") or 0),
                "max_temp_c": round(day.get("temp_max_c") or 0),
                "condition_code": _WMO_TO_OWM.get(int(day.get("weather_code") or 0), 804),
            }
            # Today is already the "current" block above; the watch's forecast
            # rows are the days after it.
            for day in daily[1:]
        ],
    }


def _hourly_rows(forecast: dict, limit: int) -> list[dict]:
    """Hourly entries from now onward, as epoch seconds.

    Open-Meteo is asked with `timezone=auto`, so every timestamp it returns is
    local wall-clock with no offset attached — unusable to a device until the
    offset it also reports is added back. Rows before the current hour are
    dropped rather than sent: the watch renders the strip in order and would
    otherwise open on this morning.
    """
    offset = forecast.get("utc_offset_seconds")
    if offset is None:
        return []

    now = datetime.now(timezone.utc).timestamp()
    rows = []
    for entry in forecast.get("hourly") or []:
        try:
            local = datetime.fromisoformat(entry["time"])
        except (TypeError, ValueError, KeyError):
            continue
        epoch = local.replace(tzinfo=timezone.utc).timestamp() - offset
        if epoch < now - 3600 or entry.get("temp_c") is None:
            continue
        rows.append({
            "timestamp": int(epoch),
            "temp_c": round(entry["temp_c"]),
            "condition_code": _WMO_TO_OWM.get(int(entry.get("weather_code") or 0), 804),
            "precip_probability": entry.get("precip_prob_pct"),
            "humidity_percent": entry.get("humidity_pct"),
        })
        if len(rows) >= limit:
            break
    return rows


# Half a day. The glance scrolls a short strip, and every extra hour is another
# FIT record on a link that is also carrying activity files.
_HOURLY_HOURS = 12


def _synced_phone_point(us: UserSettings | None) -> tuple[float, float] | None:
    """Where the phone last was, as it synced it (settings.weather_location).

    Preferred over the activity history: it is where the user is now rather
    than where they last recorded something, and it is what the phone itself
    forecasts for, so the fallback and the phone agree. Anything malformed is
    ignored rather than trusted — the value arrives through sync from a client.
    """
    loc = us.weather_location if us is not None else None
    if not isinstance(loc, dict):
        return None
    lat, lon = loc.get("lat"), loc.get("lon")
    if isinstance(lat, bool) or isinstance(lon, bool):
        return None
    if not isinstance(lat, (int, float)) or not isinstance(lon, (int, float)):
        return None
    if not (-90 <= lat <= 90 and -180 <= lon <= 180):
        return None
    return float(lat), float(lon)


def _latest_activity_point(user: User, db: Session) -> tuple[float, float] | None:
    """Start position of the most recent activity that has one.

    Walks back a few activities rather than trusting the newest: an indoor
    session records no GPS at all, and a treadmill run on Tuesday should not
    leave the watch with no forecast until the next time someone goes outside.
    """
    recent = (
        db.query(Activity)
        .filter(Activity.user_id == user.id)
        .order_by(Activity.started_at.desc())
        .limit(_WEATHER_LOOKBACK)
        .all()
    )
    for activity in recent:
        point = (
            db.query(DataPoint)
            .filter(DataPoint.activity_id == activity.id, DataPoint.lat.isnot(None))
            .order_by(DataPoint.recorded_at)
            .first()
        )
        if point is not None and point.lat is not None and point.lng is not None:
            return float(point.lat), float(point.lng)
    return None


_WEATHER_LOOKBACK = 25


def _place_name(lat: float, lon: float) -> str:
    """A label for the glance header.

    The coordinates are the honest fallback and the only one that needs no
    network call of its own — the watch shows this as a caption, so being
    unhelpful here costs a line of text rather than the forecast.
    """
    return f"{abs(lat):.2f}\u00b0{'N' if lat >= 0 else 'S'} {abs(lon):.2f}\u00b0{'E' if lon >= 0 else 'W'}"
log = logging.getLogger(__name__)

NEWFILES = "GARMIN/NewFiles"
COURSES = "GARMIN/Courses"
WORKOUTS = "GARMIN/Workouts"


@router.get("/upload-list")
def browser_upload_list(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
    bluetooth: bool = Query(
        False,
        description="Caller reaches the watch over BLE, which can write files "
                    "but not delete them.",
    ),
):
    """Everything waiting to be pushed to a watch, tagged with its folder.

    ``bluetooth=true`` changes exactly one thing: clearing every saved place
    arrives as an empty Locations.fit to write, rather than as an entry on the
    delete list. The two transports differ in what they can physically do —
    USB removes the file, BLE has no delete at all — and this is the flag that
    says which one is asking.

    Declared rather than inferred from the order the caller does things in. The
    browser happens to process deletions before uploads, which would make the
    distinction work by accident today and break silently the day that changed.
    """
    items = []
    for it in _upload_items_for_user(db, user.id):
        items.append({**it, "folder": NEWFILES})
    for it in _course_upload_items(db, user.id):
        items.append({**it, "folder": COURSES})
    for it in _waypoint_upload_items(db, user.id):
        items.append({**it, "folder": LOCATIONS_FOLDER})
    if bluetooth:
        for it in _waypoint_clear_item(db, user.id):
            items.append({**it, "folder": LOCATIONS_FOLDER})
    return {"items": items}


@router.post("/mark-uploaded")
def browser_mark_uploaded(
    payload: dict,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """payload: {"items": [{"type", "id", "filename"}]} — types as returned
    by /device-sync/upload-list (workout | race_plan | course | waypoint).

    A waypoint item also echoes the `ids` it was offered with: Locations.fit is
    one file covering every saved place, so what got delivered is that list, not
    the item's own id."""
    items = payload.get("items", [])
    courses = [i for i in items if i.get("type") == "course"]
    waypoints = [i for i in items if i.get("type") == "waypoint"]
    others = [i for i in items if i.get("type") not in ("course", "waypoint")]
    marked = 0
    if others:
        marked += _apply_mark_uploaded(db, others, user_id=user.id)
    if courses:
        marked += _apply_mark_course_uploaded(db, courses, user_id=user.id)
    if waypoints:
        marked += _apply_mark_waypoint_uploaded(db, waypoints, user_id=user.id)
    return {"marked": marked}


@router.get("/schedule-fit")
def browser_schedule_fit(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Training-calendar schedule FIT. Fetch AFTER marking uploads — it only
    includes workouts whose upload has been recorded."""
    out = _schedule_fit_for_user(db, user.id)
    out["filename"] = "SCHEDULE.fit"
    out["folder"] = NEWFILES
    return out


@router.get("/schedule-bundle")
def browser_schedule_bundle(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """The schedule together with every workout it names — what Garmin Connect
    sends, in the order it sends it. Unlike `/schedule-fit` this needs no prior
    marking: the workouts come back whether or not the server believes the watch
    already has them, because a schedule that names a file from an earlier
    session does not build a calendar. See `_schedule_bundle_for_user`."""
    return _schedule_bundle_for_user(db, user.id)


@router.get("/delete-list")
def browser_delete_list(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    items = []
    for it in _delete_items_for_user(db, user.id):
        items.append({**it, "folder": WORKOUTS})
    for it in _course_delete_items(db, user.id):
        items.append({**it, "type": "course", "folder": COURSES})
    for it in _waypoint_delete_items(db, user.id):
        items.append({**it, "folder": LOCATIONS_FOLDER})
    return {"items": items}


@router.post("/mark-deleted")
def browser_mark_deleted(
    payload: dict,
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """payload: {"items": [{"type", "id"}]} — types from /device-sync/delete-list
    (workout | pending | course | waypoint)."""
    items = payload.get("items", [])
    marked = _apply_mark_deleted(db, {
        "ids": [i["id"] for i in items if i.get("type") == "workout"],
        "pending_ids": [i["id"] for i in items if i.get("type") == "pending"],
    }, user_id=user.id)
    course_ids = [i["id"] for i in items if i.get("type") == "course"]
    if course_ids:
        marked += _apply_mark_course_deleted(db, {"ids": course_ids}, user_id=user.id)
    if any(i.get("type") == "waypoint" for i in items):
        marked += _apply_mark_waypoint_deleted(db, {}, user_id=user.id)
    return {"marked": marked}


# ── AGPS (CPE.bin) ───────────────────────────────────────────────────────────
# Browser counterpart of garmin-sync's agps_sync_cycle. The download happens
# server-side because Garmin's EPO server sends no CORS headers, so the
# browser cannot fetch it directly.

GARMIN_EPO_URL = (
    "https://api.gcs.garmin.com/ephemeris/cpe/sony/lle"
    "?coverage=WEEKS_1&constellations=GPS,GLONASS,GALILEO,QZSS"
)
_AGPS_MAX_BYTES = 20 * 1024 * 1024  # sanity cap; real CPE files are ~2 MB


def _agps_url(us: UserSettings) -> str | None:
    if us.agps_source == "garmin":
        return GARMIN_EPO_URL
    if us.agps_source == "custom" and us.agps_custom_url:
        return us.agps_custom_url
    return None


def _agps_is_stale(us: UserSettings) -> bool:
    if us.agps_last_synced_at is None:
        return True
    last = us.agps_last_synced_at
    if last.tzinfo is None:
        last = last.replace(tzinfo=timezone.utc)
    age_hours = (datetime.now(timezone.utc) - last).total_seconds() / 3600
    return age_hours >= (us.agps_max_age_hours or 24)


@router.get("/agps")
def browser_agps(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """If AGPS is enabled and stale, download the CPE data and hand it to the
    browser to write onto the watch. Returns {"due": false} otherwise."""
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us is None or not us.agps_enabled:
        return {"due": False}
    url = _agps_url(us)
    if not url:
        return {"due": False}
    if not _agps_is_stale(us):
        return {"due": False}

    epo_path = (us.agps_epo_path or "GARMIN/REMOTESW/CPE.bin").replace("\\", "/")
    parts = [p for p in epo_path.split("/") if p]
    filename = parts[-1] if parts else "CPE.bin"
    folder = "/".join(parts[:-1]) or "GARMIN/REMOTESW"

    # The custom URL is free text and the bytes go straight back to the
    # caller, so the download goes through public_fetch: http(s) to public
    # addresses only. A plain urlopen here once served file:// and loopback
    # URLs, i.e. the server's own secrets, to any logged-in user.
    try:
        data = public_fetch.fetch_public(
            url, max_bytes=_AGPS_MAX_BYTES, timeout=30,
            headers={"User-Agent": "tracks-device-sync/1.0"},
        )
    except public_fetch.UnsafeURL as exc:
        raise HTTPException(status_code=400, detail=f"AGPS URL refused: {exc}")
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"AGPS download failed: {exc}")
    if not data:
        raise HTTPException(status_code=502, detail="AGPS download was empty")

    return {
        "due": True,
        "folder": folder,
        "filename": filename,
        "data_b64": base64.b64encode(data).decode(),
    }


@router.post("/agps-synced")
def browser_agps_synced(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Record that the browser wrote CPE data to the watch."""
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us:
        us.agps_last_synced_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True}


@router.post("/synced")
def browser_watch_synced(
    user: User = Depends(require_auth),
    db: Session = Depends(get_db),
):
    """Record a completed browser sync so /training-plan/sync/status (and the
    sidebar indicator) reflect it just like a cable sync."""
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    if us:
        us.watch_last_synced_at = datetime.now(timezone.utc)
        db.commit()
    return {"ok": True}
