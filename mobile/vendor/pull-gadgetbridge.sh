#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Copy the Gadgetbridge sources listed in gadgetbridge.manifest into
# :device-garmin, recording exactly which upstream version they came from.
#
# Why a script rather than a one-off copy: the point of vendoring is that we can
# take upstream's fixes later. Garmin firmware updates break things, and
# Gadgetbridge fixes them; a manual copy would make each refresh an
# archaeology exercise. This makes it `./pull-gadgetbridge.sh <src> <version>`
# and a diff.
#
# Package names are preserved, so a refresh is a file copy and upstream diffs
# still apply. Our shims for what we do not vendor live in the same namespace
# in :device-gb-compat.
#
# Usage:
#   vendor/pull-gadgetbridge.sh /path/to/gadgetbridge 0.93.0
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$HERE/gadgetbridge.manifest"
DEST_ROOT="$HERE/../device-garmin/src/main/java"
DEST_PROTO="$HERE/../device-garmin/src/main/proto"
DEST_CODEGEN="$HERE/../fit-codegen/src/main"
PROVENANCE="$HERE/../device-garmin/VENDORED.md"

SRC="${1:-}"
VERSION="${2:-unknown}"

if [[ -z "$SRC" ]]; then
    echo "usage: $(basename "$0") <gadgetbridge-source-dir> <version>" >&2
    exit 2
fi

# Refuse to overwrite a tree that carries edits no patch records: they would
# vanish here without a word. See check-vendored.py.
if ! "$HERE/check-vendored.py" --verify-lock; then
    echo "error: record those edits as patches (see device-garmin/SHIMS.md) before refreshing." >&2
    exit 1
fi

SRC_JAVA="$SRC/app/src/main/java/nodomain/freeyourgadget/gadgetbridge"
if [[ ! -d "$SRC_JAVA" ]]; then
    echo "error: $SRC does not look like a Gadgetbridge checkout" >&2
    echo "       (expected $SRC_JAVA)" >&2
    exit 1
fi

PKG_ROOT="$DEST_ROOT/nodomain/freeyourgadget/gadgetbridge"

# Start clean so a file deleted upstream does not linger here forever, silently
# shadowing its replacement.
rm -rf "$PKG_ROOT" "$DEST_PROTO" "$DEST_CODEGEN"
mkdir -p "$PKG_ROOT" "$DEST_PROTO" "$DEST_CODEGEN"

includes=()
proto_includes=()
codegen_includes=()
excludes=()
while IFS= read -r line; do
    line="${line%%#*}"
    line="$(echo "$line" | xargs || true)"
    [[ -z "$line" ]] && continue
    case "$line" in
        include-proto\ *) proto_includes+=("${line#include-proto }") ;;
        include-codegen\ *) codegen_includes+=("${line#include-codegen }") ;;
        include\ *) includes+=("${line#include }") ;;
        exclude\ *) excludes+=("${line#exclude }") ;;
        *) echo "warning: ignoring unparsable manifest line: $line" >&2 ;;
    esac
done < "$MANIFEST"

copied=0
for path in "${includes[@]}"; do
    if [[ ! -e "$SRC_JAVA/$path" ]]; then
        echo "error: manifest lists '$path', which upstream no longer has." >&2
        echo "       It was probably moved or renamed — check the release notes" >&2
        echo "       and update the manifest rather than silently dropping it." >&2
        exit 1
    fi
    mkdir -p "$PKG_ROOT/$(dirname "$path")"
    cp -r "$SRC_JAVA/$path" "$PKG_ROOT/$(dirname "$path")/"
done

SRC_PROTO="$SRC/app/src/main/proto"
for path in "${proto_includes[@]}"; do
    if [[ ! -e "$SRC_PROTO/$path" ]]; then
        echo "error: manifest lists proto '$path', which upstream no longer has." >&2
        exit 1
    fi
    mkdir -p "$DEST_PROTO/$(dirname "$path")"
    cp -r "$SRC_PROTO/$path" "$DEST_PROTO/$(dirname "$path")/"
done

