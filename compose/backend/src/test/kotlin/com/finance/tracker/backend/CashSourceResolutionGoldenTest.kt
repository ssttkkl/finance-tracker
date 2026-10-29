package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceResolutionGoldenTest {
    private fun assertWireEquals(expected: JsonElement, actual: JsonElement, context: String) {
        when (expected) {
            is JsonObject -> {
                assertIs<JsonObject>(actual, context)
                assertEquals(expected.keys, actual.keys, context)
                expected.forEach { (key, value) -> assertWireEquals(value, actual.getValue(key), "$context.$key") }
            }
            is JsonArray -> {
                assertIs<JsonArray>(actual, context)
                assertEquals(expected.size, actual.size, context)
                expected.forEachIndexed { index, value -> assertWireEquals(value, actual[index], "$context[$index]") }
            }
            is JsonPrimitive -> {
                assertIs<JsonPrimitive>(actual, context)
                if (expected == JsonNull || expected.isString || expected.booleanOrNull != null) {
                    assertEquals(expected, actual, context)
                } else {
                    assertFalse(actual.isString, context)
                    assertNull(actual.booleanOrNull, context)
                    val integer = expected.content.matches(Regex("-?[0-9]+"))
                    assertEquals(integer, actual.content.matches(Regex("-?[0-9]+")), context)
                    if (integer) assertEquals(expected.content.toBigInteger(), actual.content.toBigInteger(), context)
                    else assertEquals(expected.double.toRawBits(), actual.double.toRawBits(), context)
                }
            }
        }
    }

    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is CashAmount -> JsonPrimitive(value.fixedTransferText())
        is Instant -> JsonPrimitive(manualInstantText(value))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to wire(it.value) })
        is List<*> -> JsonArray(value.map(::wire))
        else -> error("unexpected source value")
    }
    private fun issue(value: JsonObject) = CashSourceIssue(value["id"]?.jsonPrimitive?.long ?: 1,
        value["revision"]?.jsonPrimitive?.long ?: 2, value["code"]?.jsonPrimitive?.content ?: "source_update_pending",
        value["field_path"]?.jsonPrimitive?.contentOrNull, value["pending_fields"], value["pending_source_payload"], value["origin"])
    @Test fun sourceChoicesMatchCompletePythonValuesRejectionsAndDigests() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-source-resolutions.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        for ((index, raw) in cases.withIndex()) {
            val expected = raw.jsonObject
            val input = expected.getValue("input").jsonObject
            val actual = try {
                val value = if (input.getValue("mode").jsonPrimitive.content == "pending") pendingCashSourcePatch(issue(input.getValue("issue").jsonObject))
                else {
                    val choices = input.getValue("choices").jsonArray.map { it.jsonObject.let { choice ->
                        CashSourceResolution(choice.getValue("issue_id").jsonPrimitive.content,
                            choice.getValue("expected_issue_revision").jsonPrimitive.long, choice.getValue("resolution").jsonPrimitive.content)
                    } }
                    val result = prepareCashSourceResolutions(input.getValue("current").jsonObject.mapValues { cashSourceValue(it.value) },
                        input.getValue("issues").jsonArray.map { issue(it.jsonObject) }, choices,
                        input.getValue("patch").jsonObject.mapValues { cashSourceValue(it.value) },
                        input.getValue("protected").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    buildJsonObject {
                        put("patch", wire(result.patch))
                        put("resolutions", JsonArray(result.resolutions.map { prepared -> buildJsonObject {
                            put("issue_id", prepared.choice.issueId); put("expected_issue_revision", prepared.choice.expectedRevision)
                            put("resolution", prepared.choice.resolution); put("body_digest", prepared.bodyDigest)
                        } }))
                    }
                }
                buildJsonObject { put("value", value) }
            } catch (error: CashPolicyError) {
                buildJsonObject { put("error", error.code); put("fields", JsonArray(error.fields.map(::JsonPrimitive))) }
            } catch (error: ReceiptConflict) {
                buildJsonObject { put("error", error.code); put("fields", JsonArray(emptyList())) }
            }
            assertWireEquals(JsonObject(expected - "input"), actual, "source choice $index: $input")
        }
    }
}
