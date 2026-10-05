# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""
How fit a runner is today, as the VDOT every running pace is derived from.

This is the one running-fitness number in the system. The training plan, the
fitness plan, a triathlon's run leg and the race plan all read it, and the
watch's pace targets are built from what the plan stored of it — so a
change here moves every pace, everywhere, together. There is deliberately no
second copy: the plan generators used to take the single fastest 3 km+ stretch
of any run ever recorded, which made a four-year-old personal best set this
week's easy pace, turned a runner who never runs hard into a slow one, and
gave everyone without history the paces of a 23-minute 5 K runner.

Three kinds of evidence, strongest first. docs/running-pace-research.md has
the literature behind each, and the numbers this module could not check
against a primary paper are marked there and here as unverified.

1. Efforts — the pace bests (fastest 3 km, 5 km, … stretch of each run).
   Daniels' VDOT formula assumes an all-out effort, so each one is a *floor*
   on fitness at the time it was run: nobody holds a pace their fitness cannot
   sustain, but a training run held well below it. An effort stays current for
   six weeks (a training block — Daniels re-tests VDOT on about that cycle) and
   then ages along the detraining curve below; one older than a year is
   ignored, since long-term detraining (Mujika & Padilla 2000, Part II) puts
   the loss anywhere from 6–20% to all of it.

2. Heart rate on ordinary runs — the central estimate. Percent heart-rate
   reserve equals percent VO2 reserve (Swain & Leutholtz 1997; Swain et al.
   1998 on the treadmill), so a steady run's speed and average HR, with the
   athlete's max and resting HR, say what VO2max that run implies. The O2 cost
   of the speed is Daniels' own curve (the one VDOT is defined by), which
   makes the estimate self-consistent with the paces built from it: a runner
   whose runs at 6:00/km sit at 70% of reserve gets an easy pace of about
   6:00/km, whatever their height, weight or economy. This is what
   personalises pacing — it measures the individual rather than assuming the
   average runner. Median of at least three qualifying runs in 90 days.

   The watch's own VO2max (Firstbeat, read from the FIT file — the number on
   the dashboard's VO2max chart) is used instead only when the HR estimate
   cannot be made (no resting HR, too few steady runs). It is taken as
   VDOT-equivalent — unverified: Firstbeat estimates lab VO2max, and VDOT
   runs below lab VO2max in recreational runners (Scudamore et al. 2018), so
   it errs fast, which is why it is the fallback and not the default.

   Whichever central estimate is used, the fitness is the greater of it and
   the effort floor: an old race cannot pull a current estimate down, and a
   noisy HR reading cannot pull a race below what was actually run.

3. The person — only with no running evidence at all (the user's choice:
   height and weight inform the estimate only until real runs exist, since
   measured performance already carries them and adding them again would count
   them twice). A non-exercise VO2max model, Jackson et al. (1990):

       VO2max = 56.363 + 1.921·PA-R − 0.381·age − 0.754·BMI + 10.987·sex

   (sex 1 male, 0 female; coefficients from secondary sources — unverified).
   PA-R, the 0–7 NASA activity rating, comes from how often the person does
   each endurance sport (user_settings.activity_frequency). With no recorded
   runs the person is untrained *at running* whatever their aerobic fitness,
   and untrained runners use ~10% more oxygen at a given speed (Morgan &
   Martin 1989; Bransford & Howley 1977) and hold a smaller share of VO2max,
   so VDOT is the estimate × 0.85 — a heuristic, unverified, chosen to err
   slow. With nothing answered at all it is ~31.8 VDOT, about a 29-minute
   5 K, where the old default was 42.

Detraining
----------
Coyle et al. (1984): VO2max fell 7% over the first 21 days without training
and stabilised 16% down by day 56. The curve here is those two points joined
linearly, flat for the first week (ordinary rest days and tapers, which
Mujika & Padilla find cost nothing — the week of grace is ours). It is applied
to the time since the athlete's last run (a layoff) and, for an effort, to how
far it is past its six current weeks.

