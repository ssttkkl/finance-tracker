package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportPublicationRulesTest {
    private fun map(value: Any?) = value as Map<*, *>
    private fun rows(value: Any?) = value as List<*>
    @Suppress("UNCHECKED_CAST") private fun mutable(value: Any?) = value as MutableMap<String, Any?>
    private fun error(code: String, block: () -> Any?) = assertEquals(code, assertFailsWith<CashImportPlanError>(block = block).code)
    private fun wire(value: Any?): JsonElement = when (value) {
        is Int -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key as String to wire(item) })
        is List<*> -> JsonArray(value.map(::wire))
        else -> encodeLocalCashValue(value)
    }
    private fun reader(plan: Map<String, Any?> = built(), id: String = "local-plan") =
        LocalCashImportPlanReader(wire(plan + ("plan_id" to id)).jsonObject) { _, _ -> emptyMap() }
    private fun built(): Map<String, Any?> {
        val items = (0..120).map { index -> linkedMapOf<String, Any?>(
            "preview_item_id" to "item_$index", "confirmation_status" to if (index % 2 == 0) "confirmed" else "pending",
            "anticipated_state" to if (index % 2 == 0) "posted" else "draft",
            "display" to mutableMapOf("amount" to if (index % 2 == 0) "-20.00" else null,
                "record_type" to "expense", "record_subtype" to null),
            "statistics_eligible" to (index % 2 == 0), "member_refs" to listOf(mutableMapOf("row_ref" to "f:r$index")),
            "attention_reasons" to emptyList<Any?>(), "detail_available" to true) }
        return linkedMapOf("items" to items, "monthly_source" to emptyList<Any?>(), "summary" to mutableMapOf("create_posted" to 61, "create_draft" to 60),
            "file_results" to emptyList<Any?>(), "can_commit" to true,
            "processing_details" to listOf(mutableMapOf("row_ref" to "skipped:r1", "planned_action" to "skip", "code" to "row_unreadable")),
            "item_details" to items.associate { it.getValue("preview_item_id") as String to mutableMapOf(
                "members" to it.getValue("member_refs"), "field_diffs" to emptyList<Any?>(), "issues" to emptyList<Any?>(),
                "relation_choices" to emptyList<Any?>(), "relation_effects" to emptyList<Any?>()) },
            "operations" to listOf(mapOf("operation_ref" to "server_only", "private_source" to "not-a-public-plan-field")))
    }


    @Test fun mutableInputContainersCannotChangeTheFrozenReader() {
        val plan = wire(built() + ("plan_id" to "local-plan")).jsonObject
        val summary = plan.getValue("summary").jsonObject.toMutableMap()
        val items = plan.getValue("items").jsonArray.toMutableList()
        val first = items[0].jsonObject
        val display = first.getValue("display").jsonObject.toMutableMap()
        items[0] = JsonObject(first + ("display" to JsonObject(display)))
        val detail = plan.getValue("item_details").jsonObject.getValue("item_0").jsonObject
        val members = detail.getValue("members").jsonArray.toMutableList()
        val source = JsonObject(plan + mapOf("summary" to JsonObject(summary), "items" to JsonArray(items),
            "item_details" to JsonObject(plan.getValue("item_details").jsonObject +
                ("item_0" to JsonObject(detail + ("members" to JsonArray(members)))))))
        val read = LocalCashImportPlanReader(source) { _, _ -> emptyMap() }
        summary["create_posted"] = JsonPrimitive(0); display["amount"] = JsonPrimitive("0"); members.clear(); items.clear()
        read.validateReadable()
        val restored = read.view()
        assertEquals(JsonPrimitive(61), restored.getValue("summary").jsonObject["create_posted"])
        assertEquals(JsonPrimitive("-20.00"), restored.getValue("items").jsonArray[0].jsonObject.getValue("display").jsonObject["amount"])
        assertTrue(read.detail("item_0").getValue("members").jsonArray.isNotEmpty())
        assertEquals(restored, read.view()); assertEquals(read.detail("item_0"), read.detail("item_0"))
    }

    @Test fun filteredPagesBindCursorToPlanKindAndFilter() {
        val read = reader(); val ids = mutableListOf<String>(); var cursor: String? = null
        do {
            val page = read.page("items", cursor = cursor, limit = 17, confirmationStatus = "pending")
            val items = page.getValue("items").jsonArray
            assertTrue(items.all { it.jsonObject.getValue("confirmation_status") == JsonPrimitive("pending") })
            ids.addAll(items.map { it.jsonObject.getValue("preview_item_id").jsonPrimitive.content })
            cursor = page.getValue("next_cursor").let { if (it == JsonNull) null else it.jsonPrimitive.content }
        } while (cursor != null)
        assertEquals(60, ids.size); assertEquals(60, ids.toSet().size)
        assertEquals(JsonPrimitive("row_unreadable"), read.page("processing_details").getValue("items").jsonArray[0].jsonObject["code"])
        cursor = read.page("items", limit = 1).getValue("next_cursor").jsonPrimitive.content
        error("plan_cursor_invalid") { read.page("items", cursor = cursor, confirmationStatus = "pending") }
        error("plan_cursor_invalid") { read.page("processing_details", cursor = cursor) }
        error("plan_cursor_invalid") { reader(id = "another-plan").page("items", cursor = cursor) }
        for (limit in listOf(0, 201)) error("plan_page_invalid") { read.page("items", limit = limit) }
    }

    @Test fun absolutePositionsUseFilteredTotalsAndRejectOutOfBounds() {
        val read = reader(); val page = read.page("items", startIndex = 10, limit = 20, confirmationStatus = "pending", indexed = true)
        assertEquals(JsonPrimitive(10), page["start_index"]); assertEquals(JsonPrimitive(60), page["total_count"])
        assertEquals((21..59 step 2).map { "item_$it" }, page.getValue("items").jsonArray.map { it.jsonObject.getValue("preview_item_id").jsonPrimitive.content })
        assertNotEquals(JsonNull, page["next_cursor"])
        assertEquals(buildJsonObject {
            put("items", JsonArray(emptyList())); put("next_cursor", JsonNull); put("start_index", 60); put("total_count", 60)
        }, read.page("items", startIndex = 60, limit = 20, confirmationStatus = "pending", indexed = true))
        error("plan_page_invalid") { read.page("items", startIndex = 61, confirmationStatus = "pending") }
        error("plan_page_invalid") { read.page("items", cursor = "cursor", startIndex = 0) }
    }

    @Test fun invalidSelectionStructuresKeepAllSixOriginalRejections() {
        val patches = listOf(
            mapOf("row_choices" to listOf(mapOf("row_ref" to "a:r1", "action" to emptyList<Any?>()))),
            mapOf("row_choices" to listOf(mapOf("row_ref" to "a:r1", "action" to "apply", "patch" to emptyList<Any?>()))),
            mapOf("row_choices" to List(2) { mapOf("row_ref" to "a:r1", "action" to "apply") }),
            mapOf("account_selections" to listOf(mapOf("source_account_ref" to "a", "account_id" to "1", "expected_account_revision" to true))),
            mapOf("account_selections" to listOf(mapOf("source_account_ref" to "a", "account_id" to null, "expected_account_revision" to 1))),
            mapOf("relation_choices" to listOf(mapOf("suggestion_id" to "a", "action" to "accept", "operation_graph" to emptyMap<String, Any?>()))))
        assertEquals(6, patches.size)
        for (patch in patches) {
            val input = wire(patch).jsonObject; val before = input.toString(); val empty = JsonArray(emptyList())
            error("plan_input_invalid") { prepareLocalCashImportInput(input["account_selections"] ?: empty,
                input["row_choices"] ?: empty, input["relation_choices"] ?: empty) }
            assertEquals(before, input.toString())
        }
    }

    @Test fun publicItemRowsDropServerExecutionFields() {
        val input = built().also { plan -> rows(plan["items"]).forEach {
            mutable(it)["private_operation"] = mapOf("technical" to "server-only")
        } }
        val read = reader(input)
        for (items in listOf(read.view().getValue("items"), read.page("items").getValue("items")))
            assertTrue(items.jsonArray.all { "private_operation" !in it.jsonObject })
    }

    @Test fun malformedOutputsRetainAllFourOriginalRejections() {
        val read = reader(); val before = read.view()
        val malformed = listOf(mapOf("processing_details" to null), mapOf("item_details" to emptyList<Any?>()),
            mapOf("file_results" to null), mapOf("item_details" to mapOf("item_0" to mapOf("members" to null))))
        assertEquals(4, malformed.size)
        for (patch in malformed) {
            error("plan_build_failed") { reader(built() + patch).validateReadable() }
            assertEquals(before, read.view())
        }
    }

    @Test fun processingCursorStartsAtFirstUndeliveredItem() {
        val read = reader(built() + ("processing_details" to (0..120).map { mapOf("row_ref" to "skip:r$it", "code" to "row_unreadable") }))
        var cursor = read.view().getValue("pages").jsonObject.getValue("processing_details").let { if (it == JsonNull) null else it.jsonPrimitive.content }
        val refs = mutableListOf<String>()
        do {
            val page = read.page("processing_details", cursor = cursor)
            refs.addAll(page.getValue("items").jsonArray.map { it.jsonObject.getValue("row_ref").jsonPrimitive.content })
            cursor = page.getValue("next_cursor").let { if (it == JsonNull) null else it.jsonPrimitive.content }
        } while (cursor != null)
        assertEquals((0..120).map { "skip:r$it" }, refs)
    }

    @Test fun publicNestedFieldsDropPrivateExecutionData() {
        val input = built().also { plan ->
            val detail = mutable(map(plan["item_details"])["item_0"])
            detail["private_operation"] = mapOf("secret" to "server-only")
            mutable(rows(detail["members"])[0])["private_operation"] = "private-member"
            mutable(map(rows(plan["items"])[0])["display"])["private_operation"] = "private-display"
            mutable(rows(plan["processing_details"])[0])["private_operation"] = "private-processing"
        }
        val read = reader(input); val detail = read.detail("item_0")
        assertFalse("private_operation" in read.view().getValue("items").jsonArray[0].jsonObject.getValue("display").jsonObject)
        assertFalse("private_operation" in detail); assertFalse("private_operation" in detail.getValue("members").jsonArray[0].jsonObject)
        assertFalse("private_operation" in read.page("processing_details").getValue("items").jsonArray[0].jsonObject)
    }

    @Test fun publicIssuesKeepScopeActionAndSafeMessage() {
        val issue = mapOf("code" to "account_missing", "message" to "尚未选择账户", "field_path" to "account_id",
            "blocking_scope" to "row", "next_action" to "edit")
        val input = built().toMutableMap().also { plan ->
            mutable(map(plan["item_details"])["item_0"])["issues"] = listOf(issue)
            plan["file_results"] = listOf(mapOf("file_ref" to "a", "issues" to listOf(issue +
                mapOf("blocking_scope" to "file", "next_action" to "reselect_file"))))
        }
        val read = reader(input)
        assertEquals(wire(listOf(issue)), read.detail("item_0")["issues"])
        assertEquals(JsonPrimitive("reselect_file"), read.view().getValue("file_results").jsonArray[0].jsonObject.getValue("issues").jsonArray[0].jsonObject["next_action"])
    }

    @Test fun issueMessagesUseSafeDescriptionsAndRejectInvalidScope() {
        fun output(issue: Map<String, Any?>) = built().also { mutable(map(it["item_details"])["item_0"])["issues"] = listOf(issue) }
        val read = reader(output(mapOf("code" to "row_unreadable", "message" to "SQL secret-provider")))
        read.validateReadable()
        val issue = read.detail("item_0").getValue("issues").jsonArray[0].jsonObject
        assertFalse("SQL" in issue.getValue("message").jsonPrimitive.content); assertEquals(JsonPrimitive("row"), issue["blocking_scope"])
        error("plan_build_failed") { reader(output(mapOf("code" to "row_unreadable", "blocking_scope" to "database"))).validateReadable() }
    }

    @Test fun processingWithoutIssueCodeDropsUntrustedProblemText() {
        for (extra in listOf(emptyMap<String, Any?>(), mapOf("code" to null))) {
            val read = reader(built() + ("processing_details" to listOf(mapOf("row_ref" to "a:r1", "planned_action" to "duplicate",
                "message" to "SQL secret-provider", "blocking_scope" to "request") + extra)))
            val item = read.page("processing_details").getValue("items").jsonArray[0].jsonObject
            assertFalse("message" in item); assertFalse("blocking_scope" in item)
        }
    }
}
