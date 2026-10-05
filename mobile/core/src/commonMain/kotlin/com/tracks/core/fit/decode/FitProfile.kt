// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit.decode

/**
 * The FIT profile the decoder resolves names, enums, subfields and components
 * against — fitdecode's, generated into [FitProfileData].
 *
 * The shapes here mirror fitdecode's `types.py` one for one (`BaseType`,
 * `FieldType`, `Field`, `SubField`, `ComponentField`), because the decoder is
 * a port of its reader and every rule it applies is phrased in those terms.
 * Keeping the vocabulary identical is what makes a line-by-line comparison
 * with the Python possible when the two disagree.
 */

/** Something a value can be rendered through: a base type or a named field type. */
internal sealed interface FitTypeLike {
    val name: String

    /** Enum names by value, or null when the type has none. Empty is treated as none. */
    val enum: Map<Long, String>?
}

/**
 * One of FIT's base types, with fitdecode's rule for what counts as "no value".
 *
 * [identifier] is the byte a definition message carries; [size] is the width of
 * one element (1 for strings, whose count is their byte length).
 */
internal class FitBaseType(
    override val name: String,
    val identifier: Int,
    val size: Int,
) : FitTypeLike {
    override val enum: Map<Long, String>? get() = null

    val isByte: Boolean get() = identifier == BYTE.identifier
    val isString: Boolean get() = identifier == STRING.identifier

    companion object {
        val ENUM = FitBaseType("enum", 0x00, 1)
        val SINT8 = FitBaseType("sint8", 0x01, 1)
        val UINT8 = FitBaseType("uint8", 0x02, 1)
        val SINT16 = FitBaseType("sint16", 0x83, 2)
        val UINT16 = FitBaseType("uint16", 0x84, 2)
        val SINT32 = FitBaseType("sint32", 0x85, 4)
        val UINT32 = FitBaseType("uint32", 0x86, 4)
        val STRING = FitBaseType("string", 0x07, 1)
        val FLOAT32 = FitBaseType("float32", 0x88, 4)
        val FLOAT64 = FitBaseType("float64", 0x89, 8)
        val UINT8Z = FitBaseType("uint8z", 0x0a, 1)
        val UINT16Z = FitBaseType("uint16z", 0x8b, 2)
        val UINT32Z = FitBaseType("uint32z", 0x8c, 4)
        val BYTE = FitBaseType("byte", 0x0d, 1)
        val SINT64 = FitBaseType("sint64", 0x8e, 8)
        val UINT64 = FitBaseType("uint64", 0x8f, 8)
        val UINT64Z = FitBaseType("uint64z", 0x90, 8)

        private val all = listOf(
            ENUM, SINT8, UINT8, SINT16, UINT16, SINT32, UINT32, STRING, FLOAT32, FLOAT64,
            UINT8Z, UINT16Z, UINT32Z, BYTE, SINT64, UINT64, UINT64Z,
        )

        val byIdentifier: Map<Int, FitBaseType> = all.associateBy { it.identifier }
        val byName: Map<String, FitBaseType> = all.associateBy { it.name }
    }
}

/** A named profile type (`sport`, `date_time`, …) over a base type, with its enum. */
internal class FitFieldType(
    override val name: String,
    val baseType: FitBaseType,
    override val enum: Map<Long, String>?,
) : FitTypeLike

/**
 * A component: a bit range of a field's raw value that expands into a field of
 * its own. `speed` → `enhanced_speed`, `current_activity_type_intensity` →
 * `activity_type` + `intensity`.
 *
 * [scale] and [offset] are held exactly as fitdecode tests them — falsy (null
 * or zero) means "not applied" — and [offset] keeps its integer-ness, because
 * an integer offset on an unscaled value leaves the value an integer.
 */
internal class FitComponent(
    val name: String,
    val defNum: Int,
    val scale: Double?,
    val offset: Number?,
    val accumulate: Boolean,
    val bits: Int,
    val bitOffset: Int,
)

/** What fitdecode's `_FieldAndSubFieldBase` and `DevField` have in common. */
internal sealed interface FitFieldLike {
    val name: String?
    val defNum: Int
    val type: FitTypeLike
    val scale: Double?
    val offset: Number?
    val components: List<FitComponent>
}

internal class FitSubFieldRef(val name: String, val defNum: Int, val rawValue: Long)

internal class FitSubField(
    override val name: String,
    override val defNum: Int,
    override val type: FitTypeLike,
    override val scale: Double?,
    override val offset: Number?,
    override val components: List<FitComponent>,
    val refs: List<FitSubFieldRef>,
) : FitFieldLike

internal class FitProfileField(
    override val name: String,
    override val defNum: Int,
    override val type: FitTypeLike,
    override val scale: Double?,
    override val offset: Number?,
    override val components: List<FitComponent>,
    /** In profile order — the first whose reference matches wins. */
    val subfields: List<FitSubField>,
) : FitFieldLike

/**
 * A developer field, declared by a `field_description` message in the file.
 *
 * [name] is null when the file used a field it never described — fitdecode
 * then decodes it as bytes under no name, and so must this.
 */
internal class FitDevField(
    val devDataIndex: Long?,
    override val name: String?,
    override val defNum: Int,
    override val type: FitBaseType,
    val units: String?,
    val nativeFieldNum: Long?,
) : FitFieldLike {
    override val scale: Double? get() = null
    override val offset: Number? get() = null
    override val components: List<FitComponent> get() = emptyList()
}

internal class FitMessageType(
    val num: Int,
    val name: String,
    val fields: Map<Int, FitProfileField>,
)

internal object FitProfile {

