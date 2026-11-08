# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Today's running fitness — the VDOT every running pace comes from.

Before this, paces came from the single fastest 3 km+ stretch of any run ever
recorded: a years-old personal best set this week's easy pace, a runner who
never ran hard was paced off their training runs, and anyone without history
got a 23-minute 5 K runner's paces. The phone replays
spec/fixtures/running_fitness.json; the corpus test here is what fails when a
change to the estimator is not regenerated into it.

Dates are fixed and passed in — the estimator never reads the clock.
"""
import json
from datetime import date, timedelta
from pathlib import Path

import pytest

from app.calculators.plan import running_fitness as rf
from app.calculators.plan.base import calculate_vdot, vdot_to_paces
from app.calculators.plan.running_fitness import estimate_running_fitness

CORPUS = json.loads(Path("/spec/fixtures/running_fitness.json").read_text())
TODAY = date(2026, 3, 4)


def _effort(days_ago, distance_m=5000, minutes=20.0):
    return {"date": TODAY - timedelta(days=days_ago), "distance_m": distance_m,
            "speed_mps": distance_m / (minutes * 60)}


def _run(days_ago, pace_sec_km=330.0, avg_hr=145, km=8.0, sport="running",
         ascent_m=40.0, vo2max=None):
    return {"date": TODAY - timedelta(days=days_ago), "sport": sport,
            "distance_m": km * 1000, "duration_s": km * pace_sec_km, "avg_speed": None,
            "avg_hr": avg_hr, "ascent_m": ascent_m, "vo2max": vo2max}


HR = {"max_hr": 185, "resting_hr": 55}


def test_detraining_follows_coyle():
    """7% gone by three weeks off and 16% by eight, then no further (Coyle 1984);
    the first week off — rest days, a taper — costs nothing."""
    assert rf.detraining_factor(0) == 1.0
    assert rf.detraining_factor(7) == 1.0
    assert rf.detraining_factor(21) == pytest.approx(0.93)
    assert rf.detraining_factor(56) == pytest.approx(0.84)
    assert rf.detraining_factor(400) == pytest.approx(0.84)


def test_an_effort_inside_six_weeks_counts_in_full():
    """A race run a month ago, still training since, is today's fitness."""
    out = estimate_running_fitness([_effort(30)], [_run(1, avg_hr=None)], TODAY)
    assert out["source"] == "effort" and out["measured"]
    assert out["vdot"] == round(calculate_vdot(5000, 1200), 1)


def test_an_old_personal_best_no_longer_sets_todays_paces():
    """The failure this replaces: a personal best from long ago paced every run
    as if it were still true. Past its six weeks it ages along the detraining
    curve, and past a year it is ignored."""
    full = calculate_vdot(5000, 1200)
    aged = estimate_running_fitness([_effort(200)], [_run(1, avg_hr=None)], TODAY)
    assert aged["vdot"] == round(full * 0.84, 1)
    ancient = estimate_running_fitness([_effort(800)], [_run(1, avg_hr=None)], TODAY)
    assert ancient["source"] == "profile" and not ancient["measured"]


def test_a_layoff_discounts_even_a_recent_effort():
    """A race three weeks ago and nothing since: the break costs what three weeks
    off costs, even though the race is well inside its six current weeks."""
    out = estimate_running_fitness([_effort(21)], [], TODAY)
    assert out["vdot"] == round(calculate_vdot(5000, 1200) * 0.93, 1)


def test_steady_runs_with_heart_rate_lift_a_runner_who_never_races():
    """Someone who only ever runs easy has only easy efforts, so the effort floor
    alone paces them slower than they are. Their HR says how hard those runs
    were, and that is the estimate."""
    easy_effort = [_effort(3, minutes=5 * 330 / 60)]  # a 5 K stretch at 5:30/km
    runs = [_run(d) for d in (2, 5, 9, 12)]
    floor_only = estimate_running_fitness(easy_effort, runs, TODAY)
    with_hr = estimate_running_fitness(easy_effort, runs, TODAY, **HR)
    assert floor_only["source"] == "effort"
    assert with_hr["source"] == "heart_rate"
    assert with_hr["vdot"] > floor_only["vdot"] + 5
    assert with_hr["effort_vdot"] == floor_only["vdot"]


def test_runs_at_the_top_of_easy_come_back_with_the_middle_of_easy():
    """The calibration is self-consistent, whatever the runner's height,
    weight or economy — which is what personalises the paces. Runs held at
    70% of heart-rate reserve (78% of max here) are at the top of Daniels'
    easy heart rates, so the easy pace they give is a little slower than they
    were run: the middle of his easy band, where it used to be its fast edge
    and an easy run sat at the limit of easy."""
    for pace in (300.0, 360.0, 420.0):
        hr = round(55 + 0.70 * (185 - 55))
        out = estimate_running_fitness([], [_run(d, pace_sec_km=pace, avg_hr=hr) for d in (1, 4, 8)],
                                       TODAY, **HR)
        assert out["source"] == "heart_rate"
        assert 15 < vdot_to_paces(out["vdot"])["easy"] - pace < 40, pace


