# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Sizing an area before it is downloaded.

The estimate is the first thing that touches the map sources, so it is where a
source the server cannot read should first say so. It used to answer 0 bytes,
which the map drew as "Estimated size: <1 MB" — and the download that followed
failed with no reason given anywhere.

It is read from the sources' directories over HTTP (services.remote_estimate);
here the "remote" archives are real PMTiles files served by a stand-in client.
"""
import httpx
import pytest
from pmtiles.tile import Compression, TileType, zxy_to_tileid
from pmtiles.writer import write

from app.config import settings
from app.services import region_downloader, region_registry, remote_estimate

BBOX = "-112,40,-111,41"


def _archive(path, zooms, size):
    """Every tile over the test box at `zooms`, each `size` bytes."""
    from app.services.map_packs import _lonlat_to_tile
    tiles = []
    for z in zooms:
        x0, y0 = _lonlat_to_tile(-112.5, 41.5, z)
        x1, y1 = _lonlat_to_tile(-110.5, 39.5, z)
        tiles += [(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]
    with write(str(path)) as w:
        for t in sorted(tiles, key=lambda t: zxy_to_tileid(*t)):
            w.write_tile(zxy_to_tileid(*t), bytes([t[1] % 251, t[2] % 251]) * (size // 2))
        w.finalize({"tile_type": TileType.MVT, "tile_compression": Compression.NONE,
                    "min_zoom": min(zooms), "max_zoom": max(zooms), "min_lon_e7": 0, "min_lat_e7": 0,
                    "max_lon_e7": 0, "max_lat_e7": 0, "center_lon_e7": 0, "center_lat_e7": 0,
                    "center_zoom": 0}, {})
    return path


class _FakeHttp:
    def __init__(self, files, fail=None):
        self.files, self.fail = files, fail

    def get(self, url, headers=None):
        if self.fail:
            raise self.fail
        start, end = map(int, headers["Range"].split("=")[1].split("-"))
        data = self.files[url].read_bytes()[start:end + 1]
        return httpx.Response(206, content=data, request=httpx.Request("GET", url))


@pytest.fixture
def sources(monkeypatch, tmp_path):
    monkeypatch.setattr(settings, "pmtiles_source_url", "https://tiles.example/planet.pmtiles")
    monkeypatch.setattr(settings, "dem_source_url", "https://dem.example/planet.pmtiles")
    monkeypatch.setattr("app.routes.regions.crud.resolve_source_url", lambda u: u)
    monkeypatch.setattr(region_downloader, "_basemap_region_minzoom", lambda: 13)
    monkeypatch.setattr(region_downloader, "_dem_region_minzoom", lambda: 8)
    monkeypatch.setattr(region_downloader, "_dem_region_maxzoom", lambda: 10)
    monkeypatch.setattr(region_registry, "list_active", lambda: [])
    files = {
        "https://tiles.example/planet.pmtiles": _archive(tmp_path / "b.pmtiles", [13, 14], 100),
        "https://dem.example/planet.pmtiles": _archive(tmp_path / "d.pmtiles", [8, 9, 10], 1000),
    }
    remote_estimate.RemoteArchive._headers.clear()
    remote_estimate.RemoteArchive._cache.clear()
    fake = _FakeHttp(files)
    monkeypatch.setattr(remote_estimate, "_http", lambda: fake)
    return fake


def _admin(client):
    r = client.post("/auth/setup", json={"username": "admin", "name": "Admin",
                                         "password": "testpass123", "enable_garmin_sync": False})
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def _tiles_in_box(z):
    from app.services.map_packs import _lonlat_to_tile
    x0, y0 = _lonlat_to_tile(-112, 41, z)
    x1, y1 = _lonlat_to_tile(-111, 40, z)
    return (x1 - x0 + 1) * (y1 - y0 + 1)


def test_the_size_is_the_box_tiles_plus_trails_and_contours(client, user, sources):
    body = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client)).json()
    basemap = 100 * (_tiles_in_box(13) + _tiles_in_box(14))
    dem = 1000 * (_tiles_in_box(8) + _tiles_in_box(9) + _tiles_in_box(10))
    assert body["basemap"] == basemap
    assert body["dem"] == dem
    assert body["bytes"] == basemap + dem + body["contours"] + body["overlay"]
    assert body["contours"] > 0 and body["overlay"] > 0


def test_ground_already_on_the_server_costs_nothing(client, user, sources, monkeypatch):
    """The server keeps each map once now: a box inside an installed area adds
    nothing, and the old estimate said it would download it all again."""
    monkeypatch.setattr(region_registry, "list_active", lambda: [{
        "id": 1, "status": "installed", "bbox": [-113, 39, -110, 42], "geometry": None}])
    body = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client)).json()
    assert body["bytes"] == 0


def test_a_source_that_cannot_be_read_is_an_error_not_a_tiny_area(client, user, sources):
    sources.fail = httpx.ConnectError("Name or service not known")
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 502
    assert "Name or service not known" in resp.json()["detail"]


def test_without_a_basemap_source_there_is_nothing_to_size(client, user, sources, monkeypatch):
    monkeypatch.setattr(settings, "pmtiles_source_url", "")
    resp = client.get(f"/maps/regions/estimate?bbox={BBOX}", headers=_admin(client))
    assert resp.status_code == 400