Why not the fitness chart
-------------------------
CTL (training_load.py) measures training *dose*; this measures *capability*.
Banister-style models that turn one into the other need fitting per athlete
and generalise poorly (Hellard et al. 2006), so the plan reads both — CTL sizes
the weeks, this sets the paces — and neither is derived from the other. They
share their inputs where it matters: the same max-HR setting the HR zones and
race HR ceilings use.

Pure, and ported to the phone as com.tracks.core.plan.RunningFitness, held to
this by spec/fixtures/running_fitness.json.
"""

from __future__ import annotations

import statistics
from datetime import date

from app.calculators.plan.base import calculate_vdot

# ── Evidence windows ─────────────────────────────────────────────────────────

# An effort's first six weeks, over which it is today's fitness.
EFFORT_CURRENT_DAYS = 42
# Older than this and an effort says nothing about today (see module doc).
EFFORT_MAX_AGE_DAYS = 365
# Daniels' formula is built for 1.5 km to the marathon; below 3 km a best
# stretch is more often a stride or a GPS spike than a sustained effort.
EFFORT_MIN_DISTANCE_M = 3000

# How far back ordinary runs and the watch's VO2max are read — the history
# window the plan generators already use (_get_activity_history).
RECENT_DAYS = 90
# Fewer than this many steady runs and one bad strap reading is the estimate.
HR_MIN_RUNS = 3
# Long enough that the run's average is mostly steady state rather than the
# first minutes' HR lag (the ACSM method needs ≥5 min steady; an activity
# average includes the start, so 20).
HR_MIN_DURATION_S = 1200
# Swain's equivalence is linear over the training range; below half of reserve
# a few beats of error become a large error in the extrapolation, and near max
# the run was not steady.
HR_MIN_RESERVE = 0.50
HR_MAX_RESERVE = 0.95
# Max HR this close to resting HR is a settings error, not a physiology.
HR_MIN_SPREAD_BPM = 60
# Below ~9:15/km the run is mixed with walking, where Daniels' running cost
# curve does not apply; above 7 m/s for 20 minutes is a GPS fault.
HR_MIN_SPEED_MPS = 1.8
HR_MAX_SPEED_MPS = 7.0
# Hills move HR without moving speed. 15 m of climbing a km is rolling at most.
HR_MAX_CLIMB_M_PER_KM = 15.0
# Treadmill speed comes from a wrist or footpod estimate, and trail terrain
# costs oxygen the speed does not show; both would mislead the calibration.
_HR_RUN_SPORTS = {"running", "road_running"}

# Any VDOT outside this is a sensor or data fault, not a person.
PLAUSIBLE = (15.0, 90.0)

# ── The profile prior (Jackson et al. 1990, N-Ex BMI model) ──────────────────
# Coefficients from secondary sources — unverified against the paper.
_JACKSON_INTERCEPT = 56.363
_JACKSON_PAR = 1.921
_JACKSON_AGE = -0.381
_JACKSON_BMI = -0.754
_JACKSON_MALE = 10.987
# Untrained running economy and utilisation (module doc) — a heuristic.
PROFILE_ECONOMY = 0.85
# Where nothing is known. Age: the model's population was 20–70; 40 is its
# middle. BMI 25: the WHO normal/overweight boundary, between the general
# population and runners. Sex unknown: halfway.
DEFAULT_AGE = 40
DEFAULT_BMI = 25.0
_AGE_RANGE = (18, 80)
_BMI_RANGE = (15.0, 45.0)
_PROFILE_RANGE = (20.0, 60.0)

# How often someone does a sport (starting.LEVELS) as a NASA PA-R rating.
# PA-R counts running miles or "comparable activity" time: <30 min a week is
# 4, 30–60 min 5, 1–3 h 6, over 3 h 7. "never" is 1, walks for pleasure.
_PAR_FOR_LEVEL = {"never": 1, "occasional": 4, "1_2": 5, "3_4": 6, "5_plus": 7}
# No answer is read as 1–2 sessions a week, as starting.py reads it.
DEFAULT_PAR = 5


def detraining_factor(days: float) -> float:
    """Share of fitness left after ``days`` without training (Coyle et al. 1984)."""
    if days <= 7:
        return 1.0
    if days <= 21:
        return 1.0 - 0.07 * (days - 7) / 14
    if days <= 56:
        return 0.93 - 0.09 * (days - 21) / 35
    return 0.84


def daniels_vo2(speed_mps: float) -> float:
    """O2 cost (ml/kg/min) of running at a speed — the curve VDOT is defined on
    (Daniels & Gilbert 1979, as ``calculate_vdot`` uses it)."""
    v = speed_mps * 60
    return -4.60 + 0.182258 * v + 0.000104 * v ** 2


def hr_run_vdot(speed_mps: float, avg_hr: float, max_hr: float, resting_hr: float) -> float | None:
    """The VDOT one steady run implies, or None when it is outside the method.

    %HRR = %VO2R (Swain & Leutholtz 1997), so
    VO2max = VO2rest + (VO2(speed) − VO2rest) / %HRR, with VO2rest 3.5.
    """
    if max_hr - resting_hr < HR_MIN_SPREAD_BPM:
        return None
    if not HR_MIN_SPEED_MPS <= speed_mps <= HR_MAX_SPEED_MPS:
        return None
    reserve = (avg_hr - resting_hr) / (max_hr - resting_hr)
    if not HR_MIN_RESERVE <= reserve <= HR_MAX_RESERVE:
        return None
    est = 3.5 + (daniels_vo2(speed_mps) - 3.5) / reserve
    return est if PLAUSIBLE[0] <= est <= PLAUSIBLE[1] else None


def _run_speed(run: dict) -> float | None:
    speed = run.get("avg_speed")
    if speed and speed > 0:
        return float(speed)
    dist, dur = run.get("distance_m"), run.get("duration_s")
    if dist and dur and dur > 0:
        return float(dist) / float(dur)
    return None


def _hr_estimate(runs: list[dict], today: date, max_hr: float | None,
                 resting_hr: float | None) -> float | None:
    if not max_hr or not resting_hr:
        return None
    ests = []
    for r in runs:
        if (today - r["date"]).days > RECENT_DAYS or r.get("sport") not in _HR_RUN_SPORTS:
            continue
        if not r.get("avg_hr") or (r.get("duration_s") or 0) < HR_MIN_DURATION_S:
            continue
        dist, climb = r.get("distance_m"), r.get("ascent_m")
        if climb is not None and dist and climb / (dist / 1000) > HR_MAX_CLIMB_M_PER_KM:
            continue
        speed = _run_speed(r)
        if speed is None:
            continue
        est = hr_run_vdot(speed, float(r["avg_hr"]), float(max_hr), float(resting_hr))
        if est is not None:
            ests.append(est)
    return statistics.median(ests) if len(ests) >= HR_MIN_RUNS else None


def _watch_estimate(runs: list[dict], today: date) -> float | None:
    """The watch's most recent running VO2max inside the window."""
    latest = None
    for r in runs:
        v = r.get("vo2max")
        if v is None or not PLAUSIBLE[0] <= v <= PLAUSIBLE[1]:
            continue
        if (today - r["date"]).days > RECENT_DAYS:
            continue
        if latest is None or r["date"] > latest[0]:
            latest = (r["date"], float(v))
    return latest[1] if latest else None


