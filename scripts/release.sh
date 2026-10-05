#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later
#
# Publish a Tracks release: Docker images to Docker Hub, the signed APK to a
# GitHub release, and a tag tying both to one commit.
#
# The image installs run is exercisetracks/tracks, the all-in-one container
# (deploy/Dockerfile), and it is the only image published. It is assembled
# from the per-service images built first; those stay local — they are build
# inputs, not something an install runs.
#
#   ./scripts/release.sh 1.2.0
#
# Read AGENTS.md, "Releasing", before running this: the checklist, the logins,
# how to verify each channel, and the things a release must never change.
#
# Needs: `docker login` as the exercisetracks Docker Hub account, `gh auth
# login` with access to github.com/exercisetracks/tracks, and the Android
# release key configured (mobile/RELEASE.md). Run from a clean checkout of main
# whose version strings already say <version> — the script checks rather than
# edits them, so the bump is an ordinary reviewed commit.
#
# Images are tagged <version>, <major>.<minor>, <major> and latest, so an
# install following "1" picks up 1.2.0 on its next pull. A pre-release
# (1.2.0-beta.1) gets only its exact tag and moves none of the others.
#
# The watch app is not built here: its Connect IQ SDK and developer key live
# on the developer's machine. The APK carries whatever watchapp/build.sh last
# bundled into mobile/app/src/main/assets/watchapp/, and this refuses to run
# when that bundle is older than the watch app's source.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:?usage: $0 <version>   e.g. $0 1.2.0}"
REGISTRY="${REGISTRY:-exercisetracks}"
REPO="${REPO:-exercisetracks/tracks}"
TAG="v${VERSION}"

die() { echo "ERROR: $*" >&2; exit 1; }

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "not a version: $VERSION"
[ -z "$(git status --porcelain)" ] || die "working tree is not clean"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "release from main"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "$TAG already exists"

# Every place a version is written down must agree before anything ships.
grep -q "^SERVER_VERSION = \"${VERSION}\"" backend/app/version.py || die "backend/app/version.py is not $VERSION"
grep -q "\"version\": \"${VERSION}\"" frontend/package.json || die "frontend/package.json is not $VERSION"
grep -q "versionName = \"${VERSION}\"" mobile/app/build.gradle.kts || die "mobile versionName is not $VERSION"

bundle=mobile/app/src/main/assets/watchapp/index.json
[ -f "$bundle" ] || die "no watch app bundle — run watchapp/build.sh all"
newer="$(find watchapp/source watchapp/resources watchapp/manifest.xml -newer "$bundle" | head -1)"
[ -z "$newer" ] || die "watch app source ($newer) is newer than its bundle — run watchapp/build.sh all"

# ── Images ────────────────────────────────────────────────────────────────────
major="${VERSION%%.*}"
minor="${VERSION#*.}"; minor="${major}.${minor%%.*}"
tags=("$VERSION")
[[ "$VERSION" == *-* ]] || tags+=("$minor" "$major" latest)

build() {  # name, then `docker build` arguments
    local name="$1"; shift
    echo "── ${REGISTRY}/${name}:${VERSION}"
    docker build --pull -t "${REGISTRY}/${name}:${VERSION}" "$@"
}
build tracks-backend --build-arg INSTALL_DEV_DEPS=false backend
build tracks-web -f caddy/Dockerfile .
build tracks-tiles go-pmtiles
build tracks-brouter brouter
build tracks-garmin-sync garmin-sync
# Not through build(): --pull would fetch its base images from Docker Hub,
# where this version's component images, built just above, do not exist yet.
echo "── ${REGISTRY}/tracks:${VERSION}"
docker build -t "${REGISTRY}/tracks:${VERSION}" --build-arg REGISTRY="$REGISTRY" \
    --build-arg VERSION="$VERSION" -f deploy/Dockerfile deploy

# ── APK ───────────────────────────────────────────────────────────────────────
mkdir -p build/release
(cd mobile && ./gradlew -q :app:assembleRelease)
apk="build/release/tracks-${VERSION}.apk"
cp mobile/app/build/outputs/apk/release/app-release.apk "$apk" \
    || die "no signed APK — is the release key configured? (mobile/RELEASE.md)"
(cd build/release && sha256sum "tracks-${VERSION}.apk" > "tracks-${VERSION}.apk.sha256")

# ── Publish ───────────────────────────────────────────────────────────────────
# Only once everything above has built, so a failure publishes nothing.
for name in tracks; do
    for t in "${tags[@]}"; do
        [ "$t" = "$VERSION" ] || docker tag "${REGISTRY}/${name}:${VERSION}" "${REGISTRY}/${name}:${t}"
        docker push -q "${REGISTRY}/${name}:${t}"
    done
done

git tag -a "$TAG" -m "Tracks ${VERSION}"
git push origin main "$TAG"

notes="build/release/notes-${VERSION}.md"
cat > "$notes" <<NOTES
## Install or update

**Server** — new install:
\`\`\`sh
docker run -d --name tracks --restart unless-stopped -p 4080:80 \\
  -v tracks-data:/data -v tracks-maps:/map-data exercisetracks/tracks:latest
\`\`\`
or save \`compose.yaml\` (below) and run \`docker compose up -d\`.

Existing install — back up first (\`docker exec tracks tracks-backup > tracks.tar.gz\`), then
\`docker compose pull && docker compose up -d\`, or pull and re-run your \`docker run\`.

**Android** — install \`tracks-${VERSION}.apk\` below (Android 8.0+); it installs over
an earlier Tracks and keeps your data. SHA-256: \`$(cut -d' ' -f1 "build/release/tracks-${VERSION}.apk.sha256")\`
NOTES
prerelease=()
[[ "$VERSION" == *-* ]] && prerelease=(--prerelease)
# Last, and the step most likely to hit a network blip — by now the images and
# the tag are public, so a failure here must say how to finish rather than
# leave the release half-made. (1.0.0 needed exactly that.)
gh release create "$TAG" -R "$REPO" --title "Tracks ${VERSION}" --notes-file "$notes" \
    --generate-notes --verify-tag "${prerelease[@]}" \
    "$apk" "build/release/tracks-${VERSION}.apk.sha256" deploy/compose.yaml \
    || die "images and tag $TAG are published, but the GitHub release is not. Finish it with:
  gh release create $TAG -R $REPO --title 'Tracks ${VERSION}' --notes-file $notes --verify-tag $apk build/release/tracks-${VERSION}.apk.sha256 deploy/compose.yaml"

echo "Released ${VERSION}: https://github.com/${REPO}/releases/tag/${TAG}"
