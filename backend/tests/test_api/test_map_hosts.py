# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A DNS filter blocking a map host is named, with everything to allow.

AdGuard Home blocking the basemap host reached the user as a bare 502 and a
download that never started; finding the cause, and which names to allow, was
left to them.
"""
import socket
import subprocess

import pytest

from app.config import settings
from app.routes.regions import crud
from app.services import map_hosts, region_downloader

BBOX = "-112,40,-111,41"


@pytest.fixture
def dns(monkeypatch):
    """A resolver that answers 0.0.0.0 for the names in `blocked`."""
    monkeypatch.setattr(settings, "pmtiles_source_url", "https://build.protomaps.com/20261001.pmtiles")
    monkeypatch.setattr(settings, "dem_source_url", "https://download.mapterhorn.com/planet.pmtiles")
    blocked, missing = set(), set()

    def resolve(host, port, *a, **kw):
        if host in missing:
            raise socket.gaierror("Name or service not known")
        ip = "0.0.0.0" if host in blocked else "203.0.113.7"
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (ip, port))]

    monkeypatch.setattr(map_hosts, "_resolve", resolve)
    return blocked, missing


def _admin(client):
    r = client.post("/auth/setup", json={"username": "admin", "name": "Admin",
                                         "password": "testpass123", "enable_garmin_sync": False})
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def test_a_null_answer_is_a_block_and_the_message_lists_everything_to_allow(dns):
    blocked, _ = dns
    blocked.update({"build.protomaps.com", "download.mapterhorn.com"})
    with pytest.raises(map_hosts.MapHostsBlocked) as exc:
        map_hosts.check_download_hosts()
    msg = str(exc.value)
    assert "build.protomaps.com and download.mapterhorn.com" in msg
    assert "AdGuard Home" in msg
    for host in map_hosts.download_hosts():
        assert host in msg


def test_a_name_the_filter_answers_does_not_exist_is_reported_too(dns):
    _, missing = dns
    missing.add("download.geofabrik.de")
    assert map_hosts.blocked_hosts(map_hosts.download_hosts()) == {"download.geofabrik.de": "not found"}


def test_no_dns_at_all_is_not_called_a_filter(dns):
    """Every name failing is a machine offline, not a blocklist; the download
    says so in its own words."""
    _, missing = dns
    missing.update(map_hosts.download_hosts())
    assert map_hosts.blocked_hosts(map_hosts.download_hosts()) == {}


def test_the_estimate_names_the_block_instead_of_a_bare_502(client, user, dns, monkeypatch):
    blocked, _ = dns
    blocked.add("build.protomaps.com")
    monkeypatch.setattr(region_downloader.subprocess, "run",
                        lambda *a, **k: pytest.fail("sized a blocked source"))
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 502
    assert "build.protomaps.com" in resp.json()["detail"]
    assert "Allow these" in resp.json()["detail"]


def test_a_blocked_download_is_refused_without_leaving_a_failed_area(client, user, dns, monkeypatch):
    blocked, _ = dns
    blocked.add("download.mapterhorn.com")
    monkeypatch.setattr(crud.region_registry, "list_active", lambda: [])
    created = []
    monkeypatch.setattr(crud.region_registry, "create", lambda **kw: created.append(kw))
    resp = client.post("/maps/regions/download", headers=_admin(client),
                       json={"bbox": [-112, 40, -111, 41], "name": "x"})
    assert resp.status_code == 502
    assert "download.mapterhorn.com" in resp.json()["detail"]
    assert created == []


def test_a_dry_run_refused_by_a_null_address_says_how_to_fix_it(monkeypatch):
    """Past the DNS check (a filter that blocks by connection, or DNS cached
    from before): go-pmtiles' raw dial error becomes the same advice."""
    out = ('2026/10/08 01:39:27 main.go:185: Failed to extract, Get '
           '"https://build.protomaps.com/20261007.pmtiles": dial tcp 0.0.0.0:443: '
           'connect: connection refused\n')
    monkeypatch.setattr(region_downloader.subprocess, "run",
                        lambda cmd, **kw: subprocess.CompletedProcess(cmd, 1, stdout="", stderr=out))
    with pytest.raises(region_downloader.DryRunFailed) as exc:
        region_downloader.dry_run_size("https://build.protomaps.com/20261007.pmtiles", BBOX, 8, 15)
    msg = str(exc.value)
    assert "Allow build.protomaps.com" in msg
    assert "Map downloads use:" in msg
