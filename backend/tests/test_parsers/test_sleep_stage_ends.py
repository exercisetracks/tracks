# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A fenix 6 marks where each sleep stage ended, not where it began.

Read the other way, every stage shifts one slot: a night the watch scored
100 for awake time drew a quarter of an hour awake, and light sleep came
out as long awake spans. The sample is shaped like the measured nights (see
spec/make_fit_samples.py, `sample_sleep_end_marked`).
"""
from pathlib import Path

from app.parsers.sleep import SleepParser

SAMPLES = Path("/spec/fixtures/fit")


def _minutes(stages, level):
    return round(sum(s["seconds"] for s in stages if s["level"] == level) / 60)


def test_stages_end_at_their_records_when_the_last_sits_on_the_stop():
    out = SleepParser().parse(SAMPLES / "sleep_end_marked.fit")
    stages = out["stages"]
    # The first stage runs from the session's start, not from the first record.
    assert stages[0]["level"] == "light"
    assert stages[0]["seconds"] == 27 * 60
    assert _minutes(stages, "awake") == 7
    assert _minutes(stages, "rem") == 34
    assert stages[-1]["level"] == "rem", "the night ends in the stage people wake from"
    assert round(out["metrics"]["sleep_awake_hours"] * 60) == 7


def test_a_stream_without_the_signature_keeps_opening_records():
    # The older synthetic night has no session events; its reading is unchanged.
    out = SleepParser().parse(SAMPLES / "sleep_stream_only.fit")
    assert out["stages"][0]["level"] == "awake"


def test_a_grown_sleep_file_brings_its_totals_with_its_timeline(db, user):
    """A mid-night pull wrote a fragment; the whole night arrives later.

    The longer timeline already replaced the fragment's; its hours did not,
    so the chart drew the whole night beside the fragment's totals.
    """
    from datetime import date

    from app.models.metrics import DailyMetric
    from app.services.fit_import import _write_one_day

    def night(hours, segments):
        stages = [{"start": f"s{i}", "end": f"e{i}", "level": "light", "seconds": 600} for i in range(segments)]
        return {"date": date(2026, 10, 1), "metrics": {"sleep_hours": hours, "sleep_light_hours": hours},
                "stages": stages}

    _write_one_day(db, night(2.0, 3), None, user.id)
    _write_one_day(db, night(7.5, 12), None, user.id)
    row = db.query(DailyMetric).filter_by(user_id=user.id, date=date(2026, 10, 1)).one()
    assert row.sleep_hours == 7.5
    assert len(row.extra["sleep_stages"]) == 12
