# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from types import SimpleNamespace

import pytest

from app.calculators.readiness import (
    _acute_recovery_score,
    _normalize_tsb,
    acute_load_ema,
    compute_readiness,
)


def _metric(**kwargs):
    defaults = dict(hrv=None, resting_hr=None, sleep_hours=None, sleep_score=None)
    defaults.update(kwargs)
    return SimpleNamespace(**defaults)


class TestNormalizeTsb:
    def test_optimal_zone(self):
        assert _normalize_tsb(-5) == 50.0

    def test_neutral(self):
        assert _normalize_tsb(0) == 60.0

    def test_fresh(self):
        assert _normalize_tsb(20) == 100.0

    def test_clamps_above(self):
        assert _normalize_tsb(50) == 100.0

    def test_linear_branch_meets_tail_at_minus_ten(self):
        # Continuity: both branches yield 40 at the boundary.
        assert _normalize_tsb(-10) == pytest.approx(40.0, abs=0.1)

    def test_moderate_fatigue_no_longer_floored(self):
        # Old model clipped -30 to 0; the gentle tail keeps it meaningful.
        assert _normalize_tsb(-30) == pytest.approx(28.0, abs=0.5)

    def test_deep_fatigue_stays_positive_and_monotonic(self):
        # No hard floor: recovery from a deep hole is always reflected, so the
        # score can track days since the last workout instead of pinning at 0.
        assert _normalize_tsb(-130) > 0
        assert (_normalize_tsb(-130) < _normalize_tsb(-100)
                < _normalize_tsb(-60) < _normalize_tsb(-30) < _normalize_tsb(-10))


class TestAcuteRecoveryModel:
    def test_ema_decays_fast_after_a_hard_day(self):
        # One 300-TSS day then rest: acute load falls on a ~2-day time constant.
        series = acute_load_ema([300.0, 0.0, 0.0, 0.0, 0.0])
        assert series[0] > series[1] > series[2] > series[3] > series[4]
        assert series[4] < 0.25 * series[0]        # ~85%+ cleared within ~4 days

    def test_fully_rested_scores_high(self):
        assert _acute_recovery_score(0.0, 50.0) > 90

    def test_normal_day_is_moderate(self):
        # acute == chronic (ratio 1.0) → ~70
        assert _acute_recovery_score(50.0, 50.0) == pytest.approx(70.0, abs=1.0)

    def test_midpoint_ratio_scores_fifty(self):
        # ratio 1.5 → exactly 50
        assert _acute_recovery_score(75.0, 50.0) == pytest.approx(50.0, abs=0.1)

    def test_big_overreach_scores_low(self):
        assert _acute_recovery_score(150.0, 50.0) < 12   # ratio 3.0

    def test_monotonic_recovery_as_acute_falls(self):
        vals = [_acute_recovery_score(a, 50.0) for a in (300, 200, 120, 60, 20)]
        assert vals == sorted(vals)                 # readiness rises as fatigue clears

    def test_chronic_floor_guards_new_athlete(self):
        assert _acute_recovery_score(20.0, 1.0) == _acute_recovery_score(20.0, 20.0)


class TestComputeReadinessAcute:
    def test_acute_model_preferred_over_tsb(self):
        # A decayed acute load reads recovered even when TSB is deeply negative.
        r = compute_readiness(None, [], tsb=-120.0, acute_load=0.0, chronic_load=50.0)
        assert r.training_score > 90
        assert r.primary_driver == "training_load"

    def test_fatigued_acute_load_lowers_score(self):
        r = compute_readiness(None, [], acute_load=150.0, chronic_load=50.0)
        assert r.training_score < 12
        assert r.score < 30

    def test_recovery_over_rest_days_raises_readiness(self):
        day0 = compute_readiness(None, [], acute_load=300.0, chronic_load=50.0)
        day3 = compute_readiness(None, [], acute_load=60.0, chronic_load=50.0)
        assert day3.score > day0.score

    def test_falls_back_to_tsb_without_acute_inputs(self):
        # No acute inputs → old TSB path: tsb 10 → 80 → 80*0.85+15 = 83.
        assert compute_readiness(None, [], tsb=10.0).score == 83.0


