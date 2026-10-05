# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
from datetime import datetime, timedelta

import pytest

from app.calculators.activity_metrics import (
    compute_aerobic_decoupling,
    compute_efficiency_factor,
    compute_pace_curve,
    compute_power_curve,
    compute_power_tss,
)

_T0 = datetime(2024, 6, 1, 8, 0, 0)


def _power_points(n=3700, power=220):
    return [{"recorded_at": _T0 + timedelta(seconds=i), "power": power} for i in range(n)]


def _run_points(n=3700, speed=3.5):
    return [{"recorded_at": _T0 + timedelta(seconds=i), "speed": speed} for i in range(n)]


def _decoupling_points(n=120, hr1=140, hr2=155, use_power=True):
    """Two halves: first half steady, second half with cardiac drift (rising HR)."""
    half = n // 2
    points = []
    for i in range(half):
        p = {"heart_rate": hr1, "speed": 3.5}
        if use_power:
            p["power"] = 200
        points.append(p)
    for i in range(half):
        p = {"heart_rate": hr2, "speed": 3.5}
        if use_power:
            p["power"] = 200
        points.append(p)
    return points


# ── compute_efficiency_factor ────────────────────────────────────────────────

class TestComputeEfficiencyFactor:
    def test_cycling_ef(self):
        ef = compute_efficiency_factor("cycling", avg_hr=140, normalized_power=280, avg_speed=None)
        assert ef == round(280 / 140, 3)

    def test_running_ef(self):
        ef = compute_efficiency_factor("running", avg_hr=150, normalized_power=None, avg_speed=3.5)
        assert ef == round(3.5 / 150, 4)

    def test_unknown_sport_returns_none(self):
        assert compute_efficiency_factor("swimming", avg_hr=140, normalized_power=200, avg_speed=1.5) is None

    def test_no_hr_returns_none(self):
        assert compute_efficiency_factor("cycling", avg_hr=None, normalized_power=280, avg_speed=None) is None

    def test_zero_hr_returns_none(self):
        assert compute_efficiency_factor("running", avg_hr=0, normalized_power=None, avg_speed=3.5) is None

    def test_cycling_no_power_returns_none(self):
        assert compute_efficiency_factor("cycling", avg_hr=140, normalized_power=None, avg_speed=None) is None

    def test_sport_case_insensitive(self):
        ef1 = compute_efficiency_factor("Cycling", avg_hr=140, normalized_power=280, avg_speed=None)
        ef2 = compute_efficiency_factor("cycling", avg_hr=140, normalized_power=280, avg_speed=None)
        assert ef1 == ef2


# ── compute_aerobic_decoupling ───────────────────────────────────────────────

class TestComputeAerobicDecoupling:
    def test_insufficient_data_returns_none(self):
        points = _decoupling_points(n=30)
        assert compute_aerobic_decoupling("cycling", points) is None

    def test_unknown_sport_returns_none(self):
        assert compute_aerobic_decoupling("swimming", _decoupling_points()) is None

    def test_cycling_decoupling_positive_when_hr_rises(self):
        # First half: 200w / 140bpm = 1.4286 EF
        # Second half: 200w / 155bpm = 1.2903 EF
        # Decoupling = (1.4286 - 1.2903) / 1.4286 * 100 ≈ 9.68%
        points = _decoupling_points(n=120, hr1=140, hr2=155, use_power=True)
        d = compute_aerobic_decoupling("cycling", points)
        assert d is not None
        assert d > 0

    def test_running_decoupling_positive_when_hr_rises(self):
        points = _decoupling_points(n=120, hr1=140, hr2=155, use_power=False)
        d = compute_aerobic_decoupling("running", points)
        assert d is not None
        assert d > 0

    def test_stable_hr_gives_near_zero_decoupling(self):
        # Same HR both halves → decoupling ≈ 0
        points = _decoupling_points(n=120, hr1=140, hr2=140, use_power=True)
        d = compute_aerobic_decoupling("cycling", points)
        assert d is not None
        assert abs(d) < 0.1


# ── compute_power_tss ────────────────────────────────────────────────────────

class TestComputePowerTss:
    def test_correct_formula(self):
        # TSS = (duration_h × NP² / FTP²) × 100
        # 1h @ 250w with FTP 250 = 100 TSS (IF=1.0)
        tss = compute_power_tss(normalized_power=250, ftp=250.0, duration_seconds=3600)
        assert tss == 100.0

    def test_below_ftp(self):
        # 1h @ 200w with FTP 250 → IF 0.8 → TSS = 1 × 0.64 × 100 = 64
        tss = compute_power_tss(normalized_power=200, ftp=250.0, duration_seconds=3600)
        assert tss == 64.0

    def test_missing_power_returns_none(self):
        assert compute_power_tss(normalized_power=None, ftp=250.0, duration_seconds=3600) is None

    def test_missing_ftp_returns_none(self):
        assert compute_power_tss(normalized_power=250, ftp=None, duration_seconds=3600) is None

    def test_zero_ftp_returns_none(self):
        assert compute_power_tss(normalized_power=250, ftp=0, duration_seconds=3600) is None

    def test_missing_duration_returns_none(self):
        assert compute_power_tss(normalized_power=250, ftp=250.0, duration_seconds=None) is None


# ── compute_power_curve ──────────────────────────────────────────────────────

class TestComputePowerCurve:
    def test_returns_empty_for_no_data(self):
        assert compute_power_curve([]) == {}

    def test_returns_empty_when_no_power_values(self):
        pts = [{"recorded_at": _T0 + timedelta(seconds=i)} for i in range(100)]
        assert compute_power_curve(pts) == {}

    def test_returns_curve_for_sufficient_data(self):
        pts = _power_points(n=3700, power=250)
        curve = compute_power_curve(pts)
        assert len(curve) > 0
        # 1-second and 5-second durations should be present
        assert 1 in curve
        assert 5 in curve

    def test_short_durations_only_when_activity_is_short(self):
        # 70-second activity should not produce a 5-minute (300s) power best
        pts = _power_points(n=70, power=300)
        curve = compute_power_curve(pts)
        assert 300 not in curve

    def test_constant_power_gives_consistent_bests(self):
        pts = _power_points(n=3700, power=250)
        curve = compute_power_curve(pts)
        for dur, watts in curve.items():
            assert abs(watts - 250) < 5  # within 5W of the constant power


# ── compute_pace_curve ───────────────────────────────────────────────────────

class TestComputePaceCurve:
    def test_returns_empty_for_no_data(self):
        assert compute_pace_curve([]) == {}

    def test_returns_empty_when_fewer_than_2_points(self):
        pts = [{"recorded_at": _T0, "speed": 3.5}]
        assert compute_pace_curve(pts) == {}

    def test_returns_curve_for_sufficient_data(self):
        pts = _run_points(n=3700, speed=3.5)
        curve = compute_pace_curve(pts)
        assert len(curve) > 0
        # 400m should be achievable in a 1h run at 3.5 m/s
        assert 400 in curve

    def test_constant_speed_gives_consistent_bests(self):
        pts = _run_points(n=3700, speed=4.0)
        curve = compute_pace_curve(pts)
        for dist, speed in curve.items():
            assert abs(speed - 4.0) < 0.1  # within 0.1 m/s of constant speed