def test_the_paces_are_daniels_own():
    """Daniels' table at VDOT 40 gives easy 6:07–6:44/km and threshold 5:06.
    Easy is the band's middle (it was its fast edge, 6:06), recovery its slow
    edge, threshold his 88% (it was 90%, 6 s/km quicker) — the share that
    matched recreational runners' measured lactate-threshold pace."""
    p = vdot_to_paces(40.0)
    assert 367 < p["easy"] < 404 and abs(p["easy"] - (367 + 404) / 2) < 3
    assert abs(p["recovery"] - 404) < 1.5
    assert abs(p["threshold"] - 306) < 1.5


def test_a_noisy_heart_rate_cannot_pull_fitness_below_a_real_race():
    """A strap reading high (or a hot week) makes runs look harder than they
    were; a race actually run is a floor the estimate cannot go under."""
    runs = [_run(d, avg_hr=178) for d in (2, 5, 9)]
    out = estimate_running_fitness([_effort(10, minutes=19.0)], runs, TODAY, **HR)
    assert out["source"] == "effort"
    assert out["vdot"] == round(calculate_vdot(5000, 19 * 60), 1)


def test_a_treadmill_run_is_no_floor():
    """Its speed is a wrist, footpod or belt estimate: one over-read session
    would set every pace for six weeks. The HR method refused treadmills for
    the same reason; the floor did not."""
    fast = _effort(5, minutes=17.0)
    road = estimate_running_fitness([fast], [_run(5, avg_hr=None)], TODAY)
    treadmill = estimate_running_fitness([{**fast, "sport": "treadmill_running"}], [_run(5, avg_hr=None)], TODAY)
    virtual = estimate_running_fitness([{**fast, "sport": "virtual_running"}], [_run(5, avg_hr=None)], TODAY)
    assert road["source"] == "effort"
    assert treadmill["source"] == virtual["source"] == "profile"


def _weeks(n, pace_sec_km=360.0, per_week=3, km=8.0):
    return [_run(1 + 7 * w + 2 * i, pace_sec_km=pace_sec_km, avg_hr=None, km=km)
            for w in range(n) for i in range(per_week)]


def test_a_phone_with_no_heart_rate_is_paced_from_its_training():
    """GPS alone used to leave only the effort floor: for someone who only
    runs easy, their easy pace read as a race — ten VDOT slow. Their eight
    weeks of volume and pace (Tanda 2011) now set the estimate, as the VDOT
    of the marathon those predict: slow for a 5 K, far closer than the floor."""
    easy_floor = [_effort(4, minutes=5 * 360 / 60)]
    out = estimate_running_fitness(easy_floor, _weeks(8), TODAY)
    assert out["source"] == "training" and out["measured"]
    assert out["vdot"] > out["effort_vdot"] + 5
    assert vdot_to_paces(out["vdot"])["easy"] > 360  # still easier than every run they did


def test_the_training_estimate_needs_six_of_the_eight_weeks():
    """A history that starts partway through reads as low volume."""
    assert estimate_running_fitness([], _weeks(5), TODAY)["source"] == "profile"
    assert estimate_running_fitness([], _weeks(6), TODAY)["source"] == "training"


def test_heart_rate_and_the_watch_go_before_the_training_estimate():
    hr_runs = _weeks(8, pace_sec_km=330.0) + [_run(d) for d in (1, 3, 5)]
    assert estimate_running_fitness([], hr_runs, TODAY, **HR)["source"] == "heart_rate"
    watch = _weeks(8) + [_run(2, avg_hr=None, vo2max=50.0)]
    assert estimate_running_fitness([], watch, TODAY)["source"] == "watch"


def test_two_runs_are_not_enough_for_a_heart_rate_estimate():
    """One bad strap reading must not be the estimate."""
    runs = [_run(d) for d in (2, 5)]
    out = estimate_running_fitness([], runs, TODAY, **HR)
    assert out["source"] == "profile"


@pytest.mark.parametrize("bad", [
    {"sport": "treadmill_running"},     # speed is a wrist estimate
    {"sport": "trail_running"},         # terrain costs what speed does not show
    {"ascent_m": 400.0},                # 50 m/km: hills move HR, not speed
    {"km": 3.0},                        # 16 min: mostly the first minutes' HR lag
    {"avg_hr": 90},                     # 27% of reserve: extrapolation amplifies error
    {"avg_hr": 184},                    # 99%: not a steady run
])
def test_runs_outside_the_method_are_left_out_of_the_heart_rate_estimate(bad):
    """Each of these breaks the speed-to-oxygen or HR-to-oxygen link, so three
    of them are no estimate at all."""
    runs = [_run(d, **bad) for d in (2, 5, 9)]
    assert estimate_running_fitness([], runs, TODAY, **HR)["source"] == "profile"


