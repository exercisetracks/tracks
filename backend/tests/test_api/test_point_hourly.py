# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""GET /maps/point/hourly — one day of a point's forecast, hour by hour.

Split from `/maps/point` because the cost profiles differ by an order of
magnitude: every tap on the map fetches point info, and almost none of those go
on to open a day. The tests that matter here are the ones about *which* hours
come back — the provider returns a flat list spanning every forecast day, and
handing the wrong slice of it to a phone is a mistake nobody can see, because
24 plausible readings look identical whichever day they belong to.
"""
from datetime import date, timedelta

import pytest

from app.services import weather


def _hours(day: str) -> list[dict]:
    return [
        {
            "time": f"{day}T{hour:02d}:00",
            "temp_c": 10.0 + hour,
            "weather_code": 1,
            "precip_prob_pct": hour,
            "humidity_pct": 40.0,
        }
        for hour in range(24)
    ]


@pytest.fixture
def forecast(monkeypatch):
    """Three days of hours, as Open-Meteo sends them: one flat list.

    Records the call so the tests can assert the server asked for hourly data
    at all — the service defaults it *off*, and forgetting the flag returns a
    perfectly valid response with an empty `hourly` in it.
    """
    calls = []
    today = date.today()
    covered = [str(today + timedelta(days=offset)) for offset in range(3)]

    def fake(lat, lon, days=7, include_hourly=False):
        calls.append({"lat": lat, "lon": lon, "days": days,
                      "hourly": include_hourly})
        hourly = [h for day in covered for h in _hours(day)] if include_hourly else []
        return {
            "timezone": "America/Edmonton",
            "hourly": hourly,
            "current": {"temperature_c": 21.0},
            "daily": [{"date": day} for day in covered],
        }

    monkeypatch.setattr(weather, "fetch_point_forecast", fake)
    return {"calls": calls, "days": covered}


@pytest.fixture
def headers(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "testpass123",
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


class TestHours:
    def test_returns_only_the_day_asked_for(self, client, headers, forecast):
        tomorrow = forecast["days"][1]
        resp = client.get(f"/maps/point/hourly?lat=40&lon=-150&date={tomorrow}", headers=headers)

        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["date"] == tomorrow
        assert len(body["hours"]) == 24
        # Every reading belongs to the requested day. A prefix match is the
        # whole filter, and it is only correct because the provider is asked for
        # local wall-clock — an offset conversion here would slice somebody's
        # evening into the next day.
        assert {h["time"][:10] for h in body["hours"]} == {tomorrow}

    def test_asks_the_provider_for_hourly_data(self, client, headers, forecast):
        client.get(f"/maps/point/hourly?lat=40&lon=-150&date={forecast['days'][0]}", headers=headers)

        assert forecast["calls"][0]["hourly"] is True

    def test_stretches_the_window_to_reach_a_later_day(self, client, headers, forecast):
        # Open-Meteo counts days forward from today rather than taking a range,
        # so a request for the far end of the week has to ask for the whole week
        # and then cut. Asking for one day would return an empty answer that
        # looks exactly like "no forecast here".
        far = str(date.today() + timedelta(days=6))
        client.get(f"/maps/point/hourly?lat=40&lon=-150&date={far}", headers=headers)

        assert forecast["calls"][0]["days"] >= 7

    def test_carries_the_timezone_the_hours_are_in(self, client, headers, forecast):
        resp = client.get(
            f"/maps/point/hourly?lat=40&lon=-150&date={forecast['days'][0]}", headers=headers)
        assert resp.json()["timezone"] == "America/Edmonton"


class TestRefusals:
    def test_rejects_a_date_that_is_not_one(self, client, headers, forecast):
        resp = client.get("/maps/point/hourly?lat=40&lon=-150&date=next-tuesday", headers=headers)
        assert resp.status_code == 422

    def test_rejects_a_day_beyond_the_forecast_window(self, client, headers, forecast):
        far = str(date.today() + timedelta(days=90))
        resp = client.get(f"/maps/point/hourly?lat=40&lon=-150&date={far}", headers=headers)

        assert resp.status_code == 400
        # And it did not spend a request finding that out.
        assert forecast["calls"] == []

    def test_rejects_a_day_that_has_already_gone(self, client, headers, forecast):
        past = str(date.today() - timedelta(days=3))
        resp = client.get(f"/maps/point/hourly?lat=40&lon=-150&date={past}", headers=headers)
        assert resp.status_code == 400

    def test_rejects_coordinates_off_the_planet(self, client, headers, forecast):
        resp = client.get(
            f"/maps/point/hourly?lat=95&lon=-150&date={forecast['days'][0]}", headers=headers)
        assert resp.status_code == 422


class TestSplitFromPointInfo:
    """The forecast can be fetched apart from the rest of point info.

    The panel on the phone opens on what the server knows locally — a DEM
    lookup and a gazetteer row, both sub-millisecond — and fills the weather in
    when it lands. Bundling them meant every tap on the map waited on a call to
    another company's API before anything appeared at all.
    """

    def test_point_info_can_be_asked_for_without_weather(self, client, headers, forecast):
        resp = client.get("/maps/point?lat=40&lon=-150&weather=false", headers=headers)

        assert resp.status_code == 200, resp.text
        assert resp.json()["weather"] is None
        # And it did not pay for one it then threw away.
        assert forecast["calls"] == []

    def test_point_info_still_carries_weather_by_default(self, client, headers, forecast):
        # The web app asks in one request, so the default must not change.
        resp = client.get("/maps/point?lat=40&lon=-150", headers=headers)

        assert resp.json()["weather"] is not None
        assert forecast["calls"][0]["hourly"] is False

    def test_weather_alone(self, client, headers, forecast):
        resp = client.get("/maps/point/weather?lat=40&lon=-150", headers=headers)

        assert resp.status_code == 200, resp.text
        assert resp.json()["weather"]["current"]["temperature_c"] == 21.0

    def test_weather_alone_survives_a_provider_that_is_down(self, client, headers, monkeypatch):
        monkeypatch.setattr(weather, "fetch_point_forecast", lambda *a, **k: None)
        resp = client.get("/maps/point/weather?lat=40&lon=-150", headers=headers)

        assert resp.status_code == 200
        assert resp.json() == {"weather": None}


class TestUnreachableProvider:
    def test_answers_with_no_hours_rather_than_an_error(self, client, headers, monkeypatch):
        # The forecast is somebody else's server and it goes down. A map panel
        # that fails to open is a worse answer than one that says the hours are
        # not available — the day strip it was opened from is still on screen.
        monkeypatch.setattr(
            weather, "fetch_point_forecast", lambda *a, **k: None
        )
        today = str(date.today())
        resp = client.get(f"/maps/point/hourly?lat=40&lon=-150&date={today}", headers=headers)

        assert resp.status_code == 200
        assert resp.json() == {"date": today, "hours": []}
