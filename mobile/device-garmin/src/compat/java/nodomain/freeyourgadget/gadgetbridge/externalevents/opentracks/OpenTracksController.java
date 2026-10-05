// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.externalevents.opentracks;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shim for Gadgetbridge's OpenTracks integration.
 *
 * <p>Upstream, pressing "start workout" on the watch can drive the OpenTracks
 * recording app on the phone. Tracks records on the watch and imports the FIT
 * file afterwards, so there is no phone-side recording session to control.
 *
 * <p>Left as a no-op rather than removed because the watch may still send the
 * button press, and the log line makes that visible when debugging.
 */
public final class OpenTracksController {
    private static final Logger LOG = LoggerFactory.getLogger(OpenTracksController.class);

    private OpenTracksController() {}

    public static void startRecording(final Context context) {
        LOG.debug("Watch asked to start phone recording; Tracks records on the watch");
    }

    public static void stopRecording(final Context context) {
        LOG.debug("Watch asked to stop phone recording; Tracks records on the watch");
    }

    public static void toggleRecording(final Context context) {
        LOG.debug("Watch asked to toggle phone recording; Tracks records on the watch");
    }
}
