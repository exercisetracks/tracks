// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util.calendar;

/**
 * Shim for Gadgetbridge's calendar event, adapting Tracks' own reader.
 *
 * <p>Tracks already reads the calendar in {@code com.tracks.device.feeds.CalendarReader},
 * which queries {@code CalendarContract.Instances} rather than {@code Events} —
 * the difference matters, because a recurring event is one row in Events and
 * one row per occurrence in Instances, and a watch wants occurrences.
 *
 * <p>So rather than reimplementing that query, this is a thin value type in
 * upstream's shape, populated from Tracks' reader by {@link CalendarManager}.
 */
public class CalendarEvent {
    private final long id;
    private final long begin;
    private final long end;
    private final String title;
    private final String location;
    private final String description;
    private final boolean allDay;
    private final int color;

    public CalendarEvent(final long id, final long begin, final long end, final String title,
                         final String location, final String description, final boolean allDay,
                         final int color) {
        this.id = id;
        this.begin = begin;
        this.end = end;
        this.title = title;
        this.location = location;
        this.description = description;
        this.allDay = allDay;
        this.color = color;
    }

    public long getId() { return id; }

    /** Epoch millis. */
    public long getBegin() { return begin; }

    /** Epoch millis. */
    public long getEnd() { return end; }

    /** Epoch seconds — what the GFDI calendar service speaks. */
    public int getBeginSeconds() { return (int) (begin / 1000); }

    public int getEndSeconds() { return (int) (end / 1000); }

    public String getTitle() { return title == null ? "" : title; }

    public String getLocation() { return location == null ? "" : location; }

    public String getDescription() { return description == null ? "" : description; }

    public boolean isAllDay() { return allDay; }

    public int getColor() { return color; }

    /**
     * Event organiser, or empty.
     *
     * <p>Tracks' reader does not fetch this: it is an extra column on every
     * calendar query for a field the watch truncates to a few characters, and
     * the query runs on every sync. Empty rather than null so the vendored
     * handler's length check works without a null guard.
     */
    public String getOrganizer() { return ""; }

    public long getDuration() { return end - begin; }
}
