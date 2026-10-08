# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Sizing an area before it is downloaded.

The estimate is the first thing that touches the map sources, so it is where a
source the server cannot read should first say so. It used to answer 0 bytes,
which the map drew as "Estimated size: <1 MB" — and the download that followed
failed with no reason given anywhere.
"""
import subprocess

import pytest

from app.config import settings
from app.services import region_downloader

BBOX = "-112,40,-111,41"


@pytest.fixture
def sources(monkeypatch):
    monkeypatch.setattr(settings, "pmtiles_source_url", "https://tiles.example/planet.pmtiles")
    monkeypatch.setattr(settings, "dem_source_url", "https://dem.example/planet.pmtiles")
    monkeypatch.setattr("app.routes.regions.crud.resolve_source_url", lambda u: u)
    monkeypatch.setattr(region_downloader, "_dem_region_maxzoom", lambda: 12)


def _admin(client):
    r = client.post("/auth/setup", json={"username": "admin", "name": "Admin",
                                         "password": "testpass123", "enable_garmin_sync": False})
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def _pmtiles_says(monkeypatch, returncode, stderr):
    monkeypatch.setattr(
        region_downloader.subprocess, "run",
        lambda cmd, **kw: subprocess.CompletedProcess(cmd, returncode, stdout="", stderr=stderr),
    )


def test_the_size_is_the_sum_of_the_basemap_and_terrain(client, user, sources, monkeypatch):
    _pmtiles_says(monkeypatch, 0, "extract.go:612: Extract transferred 34 MB (overfetch 0.05) "
                                  "for an archive size of 32 MB\n")
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 200, resp.text
    assert resp.json() == {"bytes": 64_000_000}


def test_a_source_that_cannot_be_read_is_an_error_not_a_tiny_area(client, user, sources, monkeypatch):
    """The regression: a failed dry run summed to 0 and was shown as "<1 MB"."""
    _pmtiles_says(monkeypatch, 1, "2026/10/08 01:39:27 extract.go:88: Failed to fetch header: 404 Not Found\n")
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 502
    assert "404 Not Found" in resp.json()["detail"]
    assert "example" in resp.json()["detail"]


def test_a_source_that_does_not_answer_says_so(client, user, sources, monkeypatch):
    def hang(cmd, **kw):
        raise subprocess.TimeoutExpired(cmd, kw.get("timeout"))
    monkeypatch.setattr(region_downloader.subprocess, "run", hang)
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 502
    assert "did not answer" in resp.json()["detail"]


def test_without_a_basemap_source_there_is_nothing_to_size(client, user, sources, monkeypatch):
    monkeypatch.setattr(settings, "pmtiles_source_url", "")
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 400
