# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Where a plan starts for someone with no history: calculators/plan/starting.py.

Before this, every newcomer got one default athlete — a 3 km first run for
someone who had never run, and the same for someone running five times a week.
The phone replays spec/fixtures/plan_start.json; the corpus test here is what
fails when a change to the generator is not regenerated into it.
"""
import json
from datetime import date, timedelta
from pathlib import Path
from types import SimpleNamespace

from app.calculators.plan import starting
from app.calculators.plan.generator.dispatch import _HIGH_INTENSITY_TYPES
from app.calculators.plan.generator.fitness import generate_fitness_plan
from app.calculators.plan.generator.plan import generate_training_plan

CORPUS = json.loads(Path("/spec/fixtures/plan_start.json").read_text())
TODAY = date(2026, 3, 4)


def _goal(sport="running", distance=10000.0, weeks=12):
    return SimpleNamespace(event_date=TODAY + timedelta(weeks=weeks), event_sport=sport,
                           event_distance_meters=distance, days_per_week=4, plan_intensity=None,
                           mtb_discipline=None, cycling_discipline=None, ctl_ramp_per_week=2.0)


def _first(workouts, wtype):
    return next(w for w in workouts if w["workout_type"] == wtype)


def _plain(v):
    if isinstance(v, date):
        return v.isoformat()
    if isinstance(v, dict):
        return {k: _plain(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_plain(x) for x in v]
    return v


def test_the_plan_start_corpus_still_holds():
    """A generator change that moves these plans must be regenerated for the phone."""
    for case in CORPUS["plans"]:
        g = SimpleNamespace(**{**{k: None for k in ("days_per_week", "plan_intensity")},
                               **case["goal"], "mtb_discipline": None, "cycling_discipline": None})
        g.event_date = date.fromisoformat(case["goal"]["event_date"])
        history = [SimpleNamespace(sport=h["sport"], started_at=date.fromisoformat(h["started"]),
                                   distance_meters=h["distance"]) for h in case["history"]]
        _, got = generate_training_plan(g, history, [], date.fromisoformat(case["today"]),
                                        activity_frequency=case["level"], **case["kw"])
        assert _plain(got) == case["workouts"], case["name"]


def test_an_unknown_answer_is_treated_as_no_answer():
    """A level the planner does not know (an old client's, a typo) must not
    read as some other level — it is no answer at all."""
    for sport in ("running", "cycling", "hiking"):
        _, before = generate_training_plan(_goal(sport), [], [], TODAY)
        _, after = generate_training_plan(_goal(sport), [], [], TODAY, activity_frequency=None)
        _, bogus = generate_training_plan(_goal(sport), [], [], TODAY, activity_frequency="daily")
        assert before == after == bogus


def _quality_weeks(workouts):
    """The 0-based plan weeks holding a quality session (the taper's strides aside)."""
    monday = TODAY - timedelta(days=TODAY.weekday())
    return sorted({(w["scheduled_date"] - monday).days // 7 for w in workouts
                   if w["workout_type"] in _HIGH_INTENSITY_TYPES and w["workout_type"] != "short_quality"})


def test_an_unanswered_newcomer_opens_with_easy_weeks():
    """The report: a brand-new user who does not run got a fartlek in week one.
    No history and no answer is not evidence of training; the first weeks are
    easy while the volume lands. Cycling isolates the intro weeks from the
    running-specific readiness gate (next test)."""
    _, ride = generate_training_plan(_goal("cycling", 100000.0), [], [], TODAY)
    weeks = _quality_weeks(ride)
    assert weeks and min(weeks) == starting.intro_weeks(None) == 3
    _, run = generate_training_plan(_goal("running", 5000.0), [], [], TODAY)
    monday = TODAY - timedelta(days=TODAY.weekday())
    assert not any(w["workout_type"] in _HIGH_INTENSITY_TYPES for w in run
                   if w["scheduled_date"] < monday + timedelta(weeks=1))


def test_a_runner_who_still_needs_walk_breaks_gets_no_quality():
    """The plan's own capacity model puts a newcomer on run/walk intervals; the
    same week must not hand them a continuous 45-minute fartlek or tempo."""
    _, run = generate_training_plan(_goal("running", 5000.0), [], [], TODAY)
    for w in run:
        if w["workout_type"] in _HIGH_INTENSITY_TYPES and w["workout_type"] != "short_quality":
            raise AssertionError(f"{w['workout_type']} on {w['scheduled_date']} for a run/walk runner")
    assert any(s["type"] == "interval_set" and s.get("rest_sec")
               for s in _first(run, "easy")["steps"]), "the premise: they are on run/walk"


def test_someone_running_once_or_twice_a_week_raises_frequency_before_intensity():
    """A 4-day plan already doubles their running days; quality waits three
    weeks rather than arriving with the extra days."""
    _, ride = generate_training_plan(_goal("cycling", 100000.0), [], [], TODAY, activity_frequency="1_2")
    assert min(_quality_weeks(ride)) == 3
    _, regular = generate_training_plan(_goal("cycling", 100000.0), [], [], TODAY, activity_frequency="3_4")
    assert min(_quality_weeks(regular)) == 0


def test_history_skips_the_intro_weeks():
    """Someone with rides on record is training now; their plan starts as it always did."""
    history = [SimpleNamespace(sport="cycling", started_at=TODAY - timedelta(days=d), distance_meters=40000.0)
               for d in range(2, 56, 3)]
    _, ride = generate_training_plan(_goal("cycling", 100000.0), history, [], TODAY)
    assert min(_quality_weeks(ride)) == 0


def test_someone_who_has_never_run_starts_on_short_run_walk_sessions():
    """The complaint: a 3 km first run is too much for someone who has never run."""
    _, default = generate_training_plan(_goal(), [], [], TODAY)
    _, never = generate_training_plan(_goal(), [], [], TODAY, activity_frequency="never")
    first = _first(never, "easy")
    assert first["duration_minutes"] <= 25
    assert first["distance_meters"] < _first(default, "easy")["distance_meters"]
    assert any(s["type"] == "interval_set" and s.get("rest_sec") for s in first["steps"]), \
        "a newcomer runs with walk breaks"


def test_a_regular_runner_with_no_history_starts_on_continuous_longer_runs():
    """The other half of it: 3 km is too little for someone who runs five times a week."""
    _, never = generate_training_plan(_goal(), [], [], TODAY, activity_frequency="never")
    _, regular = generate_training_plan(_goal(), [], [], TODAY, activity_frequency="5_plus")
    first = _first(regular, "easy")
    # ~7 km: the week is sized in load now (week.py), and a 40 km first week
    # over four days puts 40% of it in the long run.
    assert first["distance_meters"] >= 6000
    assert first["distance_meters"] > 2 * _first(never, "easy")["distance_meters"]
    assert not any(s["type"] == "interval_set" for s in first["steps"])
    assert _first(regular, "long")["distance_meters"] > _first(never, "long")["distance_meters"]


def test_the_newcomers_shortened_sessions_grow_back_over_the_first_weeks():
    start = starting.no_history_start("never")
    ramp = [starting.start_intensity(start, w) for w in range(starting.RAMP_WEEKS + 2)]
    assert ramp[0] == 0.6 and ramp[-1] == 1.0
    assert ramp == sorted(ramp)


def test_history_beats_the_answer():
    """Once there are runs on record, they are the truth; the answer is only a first guess."""
    history = [SimpleNamespace(sport="running", started_at=TODAY - timedelta(days=d), distance_meters=10000.0)
               for d in range(2, 56, 3)]
    _, never = generate_training_plan(_goal(), history, [], TODAY, activity_frequency="never")
    _, regular = generate_training_plan(_goal(), history, [], TODAY, activity_frequency="5_plus")
    _, unanswered = generate_training_plan(_goal(), history, [], TODAY)
    assert never == regular == unanswered


def test_a_mountain_biker_borrows_the_cycling_answer():
    assert starting.frequency_for({"cycling": "3_4"}, "mountain_biking") == "3_4"
    assert starting.frequency_for({"cycling": "3_4", "mountain_biking": "never"}, "mountain_biking") == "never"
    assert starting.frequency_for({"cycling": "3_4"}, "running") is None
    assert starting.frequency_for("not a map", "running") is None


def test_a_fitness_plan_with_no_load_starts_from_the_answer():
    """A fitness plan builds from CTL, and a new account's is 0 whatever they do."""
    g = _goal()
    _, none = generate_fitness_plan(g, [], [], TODAY, ctl=0.0, atl=0.0)
    _, regular = generate_fitness_plan(g, [], [], TODAY, ctl=0.0, atl=0.0, activity_frequency="3_4")
    minutes = lambda ws: sum(w["duration_minutes"] for w in ws)  # noqa: E731
    assert minutes(regular) > minutes(none)
    # And measured load beats the answer.
    _, measured = generate_fitness_plan(g, [], [], TODAY, ctl=20.0, atl=20.0)
    _, measured_answered = generate_fitness_plan(g, [], [], TODAY, ctl=20.0, atl=20.0,
                                                 activity_frequency="5_plus")
    assert measured == measured_answered


def test_a_never_runners_first_weeks_hold_no_fartlek():
    """The gap left by shortening alone: the fartlek builder has a fixed
    warm-up and at least 20 minutes of surges, so a never-runner's first week
    still held a 45-minute threshold session. Quality waits for the ramp."""
    _, never = generate_training_plan(_goal(), [], [], TODAY, activity_frequency="never")
    ramp_end = TODAY - timedelta(days=TODAY.weekday()) + timedelta(weeks=starting.RAMP_WEEKS)
    early = [w for w in never if w["scheduled_date"] < ramp_end]
    assert early and not any(w["workout_type"] in _HIGH_INTENSITY_TYPES for w in early)
    assert max(w["duration_minutes"] for w in early if w["workout_type"] != "long") <= 30
    assert any(w["workout_type"] in _HIGH_INTENSITY_TYPES for w in never
               if w["scheduled_date"] >= ramp_end), "quality arrives once the ramp is over"
    # Someone who already runs three or four times a week keeps it from the first week.
    _, regular = generate_training_plan(_goal(), [], [], TODAY, activity_frequency="3_4")
    assert any(w["workout_type"] == "fartlek" for w in regular if w["scheduled_date"] < ramp_end)


def test_a_new_fitness_runner_starts_on_easy_sessions_only():
    g = _goal()
    _, never = generate_fitness_plan(g, [], [], TODAY, ctl=0.0, atl=0.0, activity_frequency="never")
    assert not any(w["workout_type"] in _HIGH_INTENSITY_TYPES for w in never)


def _multi(sports=("running", "cycling")):
    return SimpleNamespace(event_sport=sports[0], fitness_sports=list(sports), ctl_ramp_per_week=2.0,
                           days_per_week=5, mtb_discipline=None, cycling_discipline=None)


def test_each_sport_of_a_multi_sport_goal_starts_from_its_own_answer():
    """A regular cyclist who has never run: the rides carry the load and the
    quality; the runs stay short run/walk sessions. With one answer for the
    goal (or an equal split of the load) the runs were an hour long."""
    freq = {"running": "never", "cycling": "5_plus"}
    _, ws = generate_fitness_plan(_multi(), [], [], TODAY, ctl=0.0, atl=0.0,
                                  activity_frequencies=freq)
    runs = [w for w in ws if w["sport"] == "running"]
    rides = [w for w in ws if w["sport"] == "cycling"]
    assert runs and rides
    first_monday = TODAY - timedelta(days=TODAY.weekday())
    first_runs = [w for w in runs if w["scheduled_date"] < first_monday + timedelta(weeks=1)]
    assert first_runs and max(w["duration_minutes"] for w in first_runs) <= 25
    assert not any(w["workout_type"] in _HIGH_INTENSITY_TYPES for w in runs)
    assert sum(w["duration_minutes"] for w in rides) > 4 * sum(w["duration_minutes"] for w in runs)
    # Swapped round, the runs carry it.
    _, swapped = generate_fitness_plan(_multi(), [], [], TODAY, ctl=0.0, atl=0.0,
                                       activity_frequencies={"running": "5_plus", "cycling": "never"})
    run_min = sum(w["duration_minutes"] for w in swapped if w["sport"] == "running")
    ride_min = sum(w["duration_minutes"] for w in swapped if w["sport"] == "cycling")
    assert run_min > ride_min


def test_measured_load_beats_every_sports_answer():
    freq = {"running": "never", "cycling": "5_plus"}
    _, answered = generate_fitness_plan(_multi(), [], [], TODAY, ctl=25.0, atl=25.0,
                                        activity_frequencies=freq)
    _, unanswered = generate_fitness_plan(_multi(), [], [], TODAY, ctl=25.0, atl=25.0)
    assert answered == unanswered
