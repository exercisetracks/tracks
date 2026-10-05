// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

// BRouter's routing engine, vendored verbatim. See VENDORED.md.
//
// A plain `java-library` rather than an Android library: none of it touches an
// Android API — it is `java.io` over rd5 files and arithmetic — and keeping it
// off the Android plugin means it also compiles and runs under :core's JVM
// tests, which is where the routing test actually lives. BRouter's own Android
// app consumes these same modules the same way.

plugins {
    `java-library`
}

java {
    // Matches :app. BRouter targets Java 8 and uses nothing newer, but the
    // bytecode this emits is dexed into the APK, so it must not be ahead of
    // what the app compiles at.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<JavaCompile>().configureEach {
    // Vendored code we do not edit. Upstream builds with warnings and fixing
    // them here would mean carrying patches against every file — the exact
    // thing VENDORED.md exists to avoid.
    options.compilerArgs.add("-nowarn")
    options.isWarnings = false
}
