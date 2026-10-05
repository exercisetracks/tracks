# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""GET /maps/style.json — the style document both clients render from.

The interesting behaviour is the request-time substitution: a placeholder that
must be gone, a base_url rewrite that must be safe to expose publicly, and zoom
ranges narrowed to the archives that are actually on disk.
"""
import json

import pytest

from app.config import settings
from app.routes import map_style
from tests.test_services.test_tile_coverage import write_archive


@pytest.fixture(autouse=True)
def map_archives(tmp_path, monkeypatch):
    """A fully-downloaded install, unless a test says otherwise.

    Without this the tests run against a `map_data_dir` that does not exist, and
    every vector source is correctly dropped — which would leave most of the
    assertions below passing over an empty collection and checking nothing.
    """
    for name, (lo, hi) in {
        "planet_basemap": (0, 12),
        "master_basemap_detail": (13, 15),
        "master_overlay": (6, 15),
        "master_contours": (9, 12),
        "master_routes": (3, 12),
        "master_dem": (8, 12),
        "planet_dem_z7": (0, 7),
    }.items():
        write_archive(tmp_path / f"{name}.pmtiles", lo, hi)
    monkeypatch.setattr(settings, "map_data_dir", str(tmp_path))
    return tmp_path


class TestServesTheStyle:
    def test_returns_a_maplibre_style(self, client):
        resp = client.get("/maps/style.json")
        assert resp.status_code == 200
        style = resp.json()
        assert style["version"] == 8
        assert style["layers"]
        assert style["sources"]

    def test_is_public(self, client, user, db):
        """MapLibre fetches it alongside /fonts and /sprite, which are public
        for the same reason — and a client that can't authenticate can't fetch
        a single tile through it anyway."""
        client.post("/auth/setup", json={
            "username": "admin", "name": "Admin", "password": "testpass123",
            "enable_garmin_sync": False,
        })
        assert client.get("/maps/style.json").status_code == 200

    def test_placeholder_is_substituted(self, client):
        """Left in place, every client would request a literal ?v=__TILE_VERSION__
        and cache busting would be silently dead."""
        assert map_style._VERSION_PLACEHOLDER not in client.get("/maps/style.json").text


class TestBaseUrlRewrite:
    def test_same_origin_by_default(self, client):
        style = client.get("/maps/style.json").json()
        assert style["glyphs"].startswith("/api/")

    def test_absolute_urls_for_native_clients(self, client):
        """MapLibre Native has no page origin to resolve /api against."""
        style = client.get(
            "/maps/style.json", params={"base_url": "https://tracks.example.com"}
        ).json()
        assert style["glyphs"].startswith("https://tracks.example.com/api/")
        assert style["sprite"].startswith("https://tracks.example.com/api/")
        for src in style["sources"].values():
            for url in src.get("tiles", []):
                assert url.startswith("https://tracks.example.com/api/")

    def test_no_origin_relative_paths_survive_the_rewrite(self, client):
        raw = client.get(
            "/maps/style.json", params={"base_url": "https://tracks.example.com"}
        ).text
        assert '"/api' not in raw

    @pytest.mark.parametrize("bad", [
        "not-a-url",
        "javascript:alert(1)",
        "//evil.com",
        "https://evil.com/path",
        "https://evil.com?x=1",
        "ftp://example.com",
    ])
    def test_rejects_anything_that_is_not_a_bare_origin(self, client, bad):
        """This value is interpolated into URLs the client will fetch, and
        anyone can call this endpoint — so an unvalidated base_url would let a
        caller mint a style document pointing at a host of their choosing."""
        resp = client.get("/maps/style.json", params={"base_url": bad})
        assert resp.status_code == 422, f"{bad!r} was accepted"

    def test_empty_base_url_means_same_origin(self, client):
        """An empty value is the same as omitting it — no rewrite, not an
        error. A client building the query string from an unset config field
        shouldn't get a 422 for it."""
        style = client.get("/maps/style.json", params={"base_url": ""}).json()
        assert style["glyphs"].startswith("/api/")

    def test_trailing_slash_does_not_double_up(self, client):
        style = client.get(
            "/maps/style.json", params={"base_url": "https://tracks.example.com/"}
        ).json()
        assert "//api/" not in style["glyphs"].replace("https://", "")


