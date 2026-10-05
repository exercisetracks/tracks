<!--
SPDX-FileCopyrightText: 2026 Hawk Fugagli
SPDX-License-Identifier: AGPL-3.0-or-later
-->
# Releasing Tracks Mobile

## The keystore

The 1.0.0 key was generated on 2026-10-05 and lives outside the repo, in
`~/exercisetracks-release-key/` on the release machine, with a README beside
it holding its password. **Back that folder up and never lose it.** Android
identifies an app by its signing key: if the key is gone, every installed
copy must be *uninstalled* — losing a standalone phone's data — before it can
be updated again. There is no recovery, no support ticket, and no way to
re-sign an existing install.

The same key signs every channel (GitHub releases now; F-Droid through
reproducible builds and Google Play by uploading it to Play App Signing
later), so a user can move between them without reinstalling. Should it ever
need regenerating for a *new* app id:

```sh
keytool -genkeypair -v \
  -keystore exercisetracks-release.jks -storetype PKCS12 \
  -alias tracks \
  -keyalg RSA -keysize 4096 -validity 10000
```

`*.jks`, `*.keystore` and `keystore.properties` are gitignored, and that is a
safety net rather than a plan.

## Configuring the build

Either write `mobile/keystore.properties` (gitignored):

```properties
storeFile=/absolute/path/to/tracks-release.jks
storePassword=…
keyAlias=tracks
keyPassword=…
```

…or set the environment, which is what CI should do:

```sh
TRACKS_KEYSTORE=/path/to/tracks-release.jks \
TRACKS_KEYSTORE_PASSWORD=… \
TRACKS_KEY_ALIAS=tracks \
TRACKS_KEY_PASSWORD=… \
./gradlew :app:assembleRelease
```

With neither configured the build still succeeds and emits
`app-release-unsigned.apk`. That is deliberate: reproducible builds are an
F-Droid requirement, and anyone verifying one must be able to build a release
without holding the key.

## Build it

```sh
./gradlew :app:assembleRelease     # → app/build/outputs/apk/release/
```

**Always hand testers a release build, never a debug one.** Debug Compose skips
R8 and the baseline profile, and the difference is not subtle — measured on a
fenix-era Android phone, scrolling the dashboard was 125 ms per frame and 85%
janky in debug versus 6 ms and 0% in release. A tester on a debug APK will
report that the app is unusably slow, and they will be right.

## Checking performance

```sh
adb shell dumpsys gfxinfo com.exercisetracks.app reset
# …scroll the screen under test…
adb shell dumpsys gfxinfo com.exercisetracks.app | grep -A6 "Total frames rendered"
```

Anything under 16 ms at the 90th percentile is inside the frame budget. Only
ever measure a release build; a debug measurement tells you nothing about what
ships.

## Versioning

`versionCode` and `versionName` live in `app/build.gradle.kts`, and
`versionCode` is major × 10000 + minor × 100 + patch so the two can never
disagree about order. The app also checks `GET /capabilities` and can say when
it is too old for the server it is talking to.

Releases go out through `scripts/release.sh` at the repository root (see
AGENTS.md, "Releasing"), which builds the APK with this key and attaches it to
the GitHub release. Users who want updates without checking by hand can point
[Obtainium](https://obtainium.imranr.dev) at
`https://github.com/exercisetracks/tracks`.
