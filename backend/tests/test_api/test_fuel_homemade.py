# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The homemade mix through the API: carried as the reserved "homemade"
product, made to the race's own recipe and bottle."""
from datetime import date, timedelta

from app.calculators import fuel_plan as fp
from app.models.coaching import RacePlan, TrainingGoal


def _race(db, user, **plan):
    g = TrainingGoal(user_id=user.id, goal_type="event", event_name="Ultra", event_sport="running",
                     event_distance_meters=50_000, event_date=date.today() + timedelta(days=60))
    db.add(g)
    db.flush()
    db.add(RacePlan(user_id=user.id, goal_id=g.id, predicted_seconds=5 * 3600, **plan))
    db.commit()
    return g


def test_carrying_the_mix_puts_its_sips_and_recipe_in_the_plan(client, user, db):
    g = _race(db, user, fuel_product_uids=[fp.HOMEMADE_UID], fuel_mix_preset="natural", fuel_mix_bottle_ml=600)
    plan = client.get(f"/fuel/goals/{g.id}/plan").json()
    mix = plan["homemade"]
    assert mix["preset"] == "natural" and mix["bottle_ml"] == 600
    assert "honey" in {i["key"] for i in mix["ingredients"]}
    sips = [i for i in plan["timeline"]["items"] if i["product_uid"] == fp.HOMEMADE_UID]
    assert sips and mix["bottles"] * 600 >= sum(i["fluid_ml"] for i in sips)
    assert plan["ceilings"]["carbs_g_per_h"] == 120


def test_without_the_mix_there_is_no_recipe(client, user, db):
    g = _race(db, user)
    assert client.get(f"/fuel/goals/{g.id}/plan").json()["homemade"] is None


def test_the_recipe_is_picked_by_carb_rate_unless_chosen():
    """Sugar alone is 1:1 glucose:fructose, fine below 60 g/h; above, the
    high-carb recipe sets the ratio with maltodextrin."""
    assert fp.mix_preset(60, None) == "basic"
    assert fp.mix_preset(61, None) == "high_carb"
    assert fp.mix_preset(90, "natural") == "natural"
    assert fp.mix_preset(90, "nonsense") == "high_carb"


def test_a_hot_day_makes_the_drink_weaker_and_the_gels_make_up_the_carbs():
    cool = fp.targets(240, carbs=90)
    hot = fp.targets(240, carbs=90, temperature_c=33)
    assert fp.homemade_serving(hot)["concentration_pct"] < fp.homemade_serving(cool)["concentration_pct"]
    gel = [{"uid": "g", "name": "Gel", "carbs_g": 25.0}]
    hot_tl = fp.timeline(240, hot, gel, mix=fp.homemade_serving(hot))
    assert any(i["product_uid"] == "g" for i in hot_tl["items"])
    assert hot_tl["per_hour"]["carbs_g"] >= 80


def test_lite_salt_never_pushes_sodium_past_its_budget():
    """Lite salt carries sodium as well as potassium; a potassium-heavy plan
    must not be told to over-salt to get it."""
    tg = fp.targets(240, carbs=60, sodium=200, potassium=375)
    r = fp.homemade_recipe("basic", 750, fp.homemade_serving(tg), 60, 3000)
    per_ml = r["per_bottle"]["sodium_mg"] / 750
    assert per_ml <= fp.homemade_serving(tg)["sodium_mg"] / fp.homemade_serving(tg)["fluid_ml"] + 0.05
    assert r["per_bottle"]["potassium_mg"] < 375 * 750 / tg["fluid_ml_per_h"]


def test_spoons_read_as_a_kitchen_does():
    assert fp.spoon_measure(1.5, 6.0, 1) == "¼ tsp"
    assert fp.spoon_measure(45, 4.2, 4) == "3½ tbsp"
    assert fp.spoon_measure(0.05, 6.0, 1) == "a pinch"
