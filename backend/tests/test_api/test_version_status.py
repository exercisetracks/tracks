# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""GET /version and the update check behind it.

The panels it feeds are the only way a household without an app store learns
that an update exists, so the cases that matter are the ones that would lie:
an update reported that is not newer, a GitHub outage turning into a hung or
broken settings page, and a device listed with a version it no longer runs.
"""
import httpx
import pytest

from app.config import settings
from app.models.refresh_tokens import RefreshToken
from app.services import update_check
from app.version import SERVER_VERSION


def _setup(client, password="testpass123"):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": password,
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text


def _login(client, *, client_header=None):
    headers = {"X-Tracks-Client": client_header} if client_header else {}
    resp = client.post("/auth/login", headers=headers, json={
        "username": "admin", "password": "testpass123",
        "issue_refresh_token": True, "device_label": "Pixel 9",
    })
    assert resp.status_code == 200, resp.text
    return resp.json()


def _bump(version: str) -> str:
    major, minor, patch = version.split("-")[0].split(".")
    return f"{major}.{minor}.{int(patch) + 1}"


@pytest.fixture
def github(monkeypatch):
    """Stands in for GitHub; counts how often it is asked."""
    state = {"calls": 0, "tag": "v" + SERVER_VERSION, "fail": False}

    def fake_get(url, **kwargs):
        state["calls"] += 1
        request = httpx.Request("GET", url)
        if state["fail"]:
            raise httpx.ConnectError("offline", request=request)
        return httpx.Response(200, request=request, json={
            "tag_name": state["tag"],
            "html_url": f"https://github.com/exercisetracks/tracks/releases/tag/{state['tag']}",
            "published_at": "2026-10-01T12:00:00Z",
        })

    monkeypatch.setattr(update_check.httpx, "get", fake_get)
    monkeypatch.setattr(settings, "update_check", True)
    return state


class TestVersionComparison:
    @pytest.mark.parametrize("newer,older", [
        ("1.2.0", "1.1.9"),
        ("1.10.0", "1.9.0"),
        ("v2.0.0", "1.99.99"),
        ("1.2.0", "1.2.0-beta.1"),
        ("1.2.0-beta.2", "1.2.0-beta.1"),
        ("android/1.2.0 (10200)", "1.1.3"),
    ])
    def test_a_newer_version_is_newer(self, newer, older):
        """Compared as numbers: as strings, 1.10.0 sorts below 1.9.0 and a
        household would be told to 'update' backwards."""
        assert update_check.is_newer(newer, older)
        assert not update_check.is_newer(older, newer)

    def test_the_same_version_is_not_an_update(self):
        assert not update_check.is_newer("1.1.3", "v1.1.3")

    def test_an_unparseable_version_is_never_an_update(self):
        """A permanent false 'update available' teaches people to ignore the
        panel, which defeats it."""
        assert not update_check.is_newer("nightly", "1.0.0")
        assert not update_check.is_newer("2.0.0", None)


class TestVersionStatus:
    def test_requires_sign_in(self, client, user, db, github):
        _setup(client)
        assert client.get("/version").status_code == 401

    def test_reports_a_newer_release(self, client, user, db, github):
        _setup(client)
        github["tag"] = "v" + _bump(SERVER_VERSION)
        token = _login(client)["access_token"]
        body = client.get("/version", headers={"Authorization": f"Bearer {token}"}).json()
        assert body["server_version"] == SERVER_VERSION
        assert body["latest"]["version"] == _bump(SERVER_VERSION)
        assert body["server_update_available"] is True

    def test_the_current_release_is_not_an_update(self, client, user, db, github):
        _setup(client)
        token = _login(client)["access_token"]
        body = client.get("/version", headers={"Authorization": f"Bearer {token}"}).json()
        assert body["latest"]["version"] == SERVER_VERSION
        assert body["server_update_available"] is False

    def test_github_is_asked_once_for_many_views(self, client, user, db, github):
        """Every phone sync calls this; GitHub must not see one request each."""
        _setup(client)
        auth = {"Authorization": f"Bearer {_login(client)['access_token']}"}
        for _ in range(3):
            client.get("/version", headers=auth)
        assert github["calls"] == 1

    def test_github_being_unreachable_still_answers(self, client, user, db, github):
        """An offline server must still show the versions it knows, and must
        not retry GitHub on every page view while it is down."""
        _setup(client)
        github["fail"] = True
        auth = {"Authorization": f"Bearer {_login(client)['access_token']}"}
        body = client.get("/version", headers=auth).json()
        assert body["latest"] is None
        assert body["check_error"]
        assert body["server_update_available"] is False
        client.get("/version", headers=auth)
        assert github["calls"] == 1

    def test_turning_the_check_off_never_contacts_github(self, client, user, db, github, monkeypatch):
        """UPDATE_CHECK=false is a promise that nothing leaves the server."""
        monkeypatch.setattr(settings, "update_check", False)
        _setup(client)
        auth = {"Authorization": f"Bearer {_login(client)['access_token']}"}
        body = client.get("/version", headers=auth).json()
        assert body["update_check"] is False
        assert body["latest"] is None
        assert github["calls"] == 0


class TestClientVersion:
    def test_login_records_the_app_version(self, client, user, db, github):
        _setup(client)
        _login(client, client_header="android/1.1.3 (10103)")
        sessions = client.get("/auth/sessions", headers={
            "Authorization": f"Bearer {_login(client)['access_token']}"}).json()
        assert "android/1.1.3 (10103)" in {s["client_version"] for s in sessions}

    def test_an_updated_app_is_listed_with_its_new_version(self, client, user, db, github):
        """The access token lasts 30 days; without /version recording the
        header, an updated phone would be listed as its old version for up to
        a month."""
        _setup(client)
        token = _login(client, client_header="android/1.1.3 (10103)")["access_token"]
        client.get("/version", headers={
            "Authorization": f"Bearer {token}", "X-Tracks-Client": "android/1.2.0 (10200)"})
        assert db.query(RefreshToken).one().client_version == "android/1.2.0 (10200)"

    def test_rotation_keeps_the_version_when_the_client_sends_none(self, client, user, db, github):
        _setup(client)
        refresh = _login(client, client_header="android/1.1.3 (10103)")["refresh_token"]
        client.post("/auth/refresh", json={"refresh_token": refresh})
        live = db.query(RefreshToken).filter(RefreshToken.replaced_at.is_(None)).one()
        assert live.client_version == "android/1.1.3 (10103)"

    def test_another_devices_token_is_not_stamped(self, client, user, db, github):
        """Matched on the access token's sid, so one phone's version never
        lands on another phone's row."""
        _setup(client)
        _login(client, client_header="android/1.1.3 (10103)")
        other = _login(client, client_header="android/1.1.3 (10103)")["access_token"]
        client.get("/version", headers={
            "Authorization": f"Bearer {other}", "X-Tracks-Client": "android/1.2.0 (10200)"})
        versions = sorted(r.client_version for r in db.query(RefreshToken).all())
        assert versions == ["android/1.1.3 (10103)", "android/1.2.0 (10200)"]

    def test_a_hostile_header_is_not_stored(self, client, user, db, github):
        """Shown on the web verbatim; control characters have no business there."""
        _setup(client)
        _login(client, client_header="android/1.1.3\x1b[31m")
        assert db.query(RefreshToken).one().client_version is None
