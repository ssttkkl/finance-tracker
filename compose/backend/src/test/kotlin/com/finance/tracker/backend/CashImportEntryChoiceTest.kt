package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportEntryChoiceTest {
    private val golden: JsonObject by lazy {
        Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-entry-choices.json")!!
            .bufferedReader().use { it.readText() }).jsonObject
    }
    private fun edge() = linkedMapOf<String, Any?>("id" to "e1", "kind" to "payment_mirror",
        "subtype" to "not_applicable", "primary_transaction_id" to 1001, "secondary_transaction_id" to 1002)
    private fun snapshot() = EntryTargetSnapshot(
        EntryTargetContext(listOf(edge()),
            listOf(listOf(1001, mapOf("state" to "draft", "amount" to "1.00")),
                listOf(1002, mapOf("state" to "posted", "amount" to "-1.00"))),
            listOf(listOf("entry-1", mapOf("revision" to 2))),
            listOf(listOf("rel-1", mapOf("state" to "confirmed")))),
        mapOf("1001" to mapOf("row_refs" to listOf("f:r1", "f:r2"))),
        mapOf("e1" to edge()), mapOf("1001" to setOf("e1"), "1002" to setOf("e1")))
    private fun groups() = listOf(
        mapOf("suggestion_id" to "sg1", "action" to "accept", "parent_ids" to listOf("1001", "1002")),
        mapOf("suggestion_id" to "sg2", "action" to "defer", "parent_ids" to listOf("1002")),
        mapOf("suggestion_id" to "sg3", "action" to "ignore", "parent_ids" to listOf("1001")),
        mapOf("suggestion_id" to "sg4", "action" to "unknown", "parent_ids" to listOf("1001")))

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): MutableMap<String, Any?> = value as MutableMap<String, Any?>

    @Test fun entryTargetsAndChoicesMatchPython() {
        val snapshot = snapshot()
        val index = CashImportEntryChoices.index(snapshot, groups())
        val (identifier, target) = CashImportEntryChoices.target(snapshot, listOf("1001", "1002"), index)
        assertEquals(golden.getValue("target").jsonObject.getValue("id").jsonPrimitive.content, identifier)
        assertEquals(golden.getValue("target").jsonObject.getValue("value"), sourceJson(target))
        val (singleId, single) = CashImportEntryChoices.target(snapshot, listOf("1002"), index)
        assertEquals(golden.getValue("single").jsonObject.getValue("id").jsonPrimitive.content, singleId)
        assertEquals(golden.getValue("single").jsonObject.getValue("value"), sourceJson(single))

        val plan = map(cashSourceValue(golden.getValue("plan")))
        val choices = cashSourceValue(golden.getValue("choices")) as List<Any?>
        CashImportEntryChoices.apply(plan, choices, cashSourceValue(golden.getValue("plan")) as Map<String, Any?>) { it == "cat" }
        assertEquals(golden.getValue("applied"), sourceJson(plan))

        val changedPlan = map(cashSourceValue(golden.getValue("plan")))
        val previous = map(cashSourceValue(golden.getValue("plan")))
        @Suppress("UNCHECKED_CAST")
        val targets = previous["entry_targets"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        map(targets[identifier] as Map<*, *>)["parent_ids"] = listOf("999")
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            CashImportEntryChoices.apply(changedPlan, choices.take(1), previous) { true }
        }.code)

        val categoryPlan = map(cashSourceValue(golden.getValue("plan")))
        assertEquals("category_unavailable", assertFailsWith<CashImportPlanError> {
            CashImportEntryChoices.apply(categoryPlan, choices, cashSourceValue(golden.getValue("plan")) as Map<String, Any?>) { false }
        }.code)

        val invalidPlan = map(cashSourceValue(golden.getValue("plan")))
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> {
            CashImportEntryChoices.apply(invalidPlan, listOf(mapOf("edit_ref" to identifier, "patch" to mapOf("amount" to "1"))),
                cashSourceValue(golden.getValue("plan")) as Map<String, Any?>) { true }
        }.code)
    }

    @Test fun numericEdgesSortBeforeHashingAndComputedAmountsMatchFrozenTargets() {
        val first = mapOf<String, Any?>("id" to 2, "applied_amount" to CashAmount.parse("10.00"))
        val second = mapOf<String, Any?>("id" to 10, "applied_amount" to CashAmount.parse("1E+1"))
        val snapshot = EntryTargetSnapshot(EntryTargetContext(emptyList(), emptyList(), emptyList(), emptyList()),
            emptyMap(), mapOf("10" to second, "2" to first), mapOf("1" to setOf("10", "2")))
        val (reference, target) = CashImportEntryChoices.target(snapshot, listOf("1"), CashImportEntryChoices.index(snapshot, emptyList()))
        val frozen = target + ("final_edges" to listOf(first + ("applied_amount" to "10.00"), second + ("applied_amount" to "1E+1")))
        assertEquals(listOf(first, second), target["final_edges"])
        assertEquals("ie_" + sourceDigest("cash-import-entry-v1", frozen), reference)
        val detail = linkedMapOf<String, Any?>("entry" to linkedMapOf("edit_ref" to reference, "fields" to linkedMapOf<String, Any?>()))
        val result = linkedMapOf<String, Any?>("entry_targets" to mapOf(reference to target),
            "items" to listOf(mapOf("preview_item_id" to "pi_1", "display" to linkedMapOf<String, Any?>("note" to ""))),
            "item_details" to linkedMapOf("pi_1" to detail))
        CashImportEntryChoices.apply(result, listOf(mapOf("edit_ref" to reference, "patch" to mapOf("note" to "已核对"))),
            mapOf("entry_targets" to mapOf(reference to frozen))) { true }
        assertEquals("已核对", map(map(detail["entry"])["fields"])["note"])
    }
}
