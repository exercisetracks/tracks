# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The Weather privacy toggle gates every forecast, not only race plans.

Every Open-Meteo request carries a location — a race pin, a tapped map point,
or where the user last trained — so a switch labelled "Weather" that stopped
only one of them would be a privacy setting that quietly leaks the rest. These
tests count calls to the provider: with the toggle off there must be none.
"""
from datetime import date

import pytest

from app.models.user_settings import UserSettings
from app.services import weather


@pytest.fixture
def provider(monkeypatch):
    """Record every forecast request instead of sending it."""
    calls = []

    def fake(lat, lon, days=7, include_hourly=False):
        calls.append((lat, lon))
        today = str(date.today())
        return {
            "timezone": "UTC",
            "hourly": [{"time": f"{today}T12:00", "temp_c": 20.0}],
            "current": {"temperature_c": 20.0, "weather_code": 0},
            "daily": [{"date": today, "weather_code": 0}],
        }

    monkeypatch.setattr(weather, "fetch_point_forecast", fake)
    return calls


def _set_weather(db, user, enabled):
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    us.weather_enabled = enabled
    db.commit()


def _map_requests():
    today = str(date.today())
    return [
        "/maps/point?lat=46.5&lon=-114.0",
        "/maps/point/weather?lat=46.5&lon=-114.0",
        f"/maps/point/hourly?lat=46.5&lon=-114.0&date={today}",
    ]


@pytest.mark.parametrize("path", _map_requests())
def test_a_map_point_sends_no_location_while_weather_is_off(client, user, db, provider, path):
    """Tapping the map is the most frequent forecast there is; it must honour
    the same switch the race plan does."""
    _set_weather(db, user, False)

    resp = client.get(path)

    assert resp.status_code == 200, resp.text
    assert provider == []
    assert resp.json()["weather_disabled"] is True


@pytest.mark.parametrize("path", _map_requests())
def test_a_map_point_still_gets_its_forecast_while_weather_is_on(client, user, db, provider, path):
    """The gate must not swallow the feature it guards."""
    _set_weather(db, user, True)

    resp = client.get(path)

    assert resp.status_code == 200, resp.text
    assert provider == [(46.5, -114.0)]


def test_the_rest_of_point_info_survives_weather_being_off(client, user, db, provider):
    """Elevation and the nearest place are local lookups; turning the forecast
    off must not take the whole panel with it."""
    _set_weather(db, user, False)

    body = client.get("/maps/point?lat=46.5&lon=-114.0").json()

    assert body["lat"] == 46.5
    assert body["weather"] is None


def test_the_watch_forecast_sends_no_location_while_weather_is_off(client, user, db, provider):
    """The watch forecast sends where the user trains — arguably the most
    revealing location of all — so it is refused outright, before the server
    even looks that location up. 403 rather than the 404 an account with no GPS
    activity gets, so the two are told apart."""
    # This endpoint needs a real crypto session, which only setup creates.
    token = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "testpass123",
    }).json()["access_token"]
    _set_weather(db, user, False)

    resp = client.get("/device-sync/weather", headers={"Authorization": f"Bearer {token}"})

    assert resp.status_code == 403
    assert provider == []


def _watch_token(client):
    return client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "testpass123",
    }).json()["access_token"]


def test_the_watch_forecast_is_for_where_the_phone_last_was(client, user, db, provider):
    """The phone syncs its own position; the server's fallback forecast must use
    it rather than an activity start that may be weeks and a trip out of date."""
    token = _watch_token(client)
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    us.weather_location = {"lat": 46.87, "lon": -113.99, "at": "2026-10-06T12:00:00Z"}
    db.commit()

    resp = client.get("/device-sync/weather", headers={"Authorization": f"Bearer {token}"})

    assert resp.status_code == 200, resp.text
    assert provider == [(46.87, -113.99)]


@pytest.mark.parametrize("bad", [
    {"lat": "46.87", "lon": -113.99},
    {"lat": 95.0, "lon": 10.0},
    {"lat": True, "lon": 10.0},
    ["46.87", "-113.99"],
])
def test_a_malformed_synced_location_is_not_trusted(client, user, db, provider, bad):
    """The value arrives through sync from a client. With no activity to fall
    back to, a bad one means no forecast — never a request for nonsense."""
    token = _watch_token(client)
    us = db.query(UserSettings).filter_by(user_id=user.id).first()
    us.weather_location = bad
    db.commit()

    resp = client.get("/device-sync/weather", headers={"Authorization": f"Bearer {token}"})

    assert resp.status_code == 404
    assert provider == []
