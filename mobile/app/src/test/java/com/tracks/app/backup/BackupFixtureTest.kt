// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.app.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone's sealing against spec/fixtures/backup.json, which an independent
 * Python implementation wrote and the web app's suite reads too. Passing here
 * and there is what makes a backup from one open on the other.
 */
class BackupFixtureTest {
    private val fixture: JsonObject = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "spec/fixtures/backup.json").exists()) dir = dir.parentFile
        Json.parseToJsonElement(File(dir!!, "spec/fixtures/backup.json").readText()).jsonObject
    }
    private fun str(key: String) = fixture.getValue(key).jsonPrimitive.content
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun b64(key: String) = Base64.getDecoder().decode(str(key))
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val pass get() = str("passphrase").toCharArray()
    private val iterations get() = fixture.getValue("iterations").jsonPrimitive.int

    private fun seal(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.sealing(out, pass, iterations, hex(str("salt")), hex(str("prefix"))).apply { write(plain); finish() }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, passphrase: CharArray = pass) =
        BackupCrypto.opening(ByteArrayInputStream(sealed), passphrase).readBytes()

    @Test
    fun the_chunk_size_is_the_one_the_fixture_was_made_with() {
        assertEquals(fixture.getValue("chunk_bytes").jsonPrimitive.int, BackupCrypto.CHUNK)
    }

    @Test
    fun the_key_is_pbkdf2_over_the_passphrase_as_utf8() {
        assertEquals(str("key"), BackupCrypto.key(pass, hex(str("salt")), iterations).encoded.toHex())
        val unicode = fixture.getValue("unicode_passphrase").jsonObject
        assertEquals(
            unicode.getValue("key").jsonPrimitive.content,
            BackupCrypto.key(unicode.getValue("passphrase").jsonPrimitive.content.toCharArray(), hex(str("salt")), iterations)
                .encoded.toHex(),
        )
    }

    /** Byte-identical at every size either side of a chunk boundary. */
    @Test
    fun seals_match_the_fixture_byte_for_byte() {
        for (case in fixture.getValue("seals").jsonArray.map { it.jsonObject }) {
            val n = case.getValue("length").jsonPrimitive.int
            val sealed = seal(ByteArray(n) { (it * 31).toByte() })
            assertEquals("length $n", case.getValue("sha256").jsonPrimitive.content, sha(sealed))
        }
    }

    @Test
    fun the_fixture_payload_seals_to_the_fixture_file_and_opens() {
        val encoded = b64("payload_encoded")
        assertArrayEquals(b64("payload_sealed"), seal(encoded))
        assertArrayEquals(encoded, open(b64("payload_sealed")))
    }

    @Test
    fun a_first_format_backup_from_the_fixture_opens() {
        assertArrayEquals(b64("payload_encoded"), open(b64("payload_sealed_v1")))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
