# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Deleting the basemap so a broken one can be fetched again."""
import pytest

from app.config import settings
from app.routes.regions import crud


@pytest.fixture
def map_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    for name in ("planet_basemap.pmtiles", "master_overlay.pmtiles"):
        (tmp_path / name).write_bytes(b"x")
    (tmp_path / "fonts").mkdir()
    (tmp_path / "fonts" / "keep.pbf").write_bytes(b"x")
    monkeypatch.setattr(crud.region_registry, "list_active", lambda: [])
    # The tracker's state file is the real /map-data one (its path is fixed at
    # import), so a basemap downloading on this machine would make these 409.
    monkeypatch.setattr(crud.global_download_tracker, "get_all", lambda: [])
    monkeypatch.setattr("app.services.region_merger.write_reload_trigger", lambda: None)
    started = []
    monkeypatch.setattr("app.services.global_overview.start_map_download", lambda: started.append("map"))
    monkeypatch.setattr("app.services.global_dem.start_dem_download", lambda: started.append("dem"))
    return tmp_path, started


def _admin(client):
    r = client.post("/auth/setup", json={"username": "admin", "name": "Admin",
                                         "password": "testpass123", "enable_garmin_sync": False})
    return {"Authorization": f"Bearer {r.json()['access_token']}"}


def test_the_tiles_go_and_the_styles_assets_stay(client, user, db, map_dir):
    path, _ = map_dir
    resp = client.post("/maps/basemap/reset", headers=_admin(client))
    assert resp.status_code == 200, resp.text
    assert not (path / "planet_basemap.pmtiles").exists()
    assert not (path / "master_overlay.pmtiles").exists()
    assert (path / "fonts" / "keep.pbf").exists()


def test_with_downloads_on_the_basemap_is_fetched_again(client, user, db, map_dir):
    _, started = map_dir
    headers = _admin(client)
    from app.models.user_settings import UserSettings
    db.query(UserSettings).first().map_enabled = True
    db.commit()
    assert client.post("/maps/basemap/reset", headers=headers).json()["redownloading"] is True
    assert "map" in started


def test_it_is_refused_while_a_download_is_running(client, user, db, map_dir, monkeypatch):
    monkeypatch.setattr(crud.region_registry, "list_active",
                        lambda: [{"id": 1, "status": "downloading", "bbox": [0, 0, 1, 1]}])
    assert client.post("/maps/basemap/reset", headers=_admin(client)).status_code == 409
