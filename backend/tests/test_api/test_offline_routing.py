# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""GET /maps/route/offline/* — the routing data a phone downloads to route
with the radio off.

These endpoints hand out files, which makes two things worth testing above the
happy path: that a name arriving in the URL cannot walk out of the directory it
is supposed to name, and that a bbox cannot make the server fetch the planet's
worth of rd5 before it answers.
"""
import pytest

from app.config import settings
from app.services import brouter_downloader


@pytest.fixture
def headers(client):
    resp = client.post("/auth/setup", json={
        "username": "admin", "name": "Admin", "password": "testpass123",
        "enable_garmin_sync": False,
    })
    assert resp.status_code == 200, resp.text
    return {"Authorization": f"Bearer {resp.json()['access_token']}"}


@pytest.fixture(autouse=True)
def map_data(tmp_path, monkeypatch):
    """A server that already holds one segment and both profiles.

    Upstream is stubbed out entirely: `ensure_segments` and `ensure_profiles`
    both reach brouter.de / GitHub on a miss, and a test suite that downloads
    36 MB of a state's routing data to check a JSON shape is not a test suite.
    """
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))

    segments = tmp_path / "brouter" / "segments4"
    segments.mkdir(parents=True)
    (segments / "W155_N35.rd5").write_bytes(b"rd5" * 100)

    profiles = tmp_path / "brouter" / "profiles2"
    profiles.mkdir(parents=True)
    (profiles / "lookups.dat").write_bytes(b"lookups")
    (profiles / "trekking.brf").write_bytes(b"---context:way")

    def upstream_has_nothing(*args, **kwargs):
        # Modelled as a failed fetch rather than a hard assertion, because
        # "upstream has no file for this cell" is a real state the endpoints
        # must survive — every ocean cell is exactly this.
        raise brouter_downloader.httpx.HTTPError("no upstream in tests")

    monkeypatch.setattr(brouter_downloader.httpx, "get", upstream_has_nothing)
    return tmp_path


class TestManifest:
    def test_lists_the_profiles_and_the_covering_segments(self, client, headers):
        resp = client.get(
            "/maps/route/offline/manifest?bbox=-151.5,38.5,-151.0,39.0",
            headers=headers,
        )
        assert resp.status_code == 200, resp.text
        body = resp.json()

        by_name = {f["name"]: f for f in body["files"]}
        assert by_name["lookups.dat"]["kind"] == "profile"
        assert by_name["trekking.brf"]["kind"] == "profile"
        assert by_name["W155_N35.rd5"]["kind"] == "segment"
        assert by_name["W155_N35.rd5"]["bytes"] == 300
        assert body["total_bytes"] == sum(f["bytes"] for f in body["files"])

    def test_omits_cells_with_no_rd5(self, client, headers):
        """Ocean cells have no segment file and never will, and upstream can be
        unreachable besides. Listing a name the server does not hold would send
        the phone after a guaranteed 404 and stall the download."""
        resp = client.get(
            "/maps/route/offline/manifest?bbox=-3.0,38.5,-2.5,39.0",
            headers=headers,
        )
        assert resp.status_code == 200
        assert [f for f in resp.json()["files"] if f["kind"] == "segment"] == []

    def test_refuses_an_area_too_large_to_be_a_region(self, client, headers):
        """`ensure_segments` fetches every 5° cell the bbox touches, so an
        unbounded box is an unbounded download — the same trap /route/snap has,
        capped at the same 3°."""
        resp = client.get(
            "/maps/route/offline/manifest?bbox=-120.0,20.0,-70.0,50.0",
            headers=headers,
        )
        assert resp.status_code == 400
        assert "too large" in resp.json()["detail"].lower()

    def test_rejects_a_malformed_bbox(self, client, headers):
        resp = client.get("/maps/route/offline/manifest?bbox=nonsense", headers=headers)
        assert resp.status_code == 400


class TestFileDownloads:
    def test_serves_a_segment(self, client, headers):
        resp = client.get("/maps/route/offline/segments/W155_N35.rd5", headers=headers)
        assert resp.status_code == 200
        assert resp.content == b"rd5" * 100

    def test_serves_a_profile(self, client, headers):
        resp = client.get("/maps/route/offline/profiles/lookups.dat", headers=headers)
        assert resp.status_code == 200
        assert resp.content == b"lookups"

    @pytest.mark.parametrize("name", [
        "..%2F..%2Fetc%2Fpasswd",
        "W155_N35.rd5.bak",
        "lookups.dat",
    ])
    def test_a_segment_name_must_look_like_a_segment(self, client, headers, name):
        resp = client.get(f"/maps/route/offline/segments/{name}", headers=headers)
        assert resp.status_code in (400, 404), name
        assert resp.status_code != 200

    def test_a_profile_name_must_be_one_we_publish(self, client, headers):
        """An allowlist, not a pattern: the profile directory sits inside
        map-data, and "any file whose name ends in .brf" is one symlink away
        from being "any file"."""
        resp = client.get("/maps/route/offline/profiles/car-fast.brf", headers=headers)
        assert resp.status_code == 400

    def test_a_segment_the_server_does_not_hold_is_a_404(self, client, headers):
        resp = client.get("/maps/route/offline/segments/E5_N45.rd5", headers=headers)
        assert resp.status_code == 404
