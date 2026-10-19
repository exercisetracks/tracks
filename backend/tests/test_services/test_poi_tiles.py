# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The map's places as tiles, ranked once on the server."""

from app.services import poi_tiles


def _place(lng, lat, kind="peak", min_zoom=10, ele=None, name="x"):
    return {"lng": lng, "lat": lat, "rank_ele": ele,
            "properties": {"kind": kind, "min_zoom": min_zoom, "name": name}}


def test_the_highest_peak_around_shows_first():
    """GNIS gives every summit the same grade; height is what tells a range's
    main peak from the knoll beside it."""
    low = _place(-116.000, 47.000, ele=900, name="Knoll")
    high = _place(-116.001, 47.001, ele=2100, name="Big Peak")
    poi_tiles.rank([low, high])
    assert high["properties"]["icon_zoom"] == 10
    assert low["properties"]["icon_zoom"] > high["properties"]["icon_zoom"]


def test_a_more_important_grade_wins_whatever_its_height():
    camp = _place(-116.0, 47.0, kind="camp_site", min_zoom=9, ele=None)
    peak = _place(-116.0005, 47.0005, ele=3000)
    poi_tiles.rank([peak, camp])
    assert camp["properties"]["icon_zoom"] == 9


def test_names_wait_for_their_own_room_and_never_before_z12():
    a = _place(-116.0, 47.0, ele=2000)
    b = _place(-116.0002, 47.0002, ele=1900)
    poi_tiles.rank([a, b])
    assert a["properties"]["label_zoom"] == 12
    assert b["properties"]["label_zoom"] > 12


def test_places_far_apart_both_show_at_their_grade():
    a = _place(-116.0, 47.0)
    b = _place(-110.0, 40.0)
    poi_tiles.rank([a, b])
    assert a["properties"]["icon_zoom"] == b["properties"]["icon_zoom"] == 10


def test_a_place_that_never_fits_is_left_out_of_the_tiles():
    """Hidden at every zoom costs bytes in every tile for nothing -- except the
    kinds drawn whatever their rank, such as springs and lake names."""
    assert poi_tiles._tile_min_zoom({"kind": "stream", "icon_zoom": poi_tiles.NEVER,
                                     "label_zoom": poi_tiles.NEVER}) is None
    assert poi_tiles._tile_min_zoom({"kind": "spring", "icon_zoom": poi_tiles.NEVER,
                                     "label_zoom": poi_tiles.NEVER}) == 10
    assert poi_tiles._tile_min_zoom({"kind": "peak", "icon_zoom": 16, "label_zoom": 15}) == poi_tiles.MAX_ZOOM
