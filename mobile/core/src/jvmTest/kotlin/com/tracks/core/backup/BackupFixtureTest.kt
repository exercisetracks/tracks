// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.backup

import com.tracks.core.parse.Canonical
import com.tracks.core.replica.AccountBinding
import com.tracks.core.replica.Wire
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The payload format against spec/fixtures/backup.json: written by an
 * independent Python implementation, read by the web app's suite too. The
 * phone must write exactly those bytes for that content, and read them back.
 */
class BackupFixtureTest {
    private val fixture = Json.parseToJsonElement(File(Canonical.root, "spec/fixtures/backup.json").readText()).jsonObject
    private val payload = fixture.getValue("payload").jsonObject
    private val encoded = Base64.getDecoder().decode(fixture.getValue("payload_encoded").jsonPrimitive.content)
    private val rows = payload.getValue("rows").jsonArray.map { Wire.decodeChange(it.jsonObject) }
    private val files = payload.getValue("files").jsonArray.map { it.jsonObject }.map {
        it.getValue("name").jsonPrimitive.content to it.getValue("hex").jsonPrimitive.content
            .chunked(2).map { h -> h.toInt(16).toByte() }.toByteArray()
    }
    private val binding = payload.getValue("binding").jsonObject.let {
        AccountBinding(it.getValue("server_id").jsonPrimitive.content, it.getValue("account").jsonPrimitive.content)
    }

    @Test
    fun the_phone_writes_the_fixture_payload_byte_for_byte() = runTest {
        val out = mutableListOf<Byte>()
        val bytesOf = files.toMap()
        BackupFormat.write(
            { b, off, len -> for (i in off until off + len) out += b[i] },
            binding, rows, files.map { it.first },
            payload.getValue("created_at_ms").jsonPrimitive.long,
            // The empty one stands for a blob this phone could not read.
            read = { name -> bytesOf.getValue(name).takeIf { it.isNotEmpty() } },
        )
        assertEquals(encoded.decodeToString(), out.toByteArray().decodeToString())
        assertContentEquals(encoded, out.toByteArray())
    }

    @Test
    fun the_phone_reads_the_fixture_payload() = runTest {
        val reader = BackupReader(ByteArraySource(encoded))
        assertEquals(binding, reader.binding)
        assertEquals(rows, reader.rows)
        val got = mutableListOf<Pair<String, ByteArray>>()
        reader.forEachFile { name, bytes -> got += name to bytes }
        assertEquals(files.map { it.first }, got.map { it.first })
        files.zip(got).forEach { (want, have) -> assertContentEquals(want.second, have.second) }
    }
}
