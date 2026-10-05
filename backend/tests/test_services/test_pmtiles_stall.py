# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The watchdog over a go-pmtiles extract.

It used to be a wall clock, and a wall clock punishes exactly the download it is
least able to help. A state-sized region takes over an hour of streaming to cut,
so the one-hour timer killed it *while it was working perfectly* — and since
extracts are not resumable, the retry loop then deleted the half-built file and
started the whole thing again, twice, before reporting "timed out after 3600s".
Hours of bandwidth spent to fail at something that was going fine.

So the watchdog measures silence instead. A connection that has died says
nothing; a slow extract says something every few seconds. These tests are the
difference between those two, which is the whole of the change.
"""
import sys
import time

import pytest

from app.services.pmtiles_extract import run_pmtiles_with_progress


def _script(body: str) -> list[str]:
    return [sys.executable, "-u", "-c", body]


class TestStallWatchdog:
    def test_a_slow_but_talking_process_is_left_alone(self):
        # Runs for well over the timeout, and never goes quiet for as long as
        # the timeout. This is the state-sized extract, in miniature.
        talking = _script(
            "import time\n"
            "for i in range(12):\n"
            "    print(f'fetching {i * 8}% (1 kB / 2 kB)')\n"
            "    time.sleep(0.2)\n"
        )
        seen: list[int] = []

        started = time.monotonic()
        run_pmtiles_with_progress(talking, lambda pct, detail: seen.append(pct),
                                  timeout=1, retries=1)
        elapsed = time.monotonic() - started

        assert elapsed > 1, "the test process must outlive the timeout to prove anything"
        assert seen, "progress should have been reported"

    def test_a_process_that_goes_quiet_is_killed(self):
        silent = _script("import time; time.sleep(30)")

        with pytest.raises(RuntimeError, match="stalled"):
            run_pmtiles_with_progress(silent, lambda pct, detail: None,
                                      timeout=1, retries=1)

    def test_the_failure_says_what_actually_happened(self):
        # "timed out after 3600s" sent everybody looking for a slow download.
        # The subprocess had stopped saying anything, which is a different
        # problem with a different fix.
        silent = _script("import time; time.sleep(30)")

        with pytest.raises(RuntimeError) as failure:
            run_pmtiles_with_progress(silent, lambda pct, detail: None,
                                      timeout=1, retries=1)

        assert "no output" in str(failure.value)

    def test_a_command_that_fails_still_fails(self):
        # The watchdog must not swallow an ordinary non-zero exit.
        broken = _script("import sys; print('open /planet.pmtiles: no such file'); sys.exit(1)")

        with pytest.raises(RuntimeError, match="no such file"):
            run_pmtiles_with_progress(broken, lambda pct, detail: None,
                                      timeout=30, retries=1)
