# SPDX-FileCopyrightText: 2026 Hawk Fugagli
# SPDX-License-Identifier: AGPL-3.0-or-later

# SQLCipher loads its native layer reflectively, so R8 cannot see the uses and
# strips the classes the JNI bridge looks up by name.
-keep class net.zetetic.database.** { *; }

# Tink — pulled in by androidx.security.crypto, which is what encrypts the token
# store — is compiled against Error Prone's annotations but does not ship them.
# They are source-retention hints for a static analyser and have no runtime
# meaning, so a missing reference is genuinely nothing; R8 still refuses to
# finish the build over it.
#
# This was not a warning anyone had been ignoring: `assembleRelease` had never
# completed, because every build until now was a debug one. Worth stating,
# because the release APK is the artefact that ships to testers and eventually
# to F-Droid, and "the app builds" was only ever true of the slow variant.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# kotlinx.serialization generates a companion `serializer()` per @Serializable
# class and looks the serializer up reflectively for polymorphic and default
# cases. R8 sees no call site and removes it, which turns a decode into a
# runtime crash only in release — the worst possible place to find it.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.tracks.core.api.**$$serializer { *; }
-keepclassmembers class com.tracks.core.api.** {
    *** Companion;
}

# Ktor and OkHttp both reference optional platform pieces (Conscrypt, BouncyCastle
# JSSE, Animal Sniffer) that are simply absent on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.slf4j.**

# The SLF4J binding that puts the vendored Garmin stack's logging into logcat.
#
# Found by ServiceLoader through META-INF/services, which R8 cannot see as a
# use — so without this the provider is shrunk out, SLF4J falls back to its
# no-op, and every protocol diagnostic silently disappears from release builds.
# That is precisely the state this was added to end.
-keep class com.tracks.device.garmin.logging.** { *; }
-keep class * implements org.slf4j.spi.SLF4JServiceProvider { *; }

# ── The Garmin protocol, which R8 was quietly dismantling ────────────────
#
# Every incoming GFDI message is parsed through one reflective lookup:
#
#     garminMessage.objectClass.getMethod("parseIncoming", ...)
#
# R8 cannot see that as a use, so it renamed the method on every message class
# and each one arrived as NoSuchMethodException — logged, swallowed, and turned
# into an UnhandledMessage. The watch link *looked* alive: it connected,
# registered handles, and then understood nothing it was told.
#
# The symptom was a file push that failed with no reason, because the status
# messages that carry the reason are themselves parsed this way. Debug builds
# were unaffected, which is why this only appeared once the beta was signed and
# minified — the worst possible time to find it.
-keep class nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.** { *; }
-keepclassmembers class * extends nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.messages.GFDIMessage {
    public static *** parseIncoming(...);
}

# The FIT decoder reaches its own record types the same way.
-keep class nodomain.freeyourgadget.gadgetbridge.service.devices.garmin.fit.** { *; }

# Characteristic names are read off this class's own fields for logging.
-keepclassmembers class nodomain.freeyourgadget.gadgetbridge.service.btle.GattCharacteristic {
    <fields>;
}

# Protobuf-lite keeps its field layout in generated members the runtime finds
# by name; the newer file-sync protocol speaks it.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
