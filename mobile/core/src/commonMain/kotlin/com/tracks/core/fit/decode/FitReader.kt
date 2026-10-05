// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit.decode

import com.tracks.core.parse.BigNat

/**
 * A FIT decoder that reads a file exactly as the server's fitdecode 0.11 does.
 *
 * ## Why a port and not a decoder
 *
 * There are good FIT decoders, including Gadgetbridge's, already in this app.
 * None of them is the one the server uses, and the server is what the phone
 * has to agree with: the same bytes must produce the same activity, the same
 * steps, the same night, on both, or a phone that parsed a ride offline and a
 * server that parsed it later would disagree about what happened. Every rule
 * below — which value counts as absent, when a scale turns an integer into a
 * float, which subfield a field resolves to, what order expanded components
 * appear in, what an unrecognised developer field is called — changes what the
 * parsers read, and each one is fitdecode's, taken from its `reader.py`,
 * `types.py`, `records.py` and `processors.py`. The method names follow
 * fitdecode's so the two can be read side by side.
 *
 * That includes the failures. Where fitdecode raises on a malformed file, this
 * throws [FitDecodeException] at the same point, so an import that fails on
 * the server fails on the phone too, rather than the phone quietly salvaging a
 * result the server never had.
 *
 * ## What is deliberately not ported
 *
 * CRC checking. fitdecode's default is `CrcCheck.WARN`: it computes the CRC,
 * emits a Python warning on a mismatch, and carries on. A warning changes
 * nothing the parsers see, so computing it here would be work with no effect.
 *
 * ## Laziness
 *
 * [messages] decodes one record at a time. That matters beyond memory: the
 * server's `can_parse` stops at the first `file_id`, so a file corrupted after
 * it is still *claimed* by a parser (whose `parse` then fails). Decoding the
 * whole file up front would fail at the corruption and claim nothing.
 */
class FitReader(private val bytes: ByteArray) {

    /** Every data message in the stream, in order, across chained FIT files. */
    fun messages(): Sequence<FitDataMessage> = sequence {
        val state = State()
        while (true) {
            if (!state.readHeader()) break
            while (state.bodyBytesLeft > 0) {
                val start = state.pos
                val message = state.readRecord()
                val consumed = state.pos - start
                // fitdecode asserts this *after* reading the record, so a
                // record that overruns the declared body is read, then fails.
                if (consumed > state.bodyBytesLeft) {
                    throw FitDecodeException("record overruns the declared body size")
                }
                state.bodyBytesLeft -= consumed
                if (message != null) yield(message)
            }
            state.readCrc()
            state.resetPerFitState()
        }
    }

