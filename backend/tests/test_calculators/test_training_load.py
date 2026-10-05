# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import date, timedelta
from types import SimpleNamespace

import pytest

from app.calculators.plan.load import EASY_IF
from app.calculators.training_load import (
    calculate_ctl_atl_tsb,
    estimate_tss,
    estimated_intensity,
    estimated_tss,
    load_calibration,
)


def _activity(**kwargs):
    defaults = dict(
        training_stress_score=None,
        effective_tss=None,
        duration_seconds=None,
        avg_heart_rate=None,
        max_heart_rate=None,
    )
    defaults.update(kwargs)
    return SimpleNamespace(**defaults)


# ── estimate_tss ─────────────────────────────────────────────────────────────

class TestEstimateTss:
    def test_uses_device_tss_first(self):
        a = _activity(training_stress_score=85.0, effective_tss=50.0,
                      duration_seconds=3600, avg_heart_rate=140)
        assert estimate_tss(a) == 85.0

    def test_uses_effective_tss_when_no_device_tss(self):
        a = _activity(training_stress_score=None, effective_tss=62.5,
                      duration_seconds=3600, avg_heart_rate=140)
        assert estimate_tss(a) == 62.5

    def test_calculates_hr_tss_with_known_lthr(self):
        # 1h @ 140bpm with LTHR 160 → ratio 0.875 → TSS = 1 * 0.875² * 100 = 76.6
        a = _activity(duration_seconds=3600, avg_heart_rate=140, max_heart_rate=180)
        tss = estimate_tss(a, threshold_hr=160.0)
        assert abs(tss - 76.6) < 0.2

    def test_calculates_hr_tss_from_max_hr_fallback(self):
        # LTHR not supplied → fallback: LTHR = max_hr * 0.87
        a = _activity(duration_seconds=3600, avg_heart_rate=140, max_heart_rate=180)
        expected_lthr = 180 * 0.87
        expected_ratio = min(140 / expected_lthr, 1.5)
        expected_tss = round(1.0 * expected_ratio ** 2 * 100, 1)
        assert estimate_tss(a) == expected_tss

    def test_an_activity_with_no_heart_rate_still_carries_load(self):
        """A run recorded on the phone has no heart rate. It used to count as
        zero, which left a phone-only athlete's fitness line flat whatever
        they did and had the fitness plan treat them as a beginner."""
        a = _activity(sport="running", duration_seconds=3600, avg_heart_rate=None,
                      distance_meters=10000.0, total_ascent=0.0)
        assert estimate_tss(a) == estimated_tss("running", 3600, 10000.0, 0.0)
        assert 55 < estimate_tss(a) < 70

    def test_a_walk_with_no_heart_rate_counts_for_less_than_a_run(self):
        run = estimate_tss(_activity(duration_seconds=3600, sport="running"))
        walk = estimate_tss(_activity(duration_seconds=3600, sport="walking"))
        assert 0 < walk < run

    def test_meditation_and_breathwork_carry_no_load(self):
        """They share mind_body with yoga, whose estimate would otherwise add
        ~20 TSS an hour of fatigue for sitting still."""
        for sport in ("meditation", "Breathwork"):
            assert estimate_tss(_activity(duration_seconds=3600, sport=sport)) == 0.0
        assert estimate_tss(_activity(duration_seconds=3600, sport="yoga")) > 0

    def test_heart_rate_still_wins_over_the_estimate(self):
        """The estimate is the last resort: a strap on the same run is a
        measurement, and it must not be averaged away."""
        a = _activity(sport="running", duration_seconds=3600, avg_heart_rate=170,
                      max_heart_rate=190, distance_meters=10000.0)
        assert estimate_tss(a, threshold_hr=160.0) == round((170 / 160) ** 2 * 100, 1)

    def test_returns_zero_when_no_duration(self):
        a = _activity(duration_seconds=None, avg_heart_rate=140)
        assert estimate_tss(a) == 0.0

    def test_hr_ratio_capped_at_1_5(self):
        # avg_hr >> threshold_hr should not produce unreasonably large TSS
        a = _activity(duration_seconds=3600, avg_heart_rate=200, max_heart_rate=200)
        tss = estimate_tss(a, threshold_hr=100.0)
        assert tss == round(1.0 * 1.5 ** 2 * 100, 1)


# ── estimated_tss (no heart rate) ──────────────────────────────────────────

