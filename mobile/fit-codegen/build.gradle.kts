// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

// Gadgetbridge's FIT message code generator, vendored verbatim.
//
// This is a build tool, not app code, which is why it is a plain JVM module
// rather than part of :device-garmin — the generator and its 300 KB profile
// JSON must never reach the APK, only the Java it emits.
//
// It is also not optional. Upstream checks in exactly four FIT message classes
// out of several hundred, and even those four are only halves: `FitRecord`
// extends a generated `AbstractFitRecord`. Skip this module and the entire FIT
// layer fails to resolve.

plugins {
    application
}

java {
    // Upstream pins 21 and the generator uses recent language features
    // (text blocks, `formatted`). This is the build JVM only; it has no
    // bearing on the bytecode level :device-garmin ships.
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

application {
    mainClass = "nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.codegen.FitCodeGen"
}

dependencies {
    implementation(libs.json)
}

// Where the generated sources land: a shared location under the root build
// directory, computed by the same rule on both sides, so neither module has to
// reach into the other's internals to find it. :device-garmin adds it as a
// source root.
val fitGeneratedDir: Directory =
    rootProject.layout.buildDirectory.dir("generated/fit").get()

val fitPackageDir: Directory =
    fitGeneratedDir.dir("nodomain/freeyourgadget/gadgetbridge/service/devices/garmin/fit")

// The generator takes three arguments: the profile JSON, where to write, and
// the directory holding the hand-written message classes. That third one is
// load-bearing rather than informational — when a class exists there, the
// generator emits `AbstractFitX` instead of `FitX` so the hand-written subclass
// has a parent to extend. Point it at the wrong place and you get either
// duplicate classes or orphaned ones.
val manualMessagesDir: File =
    project(":device-garmin").projectDir
        .resolve("src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/garmin/fit")

val genFit by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generate FIT message classes from Garmin's profile JSON"

    // Declared so Gradle can skip the task when nothing relevant moved. The
    // manual directory is an input too: adding a hand-written message class
    // changes what gets generated.
    inputs.file(layout.projectDirectory.file("src/main/resources/fit_profile.json"))
        .withPropertyName("fitProfile")
    inputs.dir(layout.projectDirectory.dir("src/main/java"))
        .withPropertyName("generatorSource")
    inputs.dir(manualMessagesDir)
        .withPropertyName("manualMessages")
    outputs.dir(fitGeneratedDir).withPropertyName("generatedSources")

    mainClass = application.mainClass
    classpath = sourceSets.main.get().runtimeClasspath
    args(
        layout.projectDirectory.file("src/main/resources/fit_profile.json").asFile.absolutePath,
        fitPackageDir.asFile.absolutePath,
        manualMessagesDir.absolutePath,
    )
}