    private inner class State {
        var pos = 0
        var bodyBytesLeft = 0L

        // Per-FIT-file state, reset at every header and after every CRC.
        val localMesgDefs = HashMap<Int, DefinitionMessage>()
        val localDevTypes = HashMap<Long?, DevType>()
        var compressedTsAccumulator: Any? = 0L
        val accumulators = HashMap<Int, HashMap<Int, Any?>>()
        var lastTimestamp: Any? = 0L
        var hrStartTimestamp: Any? = 0L

        fun resetPerFitState() {
            bodyBytesLeft = 0
            localMesgDefs.clear()
            localDevTypes.clear()
            compressedTsAccumulator = 0L
            accumulators.clear()
            lastTimestamp = 0L
            hrStartTimestamp = 0L
        }

        // ── Bytes ────────────────────────────────────────────────────────────

        fun readBytes(size: Int): Int {
            if (size <= 0) throw FitDecodeException("size")
            if (pos + size > bytes.size) {
                throw EofException(size, bytes.size - pos)
            }
            val at = pos
            pos += size
            return at
        }

        fun u8(at: Int): Int = bytes[at].toInt() and 0xff

        fun uint(at: Int, width: Int, bigEndian: Boolean): Long {
            var v = 0L
            for (i in 0 until width) {
                val b = u8(at + if (bigEndian) i else width - 1 - i).toLong()
                v = (v shl 8) or b
            }
            return v
        }

        // ── Header and CRC ───────────────────────────────────────────────────

        /** `_read_header`. False at a clean end of stream. */
        fun readHeader(): Boolean {
            resetPerFitState()
            val at = try {
                readBytes(12)
            } catch (e: EofException) {
                if (e.got == 0) return false
                throw FitDecodeException("file truncated? header")
            }
            val headerSize = u8(at)
            val bodySize = uint(at + 4, 4, bigEndian = false)
            val magic = bytes.copyOfRange(at + 8, at + 12)
            if (headerSize < 12 || !magic.contentEquals(FIT_MAGIC)) {
                throw FitDecodeException("not a FIT file")
            }
            val extra = headerSize - 12
            if (extra > 0) {
                if (extra < 2) throw FitDecodeException("unsupported FIT header (CRC field missing)")
                try {
                    readBytes(extra)
                } catch (e: EofException) {
                    throw FitDecodeException("truncated FIT header")
                }
                // fitdecode unpacks the extra header as exactly one uint16;
                // anything longer is a struct.error there, and so an error here.
                if (extra != 2) throw FitDecodeException("unpack requires a buffer of 2 bytes")
            }
            bodyBytesLeft = bodySize
            return true
        }

        fun readCrc() {
            try {
                readBytes(2)
            } catch (e: EofException) {
                throw FitDecodeException("missing CRC footer")
            }
        }

        // ── Records ──────────────────────────────────────────────────────────

        /** `_read_record`. Null for a definition message. */
        fun readRecord(): FitDataMessage? {
            val header = u8(readBytesOrFail(1))
            val isDefinition: Boolean
            val isDeveloper: Boolean
            val localNum: Int
            val timeOffset: Int?
            if (header and 0x80 != 0) {
                isDefinition = false
                isDeveloper = false
                localNum = (header shr 5) and 0x3
                timeOffset = header and 0x1f
            } else {
                isDefinition = header and 0x40 != 0
                isDeveloper = header and 0x20 != 0
                localNum = header and 0xf
                timeOffset = null
            }

            if (isDefinition) {
                readDefinition(localNum, isDeveloper)
                return null
            }
            val message = readData(localNum, timeOffset)
            val def = localMesgDefs.getValue(localNum)
            if (def.mesgType != null) {
                when (def.globalMesgNum) {
                    FitProfile.MESG_NUM_DEVELOPER_DATA_ID -> addDevDataId(message)
                    FitProfile.MESG_NUM_FIELD_DESCRIPTION -> addDevFieldDescription(message)
                }
            }
            return message
        }

        fun readBytesOrFail(size: Int): Int = try {
            readBytes(size)
        } catch (e: EofException) {
            throw FitDecodeException("unexpected end of file")
        }

        fun readDefinition(localNum: Int, isDeveloper: Boolean) {
            val at = readBytesOrFail(5)
            val bigEndian = u8(at + 1) != 0
            val globalNum = uint(at + 2, 2, bigEndian).toInt()
            val numFields = u8(at + 4)
            val mesgType = FitProfile.messages[globalNum]

            val fieldDefs = ArrayList<FieldDefinition>(numFields)
            repeat(numFields) {
                val f = readBytesOrFail(3)
                val defNum = u8(f)
                val size = u8(f + 1)
                val field = mesgType?.fields?.get(defNum)
                var baseType = FitBaseType.byIdentifier[u8(f + 2)] ?: FitBaseType.BYTE
                // A size that is not a whole number of elements: fitdecode
                // warns and falls back to reading the field as bytes.
                if (size % baseType.size != 0) baseType = FitBaseType.BYTE

                // Accumulating components start from zero at every definition
                // of a message that carries them. Only the field's own
                // components, not a subfield's — as in fitdecode.
                if (field != null) {
                    for (component in field.components) {
                        if (component.accumulate) {
                            accumulators.getOrPut(globalNum) { HashMap() }[component.defNum] = 0L
                        }
                    }
                }
                fieldDefs.add(FieldDefinition(field, defNum, baseType, size, isDev = false))
            }

            val devFieldDefs = ArrayList<FieldDefinition>()
            if (isDeveloper) {
                val count = u8(readBytesOrFail(1))
                repeat(count) {
                    val f = readBytesOrFail(3)
                    val defNum = u8(f)
                    val size = u8(f + 1)
                    val devIndex = u8(f + 2).toLong()
                    val field = getDevType(devIndex, defNum)
                    devFieldDefs.add(FieldDefinition(field, defNum, field.type, size, isDev = true))
                }
            }

            // Redefining a local message number is legal; the new one wins.
            localMesgDefs[localNum] = DefinitionMessage(globalNum, mesgType, bigEndian, fieldDefs, devFieldDefs)
        }

        fun readData(localNum: Int, timeOffset: Int?): FitDataMessage {
            val def = localMesgDefs[localNum]
                ?: throw FitDecodeException("local message $localNum not defined")
            val rawValues = def.allFieldDefs.map { readRawValue(def, it) }
            val fields = ArrayList<FitField>()

            for ((index, fieldDef) in def.allFieldDefs.withIndex()) {
                val raw = rawValues[index]
                var field = fieldDef.field
                var parent: FitProfileField? = null
                val decoded: Any?
                if (field != null) {
                    val resolved = resolveSubfield(field, def, rawValues)
                    field = resolved.first
                    parent = resolved.second

                    if (field.components.isNotEmpty()) {
                        val isHrEventTimestamp12 = def.globalMesgNum == FitProfile.MESG_NUM_HR &&
                            !fieldDef.isDev &&
                            fieldDef.defNum == FitProfile.FIELD_NUM_HR_EVENT_TIMESTAMP_12
                        for (component in field.components) {
                            var cmpRaw = try {
                                renderComponent(component, raw)
                            } catch (e: ComponentOutOfRange) {
                                continue
                            }
                            if (component.accumulate && cmpRaw != null) {
                                val accumulator = accumulators[def.globalMesgNum]
                                    ?: throw FitDecodeException("KeyError: accumulator ${def.globalMesgNum}")
                                if (!accumulator.containsKey(component.defNum)) {
                                    throw FitDecodeException("KeyError: accumulator ${component.defNum}")
                                }
                                cmpRaw = compressedAccumulation(cmpRaw, accumulator[component.defNum], component.bits)
                                accumulator[component.defNum] = cmpRaw
                            }
                            cmpRaw = applyScaleOffset(component.scale, component.offset, cmpRaw)
                            val target = def.mesgType!!.fields[component.defNum]
                                ?: throw FitDecodeException("KeyError: component field ${component.defNum}")
                            val (cmpField, cmpParent) = resolveSubfield(target, def, rawValues)
                            var cmpValue = render(cmpField, cmpRaw)
                            if (isHrEventTimestamp12) {
                                if (!pyGreaterThanZero(hrStartTimestamp)) {
                                    throw FitDecodeException("AssertionError: hr start timestamp")
                                }
                                cmpValue = pyAdd(cmpValue, hrStartTimestamp)
                            }
                            fields.add(FitField(null, cmpField, cmpParent, cmpValue, cmpRaw))
                        }
                    }
                    decoded = applyScaleOffset(field.scale, field.offset, render(field, raw))
                } else {
                    decoded = raw
                }

                if (fieldDef.defNum == FitProfile.FIELD_NUM_TIMESTAMP && raw != null) {
                    lastTimestamp = decoded
                    compressedTsAccumulator = raw
                } else if (def.globalMesgNum == FitProfile.MESG_NUM_HR && !fieldDef.isDev &&
                    fieldDef.defNum == FitProfile.FIELD_NUM_HR_EVENT_TIMESTAMP
                ) {
                    hrStartTimestamp = lastTimestamp
                }

                fields.add(FitField(fieldDef, field, parent, decoded, raw))
            }

            if (timeOffset != null) {
                val ts = compressedAccumulation(timeOffset.toLong(), compressedTsAccumulator, 5)
                compressedTsAccumulator = ts
                val tsField = FitProfile.timestampField
                fields.add(FitField(null, tsField, null, render(tsField, ts), ts))
            }

            val processed = fields.map(::processField)
            val message = FitDataMessage(def.globalMesgNum, def.name, processed, timeOffset != null)
            return if (def.globalMesgNum == FitProfile.MESG_NUM_HR) processHrMessage(message) else message
        }

        /** `_read_data_message_raw_values`, for one field. */
        fun readRawValue(def: DefinitionMessage, fieldDef: FieldDefinition): Any? {
            val base = fieldDef.baseType
            // Python's int(size / base.size): a developer field whose size is
            // not a whole number of elements silently leaves bytes unread,
            // which misaligns everything after it. fitdecode does exactly that.
            val count = fieldDef.size / base.size
            if (base.isString) {
                val at = readBytesOrFail(count)
                return parseString(at, count)
            }
            val at = readBytesOrFail(count * base.size)
            if (base.isByte) {
                val values = (0 until count).map { u8(at + it).toLong() }
                return if (values.all { it == 0xffL }) null else values
            }
            if (count > 1) {
                return (0 until count).map { parseScalar(base, at + it * base.size, def.bigEndian) }
            }
            return parseScalar(base, at, def.bigEndian)
        }

        fun parseScalar(base: FitBaseType, at: Int, bigEndian: Boolean): Any? {
            val u = if (base.size <= 8) uint(at, base.size, bigEndian) else 0L
            return when (base.identifier) {
                0x00, 0x02 -> if (u == 0xffL) null else u
                0x01 -> u.toByte().toLong().let { if (it == 0x7fL) null else it }
                0x83 -> u.toShort().toLong().let { if (it == 0x7fffL) null else it }
                0x84 -> if (u == 0xffffL) null else u
                0x85 -> u.toInt().toLong().let { if (it == 0x7fffffffL) null else it }
                0x86 -> if (u == 0xffffffffL) null else u
                0x88 -> Float.fromBits(u.toInt()).toDouble().let { if (it.isNaN()) null else it }
                0x89 -> Double.fromBits(u).let { if (it.isNaN()) null else it }
                0x0a, 0x8b, 0x8c -> if (u == 0L) null else u
                0x8e -> if (u == Long.MAX_VALUE) null else u
                // Python holds a uint64 as an unbounded int. Above 2^63 a Long
                // cannot, so those — and only those — come back as ULong.
                0x8f -> if (u == -1L) null else if (u < 0) u.toULong() else u
                0x90 -> if (u == 0L) null else if (u < 0) u.toULong() else u
                else -> throw FitDecodeException("unknown base type ${base.name}")
            }
        }

        /**
         * `types.parse_string`: up to the first NUL (or the whole field when
         * there is none — Garmin does not always terminate), UTF-8 with
         * replacement, and None for empty.
         */
        fun parseString(at: Int, size: Int): String? {
            var end = at
            while (end < at + size && bytes[end] != 0.toByte()) end++
            val text = bytes.decodeToString(at, end, throwOnInvalidSequence = false)
            return text.ifEmpty { null }
        }

        // ── Developer fields ─────────────────────────────────────────────────

        fun addDevDataId(message: FitDataMessage) {
            val field = message.fields.firstOrNull { it.isNamed("developer_data_index") }
            val index: Long? = if (field == null) {
                null
            } else {
                pyInt(field.rawValue)
            }
            localDevTypes[index] = DevType()
        }

        fun addDevFieldDescription(message: FitDataMessage) {
            fun raw(name: String): Pair<Boolean, Any?> {
                val field = message.fields.firstOrNull { it.isNamed(name) } ?: return false to null
                return true to field.rawValue
            }

            val (_, rawIndex) = raw("developer_data_index")
            val index = if (rawIndex != null) pyInt(rawIndex) else null
            if (!localDevTypes.containsKey(index)) {
                throw FitDecodeException("developer_data_index $index not defined")
            }
            val (_, rawNum) = raw("field_definition_number")
            val (_, rawBase) = raw("fit_base_type_id")
            val baseType = if (rawBase == null) {
                FitBaseType.BYTE
            } else {
                FitBaseType.byIdentifier[pyInt(rawBase)!!.toInt()]
                    ?: throw FitDecodeException("KeyError: base type $rawBase")
            }
            val (_, rawName) = raw("field_name")
            val (_, rawUnits) = raw("units")
            val (_, rawNative) = raw("native_field_num")

            // A description with no field number declares field "None" in
            // fitdecode, which no definition can ever reference. Keeping it
            // under a key nothing matches reproduces that.
            val defNum = (rawNum as? Long)?.toInt() ?: UNMATCHABLE_FIELD_NUM
            localDevTypes.getValue(index).fields[defNum] = FitDevField(
                devDataIndex = index,
                name = rawName as? String,
                defNum = defNum,
                type = baseType,
                units = rawUnits as? String,
                nativeFieldNum = rawNative as? Long,
            )
        }

        /** `_get_dev_type`: undeclared indexes and fields fall back to bytes. */
        fun getDevType(devIndex: Long, defNum: Int): FitDevField {
            val devType = localDevTypes.getOrPut(devIndex) { DevType() }
            return devType.fields.getOrPut(defNum) {
                FitDevField(devIndex, null, defNum, FitBaseType.BYTE, null, null)
            }
        }

        // ── Fields ───────────────────────────────────────────────────────────

        /** `_resolve_subfield`: the first subfield whose reference field holds its raw value. */
        fun resolveSubfield(
            field: FitFieldLike,
            def: DefinitionMessage,
            rawValues: List<Any?>,
        ): Pair<FitFieldLike, FitProfileField?> {
            if (field is FitProfileField && field.subfields.isNotEmpty()) {
                for (sub in field.subfields) {
                    for (ref in sub.refs) {
                        // Native field definitions only: fitdecode zips
                        // `field_defs` (not `all_field_defs`) with the raw values.
                        for ((i, fd) in def.fieldDefs.withIndex()) {
                            if (fd.defNum == ref.defNum && pyEqualsLong(rawValues[i], ref.rawValue)) {
                                return sub to field
                            }
                        }
                    }
                }
            }
            return field to null
        }

        /** `processors.DefaultDataProcessor`, applied to one field. */
        fun processField(f: FitField): FitField {
            val value = f.value
            val processed: Any? = when (f.typeName) {
                "bool" -> if (value == null) null else pyTruthy(value)
                "date_time" -> if (value != null && pyCompare(value, FIT_DATETIME_MIN) >= 0) {
                    toDateTime(value)
                } else {
                    value
                }
                "local_date_time" -> if (value == null) null else toDateTime(value)
                "localtime_into_day" -> if (value == null) null else toTimeOfDay(value)
                else -> return f
            }
            return FitField(f.definition, f.spec, f.parentField, processed, f.rawValue)
        }

        /** `process_message_hr`: event timestamps become instants, when field 10 is present. */
        fun processHrMessage(message: FitDataMessage): FitDataMessage {
            if (!message.hasField(FitProfile.FIELD_NUM_HR_EVENT_TIMESTAMP_12)) return message
            val fields = message.fields.map { f ->
                if (!f.isNamed(FitProfile.FIELD_NUM_HR_EVENT_TIMESTAMP)) {
                    f
                } else {
                    FitField(f.definition, f.spec, f.parentField, toDateTime(f.value), f.rawValue)
                }
            }
            return FitDataMessage(message.globalMesgNum, message.name, fields, message.hasTimeOffset)
        }
    }

