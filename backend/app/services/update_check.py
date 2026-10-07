# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""The newest published Tracks release, as GitHub reports it.

Tracks is not in an app store, so nothing else tells a household that a newer
server image or APK exists. The server asks GitHub on behalf of every client
it serves rather than each phone asking for itself: one outbound request per
few hours for the whole install instead of one per device, and a phone that
only ever reaches its server over a LAN or a VPN still learns of updates.

This is the one request this server makes to the internet without a user
having asked for something — no map download, no geocode. It carries no
identifying data (an anonymous GET of a public endpoint, the same one a
browser visiting the releases page makes), but an operator may still not want
it, so `UPDATE_CHECK=false` turns it off entirely; nothing then leaves the
server, and the panels just show the versions they know.

The answer is cached in Redis, shared by every worker. A failed check is
cached too, for less long: GitHub being down or the server being offline must
not turn every settings page view into a request that hangs for the timeout.
"""

from __future__ import annotations

import json
import logging
import re
from datetime import datetime, timezone

import httpx
import redis

from app.config import settings
from app.services.redis_client import get_redis
from app.version import SERVER_VERSION

log = logging.getLogger(__name__)

RELEASES_URL = "https://api.github.com/repos/exercisetracks/tracks/releases/latest"
RELEASES_PAGE = "https://github.com/exercisetracks/tracks/releases"

_CACHE_KEY = "tracks:update_check"
_CACHE_TTL = 6 * 3600
# A failure is retried sooner than a success is re-checked, but not so soon
# that an offline server asks GitHub on every page view.
_FAILURE_TTL = 30 * 60
_TIMEOUT = 5.0

_VERSION_RE = re.compile(r"v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.]+))?")


def parse_version(text: str | None) -> tuple | None:
    """A sortable key for `X.Y.Z` or `X.Y.Z-pre`, found anywhere in `text`.

    Searched rather than matched, so a client string like
    `android/1.2.0 (10200)` and a tag like `v1.2.0` both parse. A pre-release
    sorts below its release (1.2.0-beta.1 < 1.2.0), as in semver; pre-release
    labels compare as plain strings, which is enough for beta.1 < beta.2.
    """
    m = _VERSION_RE.search(text or "")
    if m is None:
        return None
    major, minor, patch, pre = m.groups()
    return (int(major), int(minor), int(patch), pre is None, pre or "")


def is_newer(candidate: str | None, than: str | None) -> bool:
    """True only when both parse and `candidate` is strictly newer. An
    unparseable version is never reported as an update — a false "update
    available" is worse than a missed one, because it never goes away."""
    a, b = parse_version(candidate), parse_version(than)
    return a is not None and b is not None and a > b


def _fetch() -> dict:
    resp = httpx.get(
        RELEASES_URL,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": f"Tracks/{SERVER_VERSION} (self-hosted update check)",
        },
        timeout=_TIMEOUT,
        follow_redirects=True,
    )
    resp.raise_for_status()
    body = resp.json()
    tag = body.get("tag_name") or ""
    if parse_version(tag) is None:
        raise ValueError(f"unexpected release tag {tag!r}")
    return {
        "version": tag.removeprefix("v"),
        "url": body.get("html_url") or RELEASES_PAGE,
        "published_at": body.get("published_at"),
    }


def latest_release() -> dict:
    """The update-check result, from cache when possible.

    Always returns a dict with `enabled`, `latest` (None when unknown) and
    `checked_at`; `error` is set when the last attempt failed. Never raises —
    a broken check must not break the page that shows it.
    """
    if not settings.update_check:
        return {"enabled": False, "latest": None, "checked_at": None}

    r = get_redis()
    try:
        cached = r.get(_CACHE_KEY)
        if cached:
            return json.loads(cached)
    except (redis.RedisError, ValueError):
        pass

    now = datetime.now(timezone.utc).isoformat()
    try:
        result = {"enabled": True, "latest": _fetch(), "checked_at": now}
        ttl = _CACHE_TTL
    except (httpx.HTTPError, ValueError, KeyError) as e:
        log.info("Update check failed: %s", e)
        result = {"enabled": True, "latest": None, "checked_at": now,
                  "error": "Could not reach GitHub"}
        ttl = _FAILURE_TTL

    try:
        r.set(_CACHE_KEY, json.dumps(result), ex=ttl)
    except redis.RedisError:
        pass
    return result
