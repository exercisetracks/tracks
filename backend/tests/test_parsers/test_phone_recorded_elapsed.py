# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Runs recorded on the phone, read at their real length.

Until November 2026 the phone wrote a session's elapsed time in seconds where
the FIT profile has milliseconds (mobile RunFit trusted the vendored
Gadgetbridge profile, which gives that one field no scale). Read correctly,
a 25-minute run was 1.5 seconds long: no load, no VO2max, and for someone
with no watch, fitness and readiness flat at their defaults. Those files are
kept and re-read for good, so the parser repairs them
(parsers/activity._repaired_elapsed); the phone's port is held to the same
answers by spec/fixtures/fit_parse.json.

The end-to-end case lives with the other phone-only load tests
(test_api/test_metrics.py, TestPhoneOnlyLoad).
"""
from pathlib import Path

import fitdecode
import pytest

from app.parsers.activity import ActivityParser, _repaired_elapsed

CORPUS = Path("/spec/fixtures/fit")


def _session_elapsed(path: Path) -> float:
    with fitdecode.FitReader(path) as fit:
        for frame in fit:
            if isinstance(frame, fitdecode.FitDataMessage) and frame.name == "session":
                return frame.get_value("total_elapsed_time")
    raise AssertionError("no session")


def test_a_phone_recording_is_read_at_its_real_length():
    path = CORPUS / "activity_phone_recorded.fit"
    # The sample really is one of the misread files...
    assert _session_elapsed(path) == pytest.approx(1.5)
    # ...and the parser reads it as the 25 minutes it was.
    activity = ActivityParser().parse(path)["activity"]
    assert activity["duration_seconds"] == 1500
    assert activity["sport"] == "running"
    assert activity["avg_heart_rate"] is None


def test_a_timer_a_millisecond_past_elapsed_is_rounding_not_a_unit():
    """Watches round; a session whose timer is a hair over its elapsed time
    is read exactly as written."""
    activity = ActivityParser().parse(CORPUS / "activity_timer_over_elapsed.fit")["activity"]
    assert activity["duration_seconds"] == 600


def test_every_other_activity_in_the_corpus_reads_as_written():
    """The repair fires on nothing a watch writes."""
    for path in sorted(CORPUS.glob("activity_*.fit")):
        if path.name == "activity_phone_recorded.fit":
            continue
        parser = ActivityParser()
        if not parser.can_parse(path):
            continue
        elapsed = _session_elapsed(path)
        want = None if elapsed is None else int(elapsed)
        assert parser.parse(path)["activity"]["duration_seconds"] == want, path.name


@pytest.mark.parametrize("elapsed, timer, want", [
    (None, 1500.0, None),          # nothing to repair
    (1500.0, None, 1500.0),        # no timer to tell by
    (1500.0, 1495.25, 1500.0),     # an honest file: paused for five seconds
    (1.5, 1495.25, 1500),          # the misread: a thousandth of its length
    (0.6, 600.0, 600),             # a ten-minute run, which int() made 0
    (3600.0, 3600.001, 3600.0),    # rounding, not a unit
])
def test_the_rule(elapsed, timer, want):
    assert _repaired_elapsed(elapsed, timer) == want
