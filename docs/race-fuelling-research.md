<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Race fuelling: the gauges, potassium, and the homemade mix

The reasoning behind the numbers in `backend/app/calculators/fuel_plan.py`
(and its phone port `FuelPlan.kt`) added on 2026-11-02. These decisions were
settled with the maintainer:

- the Fuelling section shows one gauge each for carbs, sodium, potassium and
  fluid, per hour;
- a gauge's full scale is what an elite ultra-endurance athlete takes, its
  fill is what the chosen products deliver, and a tick marks the target;
- potassium is tracked: products carry it, and the plan sets a target for it;
- a built-in "Homemade mix" product makes the drink from household
  ingredients, in three recipes (Basic, High-carb, Natural), per bottle, and
  it weakens in heat as the old drink-mixing model did.

Every figure below is a named constant in the code. Where a figure comes from
practice rather than a trial, this doc says so.

## Gauge ceilings: what an elite takes per hour

| Gauge | Ceiling | Where it comes from |
|---|---|---|
| Carbs | 120 g/h | The highest intake tested in the field. Elite mountain-marathon runners took 120 g/h and had less muscle damage than at 60 or 90 g/h (Viribay et al. 2020, *Nutrients* 12:1367). Two of nine in that group dropped out with gut trouble, so it really is a ceiling. |
| Sodium | 1500 mg/h | The top of the ranges practitioners give for ultras in heat for salty sweaters (800–1500 mg/h). No trial sets an upper bound. ACSM's baseline is 300–600 mg/h. Sweat sodium runs about 200–2300 mg/L between people (Baker 2017, *Sports Med* 47 S1:111), so the top of this scale is real for some athletes. |
| Fluid | 1200 ml/h | The top of ACSM's range for events over an hour (600–1200 ml/h, 1996 position stand). The 2007 stand instead individualises fluid to sweat rate (400–800 ml/h as a typical band), so drinking much past 1.2 L/h risks hyponatraemia rather than helping. |
| Potassium | 375 mg/h | No position stand sets an hourly figure. Sweat carries about 4–8 mmol/L of potassium (≈ 156–313 mg/L). The ceiling is the fluid ceiling at the top of that range: 1.2 L × 8 mmol/L × 39.1 mg/mmol ≈ 375 mg. |

The gauges are not targets. The target is the tick. The scale only shows how
demanding a plan is next to the most anyone sensibly takes.

## The potassium target

There is no trial-backed hourly dose, and the evidence that losing
potassium during exercise causes cramp is weak. So the target is
deliberately modest: **replace potassium at mid-range sweat concentration in
proportion to the fluid drunk**, about 5 mmol/L, or **0.2 mg per ml of fluid**.
At the default 500 ml/h that is 100 mg/h. In very hot weather, at 800 ml/h,
it is 160 mg/h. As with every target, the user can override it.

It follows fluid, not sodium, because potassium in sweat varies far less
between people than sodium does. A salty sweater needs more sodium, but not
proportionally more potassium.

## The homemade mix

### Strength and sipping follow the heat

The drink's strength and how often to sip come from `spec/fueling.yaml`'s
heat bands, unchanged from the old drink-mixing model:

| Band | Strength | Sip every |
|---|---|---|
| normal | 12% (120 g carbs per litre) | 15 min |
| hot (≥ 25 °C, or humid ≥ 70% at ≥ 20 °C) | 8% | 12 min |
| very hot (≥ 32 °C) | 6% | 10 min |

Hotter means a weaker drink sipped more often. Concentrated carbohydrate
slows gastric emptying, and in heat fluid is the priority. Each sip is the
fluid target's share of the hour.

The drink carries the hour's sodium and potassium in full, and carbs up to
the band's strength or the carb target, whichever is lower. Carbs above that
come from the user's other products (gels, chews) on the same timeline. So
on a hot day a 90 g/h plan drinks 8% mix and takes gels for the rest. It is
never told to drink a 15% syrup.

### Recipes

| Recipe | Carbs | Sodium | Potassium | Default when |
|---|---|---|---|---|
| Basic | table sugar | table salt | lite salt | carb target ≤ 60 g/h |
| High-carb | maltodextrin + table sugar | table salt | lite salt | carb target > 60 g/h |
| Natural | honey | table salt | coconut water | never by default; picked by choice |

**Why maltodextrin at high rates.** Glucose absorption saturates near
60 g/h (SGLT1). Above that, the extra carbohydrate must be fructose, which is
absorbed through GLUT5. Jeukendrup's guidance is about 2:1 glucose:fructose at
90 g/h, and nearer 1:0.8 to 1:1 at 110–120 g/h. Podlogar et al. (2022) found
120 g/h at 1:0.8 oxidised more exogenous carbohydrate than 90 g/h at 2:1. Table
sugar is already 1:1, so maltodextrin, which is pure glucose, sets the ratio:

