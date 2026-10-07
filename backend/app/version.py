# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
"""Server version and the API contract version clients negotiate against.

Two separate numbers, deliberately:

`SERVER_VERSION` is the human-facing release string. It changes whenever
anything ships and carries no promises.

`API_VERSION` is the machine-facing contract, and it only moves when the
REST surface changes in a way a client can observe. `MIN_CLIENT_API_VERSION`
is the oldest contract this server still answers correctly — a client
declaring less than that should be told to update rather than allowed to
fail one endpoint at a time.

This matters because of how the mobile app is distributed. The web frontend
is served by the same deployment as the backend, so the two can never
disagree. An installed Android app can be arbitrarily older or newer than
the server it points at, and once it ships through a store the update timing
isn't ours to control. `GET /capabilities` is how the two sides find that
out before anything breaks.

Bump `API_VERSION` when you add, remove, or change the shape of an endpoint.
Bump `MIN_CLIENT_API_VERSION` only when you remove something old clients
depend on — that's a deliberate act of dropping support, not a side effect.
"""

SERVER_VERSION = "1.2.1"

# 2: /sync/push + /sync/pull replace /sync/delta and the old offline write
# paths outright (docs/offline-first.md: no backwards compatibility), so a
# client built against 1 is told to update rather than half-working.
API_VERSION = 2
MIN_CLIENT_API_VERSION = 2
