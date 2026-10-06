<!-- SPDX-FileCopyrightText: 2026 Hawk Fugagli -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->

# Running pace: where the fitness number comes from

Every running pace in Tracks — the notes on a planned run, the watch's pace
targets, the race plan's lap splits — is derived from one number, a VDOT
(Daniels 2013), and every one of them is only as good as that number. This file
is the research behind how it is estimated
(`backend/app/calculators/plan/running_fitness.py`, ported to the phone as
`com.tracks.core.plan.RunningFitness`) and behind the race plan's marathon
correction (`calculators/race_predictor/running.py`).

It was compiled in September 2026 without full-text access to the journals: the
environment it was written in could reach search results and abstracts but not
papers. **Numbers marked _unverified_ were taken from secondary sources and
should be checked against the primary paper** before anyone relies on them
more than the code already does. Everything else is either a published formula
quoted in many places identically, or a heuristic said to be one.

---

## What it replaced

The plan generators and the race plan all read `_best_vdot_from_pace_bests`:
the single fastest ≥3 km stretch of any run ever recorded, as a Daniels VDOT.
That had three failures, all in the direction that hurts:

- **It never forgot.** A personal best from years ago set this week's easy
  pace, for a runner who may have lost 16% or more since (see detraining).
- **It could not tell effort.** Daniels' formula assumes an all-out effort, so
  a runner who never runs hard got paces from their training runs — slower
  than their fitness — and a runner who raced once got paces from the race.
- **Everyone without history was one person:** VDOT 42, a 23:07 5 K runner,
  easy pace 5:52/km — for someone who has never run as much as for a regular.

It also ignored everything else the app knows: heart rate, resting heart rate,
the watch's own VO2max, height, weight, sex and experience.

## The estimate, strongest evidence first

### 1. Efforts: a floor that ages

Pace bests are kept as evidence, but as what they are: the fastest a runner
held a distance, which is a **floor** on their fitness when they ran it — no one
holds a pace their fitness cannot sustain, but a training run held well below
it. Each one is current for six weeks, then ages along the detraining curve;
one older than a year is ignored.