class TestComputeReadiness:
    def test_no_data_returns_default(self):
        r = compute_readiness(None, [])
        assert r.score == 50.0
        assert r.primary_driver == "default"
        assert r.confidence == "low"
        assert r.training_score is None

    def test_training_only_uses_tsb(self):
        r = compute_readiness(None, [], tsb=10.0)
        # TSB 10 → (10+30)/50*100 = 80, then 80*0.85+15 = 83
        assert r.score == 83.0
        assert r.primary_driver == "training_load"
        assert r.confidence == "medium"
        assert r.training_score == 80.0

    def test_fatigued_tsb_produces_low_score(self):
        r = compute_readiness(None, [], tsb=-25.0)
        # TSB -25 → 40*exp(-15/56) ≈ 30.6, then 30.6*0.85+15 ≈ 41.0
        assert r.score == pytest.approx(41.0, abs=0.3)
        assert r.primary_driver == "training_load"
        assert r.score < 50  # still clearly below neutral

    def test_recovery_over_rest_days_raises_readiness(self):
        """The core fix: as fatigue dissipates (TSB climbs out of a deep hole),
        readiness must rise. The old clip floored both of these to the same score."""
        deep       = compute_readiness(None, [], tsb=-120.0)  # day after a huge effort
        recovering = compute_readiness(None, [], tsb=-60.0)   # several rest days later
        assert recovering.score > deep.score

    def test_health_data_without_training(self):
        today = _metric(hrv=70, resting_hr=48, sleep_hours=8.5, sleep_score=85)
        prior = [_metric(hrv=60, resting_hr=52) for _ in range(7)]
        r = compute_readiness(today, prior)
        # physio = 40 + 35 + 25 = 100
        assert r.score == 100.0
        assert r.primary_driver == "health_data"
        assert r.confidence == "medium"

    def test_hybrid_blend(self):
        today = _metric(hrv=70, resting_hr=48, sleep_hours=8.5, sleep_score=85)
        prior = [_metric(hrv=60, resting_hr=52) for _ in range(7)]
        r = compute_readiness(today, prior, tsb=5.0)
        # physio = 100, training = (5+30)/50*100 = 70
        # hybrid = 100*0.55 + 70*0.45 = 55 + 31.5 = 86.5
        assert r.score == 86.5
        assert r.primary_driver == "mixed"
        assert r.confidence == "high"

    def test_low_hrv_reduces_score(self):
        today = _metric(hrv=50, resting_hr=50, sleep_score=75)
        prior = [_metric(hrv=80, resting_hr=50) for _ in range(7)]
        r = compute_readiness(today, prior)
        # HRV ratio 50/80 = 0.625 < 0.75 → 5 pts
        assert r.hrv_score == 5.0

    def test_elevated_resting_hr_reduces_score(self):
        today = _metric(hrv=60, resting_hr=70)
        prior = [_metric(hrv=60, resting_hr=50) for _ in range(7)]
        r = compute_readiness(today, prior)
        assert r.resting_hr_score == 5.0

    def test_poor_sleep_reduces_score(self):
        today = _metric(sleep_score=45)
        r = compute_readiness(today, [])
        assert r.sleep_score == 5.0

    def test_sleep_hours_fallback_when_no_garmin_score(self):
        today = _metric(sleep_hours=8.0)
        r = compute_readiness(today, [])
        assert r.sleep_score == 35.0

    def test_short_sleep_hours(self):
        today = _metric(sleep_hours=5.5)
        r = compute_readiness(today, [])
        assert r.sleep_score == 8.0

    def test_baselines_use_only_prior_metrics(self):
        today = _metric(hrv=60)
        prior = [_metric(hrv=60) for _ in range(7)]
        r = compute_readiness(today, prior)
        assert r.hrv_baseline == 60.0
        assert r.hrv_today == 60.0

    def test_notes_include_warning_for_low_hrv(self):
        today = _metric(hrv=40)
        prior = [_metric(hrv=80) for _ in range(7)]
        r = compute_readiness(today, prior)
        assert any("HRV" in n for n in r.notes)

    def test_notes_include_warning_for_elevated_rhr(self):
        today = _metric(resting_hr=72)
        prior = [_metric(resting_hr=55) for _ in range(7)]
        r = compute_readiness(today, prior)
        assert any("Resting HR" in n or "resting" in n.lower() for n in r.notes)

    def test_notes_include_data_quality_for_default(self):
        r = compute_readiness(None, [])
        assert any("Not enough data" in n for n in r.notes)

    def test_notes_include_driver_for_training_only(self):
        r = compute_readiness(None, [], tsb=0.0)
        assert any("training load" in n.lower() for n in r.notes)
