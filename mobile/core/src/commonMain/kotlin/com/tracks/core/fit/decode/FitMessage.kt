// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.fit.decode

/**
 * A decoded value, as fitdecode would hand it to the server's parsers.
 *
 * Kept as `Any?` rather than a sealed wrapper because the parsers treat values
 * the way Python does — "is it None", "is it truthy", "is it a number" — and a
 * wrapper would make every one of those checks a `when` over cases that do not
 * matter to the caller. The set of runtime types is closed and small:
 *
 * | Python | Kotlin |
 * |---|---|
 * | `None` | `null` |
 * | `int` | [Long] ([ULong] for a `uint64` above 2^63, which Python holds and a Long cannot) |
 * | `float` | [Double] |
 * | `str` (strings and enum names) | [String] |
 * | `bool` | [Boolean] |
 * | `tuple` (arrays, byte fields) | [List] of the above |
 * | aware `datetime` | [FitDateTime] |
 * | `datetime.time` | [FitTimeOfDay] |
 */
typealias FitValue = Any?

/** One `FieldData`: a field of a data message, native, developer, or expanded. */
class FitField internal constructor(
    internal val definition: FieldDefinition?,
    /** The profile field, subfield, or developer field — null when the profile has none. */
    internal val spec: FitFieldLike?,
    internal val parentField: FitProfileField?,
    val value: FitValue,
    val rawValue: FitValue,
) {
    /**
     * fitdecode's `FieldData.name`: the profile (or subfield, or developer)
     * name, `unknown_<n>` for a field the profile does not know, and null for a
     * developer field the file never described.
     */
    val name: String?
        get() = if (spec != null) spec.name else "unknown_$defNum"

    val defNum: Int get() = spec?.defNum ?: definition!!.defNum

    /** True for a field produced by component expansion rather than read from the file. */
    val isExpanded: Boolean get() = definition == null

    internal val typeName: String
        get() = spec?.type?.name ?: definition!!.baseType.name

    /** `FieldData.is_named` for a name. A subfield also answers to its parent's name. */
    internal fun isNamed(name: String): Boolean =
        (spec != null && spec.name == name) || (parentField != null && parentField.name == name)

    /** `FieldData.is_named` for a number — which also matches the definition's own number. */
    internal fun isNamed(num: Int): Boolean =
        (spec != null && spec.defNum == num) ||
            (parentField != null && parentField.defNum == num) ||
            (definition != null && definition.defNum == num)
}

/** One data message. Field order is fitdecode's, which lookups depend on. */
class FitDataMessage internal constructor(
    val globalMesgNum: Int,
    /** The profile name, or `unknown_<global number>`. */
    val name: String,
    val fields: List<FitField>,
    internal val hasTimeOffset: Boolean,
) {
    /**
     * The server's `parsers.utils.get`: the value of the first field answering
     * to [fieldName] — a subfield answers to its parent's name too — or null
     * when none does. Null also when the field is present with no value.
     *
     * Unknown fields (`unknown_3`) are never found this way, exactly as in
     * fitdecode; see [getByIter].
     */
    fun get(fieldName: String): FitValue = fields.firstOrNull { it.isNamed(fieldName) }?.value

    /** Whether any field answers to [fieldName]. */
    fun has(fieldName: String): Boolean = fields.any { it.isNamed(fieldName) }

    /**
     * The server's `parsers.utils.get_by_iter`: the first field whose *name* is
     * exactly [fieldName]. The only way to reach `unknown_<n>` fields.
     */
    fun getByIter(fieldName: String): FitValue = fields.firstOrNull { it.name == fieldName }?.value

    /** The server's `get_enhanced`: `enhanced_<field>` if it has a value, else `<field>`. */
    fun getEnhanced(fieldName: String): FitValue = get("enhanced_$fieldName") ?: get(fieldName)

    internal fun hasField(num: Int): Boolean = fields.any { it.isNamed(num) }

    internal fun getFields(num: Int): List<FitField> = fields.filter { it.isNamed(num) }

    override fun toString(): String = "FitDataMessage($name, ${fields.size} fields)"
}

internal class FieldDefinition(
    val field: FitFieldLike?,
    val defNum: Int,
    val baseType: FitBaseType,
    val size: Int,
    val isDev: Boolean,
)

internal class DefinitionMessage(
    val globalMesgNum: Int,
    val mesgType: FitMessageType?,
    val bigEndian: Boolean,
    val fieldDefs: List<FieldDefinition>,
    val devFieldDefs: List<FieldDefinition>,
) {
    val name: String get() = mesgType?.name ?: "unknown_$globalMesgNum"
    val allFieldDefs: List<FieldDefinition> = fieldDefs + devFieldDefs
}

/**
 * Anything fitdecode would have raised: a malformed file, a truncated one, or
 * one of the handful of type errors its reader hits on data it did not expect.
 *
 * One exception type rather than a mirror of Python's hierarchy, because the
 * server's parsers never distinguish them — an import either parsed or failed —
 * and the only caller that catches one (`can_parse`) catches everything.
 */
class FitDecodeException(message: String) : Exception(message)