def test_the_watch_vo2max_is_used_only_when_heart_rate_cannot_be():
    """The dashboard's VO2max is the fallback, at nine tenths of itself: the
    watch estimates lab VO2max, which runs above VDOT by the recreational
    runner's economy gap to Daniels' curve — taken whole, it paced them about
    10% fast. The HR estimate, which measures the runner, goes first."""
    runs = [_run(d, vo2max=52.0) for d in (2, 5, 9)]
    no_rest = estimate_running_fitness([], runs, TODAY, max_hr=185)
    assert no_rest["source"] == "watch" and no_rest["vdot"] == round(52.0 * rf.WATCH_VDOT, 1) == 46.8
    with_hr = estimate_running_fitness([], runs, TODAY, **HR)
    assert with_hr["source"] == "heart_rate"


def test_a_watch_vo2max_older_than_the_window_is_not_today():
    runs = [_run(120, vo2max=52.0)]
    assert estimate_running_fitness([], runs, TODAY)["source"] == "profile"


def test_with_no_runs_the_profile_sets_the_estimate_and_is_not_measured():
    """Not measured means the plan uses it for paces only — no watch pace
    targets from a guess, no run/walk capacity from it (plan.py)."""
    out = estimate_running_fitness([], [], TODAY, sex="female", height_cm=165,
                                   weight_kg=60, birth_year=1991, frequencies={"running": "never"})
    assert out == {"vdot": 24.1, "source": "profile", "measured": False, "effort_vdot": None}


def test_knowing_nothing_is_slower_than_the_old_default():
    """The old default was VDOT 42 — a 23-minute 5 K, 5:52/km easy — for
    everyone, including someone who has never run. Knowing nothing now means the
    model's middle of the population, about a 29-minute 5 K."""
    out = estimate_running_fitness([], [], TODAY)
    assert out["vdot"] == 31.8
    assert vdot_to_paces(out["vdot"])["easy"] > vdot_to_paces(42.0)["easy"] + 60


def test_height_enters_only_through_bmi():
    """The evidence does not support taller runners jogging faster at the same
    fitness; height counts only through BMI, so two people of the same BMI get
    the same estimate whatever their heights."""
    tall = rf.profile_vdot(TODAY, sex="male", height_cm=193, weight_kg=24 * 1.93 ** 2)
    short = rf.profile_vdot(TODAY, sex="male", height_cm=152, weight_kg=24 * 1.52 ** 2)
    assert tall == pytest.approx(short)


def test_the_profile_is_ignored_once_any_run_is_measured():
    """The user's choice: height, weight and the rest inform the estimate only
    until real runs exist."""
    runs = [_run(d) for d in (2, 5, 9)]
    lean = estimate_running_fitness([], runs, TODAY, **HR, sex="male", height_cm=180, weight_kg=65)
    heavy = estimate_running_fitness([], runs, TODAY, **HR, sex="female", height_cm=160, weight_kg=95)
    assert lean == heavy


def test_the_most_active_sport_sets_the_activity_rating():
    """PA-R rates all activity, so a five-a-week cyclist who never runs is
    rated on the cycling."""
    assert rf.par_from_frequencies({"running": "never", "cycling": "5_plus"}) == 7
    assert rf.par_from_frequencies({"running": "bogus"}) == rf.DEFAULT_PAR
    assert rf.par_from_frequencies(None) == rf.DEFAULT_PAR


def test_evidence_dated_after_today_is_not_read():
    """A regeneration dated in the past (fixtures, replays) must not see runs
    from its future."""
    out = estimate_running_fitness([_effort(-10)], [_run(-5)], TODAY, **HR)
    assert out["source"] == "profile"


def _parse(case: dict) -> dict:
    def day(s):
        return date.fromisoformat(s)
    return {
        "efforts": [{**e, "date": day(e["date"])} for e in case["efforts"]],
        "runs": [{**r, "date": day(r["date"])} for r in case["runs"]],
        "today": day(case["today"]),
        "kw": case["kw"],
    }


def test_the_running_fitness_corpus_still_holds():
    """An estimator change that moves these numbers must be regenerated for the phone."""
    for c in CORPUS["detraining"]:
        assert rf.detraining_factor(c["days"]) == c["expect"], c
    for c in CORPUS["hr_run"]:
        assert rf.hr_run_vdot(c["speed"], c["hr"], c["max"], c["rest"]) == c["expect"], c
    for c in CORPUS["profile"]:
        kw = dict(c["kw"])
        assert rf.profile_vdot(date.fromisoformat(c["today"]), **kw) == c["expect"], c
    for c in CORPUS["estimates"]:
        p = _parse(c)
        got = estimate_running_fitness(p["efforts"], p["runs"], p["today"], **p["kw"])
        assert got == c["expect"], c["name"]