    private class DevType {
        val fields = HashMap<Int, FitDevField>()
    }

    private class EofException(val wanted: Int, val got: Int) : Exception("EOF: wanted $wanted, got $got")

    private class ComponentOutOfRange : Exception()

    companion object {
        private val FIT_MAGIC = byteArrayOf('.'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'T'.code.toByte())

        /** 1989-12-31T00:00:00Z, FIT's epoch, as a Unix second. */
        const val FIT_UTC_REFERENCE: Long = 631_065_600L

        /** Below this a `date_time` is seconds since power-on, not an instant. */
        private const val FIT_DATETIME_MIN: Long = 0x10000000L

        private const val UNMATCHABLE_FIELD_NUM = Int.MIN_VALUE

        // ── Values, with Python's semantics ──────────────────────────────────

        /** `_FieldAndSubFieldBase.render`: an enum name when the raw value is a key. */
        internal fun render(field: FitFieldLike, raw: Any?): Any? {
            val enum = field.type.enum
            if (enum.isNullOrEmpty()) return raw
            val key: Long = when (raw) {
                is Long -> raw
                // `6.0 in {6: …}` is true in Python.
                is Double -> if (raw == kotlin.math.floor(raw) && !raw.isInfinite()) raw.toLong() else return raw
                is Boolean -> if (raw) 1L else 0L
                else -> return raw
            }
            return enum[key] ?: raw
        }

        /**
         * `_apply_scale_offset`: element-wise over arrays; a scale always
         * yields a float; an offset keeps an integer an integer.
         */
        internal fun applyScaleOffset(scale: Double?, offset: Number?, raw: Any?): Any? {
            if (raw is List<*>) return raw.map { applyScaleOffset(scale, offset, it) }
            var v: Any? = when (raw) {
                is Long, is Double -> raw
                is ULong -> raw
                is Boolean -> if (raw) 1L else 0L
                else -> return raw
            }
            if (scale != null) v = pyToDouble(v) / scale
            if (offset != null) {
                v = if (v is Long && offset is Long) v - offset else pyToDouble(v) - offset.toDouble()
            }
            return v
        }

        /**
         * `ComponentField.render`: a bit range of the raw value. Byte arrays are
         * read little-endian; the range is taken straight from the bytes so a
         * 12-byte array (hr's event_timestamp_12) needs no 96-bit integer.
         */
        internal fun renderComponent(component: FitComponent, raw: Any?): Any? {
            if (raw == null) return null
            if (raw is List<*>) {
                if (component.bitOffset != 0 && component.bitOffset >= raw.size * 8) throw ComponentOutOfRange()
                // Python folds the elements into one unbounded integer,
                // little-endian, by `(n << 8) + value` — so an element wider
                // than a byte overlaps its neighbour rather than being masked.
                // BigNat reproduces that exactly, overlap included.
                var num = BigNat.ZERO
                for (element in raw.reversed()) {
                    val v = element as? Long ?: throw FitDecodeException("TypeError: unpack $element in component")
                    if (v < 0) throw FitDecodeException("negative element in component array")
                    num = num.shl(8).plus(BigNat.of(v))
                }
                val shifted = num.shr(component.bitOffset)
                var out = 0L
                for (i in 0 until minOf(component.bits, 63)) {
                    if (shifted.bit(i)) out = out or (1L shl i)
                }
                return out
            }
            if (raw is Long) {
                val mask = if (component.bits >= 64) -1L else (1L shl component.bits) - 1
                return if (component.bitOffset >= 64) {
                    if (raw < 0) mask else 0L
                } else {
                    (raw shr component.bitOffset) and mask
                }
            }
            return raw
        }

        /** `_apply_compressed_accumulation`. */
        internal fun compressedAccumulation(raw: Any?, accumulation: Any?, bits: Int): Any? {
            val acc = accumulation as? Long ?: throw FitDecodeException("TypeError: accumulation")
            val maxValue = 1L shl bits
            val mask = maxValue - 1
            return when (raw) {
                is Long -> {
                    var base = raw + (acc and mask.inv())
                    if (raw < (acc and mask)) base += maxValue
                    base
                }
                is Double -> {
                    var base = raw + (acc and mask.inv()).toDouble()
                    if (raw < (acc and mask).toDouble()) base += maxValue.toDouble()
                    base
                }
                else -> throw FitDecodeException("TypeError: compressed accumulation of $raw")
            }
        }

        internal fun toDateTime(value: Any?): FitDateTime = when (value) {
            is Long -> FitDateTime.ofEpochSeconds(FIT_UTC_REFERENCE + value)
            is Double -> FitDateTime.ofEpochSeconds(FIT_UTC_REFERENCE + value)
            else -> throw FitDecodeException("TypeError: datetime from $value")
        }

        internal fun toTimeOfDay(value: Any?): FitTimeOfDay {
            // The `>= 86400` test comes first in fitdecode, so a float that
            // large is `time.max` where a smaller float fails `datetime.time`.
            if (value is Double && value >= 86_400.0) return FitTimeOfDay.MAX
            val v = value as? Long ?: throw FitDecodeException("TypeError: time from $value")
            if (v >= 86_400) return FitTimeOfDay.MAX
            if (v < 0) throw FitDecodeException("ValueError: hour must be in 0..23")
            return FitTimeOfDay((v / 3600).toInt(), ((v % 3600) / 60).toInt(), (v % 60).toInt())
        }

        private fun pyToDouble(v: Any?): Double = when (v) {
            is Long -> v.toDouble()
            is Double -> v
            is ULong -> v.toDouble()
            else -> throw FitDecodeException("TypeError: float($v)")
        }

        private fun pyInt(v: Any?): Long? = when (v) {
            is Long -> v
            is Double -> v.toLong()
            is ULong -> v.toLong()
            null -> throw FitDecodeException("TypeError: int(None)")
            else -> throw FitDecodeException("TypeError: int($v)")
        }

        private fun pyEqualsLong(v: Any?, target: Long): Boolean = when (v) {
            is Long -> v == target
            is Double -> v == target.toDouble()
            else -> false
        }

        private fun pyCompare(v: Any, target: Long): Int = when (v) {
            is Long -> v.compareTo(target)
            is Double -> v.compareTo(target.toDouble())
            is ULong -> 1
            else -> throw FitDecodeException("TypeError: '>=' not supported for $v")
        }

        private fun pyGreaterThanZero(v: Any?): Boolean = when (v) {
            is Long -> v > 0
            is Double -> v > 0
            is FitDateTime -> true
            else -> throw FitDecodeException("TypeError: '>' not supported for $v")
        }

        private fun pyAdd(a: Any?, b: Any?): Any? = when {
            a is Long && b is Long -> a + b
            (a is Long || a is Double) && (b is Long || b is Double) -> pyToDouble(a) + pyToDouble(b)
            else -> throw FitDecodeException("TypeError: $a + $b")
        }

        private fun pyTruthy(v: Any): Boolean = when (v) {
            is Boolean -> v
            is Long -> v != 0L
            is Double -> v != 0.0
            is String -> v.isNotEmpty()
            is List<*> -> v.isNotEmpty()
            else -> true
        }
    }
}
