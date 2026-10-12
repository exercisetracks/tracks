// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.device.garmin.explore

import java.io.ByteArrayOutputStream

/**
 * Just enough protobuf to read and write the Explore sync messages, by field
 * number, with no generated schema behind it.
 *
 * Hand-rolled on purpose. The schema Tracks had for these messages is
 * Gadgetbridge's, which stubs out the fields that matter (collection pages,
 * read offsets, a collection digest's contents) and ties anything built on it
 * to Gadgetbridge's licence. Every field number used in this package comes
 * from a decoded HCI capture of the watch talking to Garmin Explore — see
 * docs/garmin-ble-protocol.md — not from that schema.
 */
internal class ProtoReader(private val buf: ByteArray) {

    /** Every field in order: number, and either a varint or a length-delimited body. */
    data class Field(val number: Int, val varint: Long?, val bytes: ByteArray?)

    val fields: List<Field> by lazy { parse() }

    fun varint(number: Int, absent: Long = 0): Long =
        fields.firstOrNull { it.number == number && it.varint != null }?.varint ?: absent

    fun has(number: Int): Boolean = fields.any { it.number == number }

    fun bytes(number: Int): ByteArray? = fields.firstOrNull { it.number == number && it.bytes != null }?.bytes

    fun message(number: Int): ProtoReader? = bytes(number)?.let(::ProtoReader)

    fun messages(number: Int): List<ProtoReader> =
        fields.filter { it.number == number && it.bytes != null }.map { ProtoReader(it.bytes!!) }

    fun string(number: Int): String? = bytes(number)?.toString(Charsets.UTF_8)

    private fun parse(): List<Field> {
        val out = mutableListOf<Field>()
        var i = 0
        while (i < buf.size) {
            val (tag, afterTag) = readVarint(buf, i)
            i = afterTag
            val number = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> readVarint(buf, i).let { (v, next) -> out += Field(number, v, null); i = next }
                2 -> {
                    val (len, start) = readVarint(buf, i)
                    val end = start + len.toInt()
                    require(len >= 0 && end <= buf.size) { "field $number overruns the message" }
                    out += Field(number, null, buf.copyOfRange(start, end))
                    i = end
                }
                // Fixed-width values are kept in [Field.varint] as their raw bits.
                1 -> { require(i + 8 <= buf.size); out += Field(number, le(i, 8), null); i += 8 }
                5 -> { require(i + 4 <= buf.size); out += Field(number, le(i, 4), null); i += 4 }
                else -> error("unsupported wire type in field $number")
            }
        }
        return out
    }

    private fun le(at: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((buf[at + k].toLong() and 0xff) shl (8 * k))
        return v
    }

    companion object {
        fun readVarint(b: ByteArray, from: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var i = from
            while (true) {
                require(i < b.size) { "truncated varint" }
                val x = b[i++].toInt() and 0xff
                result = result or ((x and 0x7f).toLong() shl shift)
                if (x < 0x80) return result to i
                shift += 7
                require(shift < 64) { "varint too long" }
            }
        }
    }
}

/** A message under construction: fields are written in the order they are added. */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(number: Int, value: Long): ProtoWriter {
        writeRaw((number.toLong() shl 3) or 0)
        writeRaw(value)
        return this
    }

    fun varint(number: Int, value: Int): ProtoWriter = varint(number, value.toLong())

    fun varint(number: Int, value: Boolean): ProtoWriter = varint(number, if (value) 1L else 0L)

    fun bytes(number: Int, value: ByteArray): ProtoWriter {
        writeRaw((number.toLong() shl 3) or 2)
        writeRaw(value.size.toLong())
        out.write(value)
        return this
    }

    fun fixed64(number: Int, value: Long): ProtoWriter {
        writeRaw((number.toLong() shl 3) or 1)
        for (i in 0 until 8) out.write(((value ushr (8 * i)) and 0xff).toInt())
        return this
    }

    fun fixed32(number: Int, value: Int): ProtoWriter {
        writeRaw((number.toLong() shl 3) or 5)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xff)
        return this
    }

    fun float(number: Int, value: Float): ProtoWriter = fixed32(number, java.lang.Float.floatToIntBits(value))

    fun string(number: Int, value: String): ProtoWriter = bytes(number, value.toByteArray(Charsets.UTF_8))

    fun message(number: Int, body: ProtoWriter): ProtoWriter = bytes(number, body.toByteArray())

    fun message(number: Int, build: ProtoWriter.() -> Unit): ProtoWriter = message(number, ProtoWriter().apply(build))

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeRaw(value: Long) {
        var v = value
        while (true) {
            if (v and 0x7fL.inv() == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