**Detraining** — Coyle et al. (1984), seven endurance-trained subjects: VO2max
fell 7% in the first 21 days without training and stabilised 16% below the
trained value by day 56; muscle capillarisation did not decline, and after 84
days they were still above sedentary controls. Mujika & Padilla (2000, Part I)
put the short-term (<4 weeks) VO2max loss in trained athletes at roughly 4–14%;
Part II (long-term, >4 weeks) at 6–20%, with recently acquired gains in the less
trained lost entirely. Tracks joins Coyle's two points linearly, flat for the
first week (ordinary rest days and tapers cost nothing — _the week of grace is
Tracks' own choice_), and ignores efforts past a year.

Six weeks as an effort's current life is _a heuristic_: a training block, over
which Daniels re-tests VDOT.

### 2. Heart rate on ordinary runs: the central estimate

Percent heart-rate reserve equals percent VO2 **reserve**, not percent VO2max
(Swain & Leutholtz 1997, cycling; Swain et al. 1998, treadmill). Given a steady
run's speed and average HR, and the athlete's max and resting HR:

    VO2max = VO2rest + (VO2(speed) − VO2rest) / %HRR,    VO2rest = 3.5

VO2(speed) is Daniels' own cost curve (Daniels & Gilbert 1979) — the curve VDOT
is defined on. That choice is what makes this the personalisation: the
estimate is self-consistent with the paces built from it, so a runner whose
runs at 6:00/km sit at 70% of reserve gets an easy pace of about 6:00/km,
whatever their height, weight or running economy.

Swain & Leutholtz also found the %HRR–%VO2max relation furthest from identity
in the least fit (VO2max < 30), which is the reason for reserve rather than
max. The method's known failure is **cardiac drift**: heat and dehydration raise
HR at constant pace over long runs, which reads as lower fitness. So runs are
used only when the method holds — ≥20 min, 50–95% of reserve, flat (≤15 m of
climbing per km), road running (treadmill speed is a wrist estimate; trail
terrain costs oxygen speed does not show) — and the estimate is the **median of
at least three**. _The thresholds are Tracks' own_, chosen from the method's
stated conditions (steady state; a submaximal band; ≥5 min steady for the ACSM
equation, 20 min here because an activity average includes the start).

**The watch's VO2max** (Firstbeat, read from the FIT file — the number on the
dashboard's VO2max chart) is used instead only when the HR estimate cannot be
made. Firstbeat's white paper reports ~5% mean absolute percentage error
against lab VO2max over 2,690 free runs (79 runners); independent studies put
the error at roughly 3–5 ml/kg/min with a chest strap and 5–8 wrist-only
(_secondary sources, unverified_). Tracks takes it as VDOT-equivalent, which is
_unverified_ and errs fast: VDOT underestimates lab VO2max, and more so in
recreational runners than in trained ones (Scudamore et al. 2018).

Whichever central estimate is used, fitness is **the greater of it and the
effort floor**: an old race cannot pull a current estimate down, and a noisy
HR reading cannot pull fitness below a race actually run. The central estimate
is scaled by the current layoff (days since the last run) on the same curve.

### 3. The person, only with no running evidence at all

Height, weight, sex, age and experience inform the estimate **only until real
runs exist** — measured performance already carries them, and adding them
again would count them twice. The model is the non-exercise VO2max prediction
of Jackson et al. (1990), BMI form (R = 0.78, SEE 5.6 ml/kg/min, ages 20–70):

    VO2max = 56.363 + 1.921·PA-R − 0.381·age − 0.754·BMI + 10.987·sex

(sex: male 1, female 0) — _coefficients unverified_. PA-R is the NASA 0–7
physical-activity rating: 4 is under 30 min a week of running or comparable
activity, 5 is 30–60 min, 6 is 1–3 h, 7 over 3 h; Tracks maps its
activity-frequency answers onto it (never 1, occasional 4, 1–2 a week 5, 3–4
a week 6, 5+ a week 7) and takes the most active sport, since PA-R rates all
activity. Unknowns default to the model's middle: age 40, BMI 25, sex halfway,
PA-R 5.

A person with no recorded runs is untrained *at running* whatever their
aerobic fitness. Untrained runners use ~10% more oxygen at a given speed
(Morgan & Martin 1989; Bransford & Howley 1977) and sustain a smaller fraction
of VO2max, and VDOT runs further below lab VO2max in recreational runners
(Scudamore et al. 2018). So the prior is the Jackson estimate × 0.85 — _a
heuristic, unverified, chosen to err slow_. With nothing answered it is VDOT
31.8, about a 29-minute 5 K; the old default was 42.

Age matters (VO2max falls roughly 10% a decade after 25; performance holds to
about 35 and declines modestly to 50–60, then faster — Tanaka & Seals 2008), so
setup and settings require an **age**. It is stored as the birth year
(`birth_year`), so it stays right in later years without an edit; a year rather
than a date, because the model needs age in years, and asked as an age because
that is what people can answer without arithmetic.

A profile estimate sets the paces in a plan's notes and nothing else: it is not
stored as the plan's VDOT, so the watch gets no pace targets from a guess, and
it does not move the run/walk capacity model.

## On height

The request that started this work was that a tall person naturally jogs
faster than a short one, and that pacing should know it. The evidence does not
support that for prescribed paces:

- **Self-selected speed is set by relative intensity**, not stride length: it
  clusters around the first ventilatory threshold, with wide individual
  variation (62–160% of it at 20 minutes in one study), shaped by fitness, body
  composition and affect.
- **Height's effect on running economy is small and inconsistent.** In highly
  trained runners, body dimensions relate to economy differently by speed and
  event, with economy and VO2max counterbalancing (Maldonado et al. 2002).
- **In recreational marathoners, height and body mass did not predict race
  time** once training was accounted for; percent body fat and training speed
  did (Barandun et al. 2012; Tanda & Knechtle 2013).
- Smaller bodies dissipate heat better (higher surface area to mass), which
  favours them in distance running if anything.
- The non-exercise VO2max models that do use size use it as **BMI**, or as
  height and mass together (Matthews et al. 1999: +9.17 ml/kg/min per metre of
  height, −0.254 per kg — _unverified_). At equal BMI, both predict a *taller*
  person slightly *less* fit per kilogram, consistent with allometric scaling
  of VO2max.

A tall runner does take longer strides; the pace they can hold at an effort is
set by their physiology, which their heart rate on real runs measures
directly. So height enters only through BMI, and only before there are runs.

## Race plan: the marathon

VDOT carries a fixed endurance curve: a VDOT set by a 5 K predicts the marathon
of a runner with the endurance behind Daniels' runners. In 2,303 recreational
runners, the Riegel formula (the same kind of extrapolation) was well
calibrated up to the half marathon but at least ten minutes too fast in the
marathon for half of them, and weekly mileage was the strongest single
predictor of race velocity at every distance (Vickers & Vertosick 2016).

Tanda (2011) predicts the marathon from exactly what Tracks records — mean
weekly distance K and mean training pace P over the 8 weeks before:

    marathon pace (s/km) = 17.1 + 140·exp(−0.0053·K) + 0.55·P

(SEE ≈ 4 min; 22 runners, 46 marathons, 2:47–3:36). A later validation (Tanda
2022, *J Hum Sport Exerc*) found it needs individual correction for faster
runners. Since the pure extrapolation errs fast and Tanda errs slow for fast
runners, a race of 40–44 km is predicted as **the mean of the two** — _a
heuristic, unverified_ — when there are runs in at least six of the eight
weeks. Shorter races keep the VDOT prediction, which is calibrated there;
ultras have no comparable model. The same prediction is used by the race plan,
the goal card's predicted time and, on the phone, the race plans and plan
screens.

**Not done:** Blythe & Király (2016) fit each runner a personal power-law
exponent from several performances, which beats Riegel's fixed 1.06. It needs
two or more *maximal* efforts at different distances, and pace bests are not
known to be maximal, so it would fit training runs. Revisit if races are ever
marked as races.

## How this sits with the other fitness numbers

Checked because a new, disconnected fitness metric would be worse than none.

| Number | What it is | Relation to running fitness |
|---|---|---|
| CTL / ATL / TSB (fitness & form charts, `training_load.py`) | Training **dose**: EWMAs of device TSS or hrTSS against threshold HR | Independent by design. Dose is not capability: Banister-style models mapping one to the other need fitting per athlete and generalise poorly (Hellard et al. 2006). Plans read both — CTL sizes the weeks, VDOT sets the paces. The race plan's race-day freshness already comes from TSB, and scales both halves of the marathon prediction. |
| Watch VO2max (dashboard chart) | Firstbeat's VO2max estimate | **Now an input**: the fallback central estimate. Expect the plan's VDOT to sit at or below it — VDOT is a performance index and runs below lab VO2max in recreational runners. |
| Max HR / threshold HR (settings, auto or manual) | All-time highest max HR; best 20-min HR | **Shared input**: the HR method uses the same effective max HR as the HR zones and the race plan's HR ceilings. Auto max HR is an all-time maximum, so for someone whose max HR has fallen with age it reads high, which lowers %HRR and nudges the HR estimate up. |
| Resting HR (daily metrics, readiness) | Watch's daily resting HR | **Shared input**: median of the last 30 days. |
| Plan's stored VDOT | What the watch's pace targets are built from | **Is** the estimate (when measured), rounded to a tenth, as before. |
| Fitness fingerprint `vdot` (`UserFitnessFingerprint`, matching.py) | Whole-activity VDOT of each matched run, ratcheting up only | **Written but never read** — a disconnected copy of the number this replaces. Left alone here (removing it touches the matching fixtures and the phone's replay), but it should either be deleted or become the stored form of this estimate. |
| Plan's weekly VDOT progression | +≤0.1 VDOT a week over the plan, capped at +1.5 | Unchanged. Plans are rebuilt from measured fitness on every import, so the projection only ever covers a few days before the estimate replaces it. The watch's pace targets use the plan's base VDOT, not the week's, which the same rebuild keeps negligible. |

Found on the way, outside running: the training plan computes swim CSS from
`_get_pace_bests`, which returns *running* pace bests and never a 200 m one, so
swim plans never get a CSS — and they ignore the CSS a person enters in
settings, which the race plan does read.

## References

Volume and page details are given only where they were seen in the source;
the rest should be completed from the papers themselves.

- Bacon, A. P., Carter, R. E., Ogle, E. A., & Joyner, M. J. (2013). VO2max trainability and high intensity interval training in humans: a meta-analysis. *PLoS ONE*. doi:10.1371/journal.pone.0073182
- Barandun, U., Knechtle, B., et al. (2012). Running speed during training and percent body fat predict race time in recreational male marathoners. *Open Access Journal of Sports Medicine*. doi:10.2147/OAJSM.S33284
- Blythe, D. A. J., & Király, F. J. (2016). Prediction and quantification of individual athletic performance of runners. *PLoS ONE*. doi:10.1371/journal.pone.0157257
- Bransford, D. R., & Howley, E. T. (1977). Oxygen cost of running in trained and untrained men and women. *Medicine and Science in Sports*, 9, 41–44.
- Coyle, E. F., et al. (1984). Time course of loss of adaptations after stopping prolonged intense endurance training. *Journal of Applied Physiology*, 57(6), 1857 ff. doi:10.1152/jappl.1984.57.6.1857
- Daniels, J. (2013). *Daniels' Running Formula* (3rd ed.). Human Kinetics.
- Daniels, J., & Gilbert, J. (1979). *Oxygen Power: Performance Tables for Distance Runners*.
- Firstbeat Technologies (2017). *Automated Fitness Level (VO2max) Estimation with Heart Rate and Speed Data* (white paper; not peer reviewed).
- Hellard, P., et al. (2006). Assessing the limitations of the Banister model in monitoring training. *Journal of Sports Sciences*.
- Jackson, A. S., Blair, S. N., Mahar, M. T., Wier, L. T., Ross, R. M., & Stuteville, J. E. (1990). Prediction of functional aerobic capacity without exercise testing. *Medicine and Science in Sports and Exercise*, 22(6), 863–870.
- Maldonado, S., Mujika, I., & Padilla, S. (2002). Influence of body mass and height on the energy cost of running in highly trained middle- and long-distance runners. *International Journal of Sports Medicine*, 23(4), 268–272.
- Matthews, C. E., Heil, D. P., Freedson, P. S., & Pastides, H. (1999). Classification of cardiorespiratory fitness without exercise testing. *Medicine and Science in Sports and Exercise*, 31(3), 486–493.
- Morgan, D. W., Martin, P. E., & Krahenbuhl, G. S. (1989). Factors affecting running economy. *Sports Medicine*, 7(5), 310–330.
- Mujika, I., & Padilla, S. (2000). Detraining: loss of training-induced physiological and performance adaptations, Parts I and II. *Sports Medicine*, 30.
- Nes, B. M., Janszky, I., et al. (2011). Estimating VO2peak from a nonexercise prediction model: the HUNT Study, Norway. *Medicine and Science in Sports and Exercise*. (Considered: it uses resting HR, which Tracks has, but its coefficients could not be verified.)
- Scudamore, E. M., Barry, V. W., & Coons, J. M. (2018). An evaluation of time-trial–based predictions of VO2max and recommended training paces for collegiate and recreational runners. *Journal of Strength and Conditioning Research*, 32(4).
- Swain, D. P., & Leutholtz, B. C. (1997). Heart rate reserve is equivalent to %VO2 reserve, not to %VO2max. *Medicine and Science in Sports and Exercise*.
- Swain, D. P., et al. (1998). %HRR and %VO2 reserve in treadmill exercise. *Medicine and Science in Sports and Exercise*.
- Tanaka, H., & Seals, D. R. (2008). Endurance exercise performance in Masters athletes: age-associated changes and underlying physiological mechanisms. *Journal of Physiology*.
- Tanda, G. (2011). Prediction of marathon performance time on the basis of training indices. *Journal of Human Sport and Exercise*.
- Tanda, G. (2022). A simple relationship for predicting marathon performance from training: is it generally applicable? *Journal of Human Sport and Exercise*, 17(2). doi:10.14198/jhse.2022.172.05
- Tanda, G., & Knechtle, B. (2013). Marathon performance in relation to body fat percentage and training indices in recreational male runners. *Open Access Journal of Sports Medicine*.
- Vickers, A. J., & Vertosick, E. A. (2016). An empirical study of race times in recreational endurance runners. *BMC Sports Science, Medicine and Rehabilitation*. doi:10.1186/s13102-016-0052-y
