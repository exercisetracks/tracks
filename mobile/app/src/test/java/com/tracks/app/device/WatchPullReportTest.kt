// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A watch that lists its files and refuses every one is a failed pull, not an
 * empty one. Measured 2026-10-13: hours of syncs reported "0 files" while
 * every activity and night of sleep sat on a watch answering "failure" to each
 * request, until it was restarted.
 */
class WatchPullReportTest {

    @Test
    fun refusing_everything_fails_the_pull() {
        val report = WatchSyncRunner.pullReport(pulled = 0, refused = 49)
        assertEquals(WatchSyncRunner.Status.FAILED, report.status)
        assertEquals(true, report.detail!!.contains("restart the watch"))
    }

    @Test
    fun a_refusal_among_deliveries_is_counted_but_not_a_failure() {
        val report = WatchSyncRunner.pullReport(pulled = 37, refused = 1)
        assertEquals(WatchSyncRunner.Status.RAN, report.status)
        assertEquals("37 file(s), 1 refused", report.detail)
    }

    @Test
    fun nothing_new_is_still_nothing_new() {
        val report = WatchSyncRunner.pullReport(pulled = 0, refused = 0)
        assertEquals(WatchSyncRunner.Status.RAN, report.status)
        assertEquals("0 file(s)", report.detail)
    }
}
