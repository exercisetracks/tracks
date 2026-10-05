// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit

/**
 * A minimal FIT file writer, built to reproduce another encoder byte for byte.
 *
 * ## Why not the one already in the tree
 *
 * `device-garmin` can already write FIT files — see
 * `com.tracks.device.garmin.CourseFitEncoder`, which builds records with the
 * generated Gadgetbridge message classes and hands them to `FitFile`. That
 * path cannot express what a workout or a schedule needs:
 *
 * - **The header is hardcoded.** `FitFile(List<RecordData>)` writes protocol
 *   1.0 / profile 21117. Workout files that this watch has actually accepted
 *   carry protocol 2.0 / profile 21208, and `Schedule.fit` carries 1.0 / 21213
 *   — three different headers, none of them the one on offer.
 * - **Records are always little-endian.** Every record in a Connect schedule
 *   is big-endian.
 * - **Only profiled messages exist.** A schedule carries global message 137,
 *   which is in no public FIT profile, and two fields on `schedule` that are
 *   in no profile either. A writer that addresses fields by profile name
 *   cannot write any of them.
 *
 * So this is a writer that takes field *numbers*, sizes and base types
 * directly, and is told the header and byte order rather than choosing them.
 *
 * ## Byte-for-byte, and why that is the bar
 *
 * The backend's `app/calculators/fit_workout.py` produces files this watch
 * demonstrably imports. Nothing about a FIT file tells you *why* a watch threw
 * it away — the failure mode throughout this project has been silent — so the
 * only cheap, honest test of a second encoder is that its output is
 * indistinguishable from the first's. Every structural decision here was read
 * off real output rather than from the FIT specification, including the two
 * that a specification would not have told us:
 *
 * - **Definition reuse.** A definition already live in the local table is
 *   reused, no matter how long ago it was declared — a workout whose second
 *   exercise has the same shape as its first re-uses the first's local type
 *   rather than declaring an identical one.
 * - **Local types wrap.** There are 16 slots. The seventeenth distinct
 *   definition in a file lands in slot 0, evicting `file_id`'s — which is
 *   harmless, because that record has already been written, and is what the
 *   reference encoder does.
 *
 * Both are exercised by [com.tracks.core.fit.FitWriterTest] against captured
 * reference output.
 */