# The FIT code generator is a build tool rather than app code, so it lands in
# its own JVM module and never reaches the APK. It is not optional: Gadgetbridge
# checks in only four FIT message classes and generates the rest at build time.
SRC_CODEGEN="$SRC/FitCodeGenerator/src/main"
for path in "${codegen_includes[@]}"; do
    if [[ ! -e "$SRC_CODEGEN/$path" ]]; then
        echo "error: manifest lists codegen '$path', which upstream no longer has." >&2
        echo "       The FIT generator is a build prerequisite — without it nothing" >&2
        echo "       in the FIT layer compiles. Check where upstream moved it." >&2
        exit 1
    fi
    mkdir -p "$DEST_CODEGEN/$(dirname "$path")"
    cp -r "$SRC_CODEGEN/$path" "$DEST_CODEGEN/$(dirname "$path")/"
done

for path in "${excludes[@]}"; do
    rm -rf "${PKG_ROOT:?}/$path"
done

# Re-apply our edits to vendored files. There should be very few of these —
# each one is a file upstream will keep changing underneath us — and each is
# justified in SHIMS.md.
#
# Failing loudly is the entire point. If upstream reworks a patched region, we
# need to look at what changed and decide again, not silently ship a refresh
# where the patch quietly did not take.
shopt -s nullglob
patches=("$HERE/patches"/*.patch)
shopt -u nullglob
for patch in "${patches[@]}"; do
    if ! git -C "$HERE/../.." apply --verbose "$patch" 2>/dev/null; then
        echo "error: $(basename "$patch") no longer applies." >&2
        echo "       Upstream changed the code this patch edits. Read the patch," >&2
        echo "       redo the edit against the new source, and regenerate it with:" >&2
        echo "         git diff -- <file> > $patch" >&2
        echo "       Do not skip it: the patch removes Gadgetbridge's own activity" >&2
        echo "       sample store, which Tracks must not run alongside its server." >&2
        exit 1
    fi
    echo "  applied $(basename "$patch")"
done

# The refreshed tree is the new baseline; prove it and lock it.
"$HERE/check-vendored.py" "$SRC" --write-lock

copied=$(find "$PKG_ROOT" \( -name '*.java' -o -name '*.kt' \) | wc -l | tr -d ' ')
protos=$(find "$DEST_PROTO" -name '*.proto' 2>/dev/null | wc -l | tr -d ' ')
codegen=$(find "$DEST_CODEGEN" -name '*.java' 2>/dev/null | wc -l | tr -d ' ')

# The vendored code names ~600 string and drawable resources. Generating them
# after the copy — rather than checking in a hand-maintained list — means an
# activity type added upstream arrives with the next refresh instead of
# breaking the build. Fails loudly if the code starts reaching for resource
# kinds that need a judgement call. See generate-gb-resources.py.
"$HERE/generate-gb-resources.py" "$SRC"

cat > "$PROVENANCE" <<EOF
<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Vendored from Gadgetbridge

**GENERATED — do not edit by hand.** Written by
\`vendor/pull-gadgetbridge.sh\`; re-run that instead.

| | |
|---|---|
| Upstream | https://codeberg.org/Freeyourgadget/Gadgetbridge |
| Version | \`$VERSION\` |
| Pulled | $(date -u +%Y-%m-%d) |
| Java files | $copied |
| Proto files | $protos |
| Codegen files | $codegen (in \`:fit-codegen\`) |
| Licence | AGPL-3.0-or-later (same as Tracks) |

Everything under \`src/main/java/nodomain/freeyourgadget/gadgetbridge/\` is
Gadgetbridge's work, kept under its original package names so that refreshing
from upstream stays a file copy and upstream diffs still apply. Do not edit
those files: the next pull overwrites them. Anything Tracks-specific belongs in
\`com.tracks.device.garmin\`, and shims for the parts we did not vendor live in
\`:device-gb-compat\`.

See \`vendor/gadgetbridge.manifest\` for what is taken and — more usefully —
what is deliberately left behind, with reasons.

## Refreshing

\`\`\`bash
vendor/pull-gadgetbridge.sh /path/to/gadgetbridge <version>
git diff        # review what upstream changed
\`\`\`

## Adding another vendor

Add its packages to the manifest and re-run. The shim layer in
\`:device-gb-compat\` is the reusable part; the per-vendor cost should be close
to a copy plus an adapter implementing \`DeviceIntegration\`.
EOF

echo "pull-gadgetbridge: $copied java + $protos proto from Gadgetbridge $VERSION -> device-garmin/"
echo "                   $codegen codegen -> fit-codegen/"
echo "                   provenance written to device-garmin/VENDORED.md"