class TestEstimatedTss:
    def test_an_easy_run_is_scored_on_the_planners_scale(self):
        """The fitness plan counts what it prescribes with plan/load.py's IFs.
        A completed unstructured run estimated on a different scale would make
        the chart and the plan disagree about the same week."""
        assert abs(estimated_intensity("running", 3600, 10000.0, 0.0) - EASY_IF) <= 0.05

    def test_running_pace_alone_does_not_change_the_estimate(self):
        """6:00/km is a recovery jog for one runner and threshold for another.
        Reading speed as effort would score a slow runner's easy days as hard
        ones; only climb moves a run's estimate."""
        slow = estimated_tss("running", 3600, 7500.0, 0.0)
        fast = estimated_tss("running", 3600, 15000.0, 0.0)
        assert slow == fast

    def test_a_hilly_trail_run_carries_more_load_than_the_same_hour_on_the_flat(self):
        flat = estimated_tss("trail_running", 3600, 9000.0, 0.0)
        hilly = estimated_tss("trail_running", 3600, 9000.0, 600.0)
        assert hilly > flat * 1.15

    def test_a_noisy_climb_cannot_push_intensity_past_the_cap(self):
        """Altitude from a phone's GPS is filtered but never exact; an absurd
        climb rate must not produce an absurd load."""
        capped = estimated_intensity("running", 3600, 9000.0, 800.0)
        absurd = estimated_intensity("running", 3600, 9000.0, 20000.0)
        assert absurd == capped
        assert capped < 1.0

    def test_a_brisk_walk_carries_more_load_than_a_stroll_and_less_than_a_run(self):
        stroll = estimated_tss("walking", 3600, 3500.0, 0.0)
        brisk = estimated_tss("walking", 3600, 6500.0, 0.0)
        run = estimated_tss("running", 3600, 10000.0, 0.0)
        assert stroll < brisk < run

    def test_a_steep_slow_hike_is_not_scored_as_a_stroll(self):
        """A hike is slow *because* it climbs. Speed is taken as equivalent
        flat speed, so 2.5 km/h up a mountain is not a 2.5 km/h stroll."""
        stroll = estimated_intensity("hiking", 3600, 2500.0, 0.0)
        steep = estimated_intensity("hiking", 3600, 2500.0, 600.0)
        assert steep > stroll + 0.1

    def test_a_cafe_ride_carries_less_load_than_a_training_ride(self):
        cafe = estimated_tss("cycling", 3600, 14000.0, 0.0)
        training = estimated_tss("cycling", 3600, 28000.0, 0.0)
        assert cafe < training

    def test_a_missing_distance_falls_back_to_the_sports_base_intensity(self):
        """A logged session with only a duration still counts — an hour of
        something is never nothing."""
        assert estimated_tss("cycling", 3600, None, None) == round(0.70 * 0.70 * 100, 1)
        assert estimated_tss("hiking", 3600, 0.0, None) == round(0.60 * 0.60 * 100, 1)

    def test_an_unknown_sport_is_estimated_as_light_work_not_nothing(self):
        assert estimated_tss("made_up_sport", 3600) == round(0.60 * 0.60 * 100, 1)
        assert estimated_tss(None, 3600) == round(0.60 * 0.60 * 100, 1)

    def test_no_duration_is_no_load(self):
        assert estimated_tss("running", None, 10000.0, 100.0) == 0.0
        assert estimated_tss("running", 0, 10000.0, 100.0) == 0.0

    def test_the_mtb_and_indoor_multipliers_apply_to_an_estimated_ride(self):
        """They are about what the sport demands, not about how load was
        measured, so an estimate gets them as a measured ride does."""
        plain = estimated_tss("mountain_biking", 3600, 15000.0, 300.0, None)
        enduro = estimated_tss("mountain_biking", 3600, 15000.0, 300.0, "enduro")
        assert enduro > plain
        indoor = estimated_tss("indoor_cycling", 3600, None, None)
        assert indoor == round(round(0.72 * 0.72 * 100, 1) * 1.10, 1)


# ── load_calibration ─────────────────────────────────────────────────────────

def _dated(day, **kw):
    from datetime import datetime, timezone
    base = dict(sport="running", duration_seconds=3600, distance_meters=10000.0, total_ascent=0.0,
                started_at=datetime(2026, 1, 1, 7, tzinfo=timezone.utc) + timedelta(days=day))
    base.update(kw)
    return _activity(**base)


