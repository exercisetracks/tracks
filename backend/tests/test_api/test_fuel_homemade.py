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


def test_the_mix_makes_up_whatever_the_other_products_leave_short():
    """Pick the mix and every target is met: gels count first, the bottle
    carries the rest of the carbs, sodium, potassium and fluid."""
    tg = fp.targets(240, carbs=90)
    gel = [{"uid": "g", "name": "Gel", "carbs_g": 25.0, "sodium_mg": 50}]
    tl = fp.timeline(240, tg, gel, mix=True)
    ph = tl["per_hour"]
    assert abs(ph["carbs_g"] - 90) <= 2
    assert abs(ph["fluid_ml"] - tg["fluid_ml_per_h"]) <= 15
    assert abs(ph["sodium_mg"] - tg["sodium_mg_per_h"]) <= 15
    assert any(i["product_uid"] == "g" for i in tl["items"])


def test_a_mix_stronger_than_the_heat_allows_says_how_much_food_to_add():
    """The target is still met, but a 6% day's bottle at 19% is hard to
    absorb: the card says how many grams an hour should come from food."""
    tg = fp.targets(240, carbs=100, temperature_c=33)
    mix = fp.timeline(240, tg, [], mix=True)["mix"]
    assert mix["over_band"] and mix["band_pct"] == 6 and mix["concentration_pct"] > 6
    # 800 ml/h at 6% carries 48 g; the other 52 g should be food.
    assert mix["extra_carbs_g_per_h"] == 52


def test_when_drinks_bring_all_the_fluid_the_mix_has_nothing_to_carry():
    tg = fp.targets(180, carbs=70)
    drink = [{"uid": "d", "name": "Drink", "carbs_g": 45.5, "sodium_mg": 500, "fluid_ml": 600}]
    mix = fp.timeline(180, tg, drink, mix=True)["mix"]
    assert mix["fluid_ml"] == 0 and mix["extra_carbs_g_per_h"] > 0


def test_spoons_read_as_a_kitchen_does():
    assert fp.spoon_measure(1.5, 6.0, 1) == "¼ tsp"
    assert fp.spoon_measure(45, 4.2, 4) == "3½ tbsp"
    assert fp.spoon_measure(0.05, 6.0, 1) == "a pinch"
