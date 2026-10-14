# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""How many processes draw contours at once.

By cores alone, a many-core server with modest memory started more workers
than it could hold; the kernel killed one, and the whole build failed with
"A process in the process pool was terminated abruptly while the future was
running or pending" — shown on the map page as a contour error.
"""
from app.services.contour_generator import builder


def test_memory_caps_the_pool_below_the_core_count(monkeypatch):
    monkeypatch.setattr(builder.os, "cpu_count", lambda: 32)
    # 2 GB free: three quarters of it fits three workers at the budget.
    monkeypatch.setattr(builder, "_available_bytes", lambda: 2 * 1024**3)
    assert builder._worker_count(10_000) == 3


def test_there_is_always_one_worker(monkeypatch):
    monkeypatch.setattr(builder.os, "cpu_count", lambda: 8)
    monkeypatch.setattr(builder, "_available_bytes", lambda: 10 * 1024**2)
    assert builder._worker_count(10_000) == 1


def test_without_a_memory_reading_the_cores_decide(monkeypatch):
    monkeypatch.setattr(builder.os, "cpu_count", lambda: 6)
    monkeypatch.setattr(builder, "_available_bytes", lambda: None)
    assert builder._worker_count(10_000) == 6
    assert builder._worker_count(2) == 2