    const val MESG_NUM_FILE_ID = 0
    const val MESG_NUM_HR = 132
    const val MESG_NUM_DEVELOPER_DATA_ID = 207
    const val MESG_NUM_FIELD_DESCRIPTION = 206
    const val FIELD_NUM_TIMESTAMP = 253
    const val FIELD_NUM_HR_EVENT_TIMESTAMP = 9
    const val FIELD_NUM_HR_EVENT_TIMESTAMP_12 = 10

    private class Parsed(
        val types: Map<String, FitFieldType>,
        val messages: Map<Int, FitMessageType>,
    )

    // Lazy: the table is ~150 KB of text and a phone that never opens a FIT
    // file should never pay to parse it.
    private val parsed: Parsed by lazy { parse(FitProfileData.chunks) }

    val messages: Map<Int, FitMessageType> get() = parsed.messages
    val types: Map<String, FitFieldType> get() = parsed.types

    /**
     * fitdecode's `FIELD_TYPE_TIMESTAMP`: the field a compressed-timestamp
     * record header synthesises. A `date_time` named `timestamp`, number 253.
     */
    val timestampField: FitProfileField by lazy {
        FitProfileField(
            name = "timestamp",
            defNum = FIELD_NUM_TIMESTAMP,
            type = types.getValue("date_time"),
            scale = null,
            offset = null,
            components = emptyList(),
            subfields = emptyList(),
        )
    }

    private fun parse(chunks: List<String>): Parsed {
        val lines = chunks.flatMap { it.split('\n') }.filter { it.isNotEmpty() }

        val types = LinkedHashMap<String, FitFieldType>()
        for (line in lines) {
            if (!line.startsWith("T|")) continue
            val p = line.split('|')
            val enum = if (p[3].isEmpty()) {
                emptyMap()
            } else {
                p[3].split(',').associate {
                    val eq = it.indexOf('=')
                    it.substring(0, eq).toLong() to it.substring(eq + 1)
                }
            }
            types[p[1]] = FitFieldType(p[1], FitBaseType.byName.getValue(p[2]), enum)
        }

        fun typeRef(ref: String): FitTypeLike = when {
            ref.startsWith("b:") -> FitBaseType.byName.getValue(ref.substring(2))
            ref.startsWith("t:") -> types.getValue(ref.substring(2))
            else -> error("bad type ref $ref")
        }

        // Mutable builders, because components and subfields arrive on the
        // lines after the field (or subfield) they belong to.
        class SubBuilder(
            val name: String, val type: FitTypeLike, val scale: Double?, val offset: Number?,
            val refs: List<FitSubFieldRef>,
        ) {
            val components = ArrayList<FitComponent>()
        }

        class FieldBuilder(
            val num: Int, val name: String, val type: FitTypeLike, val scale: Double?, val offset: Number?,
        ) {
            val components = ArrayList<FitComponent>()
            val subs = ArrayList<SubBuilder>()
        }

        val messages = LinkedHashMap<Int, FitMessageType>()
        var mesgNum = -1
        var mesgName = ""
        var fields = ArrayList<FieldBuilder>()
        var componentOwner: MutableList<FitComponent>? = null

        fun flush() {
            if (mesgNum < 0) return
            val built = fields.associate { f ->
                f.num to FitProfileField(
                    name = f.name, defNum = f.num, type = f.type, scale = f.scale, offset = f.offset,
                    components = f.components.toList(),
                    subfields = f.subs.map { s ->
                        FitSubField(s.name, f.num, s.type, s.scale, s.offset, s.components.toList(), s.refs)
                    },
                )
            }
            messages[mesgNum] = FitMessageType(mesgNum, mesgName, built)
        }

        for (line in lines) {
            val p = line.split('|')
            when (p[0]) {
                "T" -> Unit
                "M" -> {
                    flush()
                    mesgNum = p[1].toInt()
                    mesgName = p[2]
                    fields = ArrayList()
                    componentOwner = null
                }
                "F" -> {
                    val f = FieldBuilder(p[1].toInt(), p[2], typeRef(p[3]), scaleOf(p[4]), offsetOf(p[5]))
                    fields.add(f)
                    componentOwner = f.components
                }
                "S" -> {
                    val refs = if (p[5].isEmpty()) {
                        emptyList()
                    } else {
                        p[5].split(';').map {
                            val r = it.split(':')
                            FitSubFieldRef(r[0], r[1].toInt(), r[2].toLong())
                        }
                    }
                    val s = SubBuilder(p[1], typeRef(p[2]), scaleOf(p[3]), offsetOf(p[4]), refs)
                    fields.last().subs.add(s)
                    componentOwner = s.components
                }
                "C" -> componentOwner!!.add(
                    FitComponent(
                        name = p[1], defNum = p[2].toInt(), scale = scaleOf(p[3]), offset = offsetOf(p[4]),
                        accumulate = p[5] == "1", bits = p[6].toInt(), bitOffset = p[7].toInt(),
                    ),
                )
                else -> error("bad profile line $line")
            }
        }
        flush()
        return Parsed(types, messages)
    }

    /** Null for absent *or zero*: fitdecode tests `if field.scale:`. */
    private fun scaleOf(text: String): Double? =
        if (text.isEmpty()) null else text.toDouble().takeIf { it != 0.0 }

    /**
     * Integer-typed when the profile's value is an integer, so an unscaled
     * integer minus an integer offset stays an integer as it does in Python.
     */
    private fun offsetOf(text: String): Number? {
        if (text.isEmpty()) return null
        val asLong = text.toLongOrNull()
        return if (asLong != null) {
            asLong.takeIf { it != 0L }
        } else {
            text.toDouble().takeIf { it != 0.0 }
        }
    }
}
