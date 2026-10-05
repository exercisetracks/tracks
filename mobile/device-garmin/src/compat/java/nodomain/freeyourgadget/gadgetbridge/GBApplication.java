// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge;

import android.content.Context;
import android.content.SharedPreferences;

import nodomain.freeyourgadget.gadgetbridge.util.Prefs;

/**
 * Shim for Gadgetbridge's application singleton.
 *
 * The vendored protocol code reaches for this to get a Context and to read
 * preferences. Gadgetbridge's real one also owns its database, device service,
 * logging setup, and half its UI — none of which Tracks wants, so this provides
 * only what the vendored code actually calls.
 *
 * Deliberately NOT an Application subclass. Tracks already has one, and Android
 * permits exactly one; this is a holder that {@code TracksApplication}
 * initialises at startup.
 *
 * <p>Lives in src/compat, not src/main/java: pull-gadgetbridge.sh deletes the
 * vendored package tree wholesale before each refresh, so anything of ours
 * inside it would disappear.
 */
public class GBApplication {

    private static Context context;
    private static Prefs prefs;

    /** Called once from TracksApplication.onCreate. */
    public static void init(Context appContext) {
        context = appContext.getApplicationContext();
        prefs = new Prefs(context.getSharedPreferences(
                    context.getPackageName() + "_preferences", Context.MODE_PRIVATE));
    }

    public static Context getContext() {
        if (context == null) {
            // A vendored file reached this before init(). That is a wiring bug
            // in Tracks, and failing here names it — returning null would
            // surface as an NPE somewhere unrelated and far away.
            throw new IllegalStateException(
                    "GBApplication.init() was never called — see TracksApplication");
        }
        return context;
    }

    public static Prefs getPrefs() {
        getContext();
        return prefs;
    }

    public static SharedPreferences getDeviceSpecificSharedPrefs(String deviceAddress) {
        // Gadgetbridge keeps a preference file per paired device. Same scheme,
        // so vendored code that reads per-device settings keeps working.
        final SharedPreferences prefs = getContext().getSharedPreferences(
                "devicesettings_" + deviceAddress, Context.MODE_PRIVATE);
        seedTracksDefaults(prefs);
        return prefs;
    }

    /** Marks a per-device file as already carrying Tracks' answers. */
    private static final String SEEDED = "tracks_defaults_seeded";

    /**
     * Answer the per-device settings Tracks has no screen to ask about.
     *
     * Gadgetbridge ships a settings page per paired device, so upstream can
     * default a feature to off and rely on the user finding the switch. Tracks
     * has no such page — these preference files are created empty and stay
     * empty — which means every vendored feature gated on one sits at its
     * upstream default forever, with nothing in the UI to suggest it exists.
     *
     * That is not theoretical. {@code PREF_SYNC_CALENDAR} defaults to false in
     * ProtocolBufferHandler, which answers the watch's calendar request with an
     * empty event list and logs "calendar sync is disabled" — while
     * CalendarManager reads the same key defaulting to *true*. So the calendar
     * appeared wired end to end, held the READ_CALENDAR permission, read the
     * user's events, and delivered none of them.
     *
     * Seeded once and flagged, so this never fights a value written later —
     * whether by a future Tracks settings screen or by the user.
     */
    private static void seedTracksDefaults(SharedPreferences prefs) {
        if (prefs.getBoolean(SEEDED, false)) {
            return;
        }
        prefs.edit()
                // Tracks asks for READ_CALENDAR up front and explains why. A
                // user who granted it has already answered this question, and
                // asking again in a settings screen they cannot see is not a
                // second consent — it is just an off switch nobody can reach.
                .putBoolean(
                        nodomain.freeyourgadget.gadgetbridge.activities.devicesettings
                                .DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR,
                        true)
                .putBoolean(SEEDED, true)
                .apply();
    }

    /** Gadgetbridge gates verbose protocol logging on this. */
    public static boolean isDebug() {
        return BuildConfig.DEBUG;
    }

    /**
     * Per-device preferences, as the vendored protocol code expects them.
     *
     * @see nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs
     */
    public static nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs getDevicePrefs(
            final nodomain.freeyourgadget.gadgetbridge.impl.GBDevice device) {
        return new nodomain.freeyourgadget.gadgetbridge.util.preferences.DevicePrefs(
                getDeviceSpecificSharedPrefs(device.getAddress()), device);
    }

    /**
     * Always true: Tracks' minSdk is 26, which is Oreo.
     *
     * <p>Upstream still supports older releases, so its protocol code branches
     * on this to decide whether notification channels exist. Here the branch is
     * dead, and saying so plainly is better than leaving a runtime check that
     * can only ever go one way.
     */
    public static boolean isRunningOreoOrLater() {
        return true;
    }

    /** Android 13+, where notification posting needs a runtime permission. */
    public static boolean isRunningTiramisuOrLater() {
        return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU;
    }
}
