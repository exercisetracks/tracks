# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Recognising what a search box was handed, before any database is involved.

These are the cheap checks that decide whether a query is a coordinate, a
category, a "near" phrase or a street corner — run on every keystroke, so they
are pure string handling. The interesting cases are all the ones that must
*not* match: a place genuinely named "Coffee Creek", a "bed and breakfast" that
is not a street corner, a latitude of 300.
"""

from app.routes.poi.categories import match_category
from app.routes.poi.query_shapes import (
    parse_coordinates, split_intersection, split_near, strip_self_reference,
)


class TestCoordinates:
    def test_decimal_pair(self):
        assert parse_coordinates("39.75, -150.22") == (39.75, -150.22)

    def test_decimal_without_a_comma(self):
        assert parse_coordinates("39.75 -150.22") == (39.75, -150.22)

    def test_degrees_minutes_seconds(self):
        lat, lng = parse_coordinates("39°45'23\"N 150°13'10\"W")
        assert round(lat, 4) == 39.7564
        assert round(lng, 4) == -150.2194

    def test_hemisphere_may_lead(self):
        lat, lng = parse_coordinates("N39°45' W150°13'")
        assert round(lat, 3) == 39.75
        assert round(lng, 3) == -150.217

    def test_southern_and_eastern_hemispheres(self):
        lat, lng = parse_coordinates("33°52'S 151°12'E")
        assert lat < 0 and lng > 0

    def test_an_impossible_latitude_is_not_a_coordinate(self):
        assert parse_coordinates("300, -150") is None

    def test_a_place_name_is_not_a_coordinate(self):
        assert parse_coordinates("Granite Peak") is None

    def test_a_bare_number_pair_without_hemispheres_is_refused(self):
        """"39 45 12 105 13 10" could be anything; guessing is worse than
        falling through to a name search."""
        assert parse_coordinates("39 45 12 105 13 10") is None


class TestCategories:
    def test_a_plain_category(self):
        assert "cafe" in match_category("coffee")

    def test_filler_words_are_ignored(self):
        assert "cafe" in match_category("nearest coffee near me")
        assert "fuel" in match_category("find me a gas station")

    def test_a_longer_phrase_wins_over_a_word_inside_it(self):
        """"gas station" must not be answered as "station", and "hot spring"
        must not be answered as "spring"."""
        assert match_category("gas station") == ("fuel",)
        assert "hot_spring" in match_category("hot springs")

    def test_a_place_that_contains_a_category_word_is_not_a_category(self):
        """Coffee Creek is a real place. Answering it with a list of cafes is
        the exact failure this guard exists for."""
        assert match_category("coffee creek") is None
        assert match_category("Spring Creek") is None
        assert match_category("Lake Marian") is None

    def test_an_unknown_word_is_not_a_category(self):
        assert match_category("hollowbrook") is None

    def test_outdoor_categories_cover_both_importers(self):
        """GNIS says "Summit" and OSM says "peak"; a search for peaks wants
        both."""
        kinds = match_category("peaks")
        assert "peak" in kinds and "Summit" in kinds


class TestNear:
    def test_splits_a_category_from_a_place(self):
        assert split_near("coffee near Fairview") == ("coffee", "Fairview")

    def test_in_also_splits(self):
        assert split_near("pizza in Fairview") == ("pizza", "Fairview")

    def test_near_me_is_not_a_place(self):
        """The map already knows where "me" is; treating it as a place name
        would send the search looking for a town called Me."""
        assert split_near("coffee near me") is None
        assert split_near("gas around here") is None

    def test_a_plain_name_does_not_split(self):
        assert split_near("Granite Peak") is None

    def test_self_reference_is_stripped_for_matching(self):
        assert strip_self_reference("coffee near me") == "coffee"
        assert strip_self_reference("Granite Peak") == "Granite Peak"


class TestIntersection:
    def test_and_splits_two_streets(self):
        assert split_intersection("Maple and Broadway") == ("Maple", "Broadway")

    def test_ampersand_splits(self):
        assert split_intersection("Maple & Broadway") == ("Maple", "Broadway")

    def test_a_plain_name_does_not_split(self):
        assert split_intersection("Granite Peak") is None

    def test_three_ands_is_not_a_corner(self):
        """"bed and breakfast and more" is prose, not a junction."""
        assert split_intersection("bed and breakfast and more") is None
