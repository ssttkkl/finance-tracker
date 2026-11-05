package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportEntryChoiceRulesTest {
    private val golden get() = Json.parseToJsonElement(CashEntryChoiceOriginalVectors.json).jsonObject
    @Test fun localPublicFieldChoicesReplayTheCompleteOriginalPlanWithoutMutatingIt() {
        val original = golden.getValue("plan").jsonObject
        val applied = applyLocalCashImportEntryChoices(original, golden.getValue("choices"), original, setOf("cat"))
        assertEquals(golden.getValue("applied"), applied)
        assertEquals(golden.getValue("plan"), original)
        assertEquals(applied, applyLocalCashImportEntryChoices(original, golden.getValue("choices"), original, setOf("cat")))
    }
    @Test fun staleDependenciesMissingCategoriesAndForbiddenFieldsRemainExplicitFailures() {
        val original = golden.getValue("plan").jsonObject
        val reference = golden.getValue("target").jsonObject.getValue("id").jsonPrimitive.content
        fun error(code: String, choices: JsonElement = golden.getValue("choices"), previous: JsonObject? = original,
            categories: Set<String> = setOf("cat")) {
            assertEquals(code, assertFailsWith<CashImportPlanError> {
                applyLocalCashImportEntryChoices(original, choices, previous, categories)
            }.code)
            assertEquals(golden.getValue("plan"), original)
        }
        val targets = original.getValue("entry_targets").jsonObject
        val changed = JsonObject(targets.getValue(reference).jsonObject + ("parent_ids" to JsonArray(listOf(JsonPrimitive("999")))))
        error("plan_changed", previous = JsonObject(original + ("entry_targets" to JsonObject(targets + (reference to changed)))))
        error("plan_changed", previous = null)
        error("category_unavailable", categories = emptySet())
        error("plan_input_invalid", JsonArray(listOf(buildJsonObject {
            put("edit_ref", reference); put("patch", buildJsonObject { put("amount", "1") })
        })))
        error("plan_input_invalid", JsonNull)
    }
    @Test fun indexAndStableTargetRetainTheOriginalFullDependencies() {
        fun raw(value: JsonElement): Any? = decodeLocalCashValue(value)
        val expected = golden.getValue("target").jsonObject
        val value = expected.getValue("value").jsonObject
        @Suppress("UNCHECKED_CAST")
        val rows = raw(value.getValue("rows")) as List<Any?>
        val edge = mapOf<String, Any?>("id" to "e1", "kind" to "payment_mirror", "subtype" to "not_applicable",
            "primary_transaction_id" to 1001L, "secondary_transaction_id" to 1002L)
        val snapshot = EntryTargetSnapshot(EntryTargetContext(listOf(edge), rows, emptyList(), emptyList()),
            mapOf("1001" to mapOf("row_refs" to listOf("f:r1", "f:r2"))), mapOf("e1" to edge),
            mapOf("1001" to setOf("e1"), "1002" to setOf("e1")))
        val groups = listOf(
            mapOf("suggestion_id" to "sg1", "action" to "accept", "parent_ids" to listOf("1001", "1002")),
            mapOf("suggestion_id" to "sg2", "action" to "defer", "parent_ids" to listOf("1002")),
            mapOf("suggestion_id" to "sg3", "action" to "ignore", "parent_ids" to listOf("1001")),
            mapOf("suggestion_id" to "sg4", "action" to "unknown", "parent_ids" to listOf("1001")))
        val rules = CashImportEntryChoiceRules(LocalCashRecordValues, ::localCashSourceDigest)
        val (reference, target) = rules.target(snapshot, listOf("1002", "1001"), rules.index(snapshot, groups), Comparator(::pythonStringOrder))
        assertEquals(expected.getValue("id").jsonPrimitive.content, reference)
        assertEquals(value, encodeLocalCashValue(target))
        val single = rules.target(snapshot, listOf("1002"), rules.index(snapshot, groups), Comparator(::pythonStringOrder))
        assertEquals(golden.getValue("single").jsonObject.getValue("id").jsonPrimitive.content, single.first)
        assertEquals(golden.getValue("single").jsonObject.getValue("value"), encodeLocalCashValue(single.second))
    }
}
