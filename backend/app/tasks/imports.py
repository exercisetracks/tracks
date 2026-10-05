# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Celery task wrapping app.services.fit_import.process_pending_imports_for_user
— unsealing, parsing, and inserting whatever a sync agent ingested while the
target user had no active session.

Runs in a separate worker process from the API (see docker-compose.yml's
`worker` service), so it never receives raw key material as a task argument
— that would put plaintext DEK/privkey bytes into the Redis-backed broker,
the one thing crypto_context.py goes out of its way to avoid (see that
module's docstring on why session material is stored ENCRYPTED there).
Instead this takes the session id already minted at login and re-derives the
key material itself via crypto_context.load_session_key — exactly what an
HTTP request handling the same user would do.
"""

import logging

from app.services import crypto_context, fit_import
from app.tasks.celery_app import celery_app

log = logging.getLogger(__name__)


@celery_app.task(name="tracks.process_pending_imports")
def process_pending_imports(user_id: int, sid: str) -> None:
    material = crypto_context.load_session_key(sid)
    if material is None:
        # The session (Redis, sliding TTL) expired or was dropped between
        # being enqueued and the worker picking it up — nothing to do until
        # the user's next login re-triggers this.
        log.warning(
            "process_pending_imports: session %s expired before the task "
            "ran for user %s — will retry on next login", sid[:8], user_id,
        )
        return
    fit_import.process_pending_imports_for_user(user_id, material, sid=sid)


@celery_app.task(name="tracks.reparse_daily_metrics")
def reparse_daily_metrics(user_id: int, sid: str) -> None:
    """Re-read every retained file for its daily metrics.

    Same session-id indirection as the task above and for the same reason: raw
    key material must never become a task argument sitting in Redis. Long
    enough to be worth a worker rather than a request — a year of monitoring
    files is hundreds of blobs to unseal — which is the other reason it is a
    task and not an endpoint that does the work inline.
    """
    material = crypto_context.load_session_key(sid)
    if material is None:
        log.warning(
            "reparse_daily_metrics: session %s expired before the task ran "
            "for user %s", sid[:8], user_id,
        )
        return
    fit_import.reparse_daily_metrics_for_user(user_id, material, sid=sid)


@celery_app.task(name="tracks.backfill_activity_summaries")
def backfill_activity_summaries(user_id: int, sid: str) -> None:
    """Fill feel and perceived effort into activities imported before the
    parser read them, from their retained files.

    Queued at login beside process_pending_imports, for the same reason it is
    there: the files are sealed and this is when a key exists. Same session-id
    indirection, too. A run with nothing left to do is one query
    (app.services.fit_import.backfill_activity_summaries_for_user).
    """
    material = crypto_context.load_session_key(sid)
    if material is None:
        log.warning(
            "backfill_activity_summaries: session %s expired before the task ran "
            "for user %s — will retry on next login", sid[:8], user_id,
        )
        return
    fit_import.backfill_activity_summaries_for_user(user_id, material, sid=sid)
