# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""A secondary API worker does not start serving before the schema exists.

With several uvicorn workers, only the primary builds the schema, and a
secondary used to finish its startup at once. On a fresh install the admin's
setup request could reach that worker first and fail on a missing table —
which is how this was found, testing the all-in-one image.
"""

import multiprocessing
import time

from app import main


def _primary(started, path):
    barrier = main._startup_barrier()
    started.set()
    time.sleep(0.5)                      # building the schema
    with open(path, "w") as f:
        f.write("schema ready")
    barrier.close()


def test_a_second_worker_waits_for_the_first_to_finish_startup(tmp_path, monkeypatch):
    lock = tmp_path / "startup.lock"
    marker = tmp_path / "ready"
    monkeypatch.setattr(main, "_STARTUP_LOCK", str(lock))

    ctx = multiprocessing.get_context("fork")
    started = ctx.Event()
    first = ctx.Process(target=_primary, args=(started, str(marker)))
    first.start()
    try:
        assert started.wait(5)
        barrier = main._startup_barrier()   # blocks until the first releases
        try:
            assert marker.read_text() == "schema ready"
        finally:
            barrier.close()
    finally:
        first.join(5)


def test_a_worker_that_dies_mid_startup_does_not_block_the_rest(tmp_path, monkeypatch):
    """The lock goes with the process, so a crashed primary hands over."""
    monkeypatch.setattr(main, "_STARTUP_LOCK", str(tmp_path / "startup.lock"))
    ctx = multiprocessing.get_context("fork")
    started = ctx.Event()

    def crash(started):
        main._startup_barrier()
        started.set()
        time.sleep(0.2)
        raise SystemExit(1)

    dying = ctx.Process(target=crash, args=(started,))
    dying.start()
    assert started.wait(5)
    t0 = time.monotonic()
    main._startup_barrier().close()
    assert time.monotonic() - t0 < 5
    dying.join(5)
