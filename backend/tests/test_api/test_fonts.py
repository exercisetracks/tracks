# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Map label glyphs, served from this origin.

A missing range used to be a redirect to the Protomaps CDN, which the web app's
Content-Security-Policy (connect-src 'self') does not let the browser follow —
so every label fell back to locally drawn glyphs.
"""
import pytest

from app.config import settings
from app.routes import fonts


@pytest.fixture
def font_dir(tmp_path, monkeypatch):
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    (tmp_path / "fonts").mkdir()
    return tmp_path / "fonts"


def test_a_missing_range_is_fetched_kept_and_served_not_redirected(client, font_dir, monkeypatch):
    fetched = []
    monkeypatch.setattr(fonts, "_fetch", lambda s, r: fetched.append((s, r)) or b"glyphs")
    resp = client.get("/fonts/Noto Sans Regular/0-255.pbf", follow_redirects=False)
    assert resp.status_code == 200
    assert resp.content == b"glyphs"
    assert (font_dir / "Noto Sans Regular" / "0-255.pbf").read_bytes() == b"glyphs"

    # Served from disk the second time.
    assert client.get("/fonts/Noto Sans Regular/0-255.pbf").content == b"glyphs"
    assert fetched == [("Noto Sans Regular", "0-255")]


def test_an_unavailable_range_is_a_404_and_nothing_is_stored(client, font_dir, monkeypatch):
    monkeypatch.setattr(fonts, "_fetch", lambda s, r: None)
    resp = client.get("/fonts/Noto Sans Regular/0-255.pbf", follow_redirects=False)
    assert resp.status_code == 404
    assert not any(font_dir.rglob("*.pbf"))


@pytest.mark.parametrize("path", [
    "/fonts/..%2F..%2Fetc/0-255.pbf",
    "/fonts/Noto Sans Regular/0-255;x.pbf",
    "/fonts/Noto Sans Regular/..%2F..%2Fsecret.pbf",
])
def test_a_name_that_is_not_a_font_never_reaches_the_cdn(client, font_dir, monkeypatch, path):
    monkeypatch.setattr(fonts, "_fetch", lambda s, r: pytest.fail("fetched"))
    assert client.get(path).status_code == 404
