// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later

// `java.util.Properties` cannot be written inline inside `android { }` — the
// `java` extension shadows the package name there.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tracks.app"
    compileSdk = 35

    defaultConfig {
        // The app's identity on every store and every phone, so it can never
        // change: a different id is a different app, and nothing carries an
        // install's data across. Derived from exercisetracks.com. The code's
        // own `namespace` above stays com.tracks.app — it is internal, and
        // renaming every package would buy nothing a user can see.
        applicationId = "com.exercisetracks.app"
        minSdk = 26
        targetSdk = 35
        // major * 10000 + minor * 100 + patch, so the code always climbs with
        // the name and either one can be read from the other. One release broke
        // the rule: 1.0.2 shipped after 1.1.0 (10100) and so was given 10101,
        // because Android refuses a lower code over a higher one without an
        // uninstall, which loses the app's data. Every version from 1.1.2 on
        // follows the formula again, and is above it.
        versionCode = 10401
        versionName = "1.4.1"
    }

    /**
     * The release signing config, or null when no keystore is configured.
     *
     * The keystore is never in the repo and never in this file. Credentials
     * come from `keystore.properties` (gitignored) or, in CI, from the
     * environment — so a checkout can always *build* a release, and only a
     * machine holding the key can sign one.
     *
     * Null rather than a failure when nothing is configured: `assembleRelease`
     * still has to work for anyone verifying the build reproduces, which is a
     * hard requirement for F-Droid. It simply produces an unsigned APK.
     *
     * Losing this key is unrecoverable — every installed copy must be
     * uninstalled before it can be updated again. Back it up outside the repo
     * before the first tester install, not after.
     */
    val releaseSigning = run {
        val props = Properties().apply {
            val file = rootProject.file("keystore.properties")
            if (file.exists()) file.inputStream().use { load(it) }
        }
        fun setting(key: String, env: String): String? =
            props.getProperty(key) ?: System.getenv(env)

        val storePath = setting("storeFile", "TRACKS_KEYSTORE")
        val storePass = setting("storePassword", "TRACKS_KEYSTORE_PASSWORD")
        val alias = setting("keyAlias", "TRACKS_KEY_ALIAS")
        val keyPass = setting("keyPassword", "TRACKS_KEY_PASSWORD")

        if (storePath != null && storePass != null && alias != null && keyPass != null &&
            file(storePath).exists()
        ) {
            signingConfigs.create("release") {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        } else {
            null
        }
    }

    buildTypes {
        release {
            // Present only when a keystore is configured; see releaseSigning.
            signingConfig = releaseSigning
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        // Desugaring is not a style choice here: :device-garmin needs it for
        // java.time in the vendored FIT code, and AGP requires the consuming
        // application to enable it too — a library cannot desugar on behalf of
        // the APK it ends up in.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    testOptions {
        unitTests {
            // The stub android.jar throws on every call, including
            // android.util.Log — so a unit test of pure logic that happens to
            // log a line dies with "Method d in android.util.Log not mocked".
            // Robolectric supplies real implementations instead, which is what
            // the other two modules already do for the same reason.
            isIncludeAndroidResources = true
            // Screenshot tests write their PNGs rather than compare against
            // committed ones: they exist to be looked at while a screen is
            // being built, and reference images of every state would be a
            // megabyte of churn per change.
            all { it.systemProperty("roborazzi.test.record", "true") }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Bouncy Castle and jspecify (via Vico) each ship an OSGi manifest
            // at the same multi-release path. It is build metadata for a
            // container Android does not have, so either copy will do — the
            // packager just refuses to choose.
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":device-api"))
    // The Garmin implementation of :device-api, plus the vendored Gadgetbridge
    // protocol code it wraps. The app talks to DeviceIntegration; this is the
    // one line that decides which implementation is present.
    implementation(project(":device-garmin"))
    // Unpacks the bundled watch apps — see WatchAppBundle.
    implementation(libs.xz)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Charts. Hand-drawn Canvas came first and was the wrong call: it produced
    // charts that technically plotted the data and looked nothing like a
    // finished app, with no axes, labels, or touch behaviour, and every one of
    // those would have had to be built by hand. Vico is Apache-2 on Maven
    // Central — it clears F-Droid's build-from-source and no-proprietary rules,
    // which is why the plan named it in the first place.
    //
    // Canvas is still right for the shapes Vico has no concept of: the
    // contribution calendar and the radial gauges stay hand-drawn.
    implementation(libs.vico.compose.m3)

    // Deferrable background work — delta sync, watch pushes. Never a bare
    // thread: Doze and App Standby will kill it, and WorkManager survives
    // reboots and respects battery constraints.
    implementation(libs.androidx.work.runtime)

    // NotificationCompat/ServiceCompat, for the foreground-service notification
    // a background watch sync is required to show. Not pulled in transitively
    // by Compose, so it needs its own line.
    implementation(libs.androidx.core)
    // MediaStyle for the stretching-flow notification: the one style whose
    // buttons show on the lock screen and in the compact shade. Tiny, and not
    // media3 — nothing here plays audio through a player.
    implementation(libs.androidx.media)

    // The map. Renders the same style document the web app does, served by
    // Tracks itself — see AppContainer.client().mapStyleJson().
    //
    // A local AAR rather than `libs.maplibre.android`, and the one prebuilt
    // binary in this project. MapLibre Native ships no `terrain` support in any
    // release — verified at 11.8.0 and 13.5.0, which parse glyphs/layers/light/
    // sources/sprite and nothing else — so 3D terrain is only available from the
    // branch where it is being written. `vendor/build-maplibre-terrain.sh`
    // builds it and says what the exception costs; undo it when terrain lands
    // upstream.
    implementation(files("libs/maplibre-terrain.aar"))

    // MapLibreAndroid's own dependencies, which a file dependency does not
    // carry: an AAR on disk has no POM, so without these the app compiles and
    // then dies at runtime on the first missing class. Taken from
    // MapLibreAndroid/build.gradle.kts at the commit the AAR was built from.
    implementation(libs.maplibre.geojson)
    implementation(libs.maplibre.turf)
    implementation(libs.maplibre.gestures)
    implementation(libs.okhttp)
    implementation(libs.timber)
    implementation(libs.androidx.interpolator)
    implementation(libs.androidx.fragment)

    // Trail routing with the radio off. Vendored BRouter, reading the rd5
    // segments the server hands over with a region — see routing-brouter/.
    implementation(project(":routing-brouter"))

    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.compose.ui.tooling)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // BackupFixtureTest reads spec/fixtures/backup.json; core has it, but as `implementation`.
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    // Screenshot tests (ui/*ScreenshotTest): Compose rendered by Robolectric's
    // native graphics into PNGs under build/outputs/roborazzi.
    testImplementation(libs.junit4)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    debugImplementation(libs.compose.ui.test.manifest)
}
