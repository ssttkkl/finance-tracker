package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportPlanViewTest {
    private fun golden(): JsonObject = Json.parseToJsonElement(javaClass.getResourceAsStream(
        "/python-cash-import-plan-views.json")!!.bufferedReader().use { it.readText() }).jsonObject
    private fun plan(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return cashSourceValue(golden().getValue("plan")) as Map<String, Any?>
    }
    private fun error(block: () -> Any?): String = assertFailsWith<CashImportPlanError> { block() }.code
    private fun result(block: () -> Any?): JsonElement = sourceJson(block())
    private fun expected(case: JsonObject): JsonElement = case.getValue("result")
    private fun case(name: String): JsonObject = golden().getValue("cases").jsonObject.getValue(name).jsonObject

    @Test fun publicViewsAndPagesMatchPython() {
        val plan = plan()
        assertEquals(expected(case("view")), result { CashImportPlanViews.view(plan) })
        assertEquals(expected(case("items")), result { CashImportPlanViews.page(plan, "items") })
        assertEquals(expected(case("confirmed")), result { CashImportPlanViews.page(plan, "items", confirmationStatus = "confirmed") })
        assertEquals(expected(case("indexed")), result { CashImportPlanViews.page(plan, "items", startIndex = 1, limit = 1, indexed = true) })
        assertEquals(expected(case("processing")), result { CashImportPlanViews.page(plan, "processing_details", limit = 1) })
        val cursor = golden().getValue("cases").jsonObject.getValue("cursor_input").jsonPrimitive.content
        assertEquals(expected(case("cursor")), result { CashImportPlanViews.page(plan, "items", cursor = cursor, limit = 1) })
    }

    @Test fun frozenDetailsMatchPython() {
        val plan = plan()
        for (identifier in listOf("it_1", "it_2")) {
            @Suppress("UNCHECKED_CAST")
            val detail = (plan.getValue("item_details") as Map<String, Any?>).getValue(identifier)
            val rendered = CashImportPlanViews.publicDetail(CashImportPlanViews.resolveDetail(plan, detail)) +
                ("preview_item_id" to identifier)
            assertEquals(expected(case("detail_${identifier.takeLast(1)}")), result { rendered })
        }
    }

    @Test fun pageAndSessionErrorsMatchPython() {
        val plan = plan()
        val errors = golden().getValue("cases").jsonObject.getValue("errors").jsonObject
        assertEquals(errors.getValue("limit_zero").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.page(plan, "items", limit = 0) })
        assertEquals(errors.getValue("limit_large").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.page(plan, "items", limit = 201) })
        assertEquals(errors.getValue("cursor_garbage").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.page(plan, "items", cursor = "not base64!") })
        assertEquals(errors.getValue("cursor_plan").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.page(plan, "items", cursor =
                "W1wicF9wX3BfcF9wX3BfcF9wX3BfcF9wX3BfcF9wX3BfcF9wIiwiaXRlbXMiLCJhbGwiLDBd") })
        assertEquals(errors.getValue("start_index").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.page(plan, "items", startIndex = 3) })
        assertEquals(errors.getValue("confirmation").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.page(plan, "items", confirmationStatus = "invalid") })
        assertEquals(errors.getValue("session_id").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.sessionId("not-a-plan") })
    }

    @Test fun previewInputAndReadabilityMatchPython() {
        val plan = plan()
        val preview = golden().getValue("cases").jsonObject.getValue("preview").jsonObject
        assertEquals(preview.getValue("valid").jsonObject.getValue("result"), result {
            CashImportPlanViews.previewInput(
                listOf(mapOf("source_account_ref" to "ref", "account_id" to "9101", "expected_account_revision" to 1)),
                listOf(mapOf("row_ref" to "f:r1", "action" to "apply", "patch" to mapOf("note" to "x"))),
                listOf(mapOf("suggestion_id" to "sg", "action" to "accept", "candidate_id" to "c1",
                    "common_fields" to mapOf("note" to "x"), "inherit_common_fields" to true)),
                listOf(mapOf("edit_ref" to "e1", "patch" to mapOf("note" to "y"))))
        })
        assertEquals(preview.getValue("bad_action").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.previewInput(emptyList<Any?>(), listOf(mapOf("row_ref" to "f:r1", "action" to "nope")), emptyList<Any?>()) })
        assertEquals(preview.getValue("duplicate").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.previewInput(emptyList<Any?>(), listOf(mapOf("row_ref" to "f:r1"), mapOf("row_ref" to "f:r1")), emptyList<Any?>()) })
        assertEquals(preview.getValue("bad_common").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.previewInput(emptyList<Any?>(), emptyList<Any?>(),
                listOf(mapOf("suggestion_id" to "sg", "action" to "accept", "common_fields" to mapOf("amount" to "1")))) })
        assertEquals(preview.getValue("bad_account").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.previewInput(listOf(mapOf("source_account_ref" to "ref", "account_id" to "1",
                "expected_account_revision" to 0)), emptyList<Any?>(), emptyList<Any?>()) })
        assertEquals(preview.getValue("entry_patch").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.previewInput(emptyList<Any?>(), emptyList<Any?>(), emptyList<Any?>(),
                listOf(mapOf("edit_ref" to "e1", "patch" to mapOf("amount" to "1")))) })
        val validate = golden().getValue("cases").jsonObject.getValue("validate").jsonObject
        assertTrue(validate.getValue("valid").jsonObject.getValue("ok").jsonPrimitive.boolean)
        CashImportPlanViews.validateReadable(plan)
        val broken = plan.toMutableMap().apply { put("item_details", emptyMap<String, Any?>()) }
        assertEquals(validate.getValue("missing_detail").jsonObject.getValue("code").jsonPrimitive.content,
            error { CashImportPlanViews.validateReadable(broken) })
        val member = ((plan.getValue("item_details") as Map<String, Any?>).getValue("it_1") as Map<String, Any?>)["members"]
            .let { (it as List<*>).single() as Map<*, *> }
        assertTrue("fields_ref" in member)
        assertFalse("fields" in member)
    }
}