class TestLoadCalibration:
    def test_an_athletes_measured_runs_calibrate_their_unmeasured_ones(self):
        """Someone whose strap says their runs are 30% harder than the
        population estimate should have the run they forgot the strap for
        counted the same way."""
        est = estimated_tss("running", 3600, 10000.0, 0.0)
        history = [_dated(d, training_stress_score=round(est * 1.3, 1)) for d in range(8)]
        cal = load_calibration(history)
        assert abs(cal["running"] - 1.3) < 0.01
        no_hr = _dated(9)
        assert estimate_tss(no_hr, calibration=cal) == round(est * cal["running"], 1)

    def test_one_mis_recorded_session_cannot_move_the_calibration(self):
        est = estimated_tss("running", 3600, 10000.0, 0.0)
        history = [_dated(d, training_stress_score=est) for d in range(9)]
        history.append(_dated(9, training_stress_score=est * 6))
        assert load_calibration(history)["running"] == 1.0

    def test_only_recent_sessions_count_so_it_follows_fitness(self):
        est = estimated_tss("running", 3600, 10000.0, 0.0)
        old = [_dated(d, training_stress_score=est * 0.7) for d in range(30)]
        recent = [_dated(30 + d, training_stress_score=est * 1.2) for d in range(20)]
        assert abs(load_calibration(old + recent)["running"] - 1.2) < 0.01

    def test_a_sport_with_too_few_sessions_borrows_half_of_the_pooled_ratio(self):
        est_run = estimated_tss("running", 3600, 10000.0, 0.0)
        history = [_dated(d, training_stress_score=est_run * 1.4) for d in range(12)]
        cal = load_calibration(history)
        assert "cycling" not in cal
        assert abs(cal["*"] - 1.2) < 0.01
        ride = _dated(20, sport="cycling", distance_meters=25000.0)
        assert estimate_tss(ride, calibration=cal) == round(
            estimated_tss("cycling", 3600, 25000.0, 0.0) * cal["*"], 1)

    def test_no_history_however_strange_makes_the_estimate_absurd(self):
        history = [_dated(d, training_stress_score=900.0) for d in range(12)]
        assert load_calibration(history)["running"] == 2.0

    def test_unmeasured_sessions_never_calibrate_anything(self):
        """A phone-only history has nothing to calibrate against; its own
        estimates must not be read back as measurements of themselves."""
        assert load_calibration([_dated(d) for d in range(30)]) == {}


# ── calculate_ctl_atl_tsb ────────────────────────────────────────────────────

class TestCalculateCtlAtlTsb:
    def test_empty_input_returns_empty(self):
        assert calculate_ctl_atl_tsb([]) == []

    def test_single_day(self):
        d = date(2024, 1, 1)
        result = calculate_ctl_atl_tsb([{"date": d, "tss": 100.0}])
        assert len(result) == 1
        assert result[0]["date"] == d
        assert result[0]["tss"] == 100.0
        # After one day: CTL = 0 * decay + 100 * (1-decay); ATL similar
        assert result[0]["ctl"] > 0
        assert result[0]["atl"] > result[0]["ctl"]  # ATL reacts faster

    def test_tsb_equals_ctl_minus_atl(self):
        loads = [{"date": date(2024, 1, 1) + timedelta(days=i), "tss": 80.0}
                 for i in range(10)]
        result = calculate_ctl_atl_tsb(loads)
        for p in result:
            # TSB, CTL, ATL are each rounded independently to 1dp, so allow 0.15
            assert abs(p["tsb"] - (p["ctl"] - p["atl"])) < 0.15

    def test_rest_days_cause_decay(self):
        """A single hard day followed by rest should show CTL decaying."""
        loads = [
            {"date": date(2024, 1, 1), "tss": 200.0},
            {"date": date(2024, 1, 10), "tss": 0.0},
        ]
        result = calculate_ctl_atl_tsb(loads)
        # Verify we have 10 days of data
        assert len(result) == 10
        # CTL on day 10 should be lower than on day 1 after a week of rest
        assert result[-1]["ctl"] < result[0]["ctl"]

    def test_continuous_training_grows_ctl(self):
        """Consistent daily load should monotonically grow CTL."""
        loads = [{"date": date(2024, 1, 1) + timedelta(days=i), "tss": 80.0}
                 for i in range(60)]
        result = calculate_ctl_atl_tsb(loads)
        ctls = [p["ctl"] for p in result]
        assert ctls == sorted(ctls)

    def test_output_fills_rest_days(self):
        """Gaps between load days should produce entries with tss=0."""
        loads = [
            {"date": date(2024, 1, 1), "tss": 100.0},
            {"date": date(2024, 1, 5), "tss": 100.0},
        ]
        result = calculate_ctl_atl_tsb(loads)
        assert len(result) == 5
        assert result[1]["tss"] == 0.0
        assert result[2]["tss"] == 0.0
