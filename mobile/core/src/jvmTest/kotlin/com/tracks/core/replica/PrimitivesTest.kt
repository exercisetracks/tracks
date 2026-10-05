// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.replica

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The small pieces the merge stands on: digests, uids, order keys, and the
 * schema's identity.
 */
class PrimitivesTest {

    // ── Digests ─────────────────────────────────────────────────────────────

    /**
     * FIPS 180 test vectors. A hand-written digest is only acceptable because
     * of this test: a UUIDv5 computed with a subtly wrong SHA-1 would still
     * look like a UUID, and would silently disagree with every uid the server
     * derives.
     */
    @Test
    fun `the digests match the published vectors`() {
        val long = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Digest.hex(Digest.sha1("abc".encodeToByteArray())))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Digest.hex(Digest.sha1(ByteArray(0))))
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", Digest.hex(Digest.sha1(long.encodeToByteArray())))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digest.hex(Digest.sha256("abc".encodeToByteArray())),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Digest.hex(Digest.sha256(ByteArray(0))),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Digest.hex(Digest.sha256(long.encodeToByteArray())),
        )
    }

    /** Every padding boundary — 55, 56, 63, 64 bytes are where padding bugs live. */
    @Test
    fun `the digests agree with the JDK at every length up to three blocks`() {
        val random = Random(7)
        for (length in 0..200) {
            val input = random.nextBytes(length)
            assertEquals(jdk("SHA-1", input), Digest.hex(Digest.sha1(input)), "sha1 length $length")
            assertEquals(jdk("SHA-256", input), Digest.hex(Digest.sha256(input)), "sha256 length $length")
        }
    }

    private fun jdk(algorithm: String, input: ByteArray): String =
        Digest.hex(MessageDigest.getInstance(algorithm).digest(input))

    // ── Uids ────────────────────────────────────────────────────────────────

    @Test
    fun `a v7 uid carries its version, variant and time`() {
        val uid = Uids.v7(0x0192_2a8c_0000L, Random(1))
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(uid), uid)
        assertTrue(uid.startsWith("01922a8c-0000-7"), uid)
    }

    @Test
    fun `v7 uids sort by creation time`() {
        val earlier = Uids.v7(1_000_000L, Random(1))
        val later = Uids.v7(1_000_001L, Random(2))
        assertTrue(earlier < later)
    }

    /**
     * The point of natural keys: the same watch paired on two phones is one
     * row, not two.
     */
    @Test
    fun `two devices deriving a natural-key uid get the same one`() {
        val a = Uids.forEntity("device", mapOf("serial_number" to "3345678901"), nowMs = 1, random = Random(1))
        val b = Uids.forEntity("device", mapOf("serial_number" to "3345678901"), nowMs = 2, random = Random(2))
        assertEquals(a, b)
        assertEquals(Uids.v5("device:3345678901"), a)
    }

    @Test
    fun `an activity with no device serial is keyed by its file`() {
        val sha = "ab".repeat(32)
        val uid = Uids.forEntity(
            "activity",
            mapOf("device_serial" to null, "start_epoch_s" to "1727190000", "sha256" to sha),
            nowMs = 0,
        )
        assertEquals(Uids.v5("activity:sha256:$sha"), uid)
    }

    /** Minting a random uid for a natural-key row is how the duplicates would come back. */
    @Test
    fun `a natural-key entity without its key values is refused`() {
        assertFailsWith<IllegalArgumentException> { Uids.forEntity("daily_entry", emptyMap(), nowMs = 0) }
    }

    @Test
    fun `an entity without a key gets a fresh v7`() {
        val uid = Uids.forEntity("waypoint", nowMs = 0x0192_2a8c_0000L, random = Random(3))
        assertTrue(uid.startsWith("01922a8c-0000-7"), uid)
    }

    // ── Order keys ──────────────────────────────────────────────────────────

    @Test
    fun `the first key sits in the middle of the alphabet`() {
        assertEquals("V", OrderKeys.between(null, null))
    }

    /**
     * Random inserts anywhere in a list, checked against the property that
     * matters: the keys always sort in list order, and never end in '0' (which
     * would leave no room below them).
     */
    @Test
    fun `keys generated anywhere always sort in list order`() {
        val random = Random(11)
        val keys = mutableListOf<String>()
        repeat(2000) {
            val at = random.nextInt(keys.size + 1)
            val key = OrderKeys.between(keys.getOrNull(at - 1), keys.getOrNull(at))
            keys.add(at, key)
        }
        assertEquals(keys.sorted(), keys)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.none { it.endsWith('0') })
    }

    @Test
    fun `inserting at the front forever still works`() {
        var first = OrderKeys.between(null, null)
        repeat(500) {
            val next = OrderKeys.between(null, first)
            assertTrue(next < first, "$next !< $first")
            first = next
        }
    }

    @Test
    fun `a sequence is ascending`() {
        val keys = OrderKeys.sequence(100)
        assertEquals(keys.sorted(), keys)
    }

    // ── Schema identity ─────────────────────────────────────────────────────

    /**
     * An installed phone only migrates when [TracksSchema.VERSION] moves. So a
     * schema change without a version bump would leave every installed phone
     * on its old tables until it crashed. This fails whenever the SQL changes,
     * and says what to set.
     */
    @Test
    fun `the schema version was bumped for the current schema`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        TracksSchema.create(driver)
        val actual = TracksSchema.fingerprint(driver)
        assertEquals(
            TracksSchema.FINGERPRINT, actual,
            "The phone's schema changed. Bump TracksSchema.VERSION and set FINGERPRINT = \"$actual\".",
        )
    }
}
