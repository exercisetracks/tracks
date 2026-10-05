// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.util;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Shim for Gadgetbridge's date helpers.
 *
 * <p>Upstream's version was vendored first and then dropped: only five of its
 * methods are reachable from the code we keep, and the two that are not —
 * duration formatting — are the only ones needing
 * {@code com.github.pfichtner:durationformatter}, which resolves through
 * JitPack. JitPack builds from a git tag on request and is not reproducible,
 * which is a problem worth avoiding given F-Droid is the eventual distribution
 * target. Five small methods are a better trade than a non-reproducible
 * repository.
 */
public final class DateTimeUtils {
    private DateTimeUtils() {}

    private static final ThreadLocal<SimpleDateFormat> DAY_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd", Locale.US));
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm", Locale.US));

    public static String formatDate(final Date date) {
        return DAY_FORMAT.get().format(date);
    }

    public static String formatDate(final Date date, final int extraFlags) {
        return formatDate(date);
    }

    public static String formatTime(final int hours, final int minutes) {
        return String.format(Locale.US, "%02d:%02d", hours, minutes);
    }

    public static String formatLocalTime(final long epochMilli) {
        return TIME_FORMAT.get().format(new Date(epochMilli));
    }

    public static boolean isYesterday(final Date date) {
        final Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        final Calendar other = Calendar.getInstance();
        other.setTime(date);
        return yesterday.get(Calendar.YEAR) == other.get(Calendar.YEAR)
                && yesterday.get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Reinterpret a UTC wall-clock time as the same wall-clock time locally.
     *
     * <p>Needed for all-day calendar events. The calendar provider stores those
     * at UTC midnight, but "all day" means midnight *where the user is* — so
     * sending the raw value puts a birthday on the wrong day for anyone not on
     * UTC. This shifts by the local offset at that instant, which is what
     * upstream does and what the watch expects.
     */
    public static long utcDateTimeToLocal(final long utcMillis) {
        return utcMillis - TimeZone.getDefault().getOffset(utcMillis);
    }

    public static long utcDateTimeToLocal(final Date date) {
        return utcDateTimeToLocal(date.getTime());
    }
}
