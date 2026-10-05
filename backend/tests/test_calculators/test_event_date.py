# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The recommended date a new event goal starts from, and which edits rebuild
a plan — replayed from spec/fixtures/goal_planning.json, which the phone
replays too.

The web shows the server's date and the phone computes its own offline; a
change here that is not regenerated into the corpus would leave the two
recommending different Sundays for the same race.
"""
import json
from datetime import date
from pathlib import Path

import pytest

from app.calculators import event_date as ed
from app.calculators.plan import staleness as st

CORPUS = json.loads(Path("/spec/fixtures/goal_planning.json").read_text())


@pytest.mark.parametrize("case", CORPUS["event_date"],
                         ids=lambda c: f"{c['sport']}-{c['distance_m']}-{c['ctl']}-{c['today']}")
def test_the_event_date_corpus_still_holds(case):
    got = ed.recommend_event_date(case["sport"], case["distance_m"], date.fromisoformat(case["today"]),
                                  case["ctl"], case["sport_tss"], case["total_tss"])
    assert got == case["expect"]


def test_the_staleness_rules_are_the_corpus_rules():
    assert sorted(st.NON_PLAN_GOAL_FIELDS) == CORPUS["non_plan_goal_fields"]
    assert sorted(st.PLAN_SETTINGS) == CORPUS["plan_settings"]
    for case in CORPUS["goal_edits"]:
        assert st.goal_edit_stales_plan(case["changed"]) == case["expect"], case["changed"]


def test_with_no_history_a_5k_is_about_a_month_and_a_marathon_about_four():
    """The general rule the user asked for: one month for the shortest,
    six for the longest, the rest in between by how long the event takes."""
    today = date(2026, 9, 28)
    five = ed.recommend_event_date("running", 5000, today, 0.0, 0.0, 0.0)
    marathon = ed.recommend_event_date("running", 42195, today, 0.0, 0.0, 0.0)
    ironman = ed.recommend_event_date("triathlon", 226000, today, 0.0, 0.0, 0.0)
    assert (five["basis"], five["weeks"]) == ("general", 4)
    assert 16 <= marathon["weeks"] <= 20
    assert ironman["weeks"] == 26


def test_a_fit_runner_is_ready_sooner_than_a_beginner():
    today = date(2026, 9, 28)
    fit = ed.recommend_event_date("running", 21097, today, 60.0, 2000.0, 2000.0)
    new = ed.recommend_event_date("running", 21097, today, 10.0, 300.0, 300.0)
    assert fit["basis"] == new["basis"] == "history"
    assert fit["weeks"] < new["weeks"]


def test_fitness_from_another_sport_only_partly_counts():
    """A cyclist at CTL 60 is not a marathoner at CTL 60."""
    today = date(2026, 9, 28)
    runner = ed.recommend_event_date("running", 42195, today, 60.0, 2000.0, 2000.0)
    cyclist = ed.recommend_event_date("running", 42195, today, 60.0, 0.0, 2000.0)
    assert cyclist["weeks"] > runner["weeks"]


def test_the_date_is_a_sunday():
    got = ed.recommend_event_date("running", 10000, date(2026, 9, 30), 0.0, 0.0, 0.0)
    assert date.fromisoformat(got["date"]).weekday() == 6


def test_a_triathlon_counts_all_three_sports():
    assert ed.counts_toward("triathlon", "open_water_swimming")
    assert ed.counts_toward("triathlon", "cycling")
    assert not ed.counts_toward("triathlon", "hiking")
    assert not ed.counts_toward("running", "cycling")