def _effort_estimate(efforts: list[dict], today: date, layoff_days: int) -> float | None:
    best = None
    for e in efforts:
        age = (today - e["date"]).days
        if e["distance_m"] < EFFORT_MIN_DISTANCE_M or e["speed_mps"] <= 0 or age > EFFORT_MAX_AGE_DAYS:
            continue
        v = calculate_vdot(e["distance_m"], e["distance_m"] / e["speed_mps"])
        if not PLAUSIBLE[0] <= v <= PLAUSIBLE[1]:
            continue
        v *= detraining_factor(max(age - (EFFORT_CURRENT_DAYS - 7), layoff_days))
        if best is None or v > best:
            best = v
    return best


def par_from_frequencies(frequencies) -> int:
    """PA-R from the activity-frequency map: the most active sport sets it."""
    if not isinstance(frequencies, dict):
        return DEFAULT_PAR
    levels = [_PAR_FOR_LEVEL[v] for v in frequencies.values() if v in _PAR_FOR_LEVEL]
    return max(levels) if levels else DEFAULT_PAR


def profile_vdot(today: date, *, sex: str | None = None, height_cm: float | None = None,
                 weight_kg: float | None = None, birth_year: int | None = None,
                 frequencies=None) -> float:
    """The no-data estimate: Jackson et al. (1990) × untrained running economy."""
    age = today.year - birth_year if birth_year else DEFAULT_AGE
    age = min(max(age, _AGE_RANGE[0]), _AGE_RANGE[1])
    if height_cm and weight_kg and height_cm > 0:
        bmi = min(max(weight_kg / (height_cm / 100) ** 2, _BMI_RANGE[0]), _BMI_RANGE[1])
    else:
        bmi = DEFAULT_BMI
    male = 1.0 if sex == "male" else 0.0 if sex == "female" else 0.5
    vo2 = (_JACKSON_INTERCEPT + _JACKSON_PAR * par_from_frequencies(frequencies)
           + _JACKSON_AGE * age + _JACKSON_BMI * bmi + _JACKSON_MALE * male)
    return min(max(vo2 * PROFILE_ECONOMY, _PROFILE_RANGE[0]), _PROFILE_RANGE[1])


