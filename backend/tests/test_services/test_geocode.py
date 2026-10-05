# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Naming a region from its bbox.

The name is prefilled into an editable field, so the bar is "a person could read
this back", not "this is the canonical toponym". A name nobody can read is worse
than coordinates, because the user has to select and delete it before typing
their own.
"""

from app.services import geocode


class TestUsable:
    def test_keeps_an_ordinary_name(self):
        assert geocode._usable("Springfield, Illinois") == "Springfield, Illinois"

    def test_collapses_stray_whitespace(self):
        assert geocode._usable("Springfield,   Illinois") == "Springfield, Illinois"

    def test_takes_one_variant_when_several_are_joined(self):
        """Nominatim packs alternatives into one field with a slash."""
        assert geocode._usable("Asela / ኣሰላ") == "Asela"

    def test_rejects_a_multi_script_pile_up(self):
        """The bug as reported: four scripts, no usable name. `accept-language`
        prevents most of these; this is the backstop for a place with no English
        exonym at all."""
        assert geocode._usable("Assela ኣሰላ عسلة, النعامة") is None

    def test_rejects_a_name_in_another_script(self):
        assert geocode._usable("ኣሰላ") is None

    def test_rejects_a_name_with_no_letters(self):
        assert geocode._usable("--") is None
        assert geocode._usable("   ") is None

    def test_accepts_accented_latin(self):
        """Latin script, not ASCII — narrowing to ASCII would reject most of
        Europe."""
        assert geocode._usable("Chamonix-Mont-Blanc") == "Chamonix-Mont-Blanc"
        assert geocode._usable("Grindelwald, Bern") == "Grindelwald, Bern"
        assert geocode._usable("Åre, Jämtland") == "Åre, Jämtland"


class TestSuggestRegionName:
    def test_falls_back_to_coordinates(self, monkeypatch):
        """Unglamorous and always legible, which is the point."""
        monkeypatch.setattr(geocode, "_nominatim_reverse", lambda lat, lon: None)
        name = geocode.suggest_region_name([-150.3, 39.5, -150.0, 39.8])
        assert name == "39.65°N, 150.15°W"

    def test_survives_a_geocoder_that_is_down(self, monkeypatch):
        """A region download must not fail because a third-party lookup did."""
        def boom(lat, lon):
            raise RuntimeError("nominatim unreachable")

        monkeypatch.setattr(geocode, "_nominatim_reverse", boom)
        assert geocode.suggest_region_name([-150.3, 39.5, -150.0, 39.8])

    def test_uses_the_geocoded_name_when_there_is_one(self, monkeypatch):
        monkeypatch.setattr(geocode, "_nominatim_reverse", lambda lat, lon: "Springfield, Illinois")
        assert geocode.suggest_region_name([-150.3, 39.5, -150.0, 39.8]) == "Springfield, Illinois"
