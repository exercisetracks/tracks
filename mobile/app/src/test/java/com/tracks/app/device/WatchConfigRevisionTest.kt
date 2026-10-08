// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.device

import com.tracks.app.device.WatchConfigRevision.NO_SERVER
import com.tracks.app.device.WatchConfigRevision.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the number the watch compares moves, and when it must not.
 *
 * The watch applies the phone's music settings only when this number is newer
 * than the last it applied. Moving it too readily overwrites an account typed
 * on the watch every time the app opens; failing to move it leaves a changed
 * password — or a removed server — never reaching the watch.
 */
class WatchConfigRevisionTest {

    private val now = System.currentTimeMillis()
    private val salt = byteArrayOf(1, 2, 3, 4)
    private val login = WatchConfigRevision.fingerprint(salt, "https://music.example.com", "alex", "hunter2")

    @Test
    fun `the revision does not move while the settings stay the same`() {
        // The phone re-arms its answer whenever the user re-sends the login. If
        // that moved the number, the watch would take the phone's account over
        // one typed on the wrist every time.
        val first = WatchConfigRevision.reconcile(State(), login, now)

        val again = WatchConfigRevision.reconcile(first, login, now + 60_000)

        assertEquals(first, again)
    }

    @Test
    fun `a changed password moves the revision`() {
        val before = WatchConfigRevision.reconcile(State(), login, now)
        val changed = WatchConfigRevision.fingerprint(salt, "https://music.example.com", "alex", "correct horse")

        val after = WatchConfigRevision.reconcile(before, changed, now + 1_000)

        assertTrue(after.revision > before.revision)
    }

    @Test
    fun `removing the music server moves the revision`() {
        // Otherwise the watch never hears the server went, and keeps a login
        // the user meant to revoke.
        val before = WatchConfigRevision.reconcile(State(), login, now)

        val after = WatchConfigRevision.reconcile(before, NO_SERVER, now + 1_000)

        assertTrue(after.revision > before.revision)
        assertEquals(NO_SERVER, after.fingerprint)
    }

    @Test
    fun `a fresh install with no music server stays at revision zero`() {
        // Revision 0 is never announced to the watch. A phone that has simply
        // not been set up yet must not sign out a watch, and delete its
        // music, just by being asked.
        val after = WatchConfigRevision.reconcile(State(), NO_SERVER, now)

        assertEquals(0L, after.revision)
        assertEquals(NO_SERVER, after.fingerprint)
    }

    @Test
    fun `a fresh install with a music server gets a revision the watch will take`() {
        val after = WatchConfigRevision.reconcile(State(), login, now)

        assertTrue(after.revision > 0L)
    }

    @Test
    fun `sending a selection moves the revision though the login is unchanged`() {
        val before = WatchConfigRevision.reconcile(State(), login, now)

        val after = WatchConfigRevision.bump(before, now + 1_000)

        assertTrue(after.revision > before.revision)
        assertEquals(before.fingerprint, after.fingerprint)
    }

    @Test
    fun `the revision keeps rising when the clock goes back`() {
        // A phone whose clock is corrected backwards must not hand out a
        // number the watch has already applied.
        val before = State(revision = now, fingerprint = login)

        val after = WatchConfigRevision.bump(before, now - 3_600_000)

        assertEquals(now + 1, after.revision)
    }

    @Test
    fun `a reinstalled phone still outranks what the watch last applied`() {
        // Clearing the app's data restarts the stored state at nothing. A
        // counter would restart at 1 and stay below what the watch holds; a
        // time-based revision does not.
        val appliedBeforeReinstall = WatchConfigRevision.reconcile(State(), login, now - 86_400_000).revision

        val afterReinstall = WatchConfigRevision.reconcile(State(), login, now).revision

        assertTrue(afterReinstall > appliedBeforeReinstall)
    }

    @Test
    fun `the fingerprint depends on the install's salt`() {
        val elsewhere = WatchConfigRevision.fingerprint(byteArrayOf(9, 9, 9, 9), "https://music.example.com", "alex", "hunter2")

        assertNotEquals(login, elsewhere)
    }

    @Test
    fun `moving a character between fields changes the fingerprint`() {
        val a = WatchConfigRevision.fingerprint(salt, "https://m", "ab", "c")
        val b = WatchConfigRevision.fingerprint(salt, "https://m", "a", "bc")

        assertNotEquals(a, b)
    }

    @Test
    fun `a trailing slash on the address is not a change`() {
        // The watch trims it too; the same server written two ways must not
        // count as a new one and move the revision.
        val slash = WatchConfigRevision.fingerprint(salt, "https://music.example.com/", "alex", "hunter2")

        assertEquals(login, slash)
    }
}
