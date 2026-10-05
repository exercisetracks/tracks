// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package nodomain.freeyourgadget.gadgetbridge.webview;

/**
 * Shim for the last known phone position.
 *
 * <p>Always empty: Tracks never reads phone GPS, for the reasons in
 * {@link nodomain.freeyourgadget.gadgetbridge.externalevents.gps.GBLocationService}.
 * A zeroed position is what upstream itself reports before any fix.
 */
public class CurrentPosition {
    public final long timestamp = 0;
    public final double latitude = 0;
    public final double longitude = 0;
    public final double altitude = 0;
    public final float accuracy = 0;
    public final float speed = 0;

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getAltitude() { return altitude; }
    public float getAccuracy() { return accuracy; }
    public float getSpeed() { return speed; }
    public long getTimestamp() { return timestamp; }

    /**
     * A zeroed location — never null, and never a real position.
     *
     * <p>This returned null until a real watch objected. The reasoning was that
     * (0, 0) is a real place in the Gulf of Guinea and null was the more honest
     * "we don't know"; the reasoning was wrong, because it read the value in
     * isolation rather than against the protocol that consumes it.
     * {@code ProtocolBufferHandler} answers a location request by checking
     * {@code lat == 0 && lon == 0} and replying {@code NO_VALID_LOCATION} — so
     * (0, 0) *is* the agreed sentinel for "no fix", and returning it produces
     * exactly the well-formed refusal we want. Null produced a
     * {@code NullPointerException} on the BLE callback thread instead.
     *
     * <p>Which makes the zeroed value the more private answer as well as the
     * more correct one: the watch is told there is no location, rather than the
     * exchange breaking in a way that invites someone to "fix" it by wiring up
     * real GPS.
     */
    public android.location.Location getLastKnownLocation() {
        // "gps" as the provider name because the field is required and the
        // watch never reads it; the coordinates are what carry the meaning.
        return new android.location.Location("gps");
    }
}
