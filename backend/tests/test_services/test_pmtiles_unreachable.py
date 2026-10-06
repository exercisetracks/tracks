# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A tile server that cannot be reached at all.

Found on a fresh install behind AdGuard Home: the map page said
"Failed to create range reader for planet.pmtiles, Get …: dial tcp 0.0.0.0:443:
connect: connection refused", which names neither the cause nor the fix, and
after three quick retries the download stayed failed until the container was
restarted. The person had to work out for themselves that their DNS filter was
answering 0.0.0.0, allow the host, and restart. These tests hold the two
halves of the fix: the error says what to allow, and the download comes back
on its own once it is allowed.
"""
import sys

import httpx
import pytest

from app.services import global_download_tracker as tracker_module
from app.services import pmtiles_extract
from app.services.global_download_tracker import global_download_tracker
from app.services.pmtiles_extract import (
    SourceUnreachable,
    extract_global,
    resolve_source_url,
    run_pmtiles_with_progress,
)


def _failing_with(line: str) -> list[str]:
    return [sys.executable, "-u", "-c", f"import sys; print({line!r}); sys.exit(1)"]


BLOCKED = ('main.go:185: Failed to extract, Failed to create range reader for '
           'planet.pmtiles, Get "https://download.mapterhorn.com/planet.pmtiles": '
           'dial tcp 0.0.0.0:443: connect: connection refused')
NO_DNS = ('main.go:140: Failed to show archive, Failed to create range reader for '
          '20261001.pmtiles, Get "https://build.protomaps.com/20261001.pmtiles": '
          'dial tcp: lookup build.protomaps.com on 127.0.0.11:53: server misbehaving')


class TestTheErrorSaysWhatToDo:
    def test_a_dns_filter_answer_is_named_as_one(self):
        """0.0.0.0 is what AdGuard Home and Pi-hole answer for a blocked name."""
        with pytest.raises(SourceUnreachable) as failure:
            run_pmtiles_with_progress(_failing_with(BLOCKED), lambda p, d: None,
                                      timeout=30, retries=1)

        assert failure.value.blocked
        assert "AdGuard Home" in str(failure.value)
        assert "Allow download.mapterhorn.com" in str(failure.value)

    def test_a_failed_lookup_names_the_host_to_allow(self):
        with pytest.raises(SourceUnreachable) as failure:
            run_pmtiles_with_progress(_failing_with(NO_DNS), lambda p, d: None,
                                      timeout=30, retries=1)

        assert not failure.value.blocked
        assert "allow build.protomaps.com" in str(failure.value)
        assert "VPN" in str(failure.value)

    def test_an_ordinary_failure_is_not_blamed_on_the_network(self):
        """Sending someone to their ad blocker over a missing file is worse than the raw error."""
        with pytest.raises(RuntimeError) as failure:
            run_pmtiles_with_progress(
                _failing_with("open /planet.pmtiles: no such file or directory"),
                lambda p, d: None, timeout=30, retries=1)

        assert not isinstance(failure.value, SourceUnreachable)


class TestTheDownloadComesBackByItself:
    @pytest.fixture(autouse=True)
    def _state_file(self, tmp_path, monkeypatch):
        monkeypatch.setattr(tracker_module, "_STATE_FILE", tmp_path / "state.json")

    def test_it_keeps_trying_until_the_host_is_allowed(self, tmp_path, monkeypatch):
        """Three quick retries and then nothing until a restart was the bug."""
        attempts, errors_seen = [], []

        def extract(source, output, maxzoom, download_id, timeout):
            attempts.append(source)
            if len(attempts) <= 6:
                raise SourceUnreachable("build.protomaps.com", BLOCKED, blocked=True)

        def sleep(seconds):
            errors_seen.append(global_download_tracker.get_state("overview")["error"])

        monkeypatch.setattr(pmtiles_extract, "extract_with_progress", extract)
        extract_global(lambda: "https://build.protomaps.com/x.pmtiles",
                       tmp_path / "out.pmtiles", 12, "overview", sleep=sleep)

        assert len(attempts) == 7
        assert "Allow build.protomaps.com" in errors_seen[0]
        assert "retry by itself in 1 minute" in errors_seen[0]
        assert "15 minutes" in errors_seen[-1], "the wait should level off, not grow forever"

    def test_any_other_failure_still_fails(self, tmp_path, monkeypatch):
        def extract(source, output, maxzoom, download_id, timeout):
            raise RuntimeError("disk full")

        monkeypatch.setattr(pmtiles_extract, "extract_with_progress", extract)
        with pytest.raises(RuntimeError, match="disk full"):
            extract_global(lambda: "https://x/y.pmtiles", tmp_path / "out.pmtiles",
                           12, "overview", sleep=lambda s: pytest.fail("should not retry"))


def test_an_unreachable_host_is_not_searched_for_older_builds(monkeypatch):
    """Every candidate build is on the same blocked host: 21 DNS timeouts learn nothing."""
    calls = []

    def get(url, **kwargs):
        calls.append(url)
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "get", get)
    url = "https://build.protomaps.com/20200101.pmtiles"

    assert resolve_source_url(url) == url
    assert len(calls) == 1
