# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Ski runs and nordic trails in the trail overlay.

Most runs are OSM ways tagged only `piste:type`, with no highway, and the
overlay used to keep highways alone: a ski area's runs never reached the map.
"""

from shapely.geometry import LineString

from app.services import trail_classify
from app.services.osm_source import TAGS_FILTER_EXPR
from app.services.osm_source.config import tags_filter_stamp

RUN = LineString([(-116.0, 47.0), (-116.0, 47.01)])  # about 1.1 km


def test_a_run_with_no_highway_is_a_piste():
    props, mz = trail_classify.classify_feature(
        {"piste:type": "downhill", "piste:difficulty": "intermediate", "name": "Upper Ridge"}, RUN)
    assert props["kind"] == "piste"
    assert props["use"] == "ski"
    assert props["piste_type"] == "downhill"
    assert props["piste_difficulty"] == "intermediate"
    assert props["name"] == "Upper Ridge"
    assert mz == 10  # named: with the resort, not after it


def test_a_piste_named_only_by_piste_name_keeps_its_name():
    props, _ = trail_classify.classify_feature({"piste:type": "nordic", "piste:name": "Lakeside Loop"}, RUN)
    assert props["name"] == "Lakeside Loop"


def test_a_summer_trail_that_is_a_winter_piste_stays_a_trail_with_the_piste_tags():
    props, _ = trail_classify.classify_feature(
        {"highway": "path", "piste:type": "nordic", "piste:grooming": "classic"}, RUN)
    assert props["kind"] == "path"
    assert props["use"] == "foot"
    assert props["piste_type"] == "nordic"
    assert props["piste_grooming"] == "classic"


def test_an_unknown_difficulty_is_left_blank_and_other_ways_are_still_skipped():
    props, _ = trail_classify.classify_feature({"piste:type": "downhill", "piste:difficulty": "spicy"}, RUN)
    assert props["piste_difficulty"] == ""
    assert trail_classify.classify_feature({"building": "yes"}, RUN) is None
    assert trail_classify.classify_feature({"piste:type": "made_up"}, RUN) is None


def test_the_extract_keeps_pistes_and_says_what_it_was_filtered_by():
    """A slim extract cached before pistes were filtered in has none; the stamp
    is what tells it apart, so it is refreshed rather than trusted."""
    assert "w/piste:type" in TAGS_FILTER_EXPR
    assert len(tags_filter_stamp()) == 12


def test_an_extract_filtered_for_less_is_refreshed_and_still_served(tmp_path, monkeypatch):
    from app.services.osm_source import osmium_runner
    monkeypatch.setattr(osmium_runner, "_osm_dir", lambda: tmp_path)
    refreshed = []
    monkeypatch.setattr(osmium_runner, "_refresh_later", lambda eid, url, slim: refreshed.append(eid))
    slim = tmp_path / "idaho.filtered.osm.pbf"
    slim.write_bytes(b"old extract, no stamp")
    assert osmium_runner.ensure_extract("idaho", "https://example.invalid/idaho.pbf") == slim
    assert refreshed == ["idaho"]
    # Stamped with today's filter: served, not refreshed.
    refreshed.clear()
    (tmp_path / "idaho.filtered.osm.pbf.filter").write_text(tags_filter_stamp())
    assert osmium_runner.ensure_extract("idaho", "https://example.invalid/idaho.pbf") == slim
    assert refreshed == []
