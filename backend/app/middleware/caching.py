# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Cache-Control header middleware for static map assets.

go-pmtiles serve does not set Cache-Control headers. This middleware
injects appropriate caching headers for response types from the Python
backend (fonts, sprites).
"""

from fastapi import Request
from starlette.middleware.base import BaseHTTPMiddleware


class CacheControlMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        response = await call_next(request)

        path = request.url.path
        if path.startswith("/fonts/"):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        elif path.startswith("/sprite/"):
            response.headers["Cache-Control"] = "public, max-age=31536000, immutable"
        elif path.startswith("/maps/poi/features"):
            # Map-icon POIs for a bbox+zoom. The exact same URL recurs constantly
            # (button zoom round-trips, zoom-out-and-back), and the data only
            # changes on a region download — a short private cache turns those
            # repeats into browser-cache hits instead of Postgres queries.
            response.headers["Cache-Control"] = "private, max-age=60"

        return response