- up to 90 g/h, aiming for **2:1**: one part maltodextrin to two parts sugar;
- above 90 g/h, aiming for **1:0.8**: one part maltodextrin to eight parts sugar.

The Basic recipe's sugar alone gives 1:1, which is fine below 60 g/h, where
neither transporter is saturated.

**Natural.** Honey is about 82% carbohydrate, roughly equal fructose and
glucose. Fresh coconut water is about 600 mg potassium, 252 mg sodium and 9 g
carbohydrate per 240 ml cup (USDA, "Nuts, coconut water"). Coconut water
takes as much of the bottle as the potassium needs, and its sodium and carbs
count. Salt makes up the sodium, and honey the carbs. Packaged coconut water
varies widely, with USDA entries from 165 to 600 mg potassium per cup, so the
recipe card says to check the label.

### Ingredient table

| Ingredient | Per gram | Kitchen measure | Source |
|---|---|---|---|
| Table sugar | 1.0 g carbs | 4.2 g per tsp | sucrose; USDA |
| Maltodextrin | 0.95 g carbs | 2.5 g per tsp (a light powder) | product labels |
| Honey | 0.82 g carbs | 7 g per tsp | USDA |
| Table salt | 393 mg sodium | 6 g per tsp (590 mg Na per ¼ tsp) | Morton label |
| Lite salt | 207 mg sodium, 250 mg potassium | 5.6 g per tsp (290 mg Na + 350 mg K per ¼ tsp) | Morton Lite Salt label |
| Coconut water | per ml: 2.5 mg K, 1.05 mg Na, 0.037 g carbs | 240 ml per cup | USDA |

Salt is measured to the nearest ⅛ teaspoon and sugar to the nearest ½
teaspoon. Both are shown in grams first, since spoon measures of salt are
coarse at the amounts a bottle needs.

Lite salt carries sodium as well as potassium. When a plan's potassium would
need more lite salt than its sodium allows, the lite salt is capped at the
sodium budget and the shortfall shows on the potassium gauge. It is not
hidden.

## Sources

- Viribay A. et al. (2020). Effects of 120 g/h of carbohydrates intake during
  a mountain marathon on exercise-induced muscle damage in elite runners.
  *Nutrients* 12(5):1367.
  <https://pmc.ncbi.nlm.nih.gov/articles/PMC7400827>
- Jeukendrup A. *The optimal ratio of carbohydrates*.
  <https://www.mysportscience.com/post/the-optimal-ratio-of-carbohydrates>
- Jeukendrup A. (2014). A step towards personalized sports nutrition:
  carbohydrate intake during exercise. *Sports Med* 44 S1:25.
  <https://pmc.ncbi.nlm.nih.gov/articles/PMC4008807>
- GSSI SSE #108, Multiple transportable carbohydrates and their benefits.
  <https://www.gssiweb.org/sports-science-exchange/article/sse-108-multiple-transportable-carbohydrates-and-their-benefits>
- Baker L.B. (2017). Sweating rate and sweat sodium concentration in
  athletes. *Sports Med* 47 S1:111.
  <https://www.gssiweb.org/sports-science-exchange/article/sweating-rate-and-sweat-sodium-concentrations-in-athletes-a-review-of-methodology-and-intra-interindividual-variability>
- Veniamakis E. et al. (2022). Effects of sodium intake on health and
  performance in endurance and ultra-endurance sports. *IJERPH* 19:3651.
  <https://mdpi-res.com/d_attachment/ijerph/ijerph-19-03651/article_deploy/ijerph-19-03651.pdf>
- ACSM (2007). Exercise and fluid replacement. *Med Sci Sports Exerc*
  39:377. <https://pubmed.ncbi.nlm.nih.gov/17277604/>
- Rehydration during endurance exercise (2021). *Nutrients* 13:887.
  <https://mdpi.com/2072-6643/13/3/887>
- Morton Lite Salt label: ¼ tsp = 290 mg sodium, 350 mg potassium.
  <https://www.shoprite.com/sm/delivery/rsid/207/product/morton-lite-salt-11-oz-id-00024600010412>
- USDA, Nuts, coconut water (liquid from coconuts), 1 cup.
  <https://www.nutritionvalue.org/Nuts%2C_coconut_water_%28liquid_from_coconuts%29_nutritional_value.html>
