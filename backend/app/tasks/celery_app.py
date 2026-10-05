# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Celery application: Redis as both broker and result backend — the same
instance already used for the crypto session cache (see
app.services.redis_client), just a different keyspace, so no new
infrastructure to run. A separate `worker` container (see docker-compose.yml)
runs this; the API process only ever enqueues.

task_always_eager stays False here — tests flip it via a fixture (see
tests/conftest.py) so `.delay()` runs synchronously against fakeredis with no
real broker needed, without this production module knowing tests exist.
"""

from celery import Celery

from app.config import settings

celery_app = Celery(
    "tracks",
    broker=settings.redis_url,
    backend=settings.redis_url,
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    # Task arguments/results ride the same Redis instance as unrelated,
    # security-sensitive keyspaces (crypto sessions, rate limits) — never a
    # place for anything that isn't already safe to have briefly at rest
    # there. See app.tasks.imports for why the pending-imports task takes a
    # session id, never raw key material, as its argument.
    task_ignore_result=True,
)

# Explicit import (not autodiscover) — registers imports.py's @celery_app.task
# functions. Small, fixed task list; add new task modules here as they show up.
from app.tasks import imports  # noqa: E402, F401
