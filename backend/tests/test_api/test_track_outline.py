# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The activity list's route thumbnails: one outline per activity, shape only."""
from app.calculators.track_outline import MAX_POINTS, normalise

from .test_mobile_client_api import _auth, _make_activity_with_track


def test_an_outline_carries_no_coordinates(client, user, db):
    """The browser caches outlines in localStorage, which is only acceptable
    because nothing in one says where on earth the route was."""
    headers, material = _auth(client, db, user)
    a = _make_activity_with_track(db, user, material, lat0=47.5, lng0=8.5, npts=50)
    body = client.get(f"/activities/{a.id}/outline", headers=headers).json()
    xs = [p[0] for p in body["points"]]
    ys = [p[1] for p in body["points"]]
    assert all(0 <= v <= 1 for v in xs + ys)
    assert "lat" not in str(body) and "47.5" not in str(body)


def test_an_activity_without_gps_has_no_outline(client, user, db):
    headers, material = _auth(client, db, user)
    a = _make_activity_with_track(db, user, material, npts=1)
    assert client.get(f"/activities/{a.id}/outline", headers=headers).json() is None


def test_a_long_track_is_thinned_but_still_ends_where_it_ended():
    pts = [(8.0 + i * 1e-4, 47.0 + (i % 7) * 1e-5) for i in range(5000)]
    shape = normalise(pts)
    assert len(shape["points"]) <= MAX_POINTS + 2
    assert shape["points"][-1][0] == 1.0


def test_a_track_that_never_moved_has_no_shape():
    assert normalise([(8.0, 47.0)] * 10) is None