class TestFitsTheStyleToWhatIsDownloaded:
    """A source may only promise zooms an archive on disk can serve.

    Past a source's maxzoom MapLibre overzooms the deepest tile it has, which is
    what keeps a map legible when you zoom in further than the data goes.
    Promise more than exists and it requests tiles that 404 instead — and on
    MapLibre Native an absent tile is an *empty* tile, so the renderer stops
    falling back to the parent and the map goes to bare background colour.
    """

    def sources(self, client, **params):
        return client.get("/maps/style.json", params=params).json()["sources"]

    def test_basemap_reaches_the_detail_archive_when_it_is_there(self, client):
        assert self.sources(client)["basemap"]["maxzoom"] == 15

    def test_basemap_stops_at_the_overview_without_a_region(self, client, map_archives):
        """The stock install, and the bug as reported: zoom past 12 and the map
        turned into a flat tan rectangle."""
        (map_archives / "master_basemap_detail.pmtiles").unlink()
        assert self.sources(client)["basemap"]["maxzoom"] == 12

    def test_a_deliberate_cap_is_never_raised(self, client):
        """`basemap_overview` is capped at 7 against a z0-12 archive on purpose,
        so the z7 landcover overzooms across the whole detail band. Fitting must
        narrow claims, never widen them."""
        assert self.sources(client)["basemap_overview"]["maxzoom"] == 7

    def test_minzoom_is_never_lowered(self, client, map_archives):
        """The mirror image: minzoom keeps MapLibre off levels the archive does
        not carry, so lowering it would manufacture the 404s this removes."""
        write_archive(map_archives / "master_routes.pmtiles", 0, 12)
        assert self.sources(client)["routes_osm"]["minzoom"] == 3

    def test_a_source_with_no_archive_is_dropped(self, client, map_archives):
        (map_archives / "master_overlay.pmtiles").unlink()
        assert "overlay" not in self.sources(client)

    def test_layers_never_outlive_their_source(self, client, map_archives):
        """MapLibre refuses to load a style whose layer names a source that is
        not there, so dropping the layers too is required, not tidiness."""
        (map_archives / "master_overlay.pmtiles").unlink()
        (map_archives / "master_contours.pmtiles").unlink()
        style = client.get("/maps/style.json").json()
        present = set(style["sources"])
        orphans = [l["id"] for l in style["layers"] if l.get("source") not in present
                   and l.get("source") is not None]
        assert orphans == []
        assert style["layers"], "the basemap layers should have survived"

    def test_client_fed_sources_are_left_alone(self, client, map_archives):
        """GeoJSON sources are filled in at runtime by the client and have no
        archive to check — dropping them would take the user's own tracks off
        the map."""
        for name in ("planet_basemap", "master_basemap_detail", "master_overlay",
                     "master_contours", "master_routes"):
            (map_archives / f"{name}.pmtiles").unlink()
        sources = self.sources(client)
        assert sources["activity_tracks"]["type"] == "geojson"


class TestRelief:
    """Hillshade is in the served document, which the browser's copy is not.

    The web app adds its DEM sources after load, once the version endpoint says
    an archive exists. MapLibre Native cannot patch its own style that way, so
    the phone gets relief only if the document carries it — and the document can
    only afford to name `master_dem` because a missing archive is dropped here,
    layers and all, before it goes out.
    """

    def style(self, client):
        return client.get("/maps/style.json").json()

    def test_relief_is_served_when_the_archives_are_there(self, client):
        style = self.style(client)
        assert style["sources"]["dem"]["type"] == "raster-dem"
        ids = [l["id"] for l in style["layers"]]
        assert "hillshade" in ids
        assert "hillshade_overview" in ids

    def test_regional_relief_is_honest_about_its_depth(self, client):
        """The style asks for z16; the archive stops at 12. Promising the deeper
        one is the empty-tile failure this whole mechanism exists to prevent."""
        assert self.style(client)["sources"]["dem"]["maxzoom"] == 12

    def test_no_region_downloaded_means_no_regional_hillshade(self, client, map_archives):
        (map_archives / "master_dem.pmtiles").unlink()
        style = self.style(client)
        assert "dem" not in style["sources"]
        ids = [l["id"] for l in style["layers"]]
        assert "hillshade" not in ids
        # The global overview survives on its own: relief everywhere is the
        # point of carrying a second, shallower DEM.
        assert "hillshade_overview" in ids

    def test_an_install_with_no_elevation_data_at_all_still_loads(self, client, map_archives):
        (map_archives / "master_dem.pmtiles").unlink()
        (map_archives / "planet_dem_z7.pmtiles").unlink()
        style = self.style(client)
        present = set(style["sources"])
        assert not {"dem", "dem_overview"} & present
        assert [l["id"] for l in style["layers"] if l.get("source") in ("dem", "dem_overview")] == []
        assert style["layers"], "the rest of the map should be untouched"


class TestMissingArtifact:
    def test_reports_a_build_step_not_a_crash(self, client, monkeypatch, tmp_path):
        """A backend running against a tree that was never built has no
        artifact. The fix is a build step, so say that rather than 500."""
        monkeypatch.setattr(map_style, "_STYLE_PATH", tmp_path / "nope.json")
        resp = client.get("/maps/style.json")
        assert resp.status_code == 503
        assert "build-map-style" in resp.json()["detail"]
