<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Vendored from Gadgetbridge

**GENERATED — do not edit by hand.** Written by
`vendor/pull-gadgetbridge.sh`; re-run that instead.

| | |
|---|---|
| Upstream | https://codeberg.org/Freeyourgadget/Gadgetbridge |
| Version | `0.93.0` |
| Pulled | 2026-08-14 |
| Java files | 223 |
| Proto files | 17 |
| Codegen files | 1 (in `:fit-codegen`) |
| Licence | AGPL-3.0-or-later (same as Tracks) |

Everything under `src/main/java/nodomain/freeyourgadget/gadgetbridge/` is
Gadgetbridge's work, kept under its original package names so that refreshing
from upstream stays a file copy and upstream diffs still apply. Do not edit
those files: the next pull overwrites them. Anything Tracks-specific belongs in
`com.tracks.device.garmin`, and shims for the parts we did not vendor live in
`:device-gb-compat`.

See `vendor/gadgetbridge.manifest` for what is taken and — more usefully —
what is deliberately left behind, with reasons.

## Refreshing

```bash
vendor/pull-gadgetbridge.sh /path/to/gadgetbridge <version>
git diff        # review what upstream changed
```

## Adding another vendor

Add its packages to the manifest and re-run. The shim layer in
`:device-gb-compat` is the reusable part; the per-vendor cost should be close
to a copy plus an adapter implementing `DeviceIntegration`.
