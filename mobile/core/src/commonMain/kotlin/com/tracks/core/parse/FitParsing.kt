// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.FitDataMessage
import com.tracks.core.fit.decode.FitReader

/**
 * The server's FIT parsers, on the phone.
 *
 * Ports of `backend/app/parsers/`, held to them by `spec/fixtures/fit_parse.json`
 * (see `spec/make_fit_parse_fixtures.py`). Their output is deliberately the same
 * *shape* as the Python's — nested maps with the same keys, holding the same
 * values — rather than typed models, for two reasons. It is what makes the
 * parity check a single structural comparison instead of a field-by-field
 * mapping that could itself be wrong. And the importer that consumes it is not
 * ported yet: the typed shape belongs to whatever stores these rows on the
 * phone, and inventing it here would be guessing.
 *
 * Values follow [com.tracks.core.fit.decode.FitValue]'s conventions: Long,
 * Double, String, Boolean, lists, instants and dates, and null.
 */
object FitParsing {

    /** Which parser the importer would hand these bytes to, or null for none. */
    enum class Kind(val key: String) { Activity("activity"), Sleep("sleep"), Daily("daily") }

    /**
     * The first parser that claims the file, in the importer's order
     * (`services/fit_import.py` `_PARSERS`): activity, sleep, daily health.
     */
    fun select(bytes: ByteArray): Kind? = Kind.entries.firstOrNull { canParse(it, bytes) }

    fun canParse(kind: Kind, bytes: ByteArray): Boolean = when (kind) {
        Kind.Activity -> ActivityParser.canParse(bytes)
        Kind.Sleep -> SleepParser.canParse(bytes)
        Kind.Daily -> DailyHealthParser.canParse(bytes)
    }

    /**
     * Parse with [kind]'s parser. Throws where the server's would — a file the
     * server fails to import must fail here too, not be half-salvaged.
     */
    fun parse(kind: Kind, bytes: ByteArray): Map<String, Any?> = when (kind) {
        Kind.Activity -> ActivityParser.parse(bytes)
        Kind.Sleep -> SleepParser.parse(bytes)
        Kind.Daily -> DailyHealthParser.parse(bytes)
    }
}

/** What every parser's `can_parse` shares: any failure while reading means "not mine". */
internal inline fun claims(bytes: ByteArray, decide: (Sequence<FitDataMessage>) -> Boolean): Boolean =
    try {
        decide(FitReader(bytes).messages())
    } catch (e: Exception) {
        false
    }

/** Port of `parsers/utils.py`. */
internal object ParseUtils {

    // fitdecode can hand the manufacturer back as a plain name, with no way to
    // recover its number from the value alone — hence the table.
    private val manufacturerIds = mapOf(
        "garmin" to 1L,
        "dynastream" to 15L,
        "dynastream_oem" to 18L,
        "wahoo_fitness" to 32L,
        "polar" to 14L,
        "suunto" to 8L,
        "tacx" to 89L,
        "specialized" to 63L,
    )

    /** `parse_file_id`. */
    fun parseFileId(frame: FitDataMessage): MutableMap<String, Any?> {
        val serial = frame.get("serial_number")
        val mfrRaw = frame.get("manufacturer")
        val mfrStr = Py.asStr(mfrRaw)
        val asInt = Py.asInt(mfrRaw)
        val mfrId = if (Py.truthy(asInt)) asInt else manufacturerIds[mfrStr]
        return linkedMapOf(
            "serial_number" to serial?.let(Py::str),
            "manufacturer" to mfrStr,
            "manufacturer_id" to mfrId,
            "product_name" to Py.or(frame.get("garmin_product"), frame.get("product_name")),
            "product_id" to Py.asInt(frame.get("product")),
        )
    }
}
