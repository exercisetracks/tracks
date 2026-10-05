# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Swimming, rowing, hiking, skiing and climbing plans, and the watch
workouts they become.

These sports used to share one 37-line generic template; each now has its own
builders (calculators/plan/). What is pinned here is what would matter if it
broke: the discipline a goal's sport string carries, the session types each
phase schedules, the rules a sport imposes (finger sessions apart, pool sets in
whole lengths), and that every workout encodes to a FIT file the watch can
read, with the targets its sport supports.

Dates are fixed: the generator takes `today`, so nothing depends on when the
suite runs.
"""

from datetime import date, timedelta
from types import SimpleNamespace

import pytest
from garmin_fit_sdk import Decoder, Stream

from app.calculators.fit_workout import generate_workout_fit
from app.calculators.plan.base import _duration_from_steps, _sport_family
from app.calculators.plan.generator.dispatch import _HIGH_INTENSITY_TYPES
from app.calculators.plan.generator.plan import generate_training_plan
from app.calculators.plan.load import workout_tss
from app.calculators.training_load import estimate_tss

MONDAY = date(2026, 3, 2)
SPORTS = ("swimming", "open_water_swimming", "rowing", "hiking", "cross_country_skiing",
          "backcountry_skiing", "alpine_skiing", "climbing")


def _plan(sport, weeks=12, dpw=5, distance=None, lthr=165):
    goal = SimpleNamespace(event_date=MONDAY + timedelta(weeks=weeks), event_sport=sport,
                           event_distance_meters=distance, days_per_week=dpw, plan_intensity=1.0,
                           mtb_discipline=None, cycling_discipline=None)
    _, workouts = generate_training_plan(goal=goal, activity_history=[], pace_bests=[],
                                         today=MONDAY, threshold_hr=lthr)
    return [w for w in workouts if w["workout_type"] != "race"]


def _decode(workout, sport, lthr=165):
    raw = generate_workout_fit(workout["title"], sport, workout["steps"], workout_id=7,
                               time_created=1_756_000_000_000, pace_coaching=True, lthr=lthr)
    messages, errors = Decoder(Stream.from_byte_array(bytearray(raw))).read()
    assert not errors
    return messages["workout_mesgs"][0], messages["workout_step_mesgs"]


def test_the_ski_sport_string_decides_endurance_or_alpine_training():
    """No discipline column: the sport the goal editors write is the
    discipline. A bare "skiing" goal (saved under Ski race / Skimo / Tour
    presets before the split) must stay an endurance plan."""
    assert _sport_family("skiing") == "nordic_skiing"
    assert _sport_family("cross_country_skiing") == "nordic_skiing"
    assert _sport_family("backcountry_skiing") == "nordic_skiing"
    assert _sport_family("alpine_skiing") == "alpine_skiing"
    assert _sport_family("bouldering") == "climbing"


@pytest.mark.parametrize("sport", SPORTS)
def test_every_new_sport_gets_its_own_sessions_not_the_generic_template(sport):
    """The generic template writes only easy/aerobic/quality/long; any of
    those four showing up alone means the sport fell through the dispatch."""
    types = {w["workout_type"] for w in _plan(sport)}
    assert types - {"easy", "aerobic", "quality", "long"}
    assert "quality" not in types
    for w in _plan(sport):
        assert w["steps"], w["title"]
        assert "Zone 4 · comfortably hard" not in w["description"]


@pytest.mark.parametrize("sport", SPORTS)
def test_every_workout_encodes_to_a_fit_file_the_watch_can_decode(sport):
    """A FIT file that fails to decode is dropped by the watch without a
    word, so every workout of every new sport is round-tripped."""
    for w in _plan(sport, dpw=6):
        _, steps = _decode(w, sport)
        assert steps


def test_pool_sets_are_whole_lengths_with_stroke_and_equipment_on_the_watch():
    """A pool workout on the watch advances by lengths: a 75 m repeat in a
    50 m pool never ends. Stroke and equipment are what make a kick set read
    as a kick set on the wrist."""
    plan = _plan("swimming", dpw=6)
    for w in plan:
        for s in w["steps"]:
            if s.get("distance_m"):
                assert s["distance_m"] % 50 == 0, (w["title"], s)
    technique = next(w for w in plan if w["workout_type"] == "technique")
    wkt, steps = _decode(technique, "swimming")
    assert wkt["sub_sport"] == "lap_swimming"
    kick = next(s for s in steps if s.get("wkt_step_name") == "Kick")
    assert kick["duration_type"] == "distance"
    assert kick["target_type"] == "swim_stroke"
    assert kick["equipment"] == "swim_kickboard"


def test_open_water_sessions_are_timed_and_rehearse_open_water_skills():
    """No walls, no lengths: open water is swum by time, and the sessions
    carry sighting — the skill the pool cannot train."""
    plan = _plan("open_water_swimming", distance=5000.0)
    assert not any(s.get("distance_m") for w in plan for s in w["steps"])
    assert any("sight" in w["description"] for w in plan)
    wkt, _ = _decode(plan[0], "open_water_swimming")
    assert wkt["sub_sport"] == "open_water"


def test_a_swim_repeat_is_timed_at_swim_pace_not_run_pace():
    """Distance repeats used to be timed at running interval pace, which put
    ten 100 m swims at under five minutes."""
    steps = [{"type": "interval_set", "reps": 10, "distance_m": 100, "rest_sec": 15,
              "sec_per_km": 1200}]
    assert _duration_from_steps(steps, None) == 22   # 10 × (2:00 + 15 s)


def test_a_swim_repeat_carries_the_load_of_its_swim_pace():
    """Its load was timed at running interval pace too — a fifth of the real
    time — so the week sizer made swims about five times too long to reach
    the week's load: a no-history 1500 m plan opened with a 132-minute swim."""
    steps = [{"type": "interval_set", "reps": 4, "distance_m": 400, "rest_sec": 0,
              "intensity": "aerobic", "sec_per_km": 1320}]
    # 1.6 km at 22:00/km is 35.2 min, at IF 0.80: 0.5867 h × 0.64 × 100.
    assert workout_tss(steps, None) == pytest.approx(37.55, abs=0.01)
    first_week = [w for w in _plan("swimming", distance=1500.0, dpw=4)
                  if w["scheduled_date"] < MONDAY + timedelta(weeks=1)]
    assert max(w["duration_minutes"] for w in first_week) <= 45
    assert sum(w["duration_minutes"] for w in first_week) <= 150


