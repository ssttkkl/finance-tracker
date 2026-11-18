package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportChoiceContextTest {
    @Test fun everyOriginalContextContractRetainsItsCompleteResultOrError() {
        val cases = Json.parseToJsonElement(CashImportChoiceContextOriginalVectors.json).jsonArray
        assertEquals(36, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val before = case.toString()
            val kind = case.getValue("kind").jsonPrimitive.content
            fun objectOrNull(key: String) = case[key]?.takeUnless { it == JsonNull }?.jsonObject
            fun result(): JsonElement = when (kind) {
                "validated" -> validateLocalCashImportChoiceContext(case.getValue("input"))
                "from_plan" -> localCashImportChoiceContextFromPlan(case.getValue("input").jsonObject)
                "retained", "retained_invalid" -> retainLocalCashImportChoices(objectOrNull("context")!!, objectOrNull("plan"))
                "previous", "previous_invalid" -> assertNotNull(previousLocalCashImportChoices(objectOrNull("context")!!))
                else -> error("Unknown original case: $kind")
            }
            if (kind.endsWith("invalid") || kind == "validated" && "expected" !in case)
                assertFailsWith<CashImportChoiceContextError>("case $index") { result() }
            else assertEquals(case.getValue("expected"), result(), "case $index")
            assertEquals(before, case.toString(), "case $index must stay read-only")
        }
    }

    @Test fun generationsPreserveExactIntegerTokensAndTheirOriginalUpperBound() {
        val base = emptyContext()
        for (text in listOf("0", "9007199254740993", "9223372036854775806")) {
            val value = JsonObject(base + ("relation_choice_generations" to buildJsonObject { put("source", Json.parseToJsonElement(text)) }))
            repeat(3) { assertEquals(value, validateLocalCashImportChoiceContext(value)) }
        }
        for (text in listOf("-1", "9223372036854775807", "18446744073709551616", "1.0", "1e0", "true", "\"1\"", "null")) {
            val value = JsonObject(base + ("relation_choice_generations" to buildJsonObject { put("source", Json.parseToJsonElement(text)) }))
            assertFailsWith<CashImportChoiceContextError>(text) { validateLocalCashImportChoiceContext(value) }
        }
        for (text in listOf("0", "2", "1.0", "1e0", "true", "\"1\"", "null")) {
            assertFailsWith<CashImportChoiceContextError>(text) {
                validateLocalCashImportChoiceContext(JsonObject(base + ("version" to Json.parseToJsonElement(text))))
            }
        }
    }

    @Test fun restorationKeepsStaleExplicitClearsAndOneLatestContextWithoutSourceBodies() {
        val raw = Json.parseToJsonElement("""{
            "operations":[{"occurrence_key":"file:r1","transaction_id":null,"values":{"record_id":"record","source_payload":{"private":"body"}}}],
            "relation_groups":[],
            "invalidated_relation_choices":{"old":{"choice":{"suggestion_id":"old","action":"accept","common_fields":{"note":"","category_id":null}},"member_refs":[{"row_ref":"file:r1"}]}},
            "relation_choice_generations":{"old":9007199254740993}
        }""").jsonObject
        val outer = buildJsonObject { put("other", "preserved") }
        var restored = retainLocalCashImportChoices(outer, raw)
        val original = restored.toString()
        repeat(3) {
            restored = retainLocalCashImportChoices(restored, null)
            assertEquals(original, restored.toString())
            val context = assertNotNull(previousLocalCashImportChoices(restored))
            assertFalse(context.toString().contains("private"))
            val stale = context.getValue("invalidated_relation_choices").jsonObject.getValue("old").jsonObject
            assertEquals(JsonNull, stale.getValue("choice").jsonObject.getValue("common_fields").jsonObject["category_id"])
            assertEquals(JsonPrimitive(""), stale.getValue("choice").jsonObject.getValue("common_fields").jsonObject["note"])
        }
        val latest = retainLocalCashImportChoices(restored, buildJsonObject {
            put("operations", JsonArray(emptyList())); put("relation_groups", JsonArray(emptyList()))
        })
        assertEquals(outer.keys + CASH_IMPORT_CHOICE_CONTEXT_KEY, latest.keys)
        assertEquals(emptyContext(), previousLocalCashImportChoices(latest))
        assertEquals(outer, withoutLocalCashImportChoices(latest))
        assertNull(previousLocalCashImportChoices(outer))
    }

    @Test fun restoredInvalidationDoesNotAcceptAnOldDecisionWhenItsOriginalGroupReturns() {
        val group = Json.parseToJsonElement("""{
            "suggestion_id":"source","origin":"discovered","action":"accept",
            "allowed_actions":["accept","defer","ignore"],"edges":[],"candidates":[],"member_refs":[{"row_ref":"file:r1"}]
        }""").jsonObject
        fun plan(result: JsonObject) = buildJsonObject {
            put("operations", JsonArray(emptyList())); put("relation_groups", result.getValue("groups"))
            put("invalidated_relation_choices", result.getValue("sources"))
            put("relation_choice_generations", result.getValue("generations"))
        }
        val previous = localCashImportChoiceContextFromPlan(buildJsonObject {
            put("operations", JsonArray(emptyList())); put("relation_groups", JsonArray(listOf(group)))
        })
        val choices = Json.parseToJsonElement("""[{"suggestion_id":"source","action":"accept"}]""").jsonArray
        val disappeared = applyLocalCashImportRelationChoices(JsonArray(emptyList()), choices, previous)
        val saved = retainLocalCashImportChoices(JsonObject(emptyMap()), plan(disappeared))
        val recovered = previousLocalCashImportChoices(Json.parseToJsonElement(saved.toString()).jsonObject)
        val returned = applyLocalCashImportRelationChoices(JsonArray(listOf(group)), choices, recovered)
        val current = returned.getValue("groups").jsonArray.single().jsonObject
        assertEquals("defer", current.getValue("action").jsonPrimitive.content)
        assertNotEquals("source", current.getValue("suggestion_id").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), current.getValue("choice_generation"))
        assertEquals("source", returned.getValue("invalidated").jsonArray.single().jsonObject.getValue("reference").jsonPrimitive.content)
    }

    @Test fun computedPlanExtractionDoesNotSerializeValuesAndCopiesReferenceContainers() {
        val members = mutableListOf<Any?>(mutableMapOf("row_ref" to "file:r1"))
        val groups = mutableListOf<Any?>(mutableMapOf<String, Any?>("suggestion_id" to "source", "member_refs" to members,
            "candidates" to emptyList<Any?>()))
        val plan = mapOf("operations" to listOf(mapOf("occurrence_key" to "file:r1", "values" to mapOf(
            "record_id" to "record", "amount" to CashAmount.parse("-1.2300"), "source_payload" to Any()))), "relation_groups" to groups)
        val context = localCashImportChoiceContextFromPlan(plan)
        val encoded = encodeLocalCashValue(context)
        members.clear(); groups.clear()
        assertEquals(encoded, encodeLocalCashValue(context))
        assertEquals("record", encoded.jsonObject.getValue("operations").jsonArray.single().jsonObject
            .getValue("values").jsonObject.getValue("record_id").jsonPrimitive.content)
        assertFalse(encoded.toString().contains("source_payload"))
        assertFalse(encoded.toString().contains("amount"))
    }

    @Test fun unrelatedContextFieldsKeepTheirOriginalJsonNumbersAndAreNotAliased() {
        val metadata = mutableMapOf<String, JsonElement>("numbers" to Json.parseToJsonElement(
            """[9007199254740993,1.000000000000000001,1e400,-0,1.2300,1e-400]"""))
        val input = JsonObject(mapOf("other" to JsonObject(metadata), CASH_IMPORT_CHOICE_CONTEXT_KEY to emptyContext()))
        val retained = retainLocalCashImportChoices(input, null)
        val without = withoutLocalCashImportChoices(input)
        val replacement = retainLocalCashImportChoices(input, buildJsonObject {
            put("operations", JsonArray(emptyList())); put("relation_groups", JsonArray(emptyList()))
        })
        metadata.clear()
        for (result in listOf(retained, without, replacement)) {
            val numbers = result.getValue("other").jsonObject.getValue("numbers").jsonArray
            assertEquals(listOf("9007199254740993", "1.000000000000000001", "1e400", "-0", "1.2300", "1e-400"),
                numbers.map { it.jsonPrimitive.content })
        }
        assertEquals(emptyContext(), previousLocalCashImportChoices(retained))
    }

    @Test fun ignoredPlanBodiesAndUnknownContextFieldsAreNotRecursivelyDecodedBeforeValidation() {
        var ignored: JsonElement = JsonPrimitive("private")
        repeat(10_000) { ignored = JsonObject(mapOf("nested" to ignored)) }
        val plan = buildJsonObject {
            put("operations", buildJsonArray { add(buildJsonObject {
                put("occurrence_key", "file:r1"); put("values", buildJsonObject {
                    put("record_id", "record"); put("source_payload", ignored)
                })
            }) })
            put("relation_groups", JsonArray(emptyList()))
        }
        val context = localCashImportChoiceContextFromPlan(plan)
        assertEquals("record", context.getValue("operations").jsonArray.single().jsonObject
            .getValue("values").jsonObject.getValue("record_id").jsonPrimitive.content)
        assertFailsWith<CashImportChoiceContextError> {
            validateLocalCashImportChoiceContext(JsonObject(emptyContext() + ("unknown" to ignored)))
        }
        assertFailsWith<CashImportChoiceContextError> {
            retainLocalCashImportChoices(JsonObject(mapOf(CASH_IMPORT_CHOICE_CONTEXT_KEY to
                JsonObject(emptyContext() + ("unknown" to ignored)))), null)
        }
    }

    private fun emptyContext() = Json.parseToJsonElement("""{
        "version":1,"operations":[],"relation_groups":[],"invalidated_relation_choices":{},"relation_choice_generations":{}
    }""").jsonObject
}