internal class FitWriter(
    private val protocolVersion: Int,
    private val profileVersion: Int,
    private val bigEndian: Boolean,
    /**
     * How many local message slots this file uses.
     *
     * Sixteen is what the FIT format allows and what the workout encoder's
     * reference uses. **One** is not a limitation but a faithful reproduction:
     * the reference schedule encoder writes every record under local type 0,
     * redeclaring it whenever the shape changes, and a schedule that declared
     * four local types would no longer be the file this watch has accepted.
     */
    private val localTypes: Int = MAX_LOCAL_TYPES,
) {

    private var body = ByteArray(256)
    private var length = 0

    /** The definition currently live in each of the 16 local message slots. */
    private val locals = arrayOfNulls<Definition>(localTypes)

    /** Round-robin allocation cursor. Wraps, evicting whatever it lands on. */
    private var nextLocal = 0

    private class Definition(val globalNumber: Int, val shape: List<FieldShape>)

    /** A field's wire footprint: what a definition record declares about it. */
    private class FieldShape(val number: Int, val size: Int, val baseType: Int) {
        override fun equals(other: Any?): Boolean =
            other is FieldShape &&
                other.number == number && other.size == size && other.baseType == baseType

        override fun hashCode(): Int = (number * 31 + size) * 31 + baseType
    }

    /**
     * Append one data record, declaring its shape first if no live local slot
     * already has it.
     *
     * Field order is the caller's order and is preserved exactly. It has no
     * meaning to a reader — FIT is self-describing — but the reference encoder
     * emits fields in the order its own step builders happen to set them, and
     * matching that is what makes the output comparable.
     */
    fun write(globalNumber: Int, fields: List<FitField>) {
        val shape = fields.map { FieldShape(it.number, it.size, it.baseType) }
        val local = localFor(globalNumber, shape)
        append(local.toByte())
        for (field in fields) field.writeTo(this)
    }

    private fun localFor(globalNumber: Int, shape: List<FieldShape>): Int {
        for (slot in 0 until localTypes) {
            val live = locals[slot] ?: continue
            if (live.globalNumber == globalNumber && live.shape == shape) return slot
        }
        val slot = nextLocal
        nextLocal = (nextLocal + 1) % localTypes
        locals[slot] = Definition(globalNumber, shape)
        writeDefinition(slot, globalNumber, shape)
        return slot
    }

    private fun writeDefinition(local: Int, globalNumber: Int, shape: List<FieldShape>) {
        append((DEFINITION_BIT or local).toByte())
        append(0)                                   // reserved
        append(if (bigEndian) 1 else 0)             // architecture
        appendShort(globalNumber)
        append(shape.size.toByte())
        for (field in shape) {
            append(field.number.toByte())
            append(field.size.toByte())
            append(field.baseType.toByte())
        }
    }

    /** The finished file: header, records, trailing CRC. */
    fun finish(): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        header[0] = HEADER_SIZE.toByte()
        header[1] = protocolVersion.toByte()
        // The header's own multi-byte fields are little-endian whatever the
        // records are — the architecture byte in a definition record governs
        // that record only.
        header[2] = (profileVersion and 0xFF).toByte()
        header[3] = ((profileVersion ushr 8) and 0xFF).toByte()
        header[4] = (length and 0xFF).toByte()
        header[5] = ((length ushr 8) and 0xFF).toByte()
        header[6] = ((length ushr 16) and 0xFF).toByte()
        header[7] = ((length ushr 24) and 0xFF).toByte()
        header[8] = '.'.code.toByte()
        header[9] = 'F'.code.toByte()
        header[10] = 'I'.code.toByte()
        header[11] = 'T'.code.toByte()
        val headerCrc = crc16(header, 0, 12)
        header[12] = (headerCrc and 0xFF).toByte()
        header[13] = ((headerCrc ushr 8) and 0xFF).toByte()

        val out = ByteArray(HEADER_SIZE + length + 2)
        header.copyInto(out, 0)
        body.copyInto(out, HEADER_SIZE, 0, length)
        val fileCrc = crc16(out, 0, HEADER_SIZE + length)
        out[HEADER_SIZE + length] = (fileCrc and 0xFF).toByte()
        out[HEADER_SIZE + length + 1] = ((fileCrc ushr 8) and 0xFF).toByte()
        return out
    }

    // ── Buffer primitives ────────────────────────────────────────────────────

    internal fun append(byte: Byte) {
        if (length == body.size) body = body.copyOf(body.size * 2)
        body[length++] = byte
    }

    internal fun append(value: Int) = append(value.toByte())

    internal fun appendBytes(bytes: ByteArray) {
        for (byte in bytes) append(byte)
    }

    /** A 16-bit value in the record byte order — used for global message numbers. */
    private fun appendShort(value: Int) {
        if (bigEndian) {
            append(((value ushr 8) and 0xFF).toByte())
            append((value and 0xFF).toByte())
        } else {
            append((value and 0xFF).toByte())
            append(((value ushr 8) and 0xFF).toByte())
        }
    }

    /** An unsigned integer of [size] bytes in the record byte order. */
    internal fun appendInt(value: Long, size: Int) {
        if (bigEndian) {
            for (shift in (size - 1) downTo 0) {
                append(((value ushr (shift * 8)) and 0xFF).toByte())
            }
        } else {
            for (shift in 0 until size) {
                append(((value ushr (shift * 8)) and 0xFF).toByte())
            }
        }
    }

    internal companion object {
        private const val HEADER_SIZE = 14
        private const val DEFINITION_BIT = 0x40

        /** The format's ceiling: a record header carries the local type in 4 bits. */
        const val MAX_LOCAL_TYPES = 16
    }
}

// ── Base types ───────────────────────────────────────────────────────────────
//
// The subset the workout, schedule and exercise-title messages actually use.
// The high bit marks a type as endian-dependent, which is why the one-byte
// types have it clear and everything wider has it set.

