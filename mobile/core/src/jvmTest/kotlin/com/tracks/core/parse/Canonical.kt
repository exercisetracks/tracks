// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.parse

import com.tracks.core.fit.decode.CivilDate
import com.tracks.core.fit.decode.FitDataMessage
import com.tracks.core.fit.decode.FitDateTime
import com.tracks.core.fit.decode.FitReader
import com.tracks.core.fit.decode.FitTimeOfDay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * The canonical form `spec/make_fit_parse_fixtures.py` writes, reproduced
 * byte for byte so that a digest computed here equals one computed there.
 *
 * Python's side: `json.dumps(sort_keys=True, separators=(",", ":"),
 * ensure_ascii=True)` over values tagged by its `tag()`. Both halves are
 * mirrored below; a divergence in either shows up as every digest failing at
 * once, which is loud enough to be found.
 */
internal object Canonical {

    /** A number exactly as the fixture spelled it — never re-parsed, so never re-rounded. */
    class NumberText(val text: String)

    /** `tag()`: a parser's (or the decoder's) value as a JSON-ready tree. */
    fun tag(value: Any?, normalize: Boolean): Any? = when (value) {
        null, is Boolean, is String -> value
        is Long -> NumberText(value.toString())
        is Int -> NumberText(value.toString())
        is ULong -> NumberText(value.toString())
        is Double -> if (normalize && value == kotlin.math.floor(value) && !value.isInfinite() &&
            kotlin.math.abs(value) < 9007199254740992.0
        ) {
            NumberText(value.toLong().toString())
        } else {
            mapOf("\$f" to value.toRawBits().toULong().toString(16).padStart(16, '0'))
        }
        is FitDateTime -> mapOf("\$dt" to value.isoformat())
        is CivilDate -> mapOf("\$d" to value.isoformat())
        is FitTimeOfDay -> mapOf("\$t" to value.isoformat())
        is List<*> -> value.map { tag(it, normalize) }
        is Map<*, *> -> value.entries.associate { (k, v) -> Py.str(k) to tag(v, normalize) }
        else -> error("cannot canonicalise ${value::class.simpleName}: $value")
    }

    /** A fixture's JSON as the same tree, numbers kept as their text. */
    fun fromJson(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" -> true
            element.content == "false" -> false
            else -> NumberText(element.content)
        }
        is JsonArray -> element.map(::fromJson)
        is JsonObject -> element.mapValues { fromJson(it.value) }
    }

    fun write(tree: Any?): String = StringBuilder().also { write(tree, it) }.toString()

    private fun write(tree: Any?, out: StringBuilder) {
        when (tree) {
            null -> out.append("null")
            true -> out.append("true")
            false -> out.append("false")
            is NumberText -> out.append(tree.text)
            is String -> writeString(tree, out)
            is List<*> -> {
                out.append('[')
                tree.forEachIndexed { i, v -> if (i > 0) out.append(','); write(v, out) }
                out.append(']')
            }
            is Map<*, *> -> {
                out.append('{')
                // Python sorts str keys by code point; for the BMP that is
                // UTF-16 order, which is String.compareTo.
                tree.entries.sortedBy { it.key as String }.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(k as String, out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            else -> error("not a canonical tree: $tree")
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c in ' '..'~' -> out.append(c)
                else -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            }
        }
        out.append('"')
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** `message_dump()`: every data message, and — if decoding fails — a marker where it stopped. */
    fun messageDump(bytes: ByteArray): List<Any?> {
        val out = ArrayList<Any?>()
        try {
            for (m in FitReader(bytes).messages()) out.add(dumpOne(m))
        } catch (e: Exception) {
            out.add(mapOf("error" to true))
        }
        return out
    }

    private fun dumpOne(m: FitDataMessage): Map<String, Any?> = mapOf(
        "n" to m.name,
        "g" to NumberText(m.globalMesgNum.toString()),
        "f" to m.fields.map { listOf(it.name, tag(it.value, false), tag(it.rawValue, false)) },
    )

    fun parseJson(file: File): Any? = fromJson(Json.parseToJsonElement(file.readText()))

    /** The repo root, found by walking up to the directory holding `spec/fixtures`. */
    val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "spec/fixtures").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("spec/fixtures not found above ${System.getProperty("user.dir")}")
    }
}
