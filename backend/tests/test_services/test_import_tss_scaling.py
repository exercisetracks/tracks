# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The sport multipliers on an activity's load.

They are applied when load is read, never stored. A multiplier baked in at
import depended on which goal was active on whichever device imported the
ride, and /backfill-metrics re-applied it to the already-scaled value on every
run. The phone's TrainingLoad is held to scale_tss by
spec/fixtures/local_import.json.
"""
from types import SimpleNamespace

from app.calculators.training_load import estimate_tss, scale_tss


def test_an_mtb_ride_is_scaled_by_the_active_goals_discipline():
    assert scale_tss("mountain_biking", 100.0, "enduro") == 120.0


def test_an_mtb_ride_with_no_goal_uses_the_trail_default():
    assert scale_tss("mountain_biking", 100.0, None) == 110.0


def test_an_indoor_ride_gets_its_ten_percent():
    assert scale_tss("indoor_cycling", 100.0, "enduro") == 110.0


def test_a_run_is_left_alone():
    assert scale_tss("running", 100.0, "enduro") == 100.0
    assert scale_tss("running", None, None) is None


def _ride(**kw):
    base = dict(sport="mountain_biking", training_stress_score=None, effective_tss=100.0,
                duration_seconds=3600, avg_heart_rate=150, max_heart_rate=180)
    base.update(kw)
    return SimpleNamespace(**base)


def test_the_load_follows_the_goal_in_force_when_it_is_read():
    """Changing the MTB goal re-weights past rides; nothing stored changes."""
    ride = _ride()
    assert estimate_tss(ride, 160.0, "xco") != estimate_tss(ride, 160.0, "enduro")
    assert ride.effective_tss == 100.0


def test_a_device_reported_tss_is_never_scaled():
    """The watch's own TSS already reflects the athlete; it was never multiplied."""
    assert estimate_tss(_ride(training_stress_score=80.0), 160.0, "enduro") == 80.0


def test_importing_stores_the_unscaled_load():
    """The import path must not scale, or reading would scale twice."""
    import inspect
    from app.services import fit_import
    assert "scale_tss(" not in inspect.getsource(fit_import)