internal const val FIT_ENUM = 0x00
internal const val FIT_UINT8 = 0x02
internal const val FIT_STRING = 0x07
internal const val FIT_UINT16 = 0x84
internal const val FIT_UINT32 = 0x86
internal const val FIT_UINT32Z = 0x8C

/** One field of one record: what it is on the wire, and its value. */
internal sealed class FitField(val number: Int, val size: Int, val baseType: Int) {
    internal abstract fun writeTo(writer: FitWriter)
}

private class IntField(number: Int, size: Int, baseType: Int, val value: Long) :
    FitField(number, size, baseType) {
    override fun writeTo(writer: FitWriter) = writer.appendInt(value, size)
}

private class BytesField(number: Int, baseType: Int, val value: ByteArray) :
    FitField(number, value.size, baseType) {
    override fun writeTo(writer: FitWriter) = writer.appendBytes(value)
}

internal fun fitEnum(number: Int, value: Int): FitField =
    IntField(number, 1, FIT_ENUM, value.toLong())

internal fun fitUint8(number: Int, value: Int): FitField =
    IntField(number, 1, FIT_UINT8, value.toLong())

internal fun fitUint16(number: Int, value: Int): FitField =
    IntField(number, 2, FIT_UINT16, value.toLong())

internal fun fitUint32(number: Int, value: Long): FitField =
    IntField(number, 4, FIT_UINT32, value)

internal fun fitUint32z(number: Int, value: Long): FitField =
    IntField(number, 4, FIT_UINT32Z, value)

/** An array of bytes typed as `uint8`, which is how the zone array is carried. */
internal fun fitUint8Array(number: Int, value: ByteArray): FitField =
    BytesField(number, FIT_UINT8, value)

/**
 * A string field whose bytes the caller has already decided, terminator
 * included. The escape hatch for [ScheduleFit]'s plan name, which is cut to a
 * byte budget rather than a character one.
 */
internal fun fitStringBytes(number: Int, bytes: ByteArray): FitField =
    BytesField(number, FIT_STRING, bytes)

/**
 * A NUL-terminated UTF-8 string, sized to its own content.
 *
 * The reference encoder declares each string field as exactly the bytes it
 * holds plus a terminator, so two records that differ only in the length of a
 * name need two definitions. Reproducing that is the reason [FitWriter] keys
 * definition reuse on the declared size rather than on the field number alone.
 */
internal fun fitString(number: Int, value: String, maxBytes: Int): FitField {
    val bytes = truncateUtf8(value, maxBytes).encodeToByteArray()
    return BytesField(number, FIT_STRING, bytes + 0)
}

/**
 * Cut a string to at most [maxBytes] UTF-8 bytes without splitting a character.
 *
 * Character-count truncation is not a substitute, and the backend has the scar
 * to prove it: a description trimmed to 250 *characters* still handed its
 * encoder well over 250 bytes once the em-dashes were counted, and the encoder
 * refused the whole file rather than the one field.
 */
internal fun truncateUtf8(value: String, maxBytes: Int): String {
    val bytes = value.encodeToByteArray()
    if (bytes.size <= maxBytes) return value
    var end = maxBytes
    // Step back off a continuation byte so the cut lands on a character boundary.
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.decodeToString(0, end)
}

// ── CRC ──────────────────────────────────────────────────────────────────────

private val CRC_TABLE = intArrayOf(
    0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
    0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
)

/** The FIT CRC-16, over `[from, to)`. */
internal fun crc16(data: ByteArray, from: Int, to: Int): Int {
    var crc = 0
    for (i in from until to) {
        val byte = data[i].toInt() and 0xFF
        var tmp = CRC_TABLE[crc and 0xF]
        crc = (crc ushr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
        tmp = CRC_TABLE[crc and 0xF]
        crc = (crc ushr 4) and 0x0FFF
        crc = crc xor tmp xor CRC_TABLE[(byte ushr 4) and 0xF]
    }
    return crc
}
