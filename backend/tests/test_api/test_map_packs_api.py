# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The pack endpoints as the phone uses them: manifest, download, resume."""

import hashlib

import pytest

from app.services import map_packs
from tests.test_services.test_map_packs import server  # noqa: F401  (fixture)


@pytest.fixture(autouse=True)
def no_background_prebuild(monkeypatch):
    """The manifest starts cutting packs in a background thread; in tests that
    thread could outlive its test and cut packs against the next one's state,
    which made the stale-version test flaky in the full suite."""
    monkeypatch.setattr(map_packs, "prebuild", lambda doc: None)


def _overlay_entry(client, cell_bounds):
    m = client.get("/maps/packs/manifest", params={"bbox": ",".join(map(str, cell_bounds))}).json()
    return next(c for c in m["cells"] if c["cell"] == "8/44/90")["layers"]["overlay"]


def test_a_pack_downloads_with_its_checksum_as_the_etag(client, user, server):  # noqa: F811
    e = _overlay_entry(client, server["cell"])
    r = client.get(e["url"].removeprefix("/api"))
    assert r.status_code == 200
    assert r.headers["etag"] == f'"{hashlib.sha256(r.content).hexdigest()}"'


def test_a_dropped_download_resumes_with_range(client, user, server):  # noqa: F811
    """The phone keeps what it has and asks for the rest; If-Range makes the
    server send the rest only if the pack is still the same bytes."""
    e = _overlay_entry(client, server["cell"])
    path = e["url"].removeprefix("/api")
    whole = client.get(path)
    rest = client.get(path, headers={"Range": "bytes=100-", "If-Range": whole.headers["etag"]})
    assert rest.status_code == 206
    assert whole.content[:100] + rest.content == whole.content
    stale = client.get(path, headers={"Range": "bytes=100-", "If-Range": '"not-it"'})
    assert stale.status_code == 200, "a different pack is sent whole, not spliced"


def test_an_old_version_is_a_conflict_naming_the_new_one(client, user, server):  # noqa: F811
    e = _overlay_entry(client, server["cell"])
    server["area"]["updated_at"] = "rebuilt"
    r = client.get(e["url"].removeprefix("/api"))
    assert r.status_code == 409
    assert r.json()["current"] != e["version"]
