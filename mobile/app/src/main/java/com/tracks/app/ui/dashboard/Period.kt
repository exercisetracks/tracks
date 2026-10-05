// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.ui.dashboard

import java.time.LocalDate

/**
 * The dashboard's display windows.
 *
 * A mirror of `PERIODS` in `frontend/src/components/dashboard/constants.js`,
 * and knowingly a hand-maintained one. It is not in `spec/` — where the shared
 * tables live — because the two clients would not actually diverge in a way a
 * fixture could catch: these values choose which *request* to make, and if the
 * lists drift the phone simply offers a window the browser does not. That is a
 * cosmetic mismatch, not the class of silent numeric disagreement the spec
 * mechanism exists to prevent. Add a period in both places, or neither.
 *
 * [days] null means lifetime, which is not the same as a very large window: the
 * server answers an unfiltered request from a pre-warmed cache and recomputes
 * for every other one.
 *
 * [short] is the same window named for an app bar, where four of these share a
 * line with the page title. "This year" and "Lifetime" do not fit there and
 * "1Y" and "All" do; the long form is still what a tooltip or a chart heading
 * would use.
 */
enum class Period(val label: String, val short: String, val days: Int?) {
    Lifetime("Lifetime", "All", null),
    Yearly("This year", "1Y", 365),
    Monthly("30 days", "30D", 30),
    Weekly("7 days", "7D", 7),
    ;

    /**
     * The ISO `after` date for this window, or null for lifetime.
     *
     * Computed against the device clock rather than the server's. The web app
     * does the same from the browser's, and a day's disagreement at a timezone
     * boundary changes which activities fall in a 7-day window by at most the
     * ones recorded around midnight — visible, but far less wrong than making
     * every dashboard load wait on a clock round trip.
     */
    fun afterDate(today: LocalDate = LocalDate.now()): String? =
        days?.let { today.minusDays(it.toLong()).toString() }

    companion object {
        /** Matches the web app's initial selection. */
        val default = Yearly
    }
}
