package com.finance.tracker.backend

import kotlin.test.*

class CashImportPlanValuesTest {
    private fun values(): MutableMap<String, Any?> = linkedMapOf("note" to "来源备注", "source_payload" to mutableMapOf("备注" to "来源备注"),
        "components" to listOf(mapOf("component_id" to "18446744073709551616", "account_id" to "101", "amount" to "-1", "currency" to "CNY", "ordinal" to 0)),
        "source_explanation" to mapOf("private" to "server-only"), "preview_transaction_id" to "server-only")
    private fun operation(values: Map<String, Any?> = values(), refs: List<String> = listOf("file_1:r1")) = mapOf("operation_ref" to "op_1", "row_refs" to refs, "transaction_id" to null, "values" to values)
    private fun plan(operations: List<Map<String, Any?>> = listOf(operation())) = mapOf("operations" to operations, "detail_values" to mapOf("102" to mapOf("note" to "已存对侧", "components" to emptyList<Any?>())))
    private fun detail(reference: Map<String, Any?> = mapOf("operation_ref" to "op_1")) = mapOf("members" to listOf(mapOf("row_ref" to "file_1:r1", "fields_ref" to reference)))
    private fun rejects(block: () -> Any?) { assertEquals("plan_build_failed", assertFailsWith<CashImportPlanError> { block() }.code) }
    @Test fun resolvedDetailIsDetachedAndUsesPublicWhitelist() {
        val plan = plan(); val before = sourceJson(plan)
        val detail = CashImportPlanViews.publicDetail(CashImportPlanViews.resolveDetail(plan, detail()))
        val member = (detail.getValue("members") as List<*>).single() as Map<*, *>
        val fields = member["fields"] as Map<*, *>
        assertEquals("来源备注", fields["note"]); assertFalse("component_id" in ((fields["components"] as List<*>).single() as Map<*, *>))
        assertFalse("source_explanation" in fields); assertFalse("preview_transaction_id" in fields); assertFalse("fields_ref" in member)
        @Suppress("UNCHECKED_CAST") val payload = fields["source_payload"] as MutableMap<String, Any?>
        payload["备注"] = "本地修改"
        assertEquals(before, sourceJson(plan))
    }
    @Test fun badFrozenReferenceFailsWithSafeError() {
        for (reference in listOf(mapOf("operation_ref" to "missing"), mapOf("transaction_id" to "missing"), mapOf("operation_ref" to emptyList<Any?>()), mapOf("transaction_id" to 102), mapOf("operation_ref" to "op_1", "transaction_id" to "102"))) {
            rejects { CashImportPlanViews.resolveDetail(plan(), detail(reference)) }
        }
    }
    @Test fun anotherMembersOperationCannotSupplyFields() { rejects { CashImportPlanViews.resolveDetail(plan(listOf(operation(refs = listOf("file_2:r1")))), detail()) } }
    @Test fun anotherStoredTransactionCannotSupplyFields() {
        rejects { CashImportPlanViews.resolveDetail(plan(), mapOf("members" to listOf(mapOf("transaction_id" to "103", "fields_ref" to mapOf("transaction_id" to "102"))))) }
    }
    @Test fun duplicateOperationReferencesCannotPickArbitraryValue() { rejects { CashImportPlanViews.resolveDetail(plan(listOf(operation(), operation())), detail()) } }
    @Test fun nullFrozenComponentsKeepSafePlanError() {
        val values = values().apply { put("components", null) }
        rejects { CashImportPlanViews.resolveDetail(plan(listOf(operation(values))), detail()) }
    }
    @Test fun missingComponentsPreserveOriginalCopyModeShapes() {
        for (copyValues in listOf(true, false)) {
            val values = values().apply { remove("components") }
            val plan = plan(listOf(operation(values))); val before = sourceJson(plan)
            val output = CashImportPlanViews.resolveDetail(plan, detail(), copyValues = copyValues)
            val fields = ((output["members"] as List<*>).single() as Map<*, *>)["fields"] as Map<*, *>
            if (copyValues) assertFalse("components" in fields) else assertEquals(emptyList<Any?>(), fields["components"])
            assertEquals(before, sourceJson(plan))
        }
    }
    @Test fun independentReadableDetailRejectsNullAndAllowsMissingComponents() {
        val golden = kotlinx.serialization.json.Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-plan-views.json")!!.bufferedReader().use { it.readText() })
        @Suppress("UNCHECKED_CAST") val template = cashSourceValue(golden) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val base = template.getValue("plan") as Map<String, Any?>
        val detail = detail() + mapOf("entry" to mapOf("fields" to emptyMap<String, Any?>()), "relation_members" to emptyList<Any?>(), "field_diffs" to emptyList<Any?>(), "issues" to emptyList<Any?>(), "relation_choices" to emptyList<Any?>(), "relation_effects" to emptyList<Any?>())
        @Suppress("UNCHECKED_CAST") val item = ((base["items"] as List<*>).first() as Map<String, Any?>) + mapOf("preview_item_id" to "it_null", "detail_available" to true)
        for (missing in listOf(false, true)) {
            val values = values().apply { if (missing) remove("components") else put("components", null) }
            val plan = base + plan(listOf(operation(values))) + mapOf("items" to listOf(item), "item_details" to mapOf("it_null" to detail))
            if (missing) CashImportPlanViews.validateReadable(plan) else rejects { CashImportPlanViews.validateReadable(plan) }
        }
    }

}
