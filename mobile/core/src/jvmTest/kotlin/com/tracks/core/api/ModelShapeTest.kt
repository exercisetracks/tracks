// SPDX-FileCopyrightText: 2026 Hawk Fugagli
// SPDX-License-Identifier: AGPL-3.0-or-later
package com.tracks.core.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The field-level half of the contract check.
 *
 * [ApiContractTest] verifies that every path the client calls exists. That is
 * necessary and demonstrably not sufficient: `PushItem` asked the server for
 * `data_b64` when the wire name has always been `fit_b64`, every path involved
 * existed, and the mismatch cost a hardware debugging session before anyone saw
 * it. The field was nullable with a default, so decoding *succeeded* — it just
 * produced an item with no bytes, and the watch sync pushed zero of eighteen
 * workouts with no exception and no log line.
 *
 * That is the failure this test exists to make impossible, and it checks the
 * two directions that actually break something:
 *
 * 1. **A declared field the server does not have.** Almost always a typo in a
 *    `@SerialName`, and always silent — `ignoreUnknownKeys` means the value
 *    simply arrives as its default forever.
 *
 * 2. **A required-in-Kotlin field the server treats as optional.** The decoder
 *    throws when it is absent, so the screen fails rather than degrading. The
 *    reverse — a server-required field this client does not model — is fine and
 *    deliberately not flagged: dropping fields we do not use is the point.
 *
 * 3. **A field declared as text that the server sends as a number**, or the
 *    reverse. Integer-vs-number really is noise — Pydantic says `integer` where
 *    kotlinx is happy with a `Double`, and nothing breaks. String-vs-number is
 *    not noise: kotlinx throws outright, so the *entire response* fails to
 *    decode, not just that field.
 *
 *    `ClimbSplit` is why this check exists. `grade_level` and `climb_result`
 *    are integers and were declared `String?`; every climbing activity's
 *    climbs therefore failed to decode, and because the detail screen defaults
 *    that call to an empty list — deliberately, so that a run costs nothing for
 *    having no climbs — the failure was completely silent. The most-recorded
 *    sport in the author's own data showed no climbs for weeks and looked like
 *    a feature nobody had written yet.
 *
 * Beyond string-vs-number, types are still not compared, for the reason above.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class ModelShapeTest {

    private val schema: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "spec/openapi.json")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("spec/openapi.json not found above ${System.getProperty("user.dir")}")
    }

    private val document: JsonObject by lazy {
        Json.parseToJsonElement(schema.readText()).jsonObject
    }

    /**
     * Each endpoint paired with the model this client decodes it into.
     *
     * Hand-maintained, and that is the cost of hand-written models — but it is
     * a list of pairs, not a schema, and a wrong entry fails loudly here rather
     * than quietly on a phone.
     */
    private val bindings: List<Triple<String, kotlinx.serialization.descriptors.SerialDescriptor, String>> = listOf(
        Triple(Endpoints.METRICS_SUMMARY, serializer<MetricsSummary>().descriptor, "MetricsSummary"),
        Triple(Endpoints.METRICS_BY_SPORT, serializer<SportBreakdown>().descriptor, "SportBreakdown"),
        Triple(Endpoints.METRICS_ACTIVITY_CALENDAR, serializer<ActivityCalendarPoint>().descriptor, "ActivityCalendarPoint"),
        Triple(Endpoints.METRICS_VO2MAX_HISTORY, serializer<Vo2MaxPoint>().descriptor, "Vo2MaxPoint"),
        Triple(Endpoints.METRICS_WEEKLY_VOLUME, serializer<WeeklyVolumePoint>().descriptor, "WeeklyVolumePoint"),
        Triple(Endpoints.METRICS_TRAINING_LOAD, serializer<TrainingLoadPoint>().descriptor, "TrainingLoadPoint"),
        Triple(Endpoints.METRICS_READINESS_HISTORY, serializer<ReadinessHistoryPoint>().descriptor, "ReadinessHistoryPoint"),
        Triple(Endpoints.COACHING_TODAY, serializer<DailyCoaching>().descriptor, "DailyCoaching"),
        Triple(Endpoints.UPCOMING_WORKOUTS, serializer<PlannedWorkout>().descriptor, "PlannedWorkout"),
        Triple(Endpoints.ACTIVITY_LAPS, serializer<Lap>().descriptor, "Lap"),
        Triple(Endpoints.ACTIVITY_CLIMBS, serializer<ClimbSplit>().descriptor, "ClimbSplit"),
        Triple(Endpoints.ACTIVITY_SETS, serializer<StrengthSet>().descriptor, "StrengthSet"),
        Triple(Endpoints.ACTIVITY_TRACK, serializer<TrackPoint>().descriptor, "TrackPoint"),
        Triple(Endpoints.CAPABILITIES, serializer<Capabilities>().descriptor, "Capabilities"),
        Triple(Endpoints.VERSION, serializer<VersionStatus>().descriptor, "VersionStatusOut"),
        Triple(Endpoints.DAILY_METRICS, serializer<DailyMetricFull>().descriptor, "DailyMetricFull"),
        Triple(Endpoints.INJURIES, serializer<Injury>().descriptor, "Injury"),
        Triple(Endpoints.MEDICATIONS, serializer<Medication>().descriptor, "Medication"),
        Triple(Endpoints.MEDICATION_LOG, serializer<MedicationLog>().descriptor, "MedicationLog"),
        Triple(Endpoints.MEALS, serializer<Meal>().descriptor, "Meal"),
        Triple(Endpoints.MEAL_LOG, serializer<MealLog>().descriptor, "MealLog"),
        Triple(Endpoints.STRENGTH_EXERCISES, serializer<Exercise>().descriptor, "Exercise"),
        Triple(Endpoints.STRENGTH_HISTORY, serializer<StrengthHistoryEntry>().descriptor, "StrengthHistoryEntry"),
        // Not bound: the flexibility library and the equipment list assemble
        // dicts rather than response models, so the server publishes no schema
        // for them and there is nothing here to check against. [Stretch]'s
        // field names came off the handler by hand.
    )

    @Test
    fun `no model declares a field the server does not send`() {
        val problems = mutableListOf<String>()
        bindings.forEach { (path, descriptor, name) ->
            val serverProps = responseProperties(path) ?: return@forEach
            (0 until descriptor.elementsCount)
                .map { descriptor.getElementName(it) }
                .filterNot { it in serverProps }
                .forEach {
                    problems += "$name.$it — not in the server's response for $path"
                }
        }
        assertTrue(
            problems.isEmpty(),
            "Fields that will silently stay at their default forever:\n" +
                problems.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `no model requires a field the server may omit`() {
        val problems = mutableListOf<String>()
        bindings.forEach { (path, descriptor, name) ->
            val required = responseRequired(path) ?: return@forEach
            (0 until descriptor.elementsCount)
                .filterNot { descriptor.isElementOptional(it) }
                .map { descriptor.getElementName(it) }
                .filterNot { it in required }
                .forEach {
                    problems += "$name.$it — has no default, but $path may omit it"
                }
        }
        assertTrue(
            problems.isEmpty(),
            "Fields whose absence will throw instead of degrading:\n" +
                problems.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `no model declares text where the server sends a number`() {
        val problems = mutableListOf<String>()
        bindings.forEach { (path, descriptor, name) ->
            val serverTypes = responsePropertyTypes(path) ?: return@forEach
            (0 until descriptor.elementsCount).forEach { index ->
                val field = descriptor.getElementName(index)
                // Absent from the map, or present as null: either way there is
                // no scalar on the server side to compare against.
                val server = serverTypes[field] ?: return@forEach
                val declared = scalarKind(descriptor.getElementDescriptor(index).kind)
                if (declared != null && declared != server) {
                    problems += "$name.$field — declared $declared, server sends $server ($path)"
                }
            }
        }
        assertTrue(
            problems.isEmpty(),
            "Type mismatches that make the whole response fail to decode:\n" +
                problems.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `every binding resolves to a schema`() {
        // Without this the two tests above pass vacuously the moment a path is
        // renamed or its response stops being JSON — which is exactly when
        // their answer matters most.
        val unresolved = bindings.filter { responseProperties(it.first) == null }.map { it.third }
        assertTrue(unresolved.isEmpty(), "no response schema found for: $unresolved")
    }

    // ── Schema walking ───────────────────────────────────────────────────────

    private fun responseSchema(path: String): JsonObject? {
        val get = document["paths"]?.jsonObject?.get(path)?.jsonObject?.get("get")?.jsonObject
            ?: return null
        val body = get["responses"]?.jsonObject?.get("200")?.jsonObject
            ?.get("content")?.jsonObject?.get("application/json")?.jsonObject
            ?.get("schema")?.jsonObject
            ?: return null
        // List endpoints wrap the model in an array; unwrap to the item.
        val resolved = deref(body) ?: return null
        return if (resolved["type"]?.jsonPrimitive?.content == "array") {
            resolved["items"]?.jsonObject?.let(::deref)
        } else {
            resolved
        }
    }

    private fun deref(node: JsonObject): JsonObject? {
        val ref = node["\$ref"]?.jsonPrimitive?.content ?: return node
        val name = ref.substringAfterLast('/')
        return document["components"]?.jsonObject?.get("schemas")?.jsonObject
            ?.get(name)?.jsonObject
    }

    private fun responseProperties(path: String): Set<String>? =
        responseSchema(path)?.get("properties")?.jsonObject?.keys

    /**
     * Each server property reduced to `text`, `number`, `boolean`, or null.
     *
     * Null means "not a scalar we can compare" — an object, an array, an enum,
     * or a union of several real types. Those are left alone rather than
     * guessed at; the point is to catch the unambiguous case, not to reimplement
     * JSON Schema.
     *
     * Nullable fields arrive as `anyOf: [{type: X}, {type: "null"}]`, so the
     * null branch is dropped before looking at what remains.
     */
    private fun responsePropertyTypes(path: String): Map<String, String?>? =
        responseSchema(path)?.get("properties")?.jsonObject?.mapValues { (_, node) ->
            val obj = node as? JsonObject ?: return@mapValues null
            val direct = obj["type"]?.jsonPrimitive?.content
            val types = if (direct != null) {
                listOf(direct)
            } else {
                obj["anyOf"]?.jsonArray
                    ?.mapNotNull { it.jsonObject["type"]?.jsonPrimitive?.content }
                    ?.filterNot { it == "null" }
                    ?: emptyList()
            }
            types.singleOrNull()?.let(::jsonScalar)
        }

    /** `integer` and `number` collapse together — that difference is the noise. */
    private fun jsonScalar(type: String): String? = when (type) {
        "string" -> "text"
        "integer", "number" -> "number"
        "boolean" -> "boolean"
        else -> null
    }

    /** The Kotlin side of the same reduction. */
    private fun scalarKind(kind: kotlinx.serialization.descriptors.SerialKind): String? = when (kind) {
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        kotlinx.serialization.descriptors.PrimitiveKind.CHAR,
        -> "text"

        kotlinx.serialization.descriptors.PrimitiveKind.INT,
        kotlinx.serialization.descriptors.PrimitiveKind.LONG,
        kotlinx.serialization.descriptors.PrimitiveKind.SHORT,
        kotlinx.serialization.descriptors.PrimitiveKind.BYTE,
        kotlinx.serialization.descriptors.PrimitiveKind.FLOAT,
        kotlinx.serialization.descriptors.PrimitiveKind.DOUBLE,
        -> "number"

        kotlinx.serialization.descriptors.PrimitiveKind.BOOLEAN -> "boolean"
        else -> null
    }

    private fun responseRequired(path: String): Set<String>? =
        responseSchema(path)?.let { schema ->
            schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        }
}
