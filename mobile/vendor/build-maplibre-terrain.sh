#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Build the MapLibre Native Android AAR with 3D terrain, from the branch where
# terrain is being developed, and install it into app/libs/.
#
# ── Why a local build ────────────────────────────────────────────────────────
#
# MapLibre Native does not implement the style's `terrain` property in any
# release. Checked at android-v13.5.0 (the current one at the time of writing)
# and at the 11.8.0 this app used before: the style root properties the parser
# recognises are glyphs / layers / light / sources / sprite, there is no
# `terrain` string in libmaplibre.so, and no Terrain class in the SDK. Adding
# "terrain" to the style document was therefore silently inert — the camera
# tilted over a flat map, which is what it looked like on the phone.
#
# It is being built, in the open, in two draft PRs:
#   maplibre/maplibre-native#4190  Terrain 3D          (the base branch)
#   maplibre/maplibre-native#4389  Continue 3d terrain (what this builds)
#
# The second one is where the Android work is. Its CI passes Android builds and
# Android instrumentation tests on both OpenGL and Vulkan; the render tests
# fail, which is expected, since those diff against reference images that
# predate terrain.
#
# ── What this costs ──────────────────────────────────────────────────────────
#
# This is a prebuilt binary in a repository whose every other dependency is
# resolved from source or from Maven Central, for the F-Droid reasons argued in
# gradle/libs.versions.toml. That is a deliberate exception, taken because 3D
# terrain is a requirement and there is no other way to have it. It should be
# undone the moment terrain lands in a MapLibre release: delete app/libs, put
# `libs.maplibre.android` back, and bump the version.
#
# ── Usage ────────────────────────────────────────────────────────────────────
#
#   vendor/build-maplibre-terrain.sh [checkout-dir]
#
# Needs the Android NDK and CMake the upstream build pins:
#   sdkmanager --install "ndk;28.2.13676358" "cmake;3.31.6"
#
# About 8 minutes on a warm machine for one ABI, and ~10 GB of intermediates in
# the checkout directory (which is not cleaned up — a rebuild is much faster).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HERE/../app/libs/maplibre-terrain.aar"

# The fork and branch of PR #4389. Pinned to a commit rather than to the branch
# head: this is a draft branch that rebases, and "whatever it was that day" is
# not a thing anyone can rebuild.
REPO="https://github.com/WifiDB/maplibre-native.git"
BRANCH="terrain-3d-color-relief"
COMMIT="07bc7180f4"

# arm64 only. Every phone this app is built for is arm64, and each extra ABI is
# another full native build and another ~11 MB of .so in the APK. Add x86_64
# here if you need the emulator.
ABIS="arm64-v8a"

SRC="${1:-$HOME/Documents/Coding/maplibre-native-terrain}"

if [[ ! -d "$SRC/.git" ]]; then
    echo "cloning $BRANCH into $SRC (this pulls ~1.7 GB with submodules)"
    git clone --recurse-submodules --shallow-submodules --depth 1 \
        -b "$BRANCH" "$REPO" "$SRC"
fi

actual="$(git -C "$SRC" rev-parse HEAD)"
if [[ "$actual" != "$COMMIT"* ]]; then
    echo "warning: $SRC is at $actual, not the pinned $COMMIT." >&2
    echo "         The branch has moved. Verify terrain still works, then" >&2
    echo "         update COMMIT here." >&2
fi

