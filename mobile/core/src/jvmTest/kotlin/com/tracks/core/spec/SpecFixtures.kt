// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.spec

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Locating and reading the shared golden corpus.
 *
 * Extracted so the fixture suites for each spec do not each carry their own
 * copy of the repo-root walk. Lives in jvmTest for the same reason
 * [SpecFixtureTest] does: it reaches for java.io, which `commonMain` and
 * `commonTest` must never do — they have to compile for iOS, and that boundary
 * is the thing keeping the core reusable.
 */
internal object SpecFixtures {

    /**
     * Walk up from the working directory to the repo root. The module builds
     * from either `mobile/` or the repo root, and the fixtures live outside the
     * Gradle build in both cases.
     */
    fun load(name: String): JsonObject {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "spec/fixtures/$name.json")
            if (candidate.isFile) {
                return Json.parseToJsonElement(candidate.readText()).jsonObject
            }
            dir = dir.parentFile
        }
        error("spec/fixtures/$name.json not found above ${System.getProperty("user.dir")}")
    }

    /** The named array of cases, as objects. */
    fun JsonObject.cases(key: String): List<JsonObject> =
        this[key]!!.jsonArray.map { it.jsonObject }

    /** A field that the corpus may legitimately record as JSON null. */
    fun JsonObject.orNull(key: String): JsonElement? =
        this[key]?.takeUnless { it is JsonNull }

    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.strOrNull(key: String): String? = orNull(key)?.jsonPrimitive?.content
    fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.content.toInt()
    fun JsonObject.intOrNull(key: String): Int? = orNull(key)?.jsonPrimitive?.content?.toInt()
    fun JsonObject.double(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()
    fun JsonObject.doubleOrNull(key: String): Double? =
        orNull(key)?.jsonPrimitive?.content?.toDouble()
    fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBoolean()
    fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
    fun JsonObject.objOrNull(key: String): JsonObject? = orNull(key)?.jsonObject
    fun JsonObject.arr(key: String): JsonArray = this[key]!!.jsonArray

    /** A `{key: number}` map, as the corpus writes activation/totals tables. */
    fun JsonObject.numberMap(key: String): Map<String, Double> =
        obj(key).mapValues { (_, v) -> v.jsonPrimitive.content.toDouble() }

    fun JsonObject.intMap(key: String): Map<String, Int> =
        obj(key).mapValues { (_, v) -> v.jsonPrimitive.content.toInt() }
}
