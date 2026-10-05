#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Copy the BRouter sources listed in brouter.manifest into :routing-brouter,
# recording exactly which upstream version they came from.
#
# Same shape and same reasoning as pull-gadgetbridge.sh: the point of vendoring
# is being able to take upstream's fixes later, and a manual copy makes each
# refresh an archaeology exercise. Package names are preserved so a refresh is a
# file copy and upstream diffs still apply.
#
# Usage:
#   vendor/pull-brouter.sh /path/to/brouter v1.7.9
#
# A checkout is easy to get:
#   git clone --depth 1 --branch v1.7.9 https://github.com/abrensch/brouter
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST="$HERE/brouter.manifest"
DEST_ROOT="$HERE/../routing-brouter/src/main/java"
PROVENANCE="$HERE/../routing-brouter/VENDORED.md"

SRC="${1:-}"
VERSION="${2:-unknown}"

if [[ -z "$SRC" ]]; then
    echo "usage: $(basename "$0") <brouter-source-dir> <version>" >&2
    exit 2
fi

if [[ ! -d "$SRC/brouter-core/src/main/java/btools/router" ]]; then
    echo "error: $SRC does not look like a BRouter checkout" >&2
    echo "       (expected $SRC/brouter-core/src/main/java/btools/router)" >&2
    exit 1
fi

# Start clean so a file deleted upstream does not linger here forever, silently
# shadowing its replacement.
rm -rf "${DEST_ROOT:?}/btools"
mkdir -p "$DEST_ROOT/btools"

modules=()
packages=()
while IFS= read -r line; do
    line="${line%%#*}"
    line="$(echo "$line" | xargs || true)"
    [[ -z "$line" ]] && continue
    case "$line" in
        include\ *)
            rest="${line#include }"
            modules+=("${rest%% *}")
            packages+=("${rest##* }")
            ;;
        *) echo "warning: ignoring unparsable manifest line: $line" >&2 ;;
    esac
done < "$MANIFEST"

for i in "${!modules[@]}"; do
    from="$SRC/${modules[$i]}/src/main/java/${packages[$i]}"
    if [[ ! -d "$from" ]]; then
        echo "error: manifest lists ${modules[$i]}/${packages[$i]}, which upstream" >&2
        echo "       no longer has. It was probably moved or renamed — check the" >&2
        echo "       release notes and update the manifest rather than silently" >&2
        echo "       dropping it." >&2
        exit 1
    fi
    mkdir -p "$DEST_ROOT/$(dirname "${packages[$i]}")"
    cp -r "$from" "$DEST_ROOT/$(dirname "${packages[$i]}")/"
done

# Upstream's own test fixtures and anything else non-source would only bloat the
# module; the manifest names packages, so nothing else should have come along,
# but a stray resource in a package directory would.
find "$DEST_ROOT/btools" -type f ! -name '*.java' -delete

copied=$(find "$DEST_ROOT/btools" -name '*.java' | wc -l | tr -d ' ')
lines=$(find "$DEST_ROOT/btools" -name '*.java' -exec cat {} + | wc -l | tr -d ' ')

cat > "$PROVENANCE" <<EOF
<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->

# Vendored from BRouter

**GENERATED — do not edit by hand.** Written by
\`vendor/pull-brouter.sh\`; re-run that instead.

| | |
|---|---|
| Upstream | https://github.com/abrensch/brouter |
| Version | \`$VERSION\` |
| Pulled | $(date +%Y-%m-%d) |
| Java files | $copied |
| Lines | $lines |
| Licence | MIT |

Everything under \`src/main/java/btools/\` is BRouter's work, kept under its
original package names so that refreshing from upstream stays a file copy and
upstream diffs still apply. Do not edit those files: the next pull overwrites
them. Anything Tracks-specific belongs in \`com.tracks.app.map\`.

See \`vendor/brouter.manifest\` for what is taken and — more usefully — what is
deliberately left behind, with reasons.

## What this is for

The phone routes on trails with no network. The Tracks server hands it the same
rd5 segments and \`.brf\` profile the \`brouter\` container uses
(\`GET /maps/route/offline/*\`), and this engine reads them on the device. When
there is signal, \`POST /maps/route/snap\` still answers — the two produce the
same GeoJSON shape because both end in BRouter's own \`FormatJson\`.

## Refreshing

\`\`\`bash
git clone --depth 1 --branch <version> https://github.com/abrensch/brouter /tmp/brouter
vendor/pull-brouter.sh /tmp/brouter <version>
git diff        # review what upstream changed
\`\`\`

## After a refresh

\`OfflineRoutingTest\` guards the seam that is ours: it asserts the formatter
still emits \`track-length\`, \`filtered ascend\` and \`total-time\`, which the
map screen reads by name and which would otherwise go blank in silence.

It cannot guard the other risk. An rd5 file carries a format version, and an
engine that no longer decodes the server's segments fails only on a real
download of a real area — so route somewhere offline on the phone after
refreshing, not just in the suite.
EOF

echo "vendored $copied BRouter files ($lines lines) from $VERSION"
echo "wrote $PROVENANCE"
