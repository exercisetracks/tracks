# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""One shared Redis client for cross-process, cross-replica state: the
crypto session cache (crypto_context.py), the setup-complete flag and login
rate limiter (app.auth / app.api.auth), and the Celery broker/result
backend. A single client/connection pool per process, and one place for
tests to swap in a fake (see tests/conftest.py's fake_redis fixture).
"""

import redis

from app.config import settings

_client: redis.Redis | None = None


def get_redis() -> redis.Redis:
    global _client
    if _client is None:
        _client = redis.Redis.from_url(settings.redis_url)
    return _client