def estimate_running_fitness(
    efforts: list[dict],
    runs: list[dict],
    today: date,
    *,
    max_hr: float | None = None,
    resting_hr: float | None = None,
    sex: str | None = None,
    height_cm: float | None = None,
    weight_kg: float | None = None,
    birth_year: int | None = None,
    frequencies=None,
) -> dict:
    """Today's running fitness.

    ``efforts``: pace bests, ``{"date", "distance_m", "speed_mps"}``.
    ``runs``: running activities, ``{"date", "sport", "distance_m",
    "duration_s", "avg_speed", "avg_hr", "ascent_m", "vo2max"}`` (any may be
    None but the date and sport).

    Returns ``{"vdot", "source", "measured", "effort_vdot"}``, VDOT to one
    decimal. ``source`` is ``effort``, ``heart_rate``, ``watch`` or
    ``profile``; ``measured`` is False only for ``profile``. ``effort_vdot``
    is the effort floor alone — what the runner has actually run, which is
    what the run/walk capacity model reads (see plan.py) — or None.
    """
    dates = [r["date"] for r in runs] + [e["date"] for e in efforts]
    last = max((d for d in dates if d <= today), default=None)
    if last is None:
        v = profile_vdot(today, sex=sex, height_cm=height_cm, weight_kg=weight_kg,
                         birth_year=birth_year, frequencies=frequencies)
        return {"vdot": round(v, 1), "source": "profile", "measured": False, "effort_vdot": None}
    layoff = (today - last).days
    past_runs = [r for r in runs if r["date"] <= today]
    past_efforts = [e for e in efforts if e["date"] <= today]

    effort = _effort_estimate(past_efforts, today, layoff)
    central, source = _hr_estimate(past_runs, today, max_hr, resting_hr), "heart_rate"
    if central is None:
        central, source = _watch_estimate(past_runs, today), "watch"
    if central is not None:
        central *= detraining_factor(layoff)

    if effort is None and central is None:
        v = profile_vdot(today, sex=sex, height_cm=height_cm, weight_kg=weight_kg,
                         birth_year=birth_year, frequencies=frequencies)
        return {"vdot": round(v, 1), "source": "profile", "measured": False, "effort_vdot": None}
    if central is None or (effort is not None and effort >= central):
        best, source = effort, "effort"
    else:
        best = central
    return {"vdot": round(best, 1), "source": source, "measured": True,
            "effort_vdot": round(effort, 1) if effort is not None else None}
