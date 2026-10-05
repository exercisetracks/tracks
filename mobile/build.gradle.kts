// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

// Every plugin any module uses is declared here with `apply false`, which puts
// it on the build classpath at one known version. Modules then request it
// without re-declaring a version.
//
// This is not stylistic. The Kotlin Multiplatform plugin already drags the
// Kotlin Gradle plugin onto the classpath, so a module asking for
// `kotlin.android` with its own version fails with "already on the classpath
// with an unknown version" — resolvable only by hoisting the declarations here.
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.protobuf) apply false
}
