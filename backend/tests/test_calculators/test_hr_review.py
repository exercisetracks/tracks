# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The heart-rate review: which samples of a recording the load believes."""

import random
from statistics import mean

from app.calculators.hr_review import _power, review_heart_rate
from app.calculators.training_load import estimated_raw_tss
from app.services.fit_import import _compute_hr_tss


def _run(n=2400, seed=3, hills=True, altitude=True):
    """A run whose heart rate follows its effort, as a heart's does: from
    rest, lagging each change, drifting up slowly, with sensor noise."""
    rng = random.Random(seed)
    times, hrs, speeds, alts, cads = [], [], [], [], []
    alt, lag = 100.0, 0.0
    for i in range(n):
        up = hills and (i // 300) % 2 == 1
        v = (2.6 if up else 3.1) + rng.uniform(-0.1, 0.1)
        grade = (0.12 if up else -0.08) if hills else 0.0
        alt += grade * v
        lag += (1 / 41) * (_power("running", v, grade) - lag)
        times.append(float(i))
        speeds.append(round(v, 3))
        alts.append(round(alt, 1) if altitude else None)
        hrs.append(round((65 + 6.0 * lag + i / 3600 * 8 + rng.uniform(-2, 2)) * 2) / 2)
        cads.append(float(rng.choice([81, 82, 83])))
    return {"times": times, "hrs": hrs, "speeds": speeds, "altitudes": alts, "cadences": cads}


def _review(r, sport="running"):
    return review_heart_rate(sport, r["times"], r["hrs"], r["speeds"], r["altitudes"], r["cadences"])


def test_a_clean_recording_is_left_exactly_as_it_was():
    """Anything this changes on a good recording is a load that moved for no
    reason — so a clean recording must come back untouched."""
    assert _review(_run()) is None


def test_clean_intervals_are_not_mistaken_for_a_sensor_fault():
    """Heart rate lags a hard rep by half a minute; the lagged effort model
    has to absorb that, or every interval session would be 'corrected'."""
    rng = random.Random(5)
    lag, hrs, speeds = 0.0, [], []
    for i in range(3000):
        v = 4.6 if (i // 120) % 2 else 2.4
        lag += (1 / 41) * (_power("running", v, 0.0) - lag)
        speeds.append(v)
        hrs.append(round(60 + 5.5 * lag + rng.uniform(-2, 2)))
    assert review_heart_rate("running", [float(i) for i in range(3000)], hrs, speeds,
                             [50.0] * 3000, [85.0] * 3000) is None


def test_a_strap_reading_low_for_minutes_is_replaced_from_the_athletes_own_fit():
    r = _run()
    truth = mean(r["hrs"])
    for i in range(900, 1300):
        r["hrs"][i] = 92.0
    raw = mean(r["hrs"])
    review = _review(r)
    assert review.usable
    assert abs(review.avg_hr - truth) < abs(raw - truth) / 3


def test_only_the_samples_that_disagree_are_replaced():
    """A recording that is partly wrong keeps everything it got right."""
    r = _run()
    for i in range(900, 1300):
        r["hrs"][i] = 92.0
    review = _review(r)
    # A few are on descents, where a strap reading 92 is barely wrong: those
    # are left, and cost the average almost nothing.
    assert 300 <= review.replaced < 520


def test_a_heart_rate_that_follows_cadence_as_it_changes_is_a_lock():
    r = _run()
    truth = mean(r["hrs"])
    for i in range(1200, 1800):
        c = float(78 + (i // 15) % 10)
        r["cadences"][i] = c
        r["hrs"][i] = 2 * c
    review = _review(r)
    assert review.replaced >= 600
    assert abs(review.avg_hr - truth) < 3


def test_a_heart_rate_that_merely_equals_cadence_is_not_a_lock():
    """A runner's heart rate and step rate genuinely overlap. Equal is not
    evidence; only following the cadence as it moves is."""
    r = _run(hills=False)
    typical = round(mean(r["hrs"][600:]) / 2)
    r["cadences"] = [float(typical)] * len(r["hrs"])
    assert sum(abs(h - 2 * typical) <= 2 for h in r["hrs"]) > len(r["hrs"]) / 2
    assert _review(r) is None


def test_a_strap_that_starts_late_is_filled_in_rather_than_ignored():
    """The device's average skips the missing minutes, which are the easy
    warm-up — it reads the session as harder than it was."""
    r = _run()
    truth = mean(r["hrs"])
    for i in range(0, 300):
        r["hrs"][i] = None
    device_avg = mean(h for h in r["hrs"] if h is not None)
    review = _review(r)
    assert abs(review.avg_hr - truth) < abs(device_avg - truth)


def test_a_multi_sample_artifact_the_parser_leaves_is_removed():
    r = _run()
    for i in range(1500, 1510):
        r["hrs"][i] = 212.0
    review = _review(r)
    assert review.replaced == 10
    assert review.max_hr < 212


def test_a_dropout_on_a_steady_treadmill_run_is_caught_without_a_gradient():
    """No altitude and no change of pace: nothing to fit, but a steady effort
    says the heart rate should be steady too."""
    n = 2400
    rng = random.Random(9)
    hrs = [150.0 + rng.uniform(-2, 2) for _ in range(n)]
    for i in range(900, 1200):
        hrs[i] = 100.0
    review = review_heart_rate("treadmill_running", [float(i) for i in range(n)], hrs,
                               [3.0] * n, [None] * n, [85.0] * n)
    assert review.replaced == 300
    assert abs(review.avg_hr - 150) < 1


def test_hills_are_not_called_a_fault_when_the_recording_has_no_altitude():
    """On a bike a 5% climb is four times the power of the flat. With no
    altitude the gradient is unknown, so no hill may be judged against a
    fit that assumed flat."""
    rng = random.Random(4)
    lag, hrs, speeds = 0.0, [], []
    for i in range(2400):
        climbing = (i // 240) % 2 == 1
        v = 6.0 if climbing else 9.5
        lag += (1 / 41) * (_power("cycling", v, 0.06 if climbing else 0.0) - lag)
        speeds.append(v)
        hrs.append(round(70 + 22 * lag + rng.uniform(-2, 2)))
    assert review_heart_rate("cycling", [float(i) for i in range(2400)], hrs, speeds,
                             [None] * 2400, [88.0] * 2400) is None


def test_a_recording_with_no_heart_rate_series_is_not_reviewed():
    r = _run()
    r["hrs"] = [None] * len(r["hrs"])
    assert _review(r) is None


def test_too_little_heart_rate_to_believe_scores_as_no_heart_rate():
    """Four minutes of a strap out of forty, on a steady effort, cannot say
    what the other thirty-six were."""
    n = 2400
    hrs = [None] * n
    for i in range(240):
        hrs[i] = 150.0
    points_review = review_heart_rate("treadmill_running", [float(i) for i in range(n)], hrs,
                                      [3.0] * n, [None] * n, [85.0] * n)
    assert points_review is not None and not points_review.usable


def test_the_importer_uses_the_reviewed_average_and_leaves_a_clean_file_alone():
    from datetime import datetime, timedelta, timezone
    t0 = datetime(2026, 3, 1, 7, tzinfo=timezone.utc)
    r = _run()

    def points(run):
        return [{"recorded_at": t0 + timedelta(seconds=t), "heart_rate": h, "speed": v,
                 "altitude": a, "cadence": c}
                for t, h, v, a, c in zip(run["times"], run["hrs"], run["speeds"],
                                         run["altitudes"], run["cadences"])]

    activity = {"sport": "running", "duration_seconds": 2400, "distance_meters": 7000.0,
                "total_ascent": 150.0, "avg_heart_rate": round(mean(r["hrs"])),
                "max_heart_rate": max(r["hrs"])}
    clean = _compute_hr_tss(dict(activity), 160.0, points(r))
    assert clean == _compute_hr_tss(dict(activity), 160.0, None)

    for i in range(900, 1300):
        r["hrs"][i] = 92.0
    broken = dict(activity, avg_heart_rate=round(mean(r["hrs"])))
    assert abs(_compute_hr_tss(broken, 160.0, points(r)) - clean) < abs(
        _compute_hr_tss(dict(broken), 160.0, None) - clean)

    r["hrs"] = [150.0] * 200 + [None] * 2200
    dead = dict(activity, avg_heart_rate=150, max_heart_rate=150)
    assert _compute_hr_tss(dead, 160.0, points(r)) == estimated_raw_tss("running", 2400, 7000.0, 150.0)
