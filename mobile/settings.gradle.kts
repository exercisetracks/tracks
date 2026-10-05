// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "tracks-mobile"

// `core` holds everything that is not platform-specific: models, domain math,
// the API client, the sync engine. It builds for the JVM (which is what CI
// tests) and for Android, and its commonMain must compile for iOS too — that
// constraint is what keeps it reusable rather than quietly Android-only.
include(":core")

// The Android app. Everything that touches Android APIs lives here or in
// core/src/androidMain.
include(":app")

// The vendor-neutral watch contract, plus the phone-side feeds (notifications,
// weather) that a watch wants. Separate from :app so a second vendor slots in
// underneath without the UI knowing, and separate from :core because none of
// it is shareable with iOS.
include(":device-api")

// Vendored Gadgetbridge Garmin support plus the shims it needs. See
// device-garmin/VENDORED.md for provenance and SHIMS.md for the boundary.
include(":device-garmin")

// BRouter's routing engine, vendored so the phone can plan a trail route with
// no network — the same rd5 segments the server routes from, read on the
// device. See vendor/brouter.manifest for why it is vendored rather than
// resolved from JitPack.
include(":routing-brouter")

// Gadgetbridge's FIT message generator, vendored as a build tool. A plain JVM
// module so neither it nor its 300 KB profile JSON can reach the APK — only
// the Java it emits, which :device-garmin picks up as a generated source root.
include(":fit-codegen")