# Our own fixes on top of the branch, in their own directory so that
# pull-gadgetbridge.sh — which globs patches/*.patch and applies them to the
# Tracks tree — never tries to apply a MapLibre patch to the wrong repository.
#
# Failing loudly is the point: if upstream reworks a patched region we need to
# look at what changed and decide again, not quietly ship a build where the
# patch did not take.
shopt -s nullglob
patches=("$HERE/patches/maplibre"/*.patch)
shopt -u nullglob
for patch in "${patches[@]}"; do
    if git -C "$SRC" apply --reverse --check "$patch" 2>/dev/null; then
        echo "  $(basename "$patch") already applied"
    elif git -C "$SRC" apply "$patch" 2>/dev/null; then
        echo "  applied $(basename "$patch")"
    else
        echo "error: $(basename "$patch") no longer applies to $SRC." >&2
        echo "       Read the patch, redo the edit against the new source, and" >&2
        echo "       regenerate it with: git -C $SRC diff > $patch" >&2
        exit 1
    fi
done

# The SDK that has the NDK, not merely the first one the environment names.
#
# A system-wide SDK (/opt/android-sdk on Arch) is usually root-owned, so
# `sdkmanager --install ndk` fails there and the NDK ends up in the user's own
# SDK instead. Taking $ANDROID_SDK_ROOT on faith then fails much later and much
# less clearly, as "License for package NDK not accepted" — which sends you
# looking at licences rather than at the path.
NDK_VERSION="28.2.13676358"
SDK=""
for candidate in "${ANDROID_SDK_ROOT:-}" "$HOME/Android/Sdk" "$ANDROID_HOME"; do
    if [[ -n "$candidate" && -d "$candidate/ndk/$NDK_VERSION" ]]; then
        SDK="$candidate"
        break
    fi
done

if [[ -z "$SDK" ]]; then
    echo "error: NDK $NDK_VERSION not found in any Android SDK." >&2
    echo "       Install it into a writable SDK:" >&2
    echo "         sdkmanager --sdk_root=\$HOME/Android/Sdk \\" >&2
    echo "           --install \"ndk;$NDK_VERSION\" \"cmake;3.31.6\"" >&2
    exit 1
fi

export ANDROID_SDK_ROOT="$SDK" ANDROID_HOME="$SDK"
echo "sdk.dir=$SDK" > "$SRC/platform/android/local.properties"
echo "building with SDK $SDK"

# Source paths are remapped by patches/maplibre/0005-remap-source-paths.patch,
# in MapLibre's own Gradle config: the Android plugin passes CMAKE_CXX_FLAGS
# itself, so CFLAGS/CXXFLAGS from the environment never reach the compiler.
# Check a new build with `strings libmaplibre.so | grep /home/` — it must be
# empty, or the builder's home directory ships inside every APK.

(cd "$SRC/platform/android" && ./gradlew :MapLibreAndroid:assembleOpenglRelease \
    -Pmaplibre.abis="$ABIS" --no-daemon)

BUILT="$SRC/platform/android/MapLibreAndroid/build/outputs/aar/MapLibreAndroid-opengl-release.aar"
[[ -f "$BUILT" ]] || { echo "error: no AAR at $BUILT" >&2; exit 1; }

# Repacked without prefab/, which is 157 MB of C++ headers and static libraries
# for consumers that link against MapLibre's native code. This app is Kotlin and
# never does; dropping it takes the artifact from 42 MB to under 5, which is the
# difference between a checked-in binary being tolerable and being absurd.
python3 - "$BUILT" "$DEST" <<'PY'
import os, shutil, sys, tempfile, zipfile

src, dest = sys.argv[1], sys.argv[2]
with tempfile.TemporaryDirectory() as tmp:
    with zipfile.ZipFile(src) as z:
        z.extractall(tmp)
    shutil.rmtree(os.path.join(tmp, "prefab"), ignore_errors=True)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for root, _, files in os.walk(tmp):
            for name in sorted(files):
                path = os.path.join(root, name)
                z.write(path, os.path.relpath(path, tmp))
print(f"wrote {dest} ({os.path.getsize(dest) / 1e6:.1f} MB)")
PY

echo
echo "Built from $BRANCH @ $actual for $ABIS."
echo "A file dependency carries no POM, so MapLibreAndroid's own dependencies"
echo "are declared by hand in app/build.gradle.kts — check them against"
echo "$SRC/platform/android/MapLibreAndroid/build.gradle.kts after a rebuild."
