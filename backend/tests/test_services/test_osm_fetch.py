# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Fetching OSM data: which extracts, and surviving a dropped connection."""

import contextlib

import pytest

from app.services.osm_source import geofabrik, osmium_runner


def _feature(ident, w, s, e, n):
    return {"properties": {"id": ident, "urls": {"pbf": f"https://x/{ident}.pbf"}},
            "geometry": {"type": "Polygon", "coordinates": [[[w, s], [e, s], [e, n], [w, n], [w, s]]]}}


@pytest.fixture
def index(monkeypatch):
    feats = [
        _feature("us-west", -125, 31, -102, 49),
        _feature("washington", -125, 45.5, -116.9, 49),
        _feature("idaho", -117.3, 42, -111, 49),
    ]
    monkeypatch.setattr(geofabrik, "_load_index", lambda: feats)


def test_a_box_across_a_state_line_uses_both_states_not_their_region(index):
    """us-west is several times the two states; downloading and reading it for
    a box on the WA/ID line was most of a small area's build (audit F21)."""
    cover = geofabrik.find_cover((-117.6, 46.5, -116.8, 46.8))
    assert sorted(c[0] for c in cover) == ["idaho", "washington"]


def test_a_box_inside_one_state_uses_that_state(index):
    assert [c[0] for c in geofabrik.find_cover((-121, 46, -120, 47))] == ["washington"]


class _Stream:
    """One response of a fake download: serves `body` from the Range asked for,
    and can drop the connection after `fail_after` bytes."""
    def __init__(self, body, headers, fail_after=None):
        start = 0
        rng = headers.get("Range")
        if rng:
            start = int(rng.split("=")[1].rstrip("-"))
        self.status_code = 206 if rng else 200
        self.data = body[start:]
        self.headers = {"content-length": str(len(self.data))}
        self.fail_after = fail_after

    def raise_for_status(self):
        pass

    def iter_bytes(self, n):
        sent = 0
        for i in range(0, len(self.data), 4):
            if self.fail_after is not None and sent >= self.fail_after:
                raise ConnectionError("connection reset")
            yield self.data[i:i + 4]
            sent += 4


def test_a_dropped_download_resumes_where_it_stopped(tmp_path, monkeypatch):
    """Any failure used to throw away everything fetched so far — gigabytes,
    for a large extract — and there was no timeout to notice a stall at all."""
    body = bytes(range(64))
    asked = []

    @contextlib.contextmanager
    def stream(method, url, headers=None, **kw):
        asked.append(headers.get("Range"))
        yield _Stream(body, headers, fail_after=24 if len(asked) == 1 else None)

    monkeypatch.setattr(osmium_runner.httpx, "stream", stream)
    dest = tmp_path / "x.raw.osm.pbf"
    osmium_runner._download_resumable("https://x/x.pbf", dest, "x")
    assert dest.read_bytes() == body
    assert asked == [None, "bytes=24-"]
