# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Race fuelling rules. The phone replays spec/fixtures/fuel_plan.json; these
pin the behaviour a person would notice if it changed."""
from app.calculators import fuel_plan as fp
from app.calculators.race_predictor.fit import generate_race_fit


def test_a_custom_target_replaces_the_calculated_one():
    """An athlete who has trained their gut to 100 g/h must not be told 80."""
    t = fp.targets(180, carbs=100)
    assert t["carbs_g_per_h"] == 100 and t["custom"]["carbs"]
    assert fp.targets(180)["carbs_g_per_h"] == 80


def test_heat_raises_fluid_and_sodium_defaults():
    assert fp.targets(120, temperature_c=33)["fluid_ml_per_h"] > fp.targets(120)["fluid_ml_per_h"]
    assert fp.targets(120, temperature_c=33)["sodium_mg_per_h"] > fp.targets(120)["sodium_mg_per_h"]


def test_the_timeline_never_fuels_at_the_start_or_the_finish():
    items = fp.timeline(60, fp.targets(60, carbs=60, interval=20), [])["items"]
    assert [i["minute"] for i in items] == [20, 40]


def test_a_gel_is_only_taken_once_enough_carbs_are_owed():
    """A 25 g gel against 30 g/h must not be taken every twenty minutes."""
    gel = [{"uid": "g", "name": "Gel", "carbs_g": 25.0}]
    items = fp.timeline(180, fp.targets(180, carbs=30), gel)["items"]
    assert len(items) <= 4


def test_a_bad_gut_day_holds_the_ramp_back():
    """Comfort 1-2 must lower the next target, not carry on climbing."""
    ws = [{"uid": f"w{i}", "scheduled_date": d, "sport": "running", "duration_minutes": 100}
          for i, d in enumerate(["2026-09-01", "2026-09-08", "2026-09-15"])]
    ok = fp.gut_training(90, "2026-11-01", ws, [])
    bad = fp.gut_training(90, "2026-11-01", ws, [
        {"uid": "l", "planned_workout_uid": "w0", "date": "2026-09-01", "comfort": 1, "carbs_g": 40}])
    assert ok["w1"] == 50 and bad["w1"] == 30


def test_strength_sessions_get_no_gut_training_target():
    ws = [{"uid": "s", "scheduled_date": "2026-10-01", "sport": "strength_training", "duration_minutes": 120}]
    assert fp.gut_training(90, "2026-11-01", ws, []) == {}


def test_a_fuelling_reminder_names_the_lap_step_it_falls_in():
    laps = [{"lap": i + 1, "distance_m": 1000.0, "target_sec_per_km": 300.0} for i in range(5)]
    data = generate_race_fit("5K", "running", laps, time_created_ms=1790000000000,
                             fuel_items=[{"distance_m": 2500, "name": "Gel", "carbs_g": 25.0}])
    assert b"Km 3 \xc2\xb7 Gel" in data and b"Km 2 \xc2\xb7" not in data