def test_rowing_pieces_steer_by_stroke_rate_and_steady_state_by_heart_rate():
    """Rowers prescribe pieces by rate; the aerobic bands are physiological,
    so with a threshold HR set they carry heart rate instead."""
    plan = _plan("rowing", dpw=6)
    at = next(w for w in plan if w["workout_type"] == "threshold")
    ut1 = next(w for w in plan if w["workout_type"] == "ut1")
    wkt, steps = _decode(at, "rowing")
    assert wkt["sport"] == "rowing" and wkt["sub_sport"] == "indoor_rowing"
    piece = next(s for s in steps if s.get("wkt_step_name") == "AT")
    assert piece["target_type"] == "cadence"
    assert (piece["custom_target_value_low"], piece["custom_target_value_high"]) == (22, 24)
    _, steps = _decode(ut1, "rowing")
    assert next(s for s in steps if s.get("wkt_step_name") == "UT1")["target_type"] == "heart_rate"


def test_climbing_finger_sessions_are_never_on_consecutive_days():
    """Tendons and pulleys recover more slowly than muscle; two finger-heavy
    days back to back is the classic route to a pulley injury."""
    finger = {"hangboard", "limit_bouldering", "power_endurance", "short_quality"}
    for dpw in range(2, 8):
        days = sorted(w["scheduled_date"] for w in _plan("climbing", weeks=16, dpw=dpw)
                      if w["workout_type"] in finger)
        for a, b in zip(days, days[1:]):
            assert (b - a).days >= 2, (dpw, a, b)


def test_strength_like_sessions_survive_the_polarisation_cap():
    """Hangboarding, limit bouldering and plyometrics are hard on tissue but
    not metabolically; counted as high intensity, the 80/20 cap would delete
    the sessions those sports are built on."""
    for t in ("hangboard", "limit_bouldering", "plyometrics", "eccentric", "agility", "descent"):
        assert t not in _HIGH_INTENSITY_TYPES
    assert any(w["workout_type"] == "hangboard" for w in _plan("climbing"))
    assert any(w["workout_type"] == "plyometrics" for w in _plan("alpine_skiing"))


def test_alpine_plans_are_dry_land_conditioning_on_the_cardio_app():
    """An alpine plan runs before the season; a box-jump circuit should not
    open the watch's ski app."""
    plan = _plan("alpine_skiing")
    types = {w["workout_type"] for w in plan}
    assert {"eccentric", "plyometrics", "agility", "ski_intervals"} <= types
    wkt, _ = _decode(plan[0], "alpine_skiing")
    assert (wkt["sport"], wkt["sub_sport"]) == ("training", "cardio_training")


def test_skimo_climbs_where_cross_country_skis():
    """Same template, different sport: skimo sessions are about skinning,
    vertical and transitions."""
    xc = " ".join(w["description"] for w in _plan("cross_country_skiing"))
    skimo = " ".join(w["description"] for w in _plan("backcountry_skiing"))
    assert "transitions" in skimo and "transitions" not in xc
    assert "roller skis" in xc
    wkt, _ = _decode(_plan("backcountry_skiing")[0], "backcountry_skiing")
    assert (wkt["sport"], wkt["sub_sport"]) == ("alpine_skiing", "backcountry")


def test_hiking_pack_weight_rises_through_the_build_and_stops_at_fifteen_kilos():
    """Load carriage injuries come from jumps in load; the pack grows about a
    kilo a week and never past 15 kg."""
    longs = [w for w in _plan("hiking", weeks=20) if w["workout_type"] == "long"]
    kgs = [int(w["description"].split(" kg pack")[0].rsplit(" ", 1)[1]) for w in longs]
    assert kgs[0] == 5
    assert max(kgs) <= 15
    assert max(kgs) > 5
    rising = [k for k in kgs if k > 5]
    assert all(b - a <= 2 for a, b in zip(rising, rising[1:]))


def test_hiking_trains_descents_from_the_first_block():
    """The repeated-bout effect: a small early eccentric dose protects
    against the soreness of the big one, so descents start in base."""
    plan = _plan("hiking", weeks=16)
    first = min(w["scheduled_date"] for w in plan if w["workout_type"] == "descent")
    assert first < MONDAY + timedelta(weeks=1)


def test_a_climbing_session_earns_load_from_heart_rate_and_time():
    """Climbing has no distance or power; its load is the heart-rate model's,
    as for any HR-only sport, so a climbing plan moves fitness like any other."""
    session = SimpleNamespace(sport="rock_climbing", training_stress_score=None, effective_tss=None,
                              duration_seconds=5400, avg_heart_rate=130, max_heart_rate=175)
    assert estimate_tss(session, threshold_hr=165) == pytest.approx(1.5 * (130 / 165) ** 2 * 100, abs=0.1)
