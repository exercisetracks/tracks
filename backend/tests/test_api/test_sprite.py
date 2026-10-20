# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""GET /sprite — the map's icon sheet, and which copies of it may be cached.

A sheet is a manifest and an image fetched apart and rebuilt in place. When
both were cached for a year under one name, a browser paired a stale image
with a new manifest and the desktop map drew every icon one slot over. These
tests hold the rule that prevents it: cached forever only under the version of
the bytes it carries.
"""
import pytest

from app.config import settings
from app.services import sprite_builder
from app.services.sprite_builder import SHEET_FILES


@pytest.fixture(autouse=True)
def sheet(tmp_path, monkeypatch):
    """A built sheet, without drawing one: the route only cares about bytes."""
    d = tmp_path / "sprite"
    d.mkdir()
    for n in SHEET_FILES:
        (d / n).write_bytes(b"{}" if n.endswith(".json") else b"\x89PNG")
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    return d


def _version(client):
    return client.get("/sprite/version").json()["version"]


def test_the_current_version_is_cached_forever(client):
    """Those bytes never change under that URL, so there is no reason to ask again."""
    v = _version(client)
    for f in ("usgs.json", "usgs.png", "usgs@2x.json", "usgs@2x.png"):
        resp = client.get(f"/sprite/{f}", params={"v": v})
        assert resp.status_code == 200, f
        assert resp.headers["cache-control"] == "public, max-age=31536000, immutable", f


def test_a_rebuild_changes_the_version(client, sheet):
    """The version is the content: a new image under the old name is a new version."""
    before = _version(client)
    (sheet / "usgs@2x.png").write_bytes(b"\x89PNG rebuilt")
    assert _version(client) != before


def test_a_stale_version_is_not_served(client, sheet):
    """A tab open across a rebuild may hold the old manifest already; handing it
    today's image under the old version would pair them for a year."""
    old = _version(client)
    (sheet / "usgs@2x.png").write_bytes(b"\x89PNG rebuilt")
    assert client.get("/sprite/usgs@2x.png", params={"v": old}).status_code == 404


def test_an_unversioned_sheet_is_revalidated(client):
    """The phone asks without a version; it must not keep a copy it cannot check."""
    resp = client.get("/sprite/usgs.json")
    assert resp.status_code == 200
    assert resp.headers["cache-control"] == "no-cache"


def test_no_version_while_the_sheet_is_missing(client, sheet):
    """Mid-build there is no pair to name; the client falls back to revalidating."""
    (sheet / "usgs.png").unlink()
    assert _version(client) is None


def test_the_version_is_public(client):
    """MapLibre fetches the sheet with no credentials, and so must the version."""
    assert client.get("/sprite/version").status_code == 200
