<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Security policy

Tracks holds health records, GPS history and the keys that protect them, so
security reports are taken seriously and handled first.

**Please report vulnerabilities privately**, through
[GitHub's private vulnerability reporting](https://github.com/exercisetracks/tracks/security/advisories/new),
not in a public issue. Include what an attacker can reach, the version, and
steps to reproduce if you have them.

Fixes go into the newest release only; installs following `TRACKS_VERSION=1`
receive them on their next `docker compose pull`. The design decisions behind
the security model — what is sealed under which key, and the trade-offs taken
knowingly — are documented at the code that makes them, and summarised in the
README's Security section.
