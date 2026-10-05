// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.android.library)
}

kotlin {
    jvmToolchain(21)

    // The JVM target is what CI runs the tests on — fast, no emulator.
    jvm()

    // The Android target exists so :app can depend on this module and so the
    // SQLCipher driver and Keystore-backed token store have somewhere to live.
    // It does NOT weaken the iOS story: androidMain is additive, and commonMain
    // still has to compile for every declared target.
    //
    // jvmTarget is pinned to 11 rather than inherited from the toolchain: the
    // toolchain compiles WITH JDK 21, but Android's Java compilation targets 11,
    // and Gradle rejects the pair as inconsistent. Compiling with a new JDK for
    // an older bytecode target is the normal arrangement here.
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    // iOS targets are declared, not built — cross-compiling them needs a Mac.
    // Their value here is at *configuration* time: commonMain must compile for
    // them, which is what actually stops a JVM-only API sneaking into shared
    // code and quietly making the module Android-only. That boundary is the
    // whole reason `core` is a separate module.
    //
    // They are skipped off macOS so a Linux build doesn't fail on a toolchain
    // it was never going to have.
    if (System.getProperty("os.name").startsWith("Mac")) {
        iosArm64()
        iosSimulatorArm64()
    }

    // JVM and Android share a source set of their own. Both run on a JVM and
    // both can use Bouncy Castle, so the sealed-box implementation would
    // otherwise have to be written twice — and a crypto construction copied
    // into two files is one that will eventually differ in one of them.
    //
    // Note this is *not* expect/actual. An `expect` in commonMain would demand
    // an iOS `actual`, and the iOS targets exist here precisely to fail when
    // shared code cannot be shared. An interface implemented per platform keeps
    // that check meaningful: commonMain declares what it needs, and a platform
    // that has no implementation simply does not offer one.
    @Suppress("UNUSED_VARIABLE")
    applyDefaultHierarchyTemplate()

    sourceSets {
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmSharedMain)
        androidMain.get().dependsOn(jvmSharedMain)

        jvmSharedMain.dependencies {
            // Pulled in for four primitives (X25519, BLAKE2b, XSalsa20,
            // Poly1305) out of a large library. R8 shrinks it to what is
            // reachable in release builds; the debug APK carries the lot, which
            // is the usual trade and not worth a stripped fork to avoid.
            implementation(libs.bouncycastle.prov)
        }

        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            // `api`, not `implementation`: TracksClient's constructor takes an
            // HttpClientEngine so tests can inject a mock, which makes the type
            // part of this module's public surface whether or not that is
            // elegant. Hiding it would only move the problem to a factory.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            // Android uses OkHttp too, so the engine the tests exercise is the
            // engine the app ships.
            implementation(libs.ktor.client.okhttp)
        }
        androidMain.dependencies {
            // Android needs its own engine — without one, HttpClient() finds no
            // implementation at runtime and every request fails on device while
            // the JVM tests pass happily.
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.driver.android)
            implementation(libs.androidx.sqlite)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.security.crypto)
        }
        jvmTest.dependencies {
            // A real SQLite file, so the storage tests exercise actual SQL
            // rather than a stand-in. Android swaps this for the SQLCipher
            // driver; the schema and queries are the same.
            implementation(libs.sqldelight.driver.jvm)
        }
    }
}

sqldelight {
    databases {
        create("TracksDb") {
            packageName.set("com.tracks.core.db")
            // json_extract/json_type, so a query can read one field of an
            // activity's detail without Kotlin parsing all of it (GPS
            // included). SQLCipher and sqlite-jdbc both ship JSON1.
            module(libs.sqldelight.json.module.get().toString())
        }
    }
}

android {
    namespace = "com.tracks.core"
    compileSdk = 35
    defaultConfig {
        // Android 8.0. Gadgetbridge supports 23, but the BLE and
        // foreground-service APIs this app relies on are far less painful from
        // 26 up, and 23-25 is a vanishing share of devices that can pair a
        // modern watch anyway.
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The fixture suites read spec/fixtures/ at runtime, from outside this build,
// so Gradle cannot see them — and with the build cache on, a jvmTest whose
// Kotlin inputs are unchanged is restored from cache rather than run. That
// made a regenerated fixture report the *old* result: a test could pass
// against a corpus it never read. Declaring the directory makes a changed
// fixture a changed input. It includes the gitignored personal/ digests,
// whose real FIT files live outside the repo and are checked against their
// recorded hashes by the test itself.
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.file("../spec/fixtures"))
        .withPropertyName("specFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
