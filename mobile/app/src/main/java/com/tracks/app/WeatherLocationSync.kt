// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import java.time.Instant
import kotlin.math.round

/**
 * Where the phone is, for the forecasts that are not about a point somebody
 * tapped — today, the watch glance — kept in the synced `weather_location`
 * setting so the server's fallback forecast uses the same place.
 *
 * It used to be the start of the newest activity with GPS, which is where the
 * user *trained*, not where they *are*: a week into a trip, the watch still
 * showed home. The phone knows better, and already holds the location grant.
 *
 * When it is read: whenever the app comes to the foreground
 * (MainActivity.onResume), after each pull, and before each watch forecast.
 * The manifest deliberately asks for no background location, so from the
 * watch link's background service the read usually returns nothing and the
 * last stored value is used — which is why it is stored at all, rather than
 * read fresh each time.
 *
 * Privacy, decided here:
 * - Only the OS's last known fix is read. Nothing turns on the GPS chip or
 *   subscribes to updates; it costs no battery and tracks nothing over time.
 * - Rounded to two decimals (~1 km) before it is stored. A forecast resolves
 *   nothing finer, and a coordinate that syncs to the server and sits in the
 *   database should not pinpoint a front door.
 * - Only while the Weather switch is on. Turning it off clears the stored
 *   value on this phone's next check, so "Locations stay local" stays true.
 */
internal object WeatherLocationSync {

    /** A stored or phone position: rounded degrees and when the fix was taken. */
    data class Fix(val lat: Double, val lon: Double, val at: Instant)

    /** What to do to the setting: write a new value, clear it, or leave it. */
    sealed interface Change {
        data class Set(val value: Map<String, Any?>) : Change
        data object Clear : Change
    }

    /**
     * The change to make, or null to leave the row alone.
     *
     * Never while the settings row does not exist yet — as with TimezoneSync,
     * writing would create a row of this phone's own ahead of the account's.
     * A fix no newer than the stored one is skipped, so a second phone with a
     * stale cache cannot drag the location back to where it was last week;
     * and an unchanged rounded position is skipped, so standing still writes
     * nothing and syncs nothing.
     */
    fun change(stored: Fix?, phone: Fix?, enabled: Boolean, rowExists: Boolean): Change? = when {
        !rowExists -> null
        !enabled -> if (stored != null) Change.Clear else null
        phone == null -> null
        stored != null && !phone.at.isAfter(stored.at) -> null
        stored != null && stored.lat == phone.lat && stored.lon == phone.lon -> null
        else -> Change.Set(mapOf("lat" to phone.lat, "lon" to phone.lon, "at" to phone.at.toString()))
    }

    /** The synced value back as a [Fix]; null for anything malformed, which sync could deliver. */
    fun stored(value: Any?): Fix? {
        val map = value as? Map<*, *> ?: return null
        val lat = (map["lat"] as? Number)?.toDouble() ?: return null
        val lon = (map["lon"] as? Number)?.toDouble() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val at = (map["at"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
        return Fix(lat, lon, at)
    }

    fun round2(v: Double): Double = round(v * 100) / 100

    /**
     * The OS's most recent fix across every enabled provider, rounded, or null
     * without a location grant, with location off, or when Android withholds
     * it from a backgrounded app.
     */
    fun phone(context: Context): Fix? {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val best = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: SecurityException) {
            // Revoked between the check and the call, or withheld in the background.
            null
        } ?: return null
        return Fix(round2(best.latitude), round2(best.longitude), Instant.ofEpochMilli(best.time))
    }
}
