<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Vendored from BRouter

**GENERATED — do not edit by hand.** Written by
`vendor/pull-brouter.sh`; re-run that instead.

| | |
|---|---|
| Upstream | https://github.com/abrensch/brouter |
| Version | `v1.7.10` |
| Pulled | 2026-10-05 |
| Java files | 101 |
| Lines | 19327 |
| Licence | MIT |

Everything under `src/main/java/btools/` is BRouter's work, kept under its
original package names so that refreshing from upstream stays a file copy and
upstream diffs still apply. Do not edit those files: the next pull overwrites
them. Anything Tracks-specific belongs in `com.tracks.app.map`.

See `vendor/brouter.manifest` for what is taken and — more usefully — what is
deliberately left behind, with reasons.

## What this is for

The phone routes on trails with no network. The Tracks server hands it the same
rd5 segments and `.brf` profile the `brouter` container uses
(`GET /maps/route/offline/*`), and this engine reads them on the device. When
there is signal, `POST /maps/route/snap` still answers — the two produce the
same GeoJSON shape because both end in BRouter's own `FormatJson`.

## Refreshing

```bash
git clone --depth 1 --branch <version> https://github.com/abrensch/brouter /tmp/brouter
vendor/pull-brouter.sh /tmp/brouter <version>
git diff        # review what upstream changed
```

## After a refresh

`OfflineRoutingTest` guards the seam that is ours: it asserts the formatter
still emits `track-length`, `filtered ascend` and `total-time`, which the
map screen reads by name and which would otherwise go blank in silence.

It cannot guard the other risk. An rd5 file carries a format version, and an
engine that no longer decodes the server's segments fails only on a real
download of a real area — so route somewhere offline on the phone after
refreshing, not just in the suite.
