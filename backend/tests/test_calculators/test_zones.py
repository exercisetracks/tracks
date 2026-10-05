# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
import pytest

from app.calculators.zones import (
    hr_zone_for_bpm,
    hr_zones,
    power_zone_for_watts,
    power_zones,
)


class TestHrZones:
    def test_returns_seven_zones(self):
        zones = hr_zones(160, "running")
        assert len(zones) == 7

    def test_first_zone_starts_at_zero(self):
        zones = hr_zones(160, "running")
        assert zones[0]["min_bpm"] == 0

    def test_last_zone_has_no_upper_bound(self):
        zones = hr_zones(160, "running")
        assert zones[-1]["max_bpm"] is None

    def test_zones_are_contiguous(self):
        """Each zone's max + 1 should equal the next zone's min."""
        zones = hr_zones(160, "running")
        for i in range(len(zones) - 1):
            assert zones[i]["max_bpm"] + 1 == zones[i + 1]["min_bpm"]

    def test_lthr_falls_in_zone_5a_for_running(self):
        """Zone 5a starts at 100% LTHR for running."""
        lthr = 160
        zones = hr_zones(lthr, "running")
        zone_5a = next(z for z in zones if z["name"] == "Zone 5a")
        assert zone_5a["min_bpm"] == lthr

    def test_cycling_zone1_boundary_differs_from_running(self):
        zones_run = hr_zones(160, "running")
        zones_cyc = hr_zones(160, "cycling")
        # Cycling Zone 1 upper boundary is lower than running
        assert zones_cyc[0]["max_bpm"] < zones_run[0]["max_bpm"]

    def test_zone_numbers_are_sequential(self):
        zones = hr_zones(160)
        numbers = [z["number"] for z in zones]
        assert numbers == [1, 2, 3, 4, 5, 6, 7]

    def test_all_fields_present(self):
        zones = hr_zones(160)
        for z in zones:
            assert "number" in z
            assert "name" in z
            assert "description" in z
            assert "min_bpm" in z
            assert "max_bpm" in z


class TestPowerZones:
    def test_returns_seven_zones(self):
        assert len(power_zones(250)) == 7

    def test_first_zone_starts_at_zero(self):
        assert power_zones(250)[0]["min_watts"] == 0

    def test_last_zone_has_no_upper_bound(self):
        assert power_zones(250)[-1]["max_watts"] is None

    def test_zones_are_contiguous(self):
        zones = power_zones(250)
        for i in range(len(zones) - 1):
            assert zones[i]["max_watts"] + 1 == zones[i + 1]["min_watts"]

    def test_ftp_falls_in_zone_4(self):
        """Coggan Zone 4 is 90–105% FTP, so FTP itself is within it."""
        ftp = 250
        zones = power_zones(ftp)
        zone_4 = zones[3]  # 0-indexed, zone 4 is index 3
        assert zone_4["min_watts"] <= ftp <= zone_4["max_watts"]


class TestZoneLookups:
    def test_hr_zone_for_lthr_is_zone_5a(self):
        assert hr_zone_for_bpm(160, lthr=160, sport="running") == 5

    def test_hr_zone_for_low_hr_is_zone_1(self):
        assert hr_zone_for_bpm(100, lthr=160, sport="running") == 1

    def test_hr_zone_above_threshold_is_zone_6_or_7(self):
        assert hr_zone_for_bpm(175, lthr=160, sport="running") >= 6

    def test_power_zone_for_ftp_is_zone_4(self):
        assert power_zone_for_watts(250, ftp=250) == 4

    def test_power_zone_for_recovery_power_is_zone_1(self):
        assert power_zone_for_watts(50, ftp=250) == 1

    def test_power_zone_for_very_high_power_is_zone_7(self):
        assert power_zone_for_watts(500, ftp=250) == 7
