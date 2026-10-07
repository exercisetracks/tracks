# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Point weather forecast from Open-Meteo (free, no API key).

Same provider as the race-day weather in api/coaching.py, but shaped for the map
point-info panel: current conditions + a multi-day daily forecast. WMO weather
codes are passed through for the frontend to label/icon.
"""

from __future__ import annotations

import logging

import httpx

logger = logging.getLogger(__name__)

_TIMEOUT = httpx.Timeout(connect=5.0, read=8.0, write=5.0, pool=5.0)

_CURRENT = ("temperature_2m,apparent_temperature,relative_humidity_2m,"
            "wind_speed_10m,wind_direction_10m,weather_code,precipitation,is_day")
_DAILY = ("weather_code,temperature_2m_max,temperature_2m_min,"
          "precipitation_sum,precipitation_probability_max,wind_speed_10m_max,"
          "sunrise,sunset")


_HOURLY = "temperature_2m,weather_code,precipitation_probability,relative_humidity_2m"


def fetch_point_forecast(
    lat: float, lon: float, days: int = 7, include_hourly: bool = False
) -> dict | None:
    """Current conditions + `days`-day daily forecast for (lat, lon).

    Returns {current:{…}, daily:[{…}], units:{…}} or None on failure. Units are
    metric (°C, m/s, mm); the frontend converts for display.

    The point goes to a third party, so it is rounded to two places (~1 km):
    finer than the forecast model's grid, so the answer is the same, and
    coarse enough that the start of someone's latest run — what the watch
    forecast falls back to — is not their front door.
    """
    url = (
        "https://api.open-meteo.com/v1/forecast"
        f"?latitude={lat:.2f}&longitude={lon:.2f}"
        f"&current={_CURRENT}"
        f"&daily={_DAILY}"
        f"&forecast_days={max(1, min(days, 16))}"
        + (f"&hourly={_HOURLY}" if include_hourly else "")
        + "&timezone=auto&wind_speed_unit=ms"
    )
    try:
        r = httpx.get(url, timeout=_TIMEOUT, follow_redirects=True)
        if not r.is_success:
            logger.warning("Open-Meteo point forecast HTTP %s", r.status_code)
            return None
        data = r.json()
    except Exception as exc:
        logger.warning("Open-Meteo point forecast failed: %s", exc)
        return None

    cur = data.get("current", {}) or {}
    d = data.get("daily", {}) or {}
    dates = d.get("time", []) or []
    daily = []
    for i in range(len(dates)):
        def g(key):
            arr = d.get(key, [])
            return arr[i] if i < len(arr) else None
        daily.append({
            "date": dates[i],
            "weather_code": g("weather_code"),
            "temp_max_c": g("temperature_2m_max"),
            "temp_min_c": g("temperature_2m_min"),
            "precip_mm": g("precipitation_sum"),
            "precip_prob_pct": g("precipitation_probability_max"),
            "wind_max_mps": g("wind_speed_10m_max"),
            "sunrise": g("sunrise"),
            "sunset": g("sunset"),
        })

    hourly = []
    if include_hourly:
        h = data.get("hourly", {}) or {}
        times = h.get("time", []) or []
        for i in range(len(times)):
            def gh(key):
                arr = h.get(key, [])
                return arr[i] if i < len(arr) else None
            hourly.append({
                "time": times[i],
                "temp_c": gh("temperature_2m"),
                "weather_code": gh("weather_code"),
                "precip_prob_pct": gh("precipitation_probability"),
                "humidity_pct": gh("relative_humidity_2m"),
            })

    return {
        "timezone": data.get("timezone"),
        # Local wall-clock is what `timezone=auto` returns for every timestamp
        # in this payload, so the offset is what makes those convertible to the
        # epoch seconds a device needs.
        "utc_offset_seconds": data.get("utc_offset_seconds"),
        "hourly": hourly,
        "current": {
            "temperature_c": cur.get("temperature_2m"),
            "apparent_c": cur.get("apparent_temperature"),
            "humidity_pct": cur.get("relative_humidity_2m"),
            "wind_mps": cur.get("wind_speed_10m"),
            "wind_direction": cur.get("wind_direction_10m"),
            "precipitation_mm": cur.get("precipitation"),
            "weather_code": cur.get("weather_code"),
            "is_day": cur.get("is_day"),
            "time": cur.get("time"),
        },
        "daily": daily,
    }
