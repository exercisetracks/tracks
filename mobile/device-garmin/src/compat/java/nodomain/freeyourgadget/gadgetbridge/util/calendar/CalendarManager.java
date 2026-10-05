// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util.calendar;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsPreferenceConst;

/**
 * Bridges the vendored GFDI calendar service to Tracks' own calendar reader.
 *
 * <p>This is one of the few shims that does real work rather than declining to.
 * Calendar sync is a feature Tracks wants, and the watch side of it is already
 * vendored; what was missing was a source of events. Tracks has one —
 * {@code com.tracks.device.feeds.CalendarReader} — so this adapts it into the
 * shape the vendored handler expects.
 *
 * <p>Crossing from the {@code nodomain} namespace into {@code com.tracks} is
 * deliberate here and rare. The alternative is a second calendar query with its
 * own idea of how recurring events expand, which is exactly the kind of
 * duplicate-source-of-truth the vendoring boundary exists to prevent.
 */
public class CalendarManager {
    private static final Logger LOG = LoggerFactory.getLogger(CalendarManager.class);

    private final Context context;
    private final String deviceAddress;

    public CalendarManager(final Context context, final String deviceAddress) {
        this.context = context;
        this.deviceAddress = deviceAddress;
    }

    /**
     * Upcoming events, or an empty list when sync is off or permission is missing.
     *
     * <p>Empty rather than throwing, and that is the important behaviour: the
     * calendar permission is optional, and a user who has not granted it should
     * get a watch that syncs workouts fine and simply shows no appointments —
     * not a sync that fails.
     */
    public List<CalendarEvent> getCalendarEventList() {
        if (!isSyncEnabled()) {
            LOG.info("Watch asked for the calendar, but calendar sync is off for this device");
            return Collections.emptyList();
        }

        final List<com.tracks.device.CalendarEvent> events;
        try {
            // Explicit window and limit rather than the Kotlin defaults:
            // default arguments are not visible from Java, and being explicit
            // documents what the watch actually receives. 64 events is well
            // past any real calendar.
            //
            // Seven days, which is upstream's default and was two here. Two is
            // defensible for a watch *face* showing "next up", but the calendar
            // glance is a list, and the difference is the difference between an
            // empty screen and a useful one: measured against a real phone
            // calendar, a two-day window contained nothing but all-day entries
            // — which the protocol handler drops unless the watch asks for them
            // — so the watch was correctly sent an empty agenda and the user
            // correctly read that as broken.
            events = com.tracks.device.feeds.CalendarReader.INSTANCE.upcoming(
                    context.getContentResolver(),
                    System.currentTimeMillis(),
                    TimeUnit.DAYS.toMillis(7),
                    64);
        } catch (final SecurityException e) {
            LOG.info("No calendar permission — syncing an empty calendar to the watch");
            return Collections.emptyList();
        } catch (final Exception e) {
            LOG.error("Could not read the calendar", e);
            return Collections.emptyList();
        }

        // Said out loud because this whole path was silent, and silence here is
        // genuinely ambiguous: an empty agenda on the wrist looks identical
        // whether the watch never asked, the permission is missing, or there is
        // simply nothing in the next week. Telling those apart from a logcat
        // took a content-provider query against the phone's calendar, which is
        // not a thing anyone should have to do twice.
        LOG.info("Watch asked for the calendar; supplying {} event(s) from the next 7 days "
                + "(the handler drops all-day entries unless the watch asked to include them)",
                events.size());

        final List<CalendarEvent> out = new ArrayList<>(events.size());
        for (final com.tracks.device.CalendarEvent e : events) {
            out.add(new CalendarEvent(
                    e.getId(),
                    e.getStartMillis(),
                    e.getEndMillis(),
                    e.getTitle(),
                    e.getLocation(),
                    null,
                    e.getAllDay(),
                    e.getColor() == null ? 0 : e.getColor()));
        }
        return out;
    }

    private boolean isSyncEnabled() {
        try {
            return GBApplication.getDeviceSpecificSharedPrefs(deviceAddress)
                    .getBoolean(DeviceSettingsPreferenceConst.PREF_SYNC_CALENDAR, true);
        } catch (final Exception e) {
            return false;
        }
    }
}
