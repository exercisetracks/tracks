// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

// The generated FIT sources, produced by :fit-codegen into a shared location
// under the root build directory. `builtBy` hangs the generator task off the
// file collection, which is enough for javac.
val fitGeneratedSources: FileCollection =
    files(rootProject.layout.buildDirectory.dir("generated/fit"))
        .builtBy(":fit-codegen:genFit")

// It is not enough for kotlinc. The Kotlin compile task reads the Java source
// roots for symbol resolution without inheriting their task dependencies, so on
// a clean checkout it ran before the generator and failed on every `FitCourse`
// and `FitWeather` the Kotlin here uses. Said explicitly instead.
tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(":fit-codegen:genFit")
}

android {
    // Gadgetbridge's package, not ours, and deliberately: the namespace is what
    // AGP names the generated `R` class after. The vendored code imports
    // `nodomain.freeyourgadget.gadgetbridge.R` some 600 times, so setting it
    // here means those resolve against a real, generated R — no hand-written
    // forwarding class listing every resource, and nothing to regenerate when
    // upstream adds an activity type.
    //
    // This constrains only R and BuildConfig. Tracks' own classes in this
    // module still live in com.tracks.device.garmin.
    namespace = "nodomain.freeyourgadget.gadgetbridge"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        // 17, not 11 like the rest of the project: the generated FIT message
        // classes use switch expressions, which are Java 14+. This matches
        // upstream's own level, so vendored code compiles as it was written.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            // The FIT encoder builds real record definitions, which reach
            // androidx annotations and (through the vendored tree) a handful of
            // android.* classes. Robolectric gives them a real implementation
            // rather than the stub jar's throw-on-everything.
            isIncludeAndroidResources = true
        }
    }

    sourceSets["main"].apply {
        // Shims live in their own source root, NOT under src/main/java, because
        // pull-gadgetbridge.sh deletes the vendored package tree wholesale
        // before each refresh — anything of ours in there would vanish with it.
        // Same package names, different directory.
        // The protobuf plugin already picks up src/main/proto by default.
        java.srcDirs("src/main/java", "src/compat/java")
        java.srcDir(fitGeneratedSources)
        // The SLF4J ServiceLoader registration. Named explicitly because the
        // default for an Android library is src/main/resources only when it
        // exists at configure time, and a missing provider fails silently at
        // runtime rather than at build time — the worst way for this to break.
        resources.srcDir("src/main/resources")
    }
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // Garmin's .proto files are proto2 and large; the lite runtime
                // keeps the method count and APK size sane on a phone.
                id("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(project(":device-api"))
    implementation(libs.protobuf.javalite)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    implementation(libs.slf4j.api)
    // Both are real upstream dependencies of the vendored code, not shims.
    // commons-lang3 is used throughout the GFDI message layer; the vendored
    // BLE bonding path uses LocalBroadcastManager (deprecated, but replacing it
    // would mean editing vendored files for no behavioural gain).
    implementation(libs.apache.commons.lang3)
    implementation(libs.androidx.localbroadcastmanager)
    implementation(libs.androidx.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}

// VendoredLockTest reads the vendored sources and vendor/gadgetbridge.lock at
// run time. Declared as inputs so an edit to either always reruns it: an edit
// that leaves the bytecode alone (a comment, a proto option) would otherwise
// count as up to date and skip the one test that exists to catch it.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("vendor/gadgetbridge.lock"))
    inputs.dir("src/main/java/nodomain")
    inputs.dir("src/main/proto")
}
